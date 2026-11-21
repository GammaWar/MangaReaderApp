package com.mangareader.ui

import android.app.Application
import android.webkit.CookieManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mangareader.CacheUsageSnapshot
import com.mangareader.MangaReaderApplication
import com.mangareader.data.AppPreferencesRepository
import com.mangareader.data.UrlChapterAnalyzer
import com.mangareader.formatBytesDe
import com.mangareader.model.AppTab
import com.mangareader.model.BookmarkItem
import com.mangareader.model.BrowserTabState
import com.mangareader.model.HistoryItem
import com.mangareader.model.ReaderChapter
import com.mangareader.model.ReaderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.UUID

data class MangaUiState(
    val activeTab: AppTab = AppTab.LIBRARY,
    val settings: ReaderSettings = ReaderSettings(),
    val bookmarks: List<BookmarkItem> = emptyList(),
    val history: List<HistoryItem> = emptyList(),
    val browserTabs: List<BrowserTabState> = listOf(
        BrowserTabState(
            id = "tab-initial-1",
            title = "Startseite",
            url = "https://mangadex.org",
        )
    ),
    val activeBrowserTabId: String = "tab-initial-1",
    val activeChapter: ReaderChapter? = null,
    val currentPageIndex: Int = 0,
    val isExtracting: Boolean = false,
    val prefetchedNextChapter: ReaderChapter? = null,
    val isPrefetchingNext: Boolean = false,
    val sessionVersion: Int = 0,
    val isSettingsOpen: Boolean = false,
    val cacheUsage: CacheUsageSnapshot = CacheUsageSnapshot(
        totalBytes = 0L,
        fileCount = 0,
        maxBytes = MangaReaderApplication.DEFAULT_MAX_CACHE_SIZE_BYTES,
    ),
)

class MangaViewModel(application: Application) : AndroidViewModel(application) {
    private val mangaApp = application as MangaReaderApplication
    private val repo = AppPreferencesRepository(application)

    private val _uiState = MutableStateFlow(
        MangaUiState(
            settings = repo.loadSettings(),
            bookmarks = repo.loadBookmarks(),
            history = repo.loadHistory(),
        )
    )
    val uiState: StateFlow<MangaUiState> = _uiState.asStateFlow()

    private val _snackbarEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val snackbarEvents: SharedFlow<String> = _snackbarEvents.asSharedFlow()

    val okHttpClient get() = mangaApp.okHttpClient

    init {
        refreshCacheUsage()
    }

    fun selectTab(tab: AppTab) {
        _uiState.update { it.copy(activeTab = tab) }
        if (tab == AppTab.LIBRARY) {
            refreshCacheUsage()
        }
    }

    fun setSettingsOpen(open: Boolean) {
        _uiState.update { it.copy(isSettingsOpen = open) }
        if (open) refreshCacheUsage()
    }

    fun refreshCacheUsage() {
        viewModelScope.launch {
            val snapshot = mangaApp.cacheManager.getUsageSnapshot()
            _uiState.update { it.copy(cacheUsage = snapshot) }
        }
    }

    fun updateSettings(transform: (ReaderSettings) -> ReaderSettings) {
        val current = _uiState.value.settings
        val updated = transform(current)
        repo.saveSettings(updated)
        _uiState.update { it.copy(settings = updated) }

        if (updated.cacheLimitMb != current.cacheLimitMb) {
            val newMaxBytes = updated.cacheLimitMb.toLong() * 1024L * 1024L
            viewModelScope.launch {
                val report = mangaApp.updateCacheLimitBytes(newMaxBytes)
                refreshCacheUsage()
                if (report.removedSizeEvictedCount > 0) {
                    notify(
                        "Cache auf Limit bereinigt: ${report.removedSizeEvictedCount} Dateien (${formatBytesDe(report.freedBytes)}) entfernt."
                    )
                } else {
                    notify("Cache-Limit auf ${formatBytesDe(newMaxBytes)} gesetzt.")
                }
            }
        }
    }

    fun runManualLimitPrune() {
        viewModelScope.launch {
            val report = mangaApp.runManualCacheLimitCheck()
            refreshCacheUsage()
            if (report.removedSizeEvictedCount > 0) {
                notify("Limit-Bereinigung: ${report.removedSizeEvictedCount} Dateien (${formatBytesDe(report.freedBytes)}) freigegeben.")
            } else {
                notify("Cache liegt bereits innerhalb des konfigurierten Limits.")
            }
        }
    }

    fun clearAllCacheManually() {
        viewModelScope.launch {
            val report = mangaApp.clearCacheManually()
            refreshCacheUsage()
            notify("Bild-Cache geleert (${formatBytesDe(report.freedBytes)} freigegeben).")
        }
    }

    fun evictSingleImageCache(imageUrl: String) {
        viewModelScope.launch {
            mangaApp.evictSingleImageFromHttpCache(imageUrl)
            refreshCacheUsage()
        }
    }

    // --- Multi-Tab Browser ---

    fun addBrowserTab(initialUrl: String = "https://mangadex.org", title: String = "Neuer Tab") {
        val newTab = BrowserTabState(
            id = "tab-${UUID.randomUUID()}",
            title = title,
            url = initialUrl,
        )
        _uiState.update { state ->
            state.copy(
                browserTabs = state.browserTabs + newTab,
                activeBrowserTabId = newTab.id,
                activeTab = AppTab.BROWSER,
            )
        }
    }

    fun selectBrowserTab(tabId: String) {
        _uiState.update { it.copy(activeBrowserTabId = tabId) }
    }

    fun closeBrowserTab(tabId: String) {
        _uiState.update { state ->
            val remaining = state.browserTabs.filterNot { it.id == tabId }
            if (remaining.isEmpty()) {
                val fresh = BrowserTabState(
                    id = "tab-${UUID.randomUUID()}",
                    title = "Neuer Tab",
                    url = "https://mangadex.org",
                )
                state.copy(browserTabs = listOf(fresh), activeBrowserTabId = fresh.id)
            } else {
                val nextActive = if (state.activeBrowserTabId == tabId) {
                    remaining.last().id
                } else {
                    state.activeBrowserTabId
                }
                state.copy(browserTabs = remaining, activeBrowserTabId = nextActive)
            }
        }
    }

    fun updateBrowserTabMeta(tabId: String, url: String? = null, title: String? = null, isLoading: Boolean? = null) {
        _uiState.update { state ->
            state.copy(
                browserTabs = state.browserTabs.map { tab ->
                    if (tab.id != tabId) tab else tab.copy(
                        url = url ?: tab.url,
                        title = title?.takeIf { it.isNotBlank() } ?: tab.title,
                        isLoading = isLoading ?: tab.isLoading,
                    )
                }
            )
        }
    }

    fun openUrlInBrowser(url: String) {
        val state = _uiState.value
        val activeId = state.activeBrowserTabId
        updateBrowserTabMeta(tabId = activeId, url = url)
        _uiState.update { it.copy(activeTab = AppTab.BROWSER) }
    }

    // --- Chapter Extraction & Prefetching ---

    fun extractAndOpenChapter(chapterUrl: String, targetPage: Int = 0) {
        val trimmed = chapterUrl.trim()
        if (!UrlChapterAnalyzer.isSafeExternalUrl(trimmed)) {
            notify("Ungültige oder unsichere URL blockiert.")
            return
        }
        if (_uiState.value.isExtracting) return

        viewModelScope.launch {
            _uiState.update { it.copy(isExtracting = true) }
            try {
                val chapter = fetchAndExtractChapter(trimmed, targetPage)
                if (chapter == null || chapter.pages.isEmpty()) {
                    notify("Keine Kapitel-Bilder gefunden. Prüfe die Seite im Browser (z. B. Cloudflare-Prüfung).")
                    return@launch
                }
                openChapterInReader(chapter)
            } catch (e: Exception) {
                notify("Extraktion fehlgeschlagen: ${e.localizedMessage ?: "Netzwerkfehler"}")
            } finally {
                // Garantiertes Entsperren im finally-Block
                _uiState.update { it.copy(isExtracting = false) }
            }
        }
    }

    private suspend fun fetchAndExtractChapter(url: String, initialPage: Int): ReaderChapter? =
        withContext(Dispatchers.IO) {
            val cookie = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
            val reqBuilder = Request.Builder()
                .url(url)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
                )
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            if (!cookie.isNullOrBlank()) {
                reqBuilder.header("Cookie", cookie)
            }

            okHttpClient.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val html = resp.body?.string()?.take(4 * 1024 * 1024) ?: return@withContext null
                val (title, pages) = UrlChapterAnalyzer.extractTitleAndPagesFromHtml(html, url)
                val analysis = UrlChapterAnalyzer.analyzeChapterUrl(url)
                val label = analysis.chapterNumber?.let { "Kapitel $it" } ?: "Kapitel"

                ReaderChapter(
                    id = url.hashCode().toString(),
                    title = title,
                    chapterLabel = label,
                    chapterUrl = url,
                    pages = pages,
                    nextChapterUrl = analysis.nextChapterUrl,
                    prevChapterUrl = analysis.prevChapterUrl,
                    initialPage = initialPage.coerceIn(0, (pages.size - 1).coerceAtLeast(0)),
                )
            }
        }

    fun openChapterInReader(chapter: ReaderChapter) {
        val safePage = chapter.initialPage.coerceIn(0, (chapter.pages.size - 1).coerceAtLeast(0))
        _uiState.update {
            it.copy(
                activeChapter = chapter,
                currentPageIndex = safePage,
                prefetchedNextChapter = null,
                isPrefetchingNext = false,
                activeTab = AppTab.READER,
            )
        }
        recordHistoryProgress(chapter, safePage)
    }

    fun onReaderPageChanged(pageIndex: Int) {
        val state = _uiState.value
        val chapter = state.activeChapter ?: return
        val clamped = pageIndex.coerceIn(0, (chapter.pages.size - 1).coerceAtLeast(0))
        if (clamped == state.currentPageIndex) return

        _uiState.update { it.copy(currentPageIndex = clamped) }
        recordHistoryProgress(chapter, clamped)
        syncBookmarkProgress(chapter.chapterUrl, clamped, chapter.pages.size)

        // Automatisches Pre-Fetching auf den letzten 2 Seiten
        val nextUrl = chapter.nextChapterUrl
        if (
            state.settings.autoPrefetchEnabled &&
            nextUrl != null &&
            clamped >= (chapter.pages.size - 2).coerceAtLeast(0) &&
            state.prefetchedNextChapter == null &&
            !state.isPrefetchingNext
        ) {
            prefetchNextChapter(nextUrl)
        }
    }

    private fun prefetchNextChapter(nextUrl: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isPrefetchingNext = true) }
            try {
                val prefetched = fetchAndExtractChapter(nextUrl, initialPage = 0)
                if (prefetched != null && prefetched.pages.isNotEmpty()) {
                    _uiState.update { it.copy(prefetchedNextChapter = prefetched) }
                }
            } catch (_: Exception) {
                // Stiller Fehler beim Hintergrund-Prefetch
            } finally {
                _uiState.update { it.copy(isPrefetchingNext = false) }
            }
        }
    }

    fun openNextChapter() {
        val state = _uiState.value
        val prefetched = state.prefetchedNextChapter
        if (prefetched != null) {
            openChapterInReader(prefetched)
            notify("Vorgeladenes Folgekapitel sofort geöffnet.")
            return
        }
        val nextUrl = state.activeChapter?.nextChapterUrl ?: return
        extractAndOpenChapter(nextUrl, targetPage = 0)
    }

    fun refreshReaderSession() {
        _uiState.update { it.copy(sessionVersion = it.sessionVersion + 1) }
        notify("Sitzung erneuert — Bilder werden mit frischen Cookies geladen.")
    }

    // --- Bookmarks & History ---

    fun toggleBookmarkForCurrentChapter() {
        val state = _uiState.value
        val chapter = state.activeChapter ?: return
        val exists = state.bookmarks.any { it.chapterUrl == chapter.chapterUrl }
        val updated = if (exists) {
            state.bookmarks.filterNot { it.chapterUrl == chapter.chapterUrl }
        } else {
            val item = BookmarkItem(
                id = "bm-${UUID.randomUUID()}",
                title = chapter.title,
                chapterLabel = chapter.chapterLabel,
                chapterUrl = chapter.chapterUrl,
                lastReadPage = state.currentPageIndex,
                totalPages = chapter.pages.size,
                updatedAtMs = System.currentTimeMillis(),
            )
            listOf(item) + state.bookmarks
        }
        repo.saveBookmarks(updated)
        _uiState.update { it.copy(bookmarks = updated) }
        notify(if (exists) "Lesezeichen entfernt." else "Lesezeichen gespeichert.")
    }

    fun removeBookmark(id: String) {
        val updated = _uiState.value.bookmarks.filterNot { it.id == id }
        repo.saveBookmarks(updated)
        _uiState.update { it.copy(bookmarks = updated) }
    }

    fun removeHistoryItem(id: String) {
        val updated = _uiState.value.history.filterNot { it.id == id }
        repo.saveHistory(updated)
        _uiState.update { it.copy(history = updated) }
    }

    fun clearHistory() {
        repo.saveHistory(emptyList())
        _uiState.update { it.copy(history = emptyList()) }
        notify("Leseverlauf geleert.")
    }

    private fun recordHistoryProgress(chapter: ReaderChapter, pageIndex: Int) {
        if (!_uiState.value.settings.historyEnabled) return
        val item = HistoryItem(
            id = "hist-${chapter.chapterUrl.hashCode()}",
            title = chapter.title,
            chapterLabel = chapter.chapterLabel,
            chapterUrl = chapter.chapterUrl,
            lastReadPage = pageIndex,
            totalPages = chapter.pages.size,
            readAtMs = System.currentTimeMillis(),
        )
        val updated = listOf(item) + _uiState.value.history.filterNot { it.chapterUrl == chapter.chapterUrl }
        repo.saveHistory(updated)
        _uiState.update { it.copy(history = updated) }
    }

    private fun syncBookmarkProgress(chapterUrl: String, pageIndex: Int, totalPages: Int) {
        val current = _uiState.value.bookmarks
        if (current.none { it.chapterUrl == chapterUrl }) return
        val updated = current.map { bm ->
            if (bm.chapterUrl == chapterUrl) {
                bm.copy(
                    lastReadPage = pageIndex,
                    totalPages = totalPages,
                    updatedAtMs = System.currentTimeMillis(),
                )
            } else bm
        }
        repo.saveBookmarks(updated)
        _uiState.update { it.copy(bookmarks = updated) }
    }

    fun notify(message: String) {
        _snackbarEvents.tryEmit(message)
    }
}
