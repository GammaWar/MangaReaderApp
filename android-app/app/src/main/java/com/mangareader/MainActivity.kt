package com.mangareader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mangareader.model.AppTab
import com.mangareader.ui.MangaViewModel
import com.mangareader.ui.screens.BrowserScreen
import com.mangareader.ui.screens.LibraryScreen
import com.mangareader.ui.screens.ReaderScreen
import com.mangareader.ui.screens.SettingsSheet
import com.mangareader.ui.theme.AmoledPalette
import com.mangareader.ui.theme.MangaReaderTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MangaReaderTheme {
                MangaReaderRoot()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MangaReaderRoot(viewModel: MangaViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.snackbarEvents.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    Scaffold(
        containerColor = AmoledPalette.Background,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            if (uiState.activeTab != AppTab.READER) {
                TopAppBar(
                    title = {
                        Text(
                            text = "MangaReaderApp",
                            color = AmoledPalette.TextPrimary,
                            fontWeight = FontWeight.Bold,
                        )
                    },
                    actions = {
                        IconButton(onClick = { viewModel.setSettingsOpen(true) }) {
                            Icon(
                                imageVector = Icons.Outlined.Tune,
                                contentDescription = "Einstellungen",
                                tint = AmoledPalette.AccentSoft,
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = AmoledPalette.Surface,
                    ),
                )
            }
        },
        bottomBar = {
            NavigationBar(
                containerColor = AmoledPalette.Surface,
                contentColor = AmoledPalette.TextPrimary,
            ) {
                AppTab.entries.forEach { tab ->
                    val icon = when (tab) {
                        AppTab.LIBRARY -> Icons.Outlined.Bookmarks
                        AppTab.BROWSER -> Icons.Outlined.Language
                        AppTab.READER -> Icons.Outlined.AutoStories
                    }
                    NavigationBarItem(
                        selected = uiState.activeTab == tab,
                        onClick = { viewModel.selectTab(tab) },
                        icon = { Icon(imageVector = icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = AmoledPalette.AccentSoft,
                            selectedTextColor = AmoledPalette.AccentSoft,
                            indicatorColor = AmoledPalette.SurfaceElevated,
                            unselectedIconColor = AmoledPalette.TextMuted,
                            unselectedTextColor = AmoledPalette.TextMuted,
                        ),
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (uiState.activeTab) {
                AppTab.LIBRARY -> {
                    LibraryScreen(
                        bookmarks = uiState.bookmarks,
                        history = uiState.history,
                        cacheUsage = uiState.cacheUsage,
                        isExtracting = uiState.isExtracting,
                        onOpenChapterUrl = { url, page -> viewModel.extractAndOpenChapter(url, page) },
                        onOpenInBrowser = { url -> viewModel.openUrlInBrowser(url) },
                        onRemoveBookmark = viewModel::removeBookmark,
                        onRemoveHistory = viewModel::removeHistoryItem,
                        onClearHistory = viewModel::clearHistory,
                        onOpenSettings = { viewModel.setSettingsOpen(true) },
                        onClearCache = viewModel::clearAllCacheManually,
                    )
                }

                AppTab.BROWSER -> {
                    BrowserScreen(
                        tabs = uiState.browserTabs,
                        activeTabId = uiState.activeBrowserTabId,
                        isExtracting = uiState.isExtracting,
                        onSelectTab = viewModel::selectBrowserTab,
                        onAddTab = { viewModel.addBrowserTab() },
                        onCloseTab = viewModel::closeBrowserTab,
                        onUpdateTabMeta = viewModel::updateBrowserTabMeta,
                        onExtractChapter = { url -> viewModel.extractAndOpenChapter(url, targetPage = 0) },
                    )
                }

                AppTab.READER -> {
                    val isBookmarked = uiState.activeChapter?.let { ch ->
                        uiState.bookmarks.any { it.chapterUrl == ch.chapterUrl }
                    } ?: false

                    ReaderScreen(
                        chapter = uiState.activeChapter,
                        currentPageIndex = uiState.currentPageIndex,
                        isBookmarked = isBookmarked,
                        settings = uiState.settings,
                        sessionVersion = uiState.sessionVersion,
                        hasPrefetchedNext = uiState.prefetchedNextChapter != null,
                        okHttpClient = viewModel.okHttpClient,
                        onPageChanged = viewModel::onReaderPageChanged,
                        onToggleBookmark = viewModel::toggleBookmarkForCurrentChapter,
                        onRefreshSession = viewModel::refreshReaderSession,
                        onEvictSingleImageCache = viewModel::evictSingleImageCache,
                        onOpenInBrowser = viewModel::openUrlInBrowser,
                        onOpenNextChapter = viewModel::openNextChapter,
                        onOpenSettings = { viewModel.setSettingsOpen(true) },
                        onUpdateAutoscroll = { enabled, speed ->
                            viewModel.updateSettings {
                                it.copy(autoscrollEnabled = enabled, autoscrollSpeed = speed)
                            }
                        },
                        onNotify = viewModel::notify,
                    )
                }
            }
        }
    }

    if (uiState.isSettingsOpen) {
        SettingsSheet(
            settings = uiState.settings,
            cacheUsage = uiState.cacheUsage,
            onDismiss = { viewModel.setSettingsOpen(false) },
            onUpdateSettings = viewModel::updateSettings,
            onPruneToLimit = viewModel::runManualLimitPrune,
            onClearAllCache = viewModel::clearAllCacheManually,
        )
    }
}
