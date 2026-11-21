package com.mangareader.app.ui.viewmodel

import android.app.Application
import android.webkit.CookieManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import coil.Coil
import coil.request.ImageRequest
import com.mangareader.app.MangaReaderApplication
import com.mangareader.app.data.cache.CacheStats
import com.mangareader.app.data.cache.ChapterCacheManager
import com.mangareader.app.data.chat.ChatContextSummary
import com.mangareader.app.data.chat.ChatHelpers
import com.mangareader.app.data.chat.ChatMessage
import com.mangareader.app.data.chat.ChatModelId
import com.mangareader.app.data.chat.ChatPersonaId
import com.mangareader.app.data.chat.ChatRepository
import com.mangareader.app.data.chat.GeminiChatClient
import com.mangareader.app.data.download.DownloadRepository
import com.mangareader.app.data.extractor.UrlChapterAnalyzer
import com.mangareader.app.data.library.LibraryRepository
import com.mangareader.app.data.model.AppTab
import com.mangareader.app.data.model.BookmarkItem
import com.mangareader.app.data.model.Chapter
import com.mangareader.app.data.model.DownloadState
import com.mangareader.app.data.model.DownloadedChapter
import com.mangareader.app.data.model.ExtractionResult
import com.mangareader.app.data.model.PrefetchState
import com.mangareader.app.data.model.PrefetchStatus
import com.mangareader.app.data.model.ReaderMode
import com.mangareader.app.data.model.ReaderPreferences
import com.mangareader.app.data.model.ReadingDirection
import com.mangareader.app.data.model.WebTab
import com.mangareader.app.data.session.SessionManager
import com.mangareader.app.util.AppConstants
import com.mangareader.app.util.UrlSafety
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

sealed class UiEvent {
    data class ShowSnackbar(val message: String) : UiEvent()
    data class NavigateWebView(val url: String) : UiEvent()
    data object TriggerExtraction : UiEvent()
    data object GoBackWebView : UiEvent()
}

class MangaViewModel(application: Application) : AndroidViewModel(application) {

    private val mangaApp: MangaReaderApplication? = application as? MangaReaderApplication
    private val downloadRepo = DownloadRepository(application)
    private val sessionManager = SessionManager(application)
    private val savedSession = sessionManager.loadSessionSync()

    // Browser Tabs Management
    private val initialTab = WebTab(
        title = "Startseite",
        url = "https://mangadex.org"
    )

    private val _tabs = MutableStateFlow<List<WebTab>>(
        savedSession?.tabs?.takeIf { it.isNotEmpty() } ?: listOf(initialTab)
    )
    val tabs: StateFlow<List<WebTab>> = _tabs.asStateFlow()

    private val initialActiveTab = savedSession?.let { s ->
        s.tabs.find { it.id == s.activeTabId } ?: s.tabs.firstOrNull()
    } ?: initialTab

    private val _activeWebTabId = MutableStateFlow(initialActiveTab.id)
    val activeWebTabId: StateFlow<String> = _activeWebTabId.asStateFlow()

    // Current URL of the active tab
    private val _currentUrl = MutableStateFlow(initialActiveTab.url)
    val currentUrl: StateFlow<String> = _currentUrl.asStateFlow()

    private val _pageTitle = MutableStateFlow(initialActiveTab.title)
    val pageTitle: StateFlow<String> = _pageTitle.asStateFlow()

    private val _isWebLoading = MutableStateFlow(false)
    val isWebLoading: StateFlow<Boolean> = _isWebLoading.asStateFlow()

    private val _webProgress = MutableStateFlow(0f)
    val webProgress: StateFlow<Float> = _webProgress.asStateFlow()

    // Reader state (active chapter)
    private val _currentChapter = MutableStateFlow<Chapter?>(initialActiveTab.chapter)
    val currentChapter: StateFlow<Chapter?> = _currentChapter.asStateFlow()

    private val _readerMode = MutableStateFlow(savedSession?.readerMode ?: ReaderMode.VERTICAL_WEBTOON)
    val readerMode: StateFlow<ReaderMode> = _readerMode.asStateFlow()

    private val _activeTab = MutableStateFlow(AppTab.LIBRARY)
    val activeTab: StateFlow<AppTab> = _activeTab.asStateFlow()

    private val _isSettingsOpen = MutableStateFlow(false)
    val isSettingsOpen: StateFlow<Boolean> = _isSettingsOpen.asStateFlow()

    fun setSettingsOpen(open: Boolean) {
        _isSettingsOpen.value = open
        if (open) {
            refreshCacheStats()
        }
    }

    // Explicit target page request (nonce to targetPage) for reliable jumps from Library/History/Offline
    private val _targetPageRequest = MutableStateFlow(0 to initialActiveTab.lastReadPage.coerceAtLeast(0))
    val targetPageRequest: StateFlow<Pair<Int, Int>> = _targetPageRequest.asStateFlow()

    private var currentReaderPageIndex: Int = initialActiveTab.lastReadPage.coerceAtLeast(0)

    // Download state
    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    private val _downloadedChapters = MutableStateFlow<List<DownloadedChapter>>(emptyList())
    val downloadedChapters: StateFlow<List<DownloadedChapter>> = _downloadedChapters.asStateFlow()

    private val _isExtracting = MutableStateFlow(false)
    val isExtracting: StateFlow<Boolean> = _isExtracting.asStateFlow()

    // Library (Bookmarks & History) and Reader Preferences
    private val libraryRepo = LibraryRepository(application)
    private val allLibraryItems = libraryRepo.loadItemsSync()
    private val savedPrefs = libraryRepo.loadPreferencesSync()

    private val _bookmarks = MutableStateFlow<List<BookmarkItem>>(allLibraryItems.filter { it.isBookmark })
    val bookmarks: StateFlow<List<BookmarkItem>> = _bookmarks.asStateFlow()

    private val _history = MutableStateFlow<List<BookmarkItem>>(
        allLibraryItems.filter { !it.isBookmark }.sortedByDescending { it.timestamp }
    )
    val history: StateFlow<List<BookmarkItem>> = _history.asStateFlow()

    private val _readerPreferences = MutableStateFlow<ReaderPreferences>(savedPrefs)
    val readerPreferences: StateFlow<ReaderPreferences> = _readerPreferences.asStateFlow()

    private val _isAutoScrolling = MutableStateFlow(false)
    val isAutoScrolling: StateFlow<Boolean> = _isAutoScrolling.asStateFlow()

    // Configurable Image Cache State (Default 4 GB, Range 512 MB - 32 GB)
    private val initialLimitMb = mangaApp?.getConfiguredCacheLimitMb()
        ?: ChapterCacheManager.DEFAULT_CACHE_LIMIT_MB

    private val _cacheLimitMb = MutableStateFlow(initialLimitMb)
    val cacheLimitMb: StateFlow<Int> = _cacheLimitMb.asStateFlow()

    private val _cacheStats = MutableStateFlow(
        CacheStats(maxBytes = ChapterCacheManager.mbToBytes(initialLimitMb))
    )
    val cacheStats: StateFlow<CacheStats> = _cacheStats.asStateFlow()

    private val _isCacheBusy = MutableStateFlow(false)
    val isCacheBusy: StateFlow<Boolean> = _isCacheBusy.asStateFlow()

    // Seamless auto-extract next chapter flag
    private var pendingAutoExtractOnLoad: Boolean = false
    private var pendingTargetPage: Int? = null
    private var extractionTimeoutJob: Job? = null

    // Prefetch state & cache
    private var prefetchedChapter: Chapter? = null
    private var isPrefetchInProgress: Boolean = false
    private val _prefetchState = MutableStateFlow(PrefetchState())
    val prefetchState: StateFlow<PrefetchState> = _prefetchState.asStateFlow()

    // Assistant (Multi-turn Gemini Chat)
    private val chatRepo = ChatRepository(application)
    private val geminiClient = GeminiChatClient()
    private var chatStreamJob: Job? = null

    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(chatRepo.loadMessagesSync())
    val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages.asStateFlow()

    private val _selectedPersona = MutableStateFlow(chatRepo.loadPersonaSync())
    val selectedPersona: StateFlow<ChatPersonaId> = _selectedPersona.asStateFlow()

    private val _selectedModel = MutableStateFlow(chatRepo.loadModelSync())
    val selectedModel: StateFlow<ChatModelId> = _selectedModel.asStateFlow()

    private val _isAssistantStreaming = MutableStateFlow(false)
    val isAssistantStreaming: StateFlow<Boolean> = _isAssistantStreaming.asStateFlow()

    private val _apiKey = MutableStateFlow(chatRepo.loadApiKeySync())
    val hasApiKey: StateFlow<Boolean>
        get() = _hasApiKey
    private val _hasApiKey = MutableStateFlow(_apiKey.value.isNotBlank())

    // Debounce save jobs to avoid excessive disk writes
    private var saveSessionJob: Job? = null
    private var persistLibraryJob: Job? = null
    private var persistChatJob: Job? = null

    // Session Refresh Trigger for retrying failed images after background token renewal
    private val _sessionRefreshTrigger = MutableStateFlow(0)
    val sessionRefreshTrigger: StateFlow<Int> = _sessionRefreshTrigger.asStateFlow()
    private var pendingSessionRefresh: Boolean = false

    // UI Events
    private val _uiEvents = MutableSharedFlow<UiEvent>()
    val uiEvents: SharedFlow<UiEvent> = _uiEvents.asSharedFlow()

    init {
        refreshDownloads()
        refreshCacheStats()
    }

    private fun requestTargetPage(page: Int) {
        val safePage = page.coerceAtLeast(0)
        currentReaderPageIndex = safePage
        _targetPageRequest.value = (_targetPageRequest.value.first + 1) to safePage
    }

    private fun scheduleSaveSession() {
        saveSessionJob?.cancel()
        saveSessionJob = viewModelScope.launch {
            delay(400)
            sessionManager.saveSession(
                tabs = _tabs.value,
                activeTabId = _activeWebTabId.value,
                readerMode = _readerMode.value
            )
        }
    }

    fun handleBrowserBack() {
        viewModelScope.launch {
            _uiEvents.emit(UiEvent.GoBackWebView)
        }
    }

    fun updateLastReadPage(pageIndex: Int) {
        currentReaderPageIndex = pageIndex.coerceAtLeast(0)
        val ch = _currentChapter.value ?: return
        if (ch.isOffline) {
            // Keep tab state untarnished when reading offline chapters
            recordHistory(ch, pageIndex)
            return
        }

        val currentTabId = _activeWebTabId.value
        val currentList = _tabs.value
        val targetTab = currentList.find { it.id == currentTabId } ?: return
        if (targetTab.lastReadPage == pageIndex) return

        _tabs.value = currentList.map { tab ->
            if (tab.id == currentTabId) tab.copy(lastReadPage = pageIndex) else tab
        }
        scheduleSaveSession()
        recordHistory(ch, pageIndex)
    }

    // --- Cache Manager Controls ---

    fun refreshCacheStats() {
        val app = mangaApp ?: return
        viewModelScope.launch {
            _cacheStats.value = app.getCacheStats()
            _cacheLimitMb.value = app.getConfiguredCacheLimitMb()
        }
    }

    fun updateCacheLimitMb(limitMb: Int) {
        val clampedMb = ChapterCacheManager.clampCacheLimitMb(limitMb)
        _cacheLimitMb.value = clampedMb
        val app = mangaApp ?: return
        val newLimitBytes = ChapterCacheManager.mbToBytes(clampedMb)

        viewModelScope.launch {
            val report = app.updateCacheLimitBytes(newLimitBytes)
            _cacheStats.value = app.getCacheStats()
            if (report.removedSizeEvictedCount > 0) {
                val entryLabel = if (report.removedSizeEvictedCount == 1) "ältester Eintrag" else "älteste Einträge"
                _uiEvents.emit(
                    UiEvent.ShowSnackbar(
                        "Cache-Limit (${ChapterCacheManager.formatCacheLimitLabel(clampedMb)}) angewendet: " +
                            "${report.removedSizeEvictedCount} $entryLabel entfernt (${ChapterCacheManager.formatBytesDe(report.freedBytes)} frei)."
                    )
                )
            }
        }
    }

    fun applyCacheLimitManually() {
        val app = mangaApp ?: return
        if (_isCacheBusy.value) return
        _isCacheBusy.value = true
        viewModelScope.launch {
            try {
                val report = app.runManualCacheLimitCheck()
                _cacheStats.value = app.getCacheStats()
                val label = ChapterCacheManager.formatCacheLimitLabel(_cacheLimitMb.value)
                val msg = if (report.removedSizeEvictedCount > 0) {
                    val entryLabel = if (report.removedSizeEvictedCount == 1) "ältester Eintrag" else "älteste Einträge"
                    "Limit angewendet: ${report.removedSizeEvictedCount} $entryLabel entfernt (${ChapterCacheManager.formatBytesDe(report.freedBytes)} frei)."
                } else {
                    "Cache innerhalb des $label-Limits — keine Bereinigung nötig."
                }
                _uiEvents.emit(UiEvent.ShowSnackbar(msg))
            } finally {
                _isCacheBusy.value = false
            }
        }
    }

    fun clearCacheManually() {
        val app = mangaApp ?: return
        if (_isCacheBusy.value) return
        _isCacheBusy.value = true
        viewModelScope.launch {
            try {
                val report = app.clearCacheManually()
                _cacheStats.value = app.getCacheStats()
                val msg = if (report.removedSizeEvictedCount > 0) {
                    val fileLabel = if (report.removedSizeEvictedCount == 1) "Datei" else "Dateien"
                    "Bild-Cache manuell geleert: ${report.removedSizeEvictedCount} $fileLabel entfernt (${ChapterCacheManager.formatBytesDe(report.freedBytes)} frei)."
                } else {
                    "Bild-Cache ist bereits leer."
                }
                _uiEvents.emit(UiEvent.ShowSnackbar(msg))
            } finally {
                _isCacheBusy.value = false
            }
        }
    }

    fun invalidateSingleImageCache(imageUrl: String, pageIndex: Int, onDone: () -> Unit) {
        val app = mangaApp
        if (app == null) {
            onDone()
            return
        }
        viewModelScope.launch {
            app.invalidateSingleImageCache(imageUrl)
            _cacheStats.value = app.getCacheStats()
            onDone()
            _uiEvents.emit(
                UiEvent.ShowSnackbar("Seite ${pageIndex + 1}: Cache-Bypass aktiv — Bild wird frisch geladen...")
            )
        }
    }

    fun notifyImageBypassSucceeded(pageIndex: Int) {
        refreshCacheStats()
        viewModelScope.launch {
            _uiEvents.emit(
                UiEvent.ShowSnackbar("Seite ${pageIndex + 1} über Cache-Bypass erfolgreich neu vom Server geladen.")
            )
        }
    }

    // --- Tab Management ---

    fun addNewTab(url: String = "https://mangadex.org") {
        val newTab = WebTab(
            title = "Neuer Tab",
            url = url
        )
        _tabs.value = _tabs.value + newTab
        selectTab(newTab.id)
        scheduleSaveSession()
    }

    fun selectTab(tabId: String) {
        val tab = _tabs.value.find { it.id == tabId } ?: return
        _activeWebTabId.value = tabId
        _currentUrl.value = tab.url
        _pageTitle.value = tab.title
        _currentChapter.value = tab.chapter
        requestTargetPage(tab.lastReadPage)
        _prefetchState.value = PrefetchState()
        prefetchedChapter = null
        scheduleSaveSession()
    }

    fun closeTab(tabId: String) {
        val currentList = _tabs.value
        if (currentList.size <= 1) return

        val newTabs = currentList.filter { it.id != tabId }
        _tabs.value = newTabs

        if (_activeWebTabId.value == tabId) {
            val nextActive = newTabs.last()
            selectTab(nextActive.id)
        } else {
            scheduleSaveSession()
        }
    }

    // --- URL Navigation (with SSRF/Scheme Safety Check) ---

    fun setUrlInput(url: String) {
        _currentUrl.value = url
    }

    fun navigateToUrl(url: String) {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return

        val lower = trimmed.lowercase()
        val hasExplicitScheme = lower.contains("://") ||
            lower.startsWith("javascript:") ||
            lower.startsWith("data:") ||
            lower.startsWith("vbscript:") ||
            lower.startsWith("file:")

        val formatted = if (hasExplicitScheme) trimmed else "https://$trimmed"
        val validation = UrlSafety.validateExternalUrl(formatted)
        if (!validation.valid) {
            viewModelScope.launch {
                _uiEvents.emit(
                    UiEvent.ShowSnackbar(validation.reason ?: "Unsichere URL aus Sicherheitsgründen blockiert.")
                )
            }
            return
        }

        val safeUrl = validation.normalizedUrl ?: formatted
        _currentUrl.value = safeUrl
        _tabs.value = _tabs.value.map { tab ->
            if (tab.id == _activeWebTabId.value) tab.copy(url = safeUrl) else tab
        }
        scheduleSaveSession()
        viewModelScope.launch {
            _uiEvents.emit(UiEvent.NavigateWebView(safeUrl))
        }
    }

    fun openDirectUrlAndExtract(rawUrl: String) {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return

        val lower = trimmed.lowercase()
        val hasExplicitScheme = lower.contains("://") ||
            lower.startsWith("javascript:") ||
            lower.startsWith("data:") ||
            lower.startsWith("vbscript:") ||
            lower.startsWith("file:")

        val formatted = if (hasExplicitScheme) trimmed else "https://$trimmed"
        val validation = UrlSafety.validateExternalUrl(formatted)
        if (!validation.valid) {
            viewModelScope.launch {
                _uiEvents.emit(
                    UiEvent.ShowSnackbar(validation.reason ?: "Unsichere URL aus Sicherheitsgründen blockiert.")
                )
            }
            return
        }

        val safeUrl = validation.normalizedUrl ?: formatted
        pendingTargetPage = 0
        pendingAutoExtractOnLoad = true
        _currentUrl.value = safeUrl
        _tabs.value = _tabs.value.map { tab ->
            if (tab.id == _activeWebTabId.value) tab.copy(url = safeUrl, lastReadPage = 0) else tab
        }
        scheduleSaveSession()
        _activeTab.value = AppTab.BROWSER
        viewModelScope.launch {
            _uiEvents.emit(UiEvent.NavigateWebView(safeUrl))
            _uiEvents.emit(UiEvent.ShowSnackbar("Lade Kapitel..."))
        }
    }

    fun openCurrentChapterInBrowser() {
        _isAutoScrolling.value = false
        val ch = _currentChapter.value
        if (ch != null && !ch.isOffline && UrlSafety.validateExternalUrl(ch.chapterUrl).valid) {
            _currentUrl.value = ch.chapterUrl
            _tabs.value = _tabs.value.map { tab ->
                if (tab.id == _activeWebTabId.value) tab.copy(url = ch.chapterUrl) else tab
            }
            scheduleSaveSession()
            viewModelScope.launch {
                _uiEvents.emit(UiEvent.NavigateWebView(ch.chapterUrl))
            }
        }
        _activeTab.value = AppTab.BROWSER
    }

    fun onWebPageStarted(url: String) {
        _currentUrl.value = url
        _isWebLoading.value = true
        _tabs.value = _tabs.value.map { tab ->
            if (tab.id == _activeWebTabId.value) tab.copy(url = url) else tab
        }
    }

    fun onWebProgressChanged(progress: Int) {
        _webProgress.value = progress / 100f
    }

    fun getLastReadPageForActiveTab(): Int {
        val ch = _currentChapter.value
        if (ch != null && ch.isOffline) {
            return _targetPageRequest.value.second
        }
        val currentTabId = _activeWebTabId.value
        return _tabs.value.find { it.id == currentTabId }?.lastReadPage ?: 0
    }

    fun refreshChapterSession() {
        val ch = _currentChapter.value ?: return
        if (ch.isOffline) return

        pendingSessionRefresh = true
        viewModelScope.launch {
            _uiEvents.emit(UiEvent.ShowSnackbar("Erneuere Web-Sitzung im Hintergrund..."))
            _uiEvents.emit(UiEvent.NavigateWebView(ch.chapterUrl))
        }
    }

    fun onWebPageFinished(url: String, title: String) {
        _currentUrl.value = url
        val cleanTitle = if (title.isNotBlank()) title else "Manga"
        _pageTitle.value = cleanTitle
        _isWebLoading.value = false

        _tabs.value = _tabs.value.map { tab ->
            if (tab.id == _activeWebTabId.value) tab.copy(url = url, title = cleanTitle) else tab
        }
        scheduleSaveSession()

        if (pendingSessionRefresh) {
            pendingSessionRefresh = false
            _sessionRefreshTrigger.value++
            viewModelScope.launch {
                _uiEvents.emit(UiEvent.ShowSnackbar("Sitzung erneuert! Bilder werden nachgeladen..."))
            }
        }

        if (pendingAutoExtractOnLoad) {
            pendingAutoExtractOnLoad = false
            viewModelScope.launch {
                delay(1200)
                triggerExtraction()
            }
        }
    }

    fun triggerExtraction() {
        extractionTimeoutJob?.cancel()
        _isExtracting.value = true
        extractionTimeoutJob = viewModelScope.launch {
            delay(12_000)
            if (_isExtracting.value) {
                _isExtracting.value = false
                _uiEvents.emit(
                    UiEvent.ShowSnackbar("Zeitüberschreitung bei der Bildextraktion. Bitte erneut versuchen.")
                )
            }
        }
        viewModelScope.launch {
            _uiEvents.emit(UiEvent.TriggerExtraction)
        }
    }

    fun onExtractionFailed(message: String) {
        extractionTimeoutJob?.cancel()
        _isExtracting.value = false
        viewModelScope.launch {
            _uiEvents.emit(UiEvent.ShowSnackbar(message))
        }
    }

    fun onImagesExtracted(result: ExtractionResult, sourceUrl: String) {
        extractionTimeoutJob?.cancel()
        _isExtracting.value = false
        if (result.images.isEmpty()) {
            viewModelScope.launch {
                _uiEvents.emit(UiEvent.ShowSnackbar("Keine Manga-Bilder auf dieser Seite gefunden."))
            }
            return
        }

        val analysis = UrlChapterAnalyzer.analyzeChapterUrl(sourceUrl)
        val nextUrl = result.nextChapterUrl ?: analysis.nextChapterUrl
        val prevUrl = analysis.prevChapterUrl

        val chapter = Chapter(
            title = if (result.title.isNotBlank()) result.title else UrlChapterAnalyzer.extractChapterTitleFromUrl(sourceUrl),
            chapterUrl = sourceUrl,
            images = result.images,
            nextChapterUrl = nextUrl,
            prevChapterUrl = prevUrl,
            isOffline = false
        )

        val targetPage = (pendingTargetPage ?: 0).coerceIn(0, (chapter.images.size - 1).coerceAtLeast(0))
        pendingTargetPage = null

        _tabs.value = _tabs.value.map { tab ->
            if (tab.id == _activeWebTabId.value) {
                tab.copy(chapter = chapter, url = sourceUrl, title = chapter.title, lastReadPage = targetPage)
            } else {
                tab
            }
        }
        scheduleSaveSession()

        prefetchedChapter = null
        _prefetchState.value = PrefetchState(forChapterUrl = chapter.nextChapterUrl.orEmpty())
        _currentChapter.value = chapter
        requestTargetPage(targetPage)
        recordHistory(chapter, targetPage)
        _activeTab.value = AppTab.READER

        viewModelScope.launch {
            _uiEvents.emit(UiEvent.ShowSnackbar("${result.images.size} Seiten geladen!"))
        }
    }

    fun loadNextChapter() {
        val chapter = _currentChapter.value
        val nextUrl = chapter?.nextChapterUrl
            ?: UrlChapterAnalyzer.predictNextChapterUrl(chapter?.chapterUrl ?: _currentUrl.value)

        if (!nextUrl.isNullOrBlank()) {
            val cachedPrefetch = prefetchedChapter
            if (cachedPrefetch != null && cachedPrefetch.chapterUrl == nextUrl) {
                prefetchedChapter = null
                _prefetchState.value = PrefetchState()
                onImagesExtracted(
                    ExtractionResult(cachedPrefetch.title, cachedPrefetch.images, cachedPrefetch.nextChapterUrl),
                    nextUrl
                )
                _currentUrl.value = nextUrl
                viewModelScope.launch {
                    _uiEvents.emit(UiEvent.NavigateWebView(nextUrl))
                }
                return
            }

            if (!UrlSafety.validateExternalUrl(nextUrl).valid) {
                viewModelScope.launch {
                    _uiEvents.emit(UiEvent.ShowSnackbar("Folgelink wurde aus Sicherheitsgründen blockiert."))
                }
                return
            }

            viewModelScope.launch {
                _uiEvents.emit(UiEvent.ShowSnackbar("Lade nächstes Kapitel nahtlos..."))
                pendingAutoExtractOnLoad = true
                _currentUrl.value = nextUrl
                _tabs.value = _tabs.value.map { tab ->
                    if (tab.id == _activeWebTabId.value) tab.copy(url = nextUrl) else tab
                }
                _uiEvents.emit(UiEvent.NavigateWebView(nextUrl))
            }
        } else {
            viewModelScope.launch {
                _uiEvents.emit(UiEvent.ShowSnackbar("Kein Folgelink gefunden. Öffne Seite im Browser."))
                _activeTab.value = AppTab.BROWSER
            }
        }
    }

    fun loadPrevChapter() {
        val chapter = _currentChapter.value
        val prevUrl = chapter?.prevChapterUrl
            ?: UrlChapterAnalyzer.predictPrevChapterUrl(chapter?.chapterUrl ?: _currentUrl.value)

        if (!prevUrl.isNullOrBlank() && UrlSafety.validateExternalUrl(prevUrl).valid) {
            viewModelScope.launch {
                _uiEvents.emit(UiEvent.ShowSnackbar("Lade vorheriges Kapitel..."))
                pendingAutoExtractOnLoad = true
                _currentUrl.value = prevUrl
                _tabs.value = _tabs.value.map { tab ->
                    if (tab.id == _activeWebTabId.value) tab.copy(url = prevUrl) else tab
                }
                _uiEvents.emit(UiEvent.NavigateWebView(prevUrl))
            }
        } else {
            viewModelScope.launch {
                _uiEvents.emit(UiEvent.ShowSnackbar("Kein vorheriges Kapitel ermittelbar."))
            }
        }
    }

    fun downloadCurrentChapter() {
        val chapter = _currentChapter.value
        if (chapter == null || chapter.images.isEmpty()) {
            viewModelScope.launch {
                _uiEvents.emit(UiEvent.ShowSnackbar("Kein aktives Kapitel zum Herunterladen vorhanden."))
            }
            return
        }

        if (chapter.isOffline) {
            viewModelScope.launch {
                _uiEvents.emit(UiEvent.ShowSnackbar("Dieses Kapitel ist bereits offline gespeichert."))
            }
            return
        }

        val workId = downloadRepo.startDownload(chapter)
        viewModelScope.launch {
            _uiEvents.emit(UiEvent.ShowSnackbar("Download für '${chapter.title}' gestartet..."))
            downloadRepo.observeWork(workId).collect { state ->
                _downloadState.value = state
                if (state is DownloadState.Completed) {
                    refreshDownloads()
                    _uiEvents.emit(UiEvent.ShowSnackbar("Kapitel '${state.chapterTitle}' erfolgreich heruntergeladen!"))
                } else if (state is DownloadState.Failed) {
                    _uiEvents.emit(UiEvent.ShowSnackbar("Fehler beim Download: ${state.error}"))
                }
            }
        }
    }

    fun refreshDownloads() {
        viewModelScope.launch {
            _downloadedChapters.value = downloadRepo.getDownloadedChapters()
        }
    }

    fun openDownloadedChapter(downloaded: DownloadedChapter) {
        val chapter = Chapter(
            title = downloaded.title,
            chapterUrl = downloaded.sourceUrl,
            images = downloaded.imageFilePaths,
            nextChapterUrl = null,
            prevChapterUrl = null,
            isOffline = true
        )
        _isAutoScrolling.value = false
        _currentChapter.value = chapter
        requestTargetPage(0)
        recordHistory(chapter, 0)
        _activeTab.value = AppTab.READER
    }

    fun deleteDownloadedChapter(chapterId: String) {
        viewModelScope.launch {
            downloadRepo.deleteChapter(chapterId)
            refreshDownloads()
            _uiEvents.emit(UiEvent.ShowSnackbar("Offline-Kapitel gelöscht."))
        }
    }

    fun toggleReaderMode() {
        setReaderMode(
            if (_readerMode.value == ReaderMode.VERTICAL_WEBTOON) {
                ReaderMode.HORIZONTAL_PAGED
            } else {
                ReaderMode.VERTICAL_WEBTOON
            }
        )
    }

    fun setReaderMode(mode: ReaderMode) {
        if (_readerMode.value == mode) return
        _readerMode.value = mode
        if (mode != ReaderMode.VERTICAL_WEBTOON) {
            _isAutoScrolling.value = false
        }
        scheduleSaveSession()
    }

    fun setActiveTab(tab: AppTab) {
        if (tab != AppTab.READER) {
            _isAutoScrolling.value = false
        }
        if (tab == AppTab.LIBRARY) {
            refreshCacheStats()
            refreshDownloads()
        }
        _activeTab.value = tab
    }

    // --- Library & Bookmarks Management ---

    fun toggleCurrentChapterBookmark() {
        val chapter = _currentChapter.value ?: return
        val existing = _bookmarks.value.find { it.chapterUrl == chapter.chapterUrl }
        val activeTab = _tabs.value.find { it.id == _activeWebTabId.value }
        val updatedBookmarks = if (existing != null) {
            _bookmarks.value.filter { it.id != existing.id }
        } else {
            val newItem = BookmarkItem(
                title = if (!chapter.isOffline && activeTab?.title?.isNotBlank() == true) activeTab.title else chapter.title,
                chapterTitle = chapter.title,
                chapterUrl = chapter.chapterUrl,
                coverImageUrl = chapter.images.firstOrNull(),
                lastReadPage = currentReaderPageIndex,
                totalPages = chapter.images.size,
                isBookmark = true
            )
            listOf(newItem) + _bookmarks.value
        }
        _bookmarks.value = updatedBookmarks
        persistLibraryItems()
        viewModelScope.launch {
            _uiEvents.emit(
                UiEvent.ShowSnackbar(
                    if (existing != null) "Lesezeichen entfernt." else "Zu Lesezeichen hinzugefügt! ⭐"
                )
            )
        }
    }

    fun recordHistory(chapter: Chapter, page: Int) {
        val activeTab = _tabs.value.find { it.id == _activeWebTabId.value }
        var changed = false

        if (_readerPreferences.value.isHistoryTrackingEnabled) {
            val currentHistory = _history.value.toMutableList()
            currentHistory.removeAll { it.chapterUrl == chapter.chapterUrl }
            val newItem = BookmarkItem(
                title = if (!chapter.isOffline && activeTab?.title?.isNotBlank() == true) activeTab.title else chapter.title,
                chapterTitle = chapter.title,
                chapterUrl = chapter.chapterUrl,
                coverImageUrl = chapter.images.firstOrNull(),
                lastReadPage = page,
                totalPages = chapter.images.size,
                isBookmark = false
            )
            currentHistory.add(0, newItem)
            _history.value = currentHistory.take(60)
            changed = true
        }

        // Always keep matching bookmark's lastReadPage synchronized
        if (_bookmarks.value.any { it.chapterUrl == chapter.chapterUrl }) {
            _bookmarks.value = _bookmarks.value.map { bm ->
                if (bm.chapterUrl == chapter.chapterUrl) bm.copy(lastReadPage = page, timestamp = System.currentTimeMillis()) else bm
            }
            changed = true
        }

        if (changed) {
            persistLibraryItems()
        }
    }

    private fun persistLibraryItems() {
        persistLibraryJob?.cancel()
        persistLibraryJob = viewModelScope.launch {
            delay(400)
            libraryRepo.saveItems(_bookmarks.value + _history.value)
        }
    }

    fun deleteLibraryItem(id: String) {
        _bookmarks.value = _bookmarks.value.filter { it.id != id }
        _history.value = _history.value.filter { it.id != id }
        persistLibraryItems()
    }

    fun clearHistory() {
        _history.value = emptyList()
        persistLibraryItems()
        viewModelScope.launch {
            _uiEvents.emit(UiEvent.ShowSnackbar("Verlauf geleert."))
        }
    }

    fun openLibraryItem(item: BookmarkItem) {
        val currentCh = _currentChapter.value
        if (currentCh != null && currentCh.chapterUrl == item.chapterUrl) {
            _tabs.value = _tabs.value.map { tab ->
                if (tab.id == _activeWebTabId.value) {
                    tab.copy(lastReadPage = item.lastReadPage)
                } else {
                    tab
                }
            }
            requestTargetPage(item.lastReadPage)
            _activeTab.value = AppTab.READER
            return
        }

        if (!UrlSafety.validateExternalUrl(item.chapterUrl).valid) {
            viewModelScope.launch {
                _uiEvents.emit(UiEvent.ShowSnackbar("Ungültige oder blockierte Kapitel-URL."))
            }
            return
        }

        pendingTargetPage = item.lastReadPage
        pendingAutoExtractOnLoad = true
        _currentUrl.value = item.chapterUrl
        _tabs.value = _tabs.value.map { tab ->
            if (tab.id == _activeWebTabId.value) {
                tab.copy(url = item.chapterUrl, title = item.title, lastReadPage = item.lastReadPage)
            } else {
                tab
            }
        }
        scheduleSaveSession()
        _activeTab.value = AppTab.BROWSER
        viewModelScope.launch {
            _uiEvents.emit(UiEvent.NavigateWebView(item.chapterUrl))
            _uiEvents.emit(UiEvent.ShowSnackbar("Lade '${item.chapterTitle}'..."))
        }
    }

    // --- Reader Preferences, Autoscroll & Display Settings ---

    fun updateReaderPreferences(prefs: ReaderPreferences) {
        _readerPreferences.value = prefs
        viewModelScope.launch {
            libraryRepo.savePreferences(prefs)
        }
    }

    fun setHistoryTrackingEnabled(enabled: Boolean) {
        updateReaderPreferences(_readerPreferences.value.copy(isHistoryTrackingEnabled = enabled))
    }

    fun setReadingDirection(direction: ReadingDirection) {
        updateReaderPreferences(_readerPreferences.value.copy(readingDirection = direction))
    }

    fun setKeepScreenOn(enabled: Boolean) {
        updateReaderPreferences(_readerPreferences.value.copy(keepScreenOn = enabled))
    }

    fun toggleAutoScroll() {
        _isAutoScrolling.value = !_isAutoScrolling.value
    }

    fun setAutoScroll(active: Boolean) {
        _isAutoScrolling.value = active
    }

    fun setAutoScrollSpeed(speed: Float) {
        updateReaderPreferences(_readerPreferences.value.copy(autoScrollSpeed = speed))
    }

    fun setCustomBrightness(enabled: Boolean, level: Float = _readerPreferences.value.brightnessLevel) {
        updateReaderPreferences(
            _readerPreferences.value.copy(
                isCustomBrightnessEnabled = enabled,
                brightnessLevel = level.coerceIn(0.20f, 1.0f)
            )
        )
    }

    fun setNightFilter(enabled: Boolean, strength: Float = _readerPreferences.value.nightFilterStrength) {
        updateReaderPreferences(
            _readerPreferences.value.copy(
                isNightFilterEnabled = enabled,
                nightFilterStrength = strength.coerceIn(0.10f, 0.80f)
            )
        )
    }

    fun setPrefetchEnabled(enabled: Boolean) {
        updateReaderPreferences(_readerPreferences.value.copy(isPrefetchEnabled = enabled))
    }

    fun prefetchNextChapter() {
        if (!_readerPreferences.value.isPrefetchEnabled) return
        if (isPrefetchInProgress) return

        val chapter = _currentChapter.value ?: return
        if (chapter.isOffline) return

        val nextUrl = chapter.nextChapterUrl
            ?: UrlChapterAnalyzer.predictNextChapterUrl(chapter.chapterUrl)
        if (nextUrl.isNullOrBlank() || nextUrl == chapter.chapterUrl) return
        if (!UrlSafety.validateExternalUrl(nextUrl).valid) return

        if (prefetchedChapter != null && prefetchedChapter?.chapterUrl == nextUrl) return

        isPrefetchInProgress = true
        _prefetchState.value = PrefetchState(forChapterUrl = nextUrl, status = PrefetchStatus.LOADING)

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val cookie = runCatching {
                    CookieManager.getInstance().getCookie(nextUrl)
                }.getOrNull()

                val client = mangaApp?.okHttpClient ?: OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(20, TimeUnit.SECONDS)
                    .build()

                val request = Request.Builder()
                    .url(nextUrl)
                    .header("User-Agent", AppConstants.USER_AGENT)
                    .header("Referer", chapter.chapterUrl)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .apply {
                        if (!cookie.isNullOrBlank()) header("Cookie", cookie)
                    }
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        _prefetchState.value = PrefetchState(forChapterUrl = nextUrl, status = PrefetchStatus.UNAVAILABLE)
                        return@use
                    }

                    val body = response.body ?: return@use
                    val html = readLimitedString(body.byteStream(), maxBytes = 4 * 1024 * 1024)
                    if (html.isBlank()) {
                        _prefetchState.value = PrefetchState(forChapterUrl = nextUrl, status = PrefetchStatus.UNAVAILABLE)
                        return@use
                    }

                    val images = UrlChapterAnalyzer.extractImagesFromHtml(html, nextUrl)
                    if (images.isEmpty()) {
                        _prefetchState.value = PrefetchState(forChapterUrl = nextUrl, status = PrefetchStatus.UNAVAILABLE)
                        return@use
                    }

                    val nextNext = UrlChapterAnalyzer.extractNextChapterUrlFromHtml(html, nextUrl)
                        ?: UrlChapterAnalyzer.predictNextChapterUrl(nextUrl)
                    val title = UrlChapterAnalyzer.extractTitleFromHtml(html, nextUrl)
                        .ifBlank { UrlChapterAnalyzer.extractChapterTitleFromUrl(nextUrl) }

                    val nextChapter = Chapter(
                        title = title,
                        chapterUrl = nextUrl,
                        images = images,
                        nextChapterUrl = nextNext,
                        prevChapterUrl = chapter.chapterUrl,
                        isOffline = false
                    )
                    prefetchedChapter = nextChapter
                    _prefetchState.value = PrefetchState(
                        forChapterUrl = nextUrl,
                        status = PrefetchStatus.READY,
                        chapter = nextChapter
                    )

                    // Preload the first 3 pages into Coil cache for instant display
                    mangaApp?.let { app ->
                        val loader = Coil.imageLoader(app)
                        images.take(3).forEach { imgUrl ->
                            val req = ImageRequest.Builder(app)
                                .data(imgUrl)
                                .setHeader("Referer", nextUrl)
                                .setParameter(MangaReaderApplication.PARAM_IMAGE_URL, imgUrl)
                                .build()
                            loader.enqueue(req)
                        }
                    }
                    Unit
                }
            } catch (e: Exception) {
                _prefetchState.value = PrefetchState(forChapterUrl = nextUrl, status = PrefetchStatus.UNAVAILABLE)
            } finally {
                isPrefetchInProgress = false
            }
        }
    }

    private fun readLimitedString(stream: java.io.InputStream, maxBytes: Int): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (total < maxBytes) {
            val read = stream.read(buffer, 0, minOf(buffer.size, maxBytes - total))
            if (read <= 0) break
            out.write(buffer, 0, read)
            total += read
        }
        return out.toString(Charsets.UTF_8.name())
    }

    // --- Assistant (Multi-Turn Gemini Chat) ---

    fun selectPersona(persona: ChatPersonaId) {
        _selectedPersona.value = persona
        chatRepo.savePersonaSync(persona)
    }

    fun selectModel(model: ChatModelId) {
        _selectedModel.value = model
        chatRepo.saveModelSync(model)
    }

    fun saveApiKey(rawKey: String) {
        val cleaned = rawKey.trim()
        _apiKey.value = cleaned
        _hasApiKey.value = cleaned.isNotBlank()
        chatRepo.saveApiKeySync(cleaned)
        viewModelScope.launch {
            _uiEvents.emit(
                UiEvent.ShowSnackbar(
                    if (cleaned.isNotBlank()) "Gemini-API-Schlüssel gespeichert." else "Gemini-API-Schlüssel entfernt."
                )
            )
        }
    }

    fun clearApiKey() {
        saveApiKey("")
    }

    fun stopAssistantStreaming() {
        chatStreamJob?.cancel()
        chatStreamJob = null
        _isAssistantStreaming.value = false
    }

    fun clearChatHistory() {
        stopAssistantStreaming()
        _chatMessages.value = emptyList()
        scheduleSaveChatHistory()
        viewModelScope.launch {
            _uiEvents.emit(UiEvent.ShowSnackbar("Assistent-Verlauf zurückgesetzt."))
        }
    }

    fun sendChatMessage(rawText: String) {
        val cleaned = ChatHelpers.sanitizeUserMessage(rawText) ?: return
        if (_isAssistantStreaming.value) return

        val key = _apiKey.value
        val model = _selectedModel.value
        val persona = _selectedPersona.value

        val userMsg = ChatMessage(
            role = "user",
            text = cleaned
        )
        val assistantMsg = ChatMessage(
            role = "model",
            text = "",
            modelId = model.id
        )

        val historySnapshot = _chatMessages.value
        _chatMessages.value = historySnapshot + userMsg + assistantMsg
        _isAssistantStreaming.value = true
        scheduleSaveChatHistory()

        val activeCh = _currentChapter.value
        val activeWebTab = _tabs.value.find { it.id == _activeWebTabId.value }
        val contextSummary = ChatContextSummary(
            seriesTitle = activeWebTab?.title ?: activeCh?.title,
            chapterTitle = activeCh?.title,
            currentPage = activeCh?.let { currentReaderPageIndex + 1 },
            totalPages = activeCh?.images?.size,
            cacheLimitLabel = ChapterCacheManager.formatCacheLimitLabel(_cacheLimitMb.value)
        )

        chatStreamJob = viewModelScope.launch {
            try {
                geminiClient.streamMessage(
                    apiKey = key,
                    model = model,
                    persona = persona,
                    history = historySnapshot,
                    userMessage = cleaned,
                    contextSummary = contextSummary
                ).collect { chunk ->
                    _chatMessages.value = _chatMessages.value.map { m ->
                        if (m.id == assistantMsg.id) m.copy(text = m.text + chunk) else m
                    }
                }

                // Fallback if stream yielded zero text
                _chatMessages.value = _chatMessages.value.map { m ->
                    if (m.id == assistantMsg.id && m.text.isBlank()) {
                        m.copy(
                            text = "Keine Textantwort erhalten. Bitte stelle deine Frage noch einmal.",
                            isError = true
                        )
                    } else {
                        m
                    }
                }
            } catch (_: CancellationException) {
                _chatMessages.value = _chatMessages.value.map { m ->
                    if (m.id == assistantMsg.id && m.text.isBlank()) {
                        m.copy(text = "Antwort abgebrochen.")
                    } else {
                        m
                    }
                }
            } catch (e: Exception) {
                val errText = e.message?.takeIf { it.isNotBlank() }
                    ?: "Verbindungsfehler zum Assistenten."
                _chatMessages.value = _chatMessages.value.map { m ->
                    if (m.id == assistantMsg.id) {
                        m.copy(text = errText, isError = true)
                    } else {
                        m
                    }
                }
            } finally {
                _isAssistantStreaming.value = false
                chatStreamJob = null
                scheduleSaveChatHistory()
            }
        }
    }

    private fun scheduleSaveChatHistory() {
        persistChatJob?.cancel()
        persistChatJob = viewModelScope.launch {
            delay(350)
            chatRepo.saveMessages(_chatMessages.value)
        }
    }

    override fun onCleared() {
        super.onCleared()
        runBlocking {
            sessionManager.saveSession(_tabs.value, _activeWebTabId.value, _readerMode.value)
            libraryRepo.saveItems(_bookmarks.value + _history.value)
            chatRepo.saveMessages(_chatMessages.value)
        }
    }
}
