package com.mangareader.app.ui.viewmodel

import android.app.Application
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
import com.mangareader.app.data.extractor.ChapterLoadOutcome
import com.mangareader.app.data.extractor.ChapterLoaderRepository
import com.mangareader.app.data.extractor.UrlChapterAnalyzer
import com.mangareader.app.data.extractor.WebViewFallbackReason
import com.mangareader.app.data.library.LibraryRepository
import com.mangareader.app.data.local.LegacyStorageMigrator
import com.mangareader.app.data.local.MangaDatabase
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
import com.mangareader.app.data.network.MangaNetworkClients
import com.mangareader.app.data.session.SessionManager
import com.mangareader.app.data.settings.SettingsDataStore
import com.mangareader.app.ui.screens.reader.AutoScrollLogic
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

sealed class UiEvent {
    data class ShowSnackbar(val message: String) : UiEvent()
    data class NavigateWebView(val url: String) : UiEvent()
    data object TriggerExtraction : UiEvent()
    data object GoBackWebView : UiEvent()
}

class MangaViewModel(application: Application) : AndroidViewModel(application) {

    private val mangaApp: MangaReaderApplication? = application as? MangaReaderApplication
    private val database: MangaDatabase = mangaApp?.database ?: MangaDatabase.getInstance(application)
    private val settingsDataStore: SettingsDataStore = mangaApp?.settingsDataStore ?: SettingsDataStore(application)

    private val chapterLoaderRepo = ChapterLoaderRepository(
        chapterHttpClient = mangaApp?.chapterHttpClient ?: MangaNetworkClients.buildChapterHttpClient()
    )
    private val downloadRepo = DownloadRepository(application, database.downloadedChapterDao())
    private val sessionManager = SessionManager(database.webTabDao(), settingsDataStore)
    private val libraryRepo = LibraryRepository(
        bookmarkDao = database.bookmarkDao(),
        readingProgressDao = database.readingProgressDao(),
        settingsDataStore = settingsDataStore
    )

    // Browser Tabs Management (restored asynchronously from Room — zero Main-Thread disk I/O)
    private var hasUserMutatedTabsBeforeRestore: Boolean = false
    private var isSessionRestored: Boolean = false

    private val _tabs = MutableStateFlow<List<WebTab>>(emptyList())
    val tabs: StateFlow<List<WebTab>> = _tabs.asStateFlow()

    private val _activeWebTabId = MutableStateFlow("")
    val activeWebTabId: StateFlow<String> = _activeWebTabId.asStateFlow()

    // Current URL of the active tab
    private val _currentUrl = MutableStateFlow(DEFAULT_START_URL)
    val currentUrl: StateFlow<String> = _currentUrl.asStateFlow()

    private val _pageTitle = MutableStateFlow(DEFAULT_START_TITLE)
    val pageTitle: StateFlow<String> = _pageTitle.asStateFlow()

    private val _isWebLoading = MutableStateFlow(false)
    val isWebLoading: StateFlow<Boolean> = _isWebLoading.asStateFlow()

    private val _webProgress = MutableStateFlow(0f)
    val webProgress: StateFlow<Float> = _webProgress.asStateFlow()

    // Reader state (active chapter)
    private val _currentChapter = MutableStateFlow<Chapter?>(null)
    val currentChapter: StateFlow<Chapter?> = _currentChapter.asStateFlow()

    private val _readerMode = MutableStateFlow(ReaderMode.VERTICAL_WEBTOON)
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
    private val _targetPageRequest = MutableStateFlow(0 to 0)
    val targetPageRequest: StateFlow<Pair<Int, Int>> = _targetPageRequest.asStateFlow()

    private var currentReaderPageIndex: Int = 0

    // Download state (reaktiv aus Room)
    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    private val _downloadedChapters = MutableStateFlow<List<DownloadedChapter>>(emptyList())
    val downloadedChapters: StateFlow<List<DownloadedChapter>> = _downloadedChapters.asStateFlow()

    private val _isExtracting = MutableStateFlow(false)
    val isExtracting: StateFlow<Boolean> = _isExtracting.asStateFlow()

    // Library (Bookmarks & History) and Reader Preferences (reaktiv aus Room + DataStore)
    private val _bookmarks = MutableStateFlow<List<BookmarkItem>>(emptyList())
    val bookmarks: StateFlow<List<BookmarkItem>> = _bookmarks.asStateFlow()

    private val _history = MutableStateFlow<List<BookmarkItem>>(emptyList())
    val history: StateFlow<List<BookmarkItem>> = _history.asStateFlow()

    private val _readerPreferences = MutableStateFlow(ReaderPreferences())
    val readerPreferences: StateFlow<ReaderPreferences> = _readerPreferences.asStateFlow()

    private val _isAutoScrolling = MutableStateFlow(false)
    val isAutoScrolling: StateFlow<Boolean> = _isAutoScrolling.asStateFlow()

    // Configurable Image Cache State (Default 4 GB, Range 512 MB - 32 GB, reaktiv aus DataStore)
    private val _cacheLimitMb = MutableStateFlow(ChapterCacheManager.DEFAULT_CACHE_LIMIT_MB)
    val cacheLimitMb: StateFlow<Int> = _cacheLimitMb.asStateFlow()

    private val _cacheStats = MutableStateFlow(
        CacheStats(maxBytes = ChapterCacheManager.DEFAULT_MAX_CACHE_SIZE_BYTES)
    )
    val cacheStats: StateFlow<CacheStats> = _cacheStats.asStateFlow()

    private val _isCacheBusy = MutableStateFlow(false)
    val isCacheBusy: StateFlow<Boolean> = _isCacheBusy.asStateFlow()

    // Seamless auto-extract next chapter flag
    private var pendingAutoExtractOnLoad: Boolean = false
    private var pendingTargetPage: Int? = null
    private var extractionTimeoutJob: Job? = null
    private var nativeChapterLoadJob: Job? = null

    // Prefetch state & cache
    private var prefetchedChapter: Chapter? = null
    private var isPrefetchInProgress: Boolean = false
    private val _prefetchState = MutableStateFlow(PrefetchState())
    val prefetchState: StateFlow<PrefetchState> = _prefetchState.asStateFlow()

    // Assistant (Multi-turn Gemini Chat — loaded asynchronously on Dispatchers.IO)
    private val chatRepo = ChatRepository(application)
    private val geminiClient = GeminiChatClient()
    private var chatStreamJob: Job? = null

    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages.asStateFlow()

    private val _selectedPersona = MutableStateFlow(ChatPersonaId.MANGA_GUIDE)
    val selectedPersona: StateFlow<ChatPersonaId> = _selectedPersona.asStateFlow()

    private val _selectedModel = MutableStateFlow(ChatModelId.STANDARD)
    val selectedModel: StateFlow<ChatModelId> = _selectedModel.asStateFlow()

    private val _isAssistantStreaming = MutableStateFlow(false)
    val isAssistantStreaming: StateFlow<Boolean> = _isAssistantStreaming.asStateFlow()

    private val _apiKey = MutableStateFlow("")
    val hasApiKey: StateFlow<Boolean>
        get() = _hasApiKey
    private val _hasApiKey = MutableStateFlow(false)

    // Debounce save jobs to avoid excessive disk/DB writes on rapid scrolling or slider drags
    private var saveSessionJob: Job? = null
    private var persistProgressJob: Job? = null
    private var persistPreferencesJob: Job? = null
    private var persistCacheLimitJob: Job? = null
    private var persistChatJob: Job? = null
    private var pendingProgressChapter: Chapter? = null
    private var pendingProgressDisplayTitle: String = ""
    private var pendingProgressPage: Int = 0
    private var hasPendingSessionWrite: Boolean = false
    private var hasPendingChatWrite: Boolean = false

    // Session Refresh Trigger for retrying failed images after background token renewal
    private val _sessionRefreshTrigger = MutableStateFlow(0)
    val sessionRefreshTrigger: StateFlow<Int> = _sessionRefreshTrigger.asStateFlow()
    private var pendingSessionRefresh: Boolean = false

    // UI Events
    private val _uiEvents = MutableSharedFlow<UiEvent>()
    val uiEvents: SharedFlow<UiEvent> = _uiEvents.asSharedFlow()

    init {
        observePersistenceFlows()
        restoreInitialStateAsync()
    }

    private fun observePersistenceFlows() {
        viewModelScope.launch {
            libraryRepo.bookmarksFlow.collect { items ->
                _bookmarks.value = items
            }
        }
        viewModelScope.launch {
            libraryRepo.historyFlow.collect { items ->
                _history.value = items
            }
        }
        viewModelScope.launch {
            libraryRepo.readerPreferencesFlow.collect { prefs ->
                if (persistPreferencesJob?.isActive != true) {
                    _readerPreferences.value = prefs
                }
            }
        }
        viewModelScope.launch {
            settingsDataStore.readerModeFlow.collect { mode ->
                _readerMode.value = mode
            }
        }
        viewModelScope.launch {
            settingsDataStore.cacheLimitMbFlow.collect { limitMb ->
                if (persistCacheLimitJob?.isActive != true) {
                    _cacheLimitMb.value = limitMb
                }
            }
        }
        viewModelScope.launch {
            downloadRepo.downloadedChaptersFlow.collect { chapters ->
                _downloadedChapters.value = chapters
            }
        }
        mangaApp?.let { app ->
            viewModelScope.launch {
                app.cacheManager.statsFlow.collect { stats ->
                    _cacheStats.value = stats
                }
            }
        }
    }

    private fun restoreInitialStateAsync() {
        viewModelScope.launch(Dispatchers.IO) {
            // 1. Ensure one-time migration from legacy JSON / SharedPreferences finishes first
            runCatching {
                mangaApp?.ensureLegacyMigrationCompleted()
                    ?: LegacyStorageMigrator(
                        context = getApplication(),
                        database = database,
                        settingsDataStore = settingsDataStore
                    ).migrateIfNeeded()
            }

            // 2. Restore saved WebTabs, active tab, chapter & exact reading progress
            val savedSession = runCatching { sessionManager.loadSession() }.getOrNull()
            if (!hasUserMutatedTabsBeforeRestore) {
                if (savedSession != null && savedSession.tabs.isNotEmpty()) {
                    val activeTab = savedSession.tabs.find { it.id == savedSession.activeTabId }
                        ?: savedSession.tabs.first()
                    val exactSavedPage = activeTab.chapter?.let { ch ->
                        val stored = libraryRepo.getReadingProgress(ch.chapterUrl)
                        val candidate = stored?.lastReadPage ?: activeTab.lastReadPage
                        candidate.coerceIn(0, (ch.images.size - 1).coerceAtLeast(0))
                    } ?: activeTab.lastReadPage.coerceAtLeast(0)

                    val syncedTabs = savedSession.tabs.map { tab ->
                        if (tab.id == activeTab.id) tab.copy(lastReadPage = exactSavedPage) else tab
                    }

                    _tabs.value = syncedTabs
                    _activeWebTabId.value = activeTab.id
                    _currentUrl.value = activeTab.url
                    _pageTitle.value = activeTab.title
                    _currentChapter.value = activeTab.chapter
                    _readerMode.value = savedSession.readerMode
                    requestTargetPage(exactSavedPage)
                } else {
                    val defaultTab = createDefaultTab()
                    _tabs.value = listOf(defaultTab)
                    _activeWebTabId.value = defaultTab.id
                    _currentUrl.value = defaultTab.url
                    _pageTitle.value = defaultTab.title
                    sessionManager.saveSession(listOf(defaultTab), defaultTab.id, _readerMode.value)
                }
            }
            isSessionRestored = true

            // 3. Reconcile disk offline downloads into Room & refresh live cache stats
            runCatching { downloadRepo.syncWithDisk() }
            mangaApp?.let { app ->
                runCatching { _cacheStats.value = app.getCacheStats() }
            }

            // 4. Load assistant chat history & preferences off the Main-Thread
            val chatInit = runCatching { chatRepo.loadInitialState() }.getOrNull()
            if (chatInit != null) {
                if (_chatMessages.value.isEmpty()) {
                    _chatMessages.value = chatInit.messages
                }
                _selectedPersona.value = chatInit.persona
                _selectedModel.value = chatInit.model
                if (_apiKey.value.isBlank()) {
                    _apiKey.value = chatInit.apiKey
                    _hasApiKey.value = chatInit.apiKey.isNotBlank()
                }
            }
        }
    }

    private fun createDefaultTab(url: String = DEFAULT_START_URL, title: String = DEFAULT_START_TITLE): WebTab =
        WebTab(title = title, url = url)

    private fun ensureActiveTabExists(initialUrl: String = DEFAULT_START_URL): String {
        val current = _tabs.value
        val activeId = _activeWebTabId.value
        if (current.isNotEmpty() && current.any { it.id == activeId }) {
            return activeId
        }
        val tab = createDefaultTab(url = initialUrl)
        _tabs.value = listOf(tab)
        _activeWebTabId.value = tab.id
        return tab.id
    }

    private fun requestTargetPage(page: Int) {
        val safePage = page.coerceAtLeast(0)
        currentReaderPageIndex = safePage
        _targetPageRequest.value = (_targetPageRequest.value.first + 1) to safePage
    }

    private fun scheduleSaveSession() {
        hasPendingSessionWrite = true
        saveSessionJob?.cancel()
        saveSessionJob = viewModelScope.launch {
            delay(350)
            val tabsSnapshot = _tabs.value
            val activeIdSnapshot = _activeWebTabId.value
            val modeSnapshot = _readerMode.value
            hasPendingSessionWrite = false
            if (tabsSnapshot.isNotEmpty()) {
                sessionManager.saveSession(
                    tabs = tabsSnapshot,
                    activeTabId = activeIdSnapshot,
                    readerMode = modeSnapshot
                )
            }
        }
    }

    fun handleBrowserBack() {
        viewModelScope.launch {
            _uiEvents.emit(UiEvent.GoBackWebView)
        }
    }

    fun updateLastReadPage(pageIndex: Int) {
        val safePage = pageIndex.coerceAtLeast(0)
        currentReaderPageIndex = safePage
        val ch = _currentChapter.value ?: return
        val activeTab = _tabs.value.find { it.id == _activeWebTabId.value }
        val displayTitle = if (!ch.isOffline && activeTab?.title?.isNotBlank() == true) activeTab.title else ch.title

        if (ch.isOffline) {
            schedulePersistReadingProgress(ch, displayTitle, safePage)
            return
        }

        val currentTabId = _activeWebTabId.value
        val currentList = _tabs.value
        val targetTab = currentList.find { it.id == currentTabId }
        if (targetTab != null && targetTab.lastReadPage != safePage) {
            _tabs.value = currentList.map { tab ->
                if (tab.id == currentTabId) tab.copy(lastReadPage = safePage) else tab
            }
            scheduleSaveSession()
        }
        schedulePersistReadingProgress(ch, displayTitle, safePage)
    }

    private fun schedulePersistReadingProgress(chapter: Chapter, displayTitle: String, page: Int) {
        pendingProgressChapter = chapter
        pendingProgressDisplayTitle = displayTitle
        pendingProgressPage = page
        persistProgressJob?.cancel()
        persistProgressJob = viewModelScope.launch {
            delay(350)
            val ch = pendingProgressChapter ?: return@launch
            val title = pendingProgressDisplayTitle
            val savedPage = pendingProgressPage
            pendingProgressChapter = null
            libraryRepo.recordReadingProgress(
                chapter = ch,
                displayTitle = title,
                page = savedPage,
                trackHistory = _readerPreferences.value.isHistoryTrackingEnabled
            )
        }
    }

    // --- Cache Manager Controls ---

    fun refreshCacheStats() {
        val app = mangaApp ?: return
        viewModelScope.launch {
            _cacheStats.value = app.getCacheStats()
            _cacheLimitMb.value = ChapterCacheManager.bytesToMb(settingsDataStore.getCacheLimitBytes())
        }
    }

    fun updateCacheLimitMb(limitMb: Int) {
        val clampedMb = ChapterCacheManager.clampCacheLimitMb(limitMb)
        _cacheLimitMb.value = clampedMb
        val newLimitBytes = ChapterCacheManager.mbToBytes(clampedMb)

        persistCacheLimitJob?.cancel()
        persistCacheLimitJob = viewModelScope.launch {
            delay(200)
            val app = mangaApp
            if (app != null) {
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
            } else {
                settingsDataStore.updateCacheLimitBytes(newLimitBytes)
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

    fun addNewTab(url: String = DEFAULT_START_URL) {
        hasUserMutatedTabsBeforeRestore = true
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
        hasUserMutatedTabsBeforeRestore = true
        val activeId = ensureActiveTabExists(safeUrl)
        _currentUrl.value = safeUrl
        _tabs.value = _tabs.value.map { tab ->
            if (tab.id == activeId) tab.copy(url = safeUrl) else tab
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
        loadChapterNativeWithWebViewFallback(
            url = safeUrl,
            refererUrl = null,
            prevChapterUrl = null,
            targetPage = null,
            openBrowserOnFallback = true,
            loadingMessage = "Lade Kapitel nativ..."
        )
    }

    fun openCurrentChapterInBrowser() {
        _isAutoScrolling.value = false
        val ch = _currentChapter.value
        if (ch != null && !ch.isOffline && UrlSafety.validateExternalUrl(ch.chapterUrl).valid) {
            val activeId = ensureActiveTabExists(ch.chapterUrl)
            _currentUrl.value = ch.chapterUrl
            _tabs.value = _tabs.value.map { tab ->
                if (tab.id == activeId) tab.copy(url = ch.chapterUrl) else tab
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
        val activeId = _activeWebTabId.value
        if (activeId.isNotBlank()) {
            _tabs.value = _tabs.value.map { tab ->
                if (tab.id == activeId) tab.copy(url = url) else tab
            }
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

        val activeId = _activeWebTabId.value
        if (activeId.isNotBlank()) {
            _tabs.value = _tabs.value.map { tab ->
                if (tab.id == activeId) tab.copy(url = url, title = cleanTitle) else tab
            }
            scheduleSaveSession()
        }

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

        val explicitTarget = pendingTargetPage
        pendingTargetPage = null
        viewModelScope.launch {
            val resolvedPage = explicitTarget ?: libraryRepo.getReadingProgress(sourceUrl)?.lastReadPage ?: 0
            applyLoadedChapter(chapter, resolvedPage)
        }
    }

    private fun applyLoadedChapter(chapter: Chapter, targetPage: Int) {
        val safePage = targetPage.coerceIn(0, (chapter.images.size - 1).coerceAtLeast(0))
        hasUserMutatedTabsBeforeRestore = true
        val activeId = ensureActiveTabExists(chapter.chapterUrl)

        _currentUrl.value = chapter.chapterUrl
        _pageTitle.value = chapter.title
        _tabs.value = _tabs.value.map { tab ->
            if (tab.id == activeId) {
                tab.copy(chapter = chapter, url = chapter.chapterUrl, title = chapter.title, lastReadPage = safePage)
            } else {
                tab
            }
        }
        scheduleSaveSession()

        prefetchedChapter = null
        _prefetchState.value = PrefetchState(forChapterUrl = chapter.nextChapterUrl.orEmpty())
        _currentChapter.value = chapter
        requestTargetPage(safePage)
        recordHistory(chapter, safePage)
        _activeTab.value = AppTab.READER

        viewModelScope.launch {
            _uiEvents.emit(UiEvent.ShowSnackbar("${chapter.images.size} Seiten geladen!"))
        }
    }

    private fun loadChapterNativeWithWebViewFallback(
        url: String,
        refererUrl: String?,
        prevChapterUrl: String?,
        targetPage: Int?,
        openBrowserOnFallback: Boolean,
        loadingMessage: String
    ) {
        nativeChapterLoadJob?.cancel()
        hasUserMutatedTabsBeforeRestore = true
        val activeId = ensureActiveTabExists(url)
        _isExtracting.value = true
        _currentUrl.value = url
        _tabs.value = _tabs.value.map { tab ->
            if (tab.id == activeId) tab.copy(url = url, lastReadPage = (targetPage ?: 0).coerceAtLeast(0)) else tab
        }
        scheduleSaveSession()

        nativeChapterLoadJob = viewModelScope.launch {
            _uiEvents.emit(UiEvent.ShowSnackbar(loadingMessage))
            try {
                when (
                    val outcome = chapterLoaderRepo.loadChapter(
                        chapterUrl = url,
                        refererUrl = refererUrl,
                        prevChapterUrl = prevChapterUrl
                    )
                ) {
                    is ChapterLoadOutcome.Success -> {
                        _isExtracting.value = false
                        val resolvedPage = targetPage
                            ?: libraryRepo.getReadingProgress(outcome.chapter.chapterUrl)?.lastReadPage
                            ?: 0
                        applyLoadedChapter(outcome.chapter, resolvedPage)
                    }
                    is ChapterLoadOutcome.FallbackToWebView -> {
                        _isExtracting.value = false
                        val isChallenge = outcome.reason == WebViewFallbackReason.SECURITY_CHALLENGE
                        pendingTargetPage = targetPage
                        pendingAutoExtractOnLoad = !isChallenge
                        _currentUrl.value = outcome.url
                        _tabs.value = _tabs.value.map { tab ->
                            if (tab.id == activeId) {
                                tab.copy(url = outcome.url, lastReadPage = (targetPage ?: 0).coerceAtLeast(0))
                            } else {
                                tab
                            }
                        }
                        scheduleSaveSession()
                        if (openBrowserOnFallback || isChallenge) {
                            _activeTab.value = AppTab.BROWSER
                        }
                        _uiEvents.emit(UiEvent.NavigateWebView(outcome.url))
                        _uiEvents.emit(UiEvent.ShowSnackbar(outcome.message))
                    }
                    is ChapterLoadOutcome.BlockedOrInvalid -> {
                        _isExtracting.value = false
                        _uiEvents.emit(UiEvent.ShowSnackbar(outcome.reason))
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                _isExtracting.value = false
                pendingTargetPage = targetPage
                pendingAutoExtractOnLoad = true
                if (openBrowserOnFallback) {
                    _activeTab.value = AppTab.BROWSER
                }
                _uiEvents.emit(UiEvent.NavigateWebView(url))
            }
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
                applyLoadedChapter(cachedPrefetch, targetPage = 0)
                return
            }

            if (!UrlSafety.validateExternalUrl(nextUrl).valid) {
                viewModelScope.launch {
                    _uiEvents.emit(UiEvent.ShowSnackbar("Folgelink wurde aus Sicherheitsgründen blockiert."))
                }
                return
            }

            loadChapterNativeWithWebViewFallback(
                url = nextUrl,
                refererUrl = chapter?.chapterUrl,
                prevChapterUrl = chapter?.chapterUrl,
                targetPage = 0,
                openBrowserOnFallback = false,
                loadingMessage = "Lade nächstes Kapitel nahtlos..."
            )
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
            loadChapterNativeWithWebViewFallback(
                url = prevUrl,
                refererUrl = chapter?.chapterUrl,
                prevChapterUrl = null,
                targetPage = 0,
                openBrowserOnFallback = false,
                loadingMessage = "Lade vorheriges Kapitel..."
            )
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
            downloadRepo.syncWithDisk()
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
        _activeTab.value = AppTab.READER

        viewModelScope.launch {
            val lookupKey = downloaded.sourceUrl.ifBlank { downloaded.title }
            val savedPage = libraryRepo.getReadingProgress(lookupKey)?.lastReadPage ?: 0
            val clampedPage = savedPage.coerceIn(0, (chapter.images.size - 1).coerceAtLeast(0))
            requestTargetPage(clampedPage)
            recordHistory(chapter, clampedPage)
        }
    }

    fun deleteDownloadedChapter(chapterId: String) {
        viewModelScope.launch {
            downloadRepo.deleteChapter(chapterId)
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
        viewModelScope.launch {
            settingsDataStore.updateReaderMode(mode)
        }
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

    // --- Library & Bookmarks Management (Room-backed) ---

    fun toggleCurrentChapterBookmark() {
        val chapter = _currentChapter.value ?: return
        val activeTab = _tabs.value.find { it.id == _activeWebTabId.value }
        val displayTitle = if (!chapter.isOffline && activeTab?.title?.isNotBlank() == true) {
            activeTab.title
        } else {
            chapter.title
        }
        val currentPage = currentReaderPageIndex

        viewModelScope.launch {
            val added = libraryRepo.toggleBookmark(
                chapter = chapter,
                displayTitle = displayTitle,
                currentPage = currentPage
            )
            _uiEvents.emit(
                UiEvent.ShowSnackbar(
                    if (added) "Zu Lesezeichen hinzugefügt! ⭐" else "Lesezeichen entfernt."
                )
            )
        }
    }

    fun recordHistory(chapter: Chapter, page: Int) {
        val activeTab = _tabs.value.find { it.id == _activeWebTabId.value }
        val displayTitle = if (!chapter.isOffline && activeTab?.title?.isNotBlank() == true) {
            activeTab.title
        } else {
            chapter.title
        }
        val trackHistory = _readerPreferences.value.isHistoryTrackingEnabled

        viewModelScope.launch {
            libraryRepo.recordReadingProgress(
                chapter = chapter,
                displayTitle = displayTitle,
                page = page,
                trackHistory = trackHistory
            )
        }
    }

    fun deleteLibraryItem(id: String) {
        viewModelScope.launch {
            libraryRepo.deleteItem(id)
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            libraryRepo.clearHistory()
            _uiEvents.emit(UiEvent.ShowSnackbar("Verlauf geleert."))
        }
    }

    fun openLibraryItem(item: BookmarkItem) {
        val currentCh = _currentChapter.value
        if (currentCh != null && currentCh.chapterUrl == item.chapterUrl) {
            val activeId = _activeWebTabId.value
            if (activeId.isNotBlank()) {
                _tabs.value = _tabs.value.map { tab ->
                    if (tab.id == activeId) tab.copy(lastReadPage = item.lastReadPage) else tab
                }
                scheduleSaveSession()
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

        loadChapterNativeWithWebViewFallback(
            url = item.chapterUrl,
            refererUrl = null,
            prevChapterUrl = null,
            targetPage = item.lastReadPage,
            openBrowserOnFallback = true,
            loadingMessage = "Lade '${item.chapterTitle}'..."
        )
    }

    // --- Reader Preferences, Autoscroll & Display Settings (DataStore-backed) ---

    fun updateReaderPreferences(prefs: ReaderPreferences) {
        _readerPreferences.value = prefs
        persistPreferencesJob?.cancel()
        persistPreferencesJob = viewModelScope.launch {
            delay(150)
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
        updateReaderPreferences(_readerPreferences.value.copy(autoScrollSpeed = AutoScrollLogic.clampSpeed(speed)))
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
                when (
                    val outcome = chapterLoaderRepo.loadChapter(
                        chapterUrl = nextUrl,
                        refererUrl = chapter.chapterUrl,
                        prevChapterUrl = chapter.chapterUrl
                    )
                ) {
                    is ChapterLoadOutcome.Success -> {
                        val nextChapter = outcome.chapter
                        prefetchedChapter = nextChapter
                        _prefetchState.value = PrefetchState(
                            forChapterUrl = nextUrl,
                            status = PrefetchStatus.READY,
                            chapter = nextChapter
                        )

                        // Preload the first 3 pages into Coil cache via imageHttpClient for instant display
                        mangaApp?.let { app ->
                            val loader = Coil.imageLoader(app)
                            nextChapter.images.take(3).forEach { imgUrl ->
                                val req = ImageRequest.Builder(app)
                                    .data(imgUrl)
                                    .setHeader("Referer", nextChapter.chapterUrl)
                                    .setParameter(MangaReaderApplication.PARAM_IMAGE_URL, imgUrl, memoryCacheKey = null)
                                    .diskCacheKey(imgUrl)
                                    .memoryCacheKey(imgUrl)
                                    .build()
                                loader.enqueue(req)
                            }
                        }
                    }
                    else -> {
                        _prefetchState.value = PrefetchState(
                            forChapterUrl = nextUrl,
                            status = PrefetchStatus.UNAVAILABLE
                        )
                    }
                }
            } catch (_: Exception) {
                _prefetchState.value = PrefetchState(forChapterUrl = nextUrl, status = PrefetchStatus.UNAVAILABLE)
            } finally {
                isPrefetchInProgress = false
            }
        }
    }

    // --- Assistant (Multi-Turn Gemini Chat) ---

    fun selectPersona(persona: ChatPersonaId) {
        _selectedPersona.value = persona
        viewModelScope.launch { chatRepo.savePersona(persona) }
    }

    fun selectModel(model: ChatModelId) {
        _selectedModel.value = model
        viewModelScope.launch { chatRepo.saveModel(model) }
    }

    fun saveApiKey(rawKey: String) {
        val cleaned = rawKey.trim()
        _apiKey.value = cleaned
        _hasApiKey.value = cleaned.isNotBlank()
        viewModelScope.launch {
            chatRepo.saveApiKey(cleaned)
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
        hasPendingChatWrite = true
        persistChatJob?.cancel()
        persistChatJob = viewModelScope.launch {
            delay(350)
            hasPendingChatWrite = false
            chatRepo.saveMessages(_chatMessages.value)
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Asynchroner Abschluss-Flush über applicationScope — kein runBlocking auf dem Main-Thread!
        val flushScope = mangaApp?.applicationScope ?: return
        val tabsSnapshot = _tabs.value
        val activeIdSnapshot = _activeWebTabId.value
        val modeSnapshot = _readerMode.value
        val prefsSnapshot = _readerPreferences.value
        val progressChapter = pendingProgressChapter
        val progressTitle = pendingProgressDisplayTitle
        val progressPage = pendingProgressPage
        val chatSnapshot = _chatMessages.value
        val shouldFlushSession = hasPendingSessionWrite && tabsSnapshot.isNotEmpty()
        val shouldFlushPrefs = persistPreferencesJob?.isActive == true
        val shouldFlushCacheLimit = persistCacheLimitJob?.isActive == true
        val cacheLimitBytesSnapshot = ChapterCacheManager.mbToBytes(_cacheLimitMb.value)
        val shouldFlushChat = hasPendingChatWrite

        flushScope.launch(Dispatchers.IO) {
            if (shouldFlushSession) {
                runCatching { sessionManager.saveSession(tabsSnapshot, activeIdSnapshot, modeSnapshot) }
            }
            if (shouldFlushPrefs) {
                runCatching { libraryRepo.savePreferences(prefsSnapshot) }
            }
            if (shouldFlushCacheLimit) {
                runCatching { settingsDataStore.updateCacheLimitBytes(cacheLimitBytesSnapshot) }
            }
            if (progressChapter != null) {
                runCatching {
                    libraryRepo.recordReadingProgress(
                        chapter = progressChapter,
                        displayTitle = progressTitle,
                        page = progressPage,
                        trackHistory = prefsSnapshot.isHistoryTrackingEnabled
                    )
                }
            }
            if (shouldFlushChat) {
                runCatching { chatRepo.saveMessages(chatSnapshot) }
            }
        }
    }

    companion object {
        private const val DEFAULT_START_TITLE = "Startseite"
        private const val DEFAULT_START_URL = "https://mangadex.org"
    }
}
