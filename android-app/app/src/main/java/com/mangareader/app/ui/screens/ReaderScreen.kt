package com.mangareader.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mangareader.app.data.model.AppTab
import com.mangareader.app.data.model.PrefetchStatus
import com.mangareader.app.data.model.ReaderMode
import com.mangareader.app.ui.screens.reader.AutoScrollEffect
import com.mangareader.app.ui.screens.reader.AutoScrollFloatingPill
import com.mangareader.app.ui.screens.reader.PagedReaderView
import com.mangareader.app.ui.screens.reader.ReaderBottomHudBar
import com.mangareader.app.ui.screens.reader.ReaderDownloadProgressBanner
import com.mangareader.app.ui.screens.reader.ReaderFilterLogic
import com.mangareader.app.ui.screens.reader.ReaderFilterOverlays
import com.mangareader.app.ui.screens.reader.ReaderHudLogic
import com.mangareader.app.ui.screens.reader.ReaderKeepScreenOnEffect
import com.mangareader.app.ui.screens.reader.ReaderNavigationLogic
import com.mangareader.app.ui.screens.reader.ReaderSystemBarsEffect
import com.mangareader.app.ui.screens.reader.ReaderTopHudBar
import com.mangareader.app.ui.screens.reader.WebtoonReaderList
import com.mangareader.app.ui.screens.reader.ZoomMath
import com.mangareader.app.ui.theme.AccentPrimary
import com.mangareader.app.ui.theme.BodyFontFamily
import com.mangareader.app.ui.theme.DisplayFontFamily
import com.mangareader.app.ui.theme.PureAmoledBlack
import com.mangareader.app.ui.viewmodel.MangaViewModel
import kotlinx.coroutines.launch

@Composable
fun ReaderScreen(
    viewModel: MangaViewModel,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    val chapter by viewModel.currentChapter.collectAsState()
    val readerMode by viewModel.readerMode.collectAsState()
    val downloadState by viewModel.downloadState.collectAsState()
    val downloadedChapters by viewModel.downloadedChapters.collectAsState()
    val isSettingsOpen by viewModel.isSettingsOpen.collectAsState()

    val readerPreferences by viewModel.readerPreferences.collectAsState()
    val isAutoScrolling by viewModel.isAutoScrolling.collectAsState()
    val bookmarks by viewModel.bookmarks.collectAsState()
    val prefetchState by viewModel.prefetchState.collectAsState()
    val targetPageRequest by viewModel.targetPageRequest.collectAsState()
    val sessionRefreshKey by viewModel.sessionRefreshTrigger.collectAsState()

    val currentChapter = chapter
    val hasActiveChapter = currentChapter != null && currentChapter.images.isNotEmpty()
    var showControls by remember { mutableStateOf(false) }

    // Synchronize Android System Bars & Keep-Screen-On with active reading state
    ReaderSystemBarsEffect(
        hasActiveChapter = hasActiveChapter,
        showHud = showControls,
        isSettingsOpen = isSettingsOpen
    )
    ReaderKeepScreenOnEffect(
        keepScreenOn = ReaderFilterLogic.shouldKeepScreenOn(
            hasActiveChapter = hasActiveChapter,
            keepScreenOnPreference = readerPreferences.keepScreenOn,
            isAutoScrolling = isAutoScrolling
        )
    )

    if (currentChapter == null || currentChapter.images.isEmpty()) {
        EmptyReaderPlaceholder(
            onOpenLibrary = { viewModel.setActiveTab(AppTab.LIBRARY) },
            modifier = modifier
        )
        return
    }

    val totalPages = currentChapter.images.size
    val isBookmarked = remember(bookmarks, currentChapter.chapterUrl) {
        bookmarks.any { it.chapterUrl == currentChapter.chapterUrl }
    }
    val isDownloaded = remember(downloadedChapters, currentChapter.chapterUrl, currentChapter.isOffline) {
        currentChapter.isOffline || downloadedChapters.any { it.sourceUrl == currentChapter.chapterUrl }
    }

    var zoomScale by remember(currentChapter.chapterUrl) { mutableFloatStateOf(ZoomMath.MIN_SCALE) }

    val listState = rememberLazyListState()
    val pagerState = rememberPagerState(pageCount = { totalPages })

    // Smooth choreographer-driven Webtoon autoscroll
    AutoScrollEffect(
        isAutoScrolling = isAutoScrolling,
        autoScrollSpeed = readerPreferences.autoScrollSpeed,
        readerMode = readerMode,
        listState = listState,
        onReachedChapterEnd = {
            viewModel.setAutoScroll(false)
            showControls = true
        }
    )

    // Automatically hide HUD when autoscroll starts for distraction-free reading
    LaunchedEffect(isAutoScrolling) {
        if (isAutoScrolling) {
            showControls = false
        }
    }

    // Auto-show HUD at chapter start/end and auto-hide during Webtoon scrolling
    LaunchedEffect(readerMode, listState, totalPages, isAutoScrolling) {
        if (readerMode != ReaderMode.VERTICAL_WEBTOON) return@LaunchedEffect
        snapshotFlow {
            val isAtTop = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
            val isAtBottom = (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= totalPages
            val isScrolling = listState.isScrollInProgress
            Triple(isAtTop, isAtBottom, isScrolling)
        }.collect { (isAtTop, isAtBottom, isScrolling) ->
            showControls = ReaderHudLogic.resolveWebtoonHudVisibility(
                currentVisible = showControls,
                isAtTop = isAtTop,
                isAtBottom = isAtBottom,
                isScrolling = isScrolling,
                isAutoScrolling = isAutoScrolling
            )
        }
    }

    // Auto-show HUD on first/last page in Paged mode & reset zoom on page change
    LaunchedEffect(readerMode, pagerState.currentPage, totalPages) {
        if (readerMode == ReaderMode.HORIZONTAL_PAGED) {
            zoomScale = ZoomMath.MIN_SCALE
            val isAtFirstPage = pagerState.currentPage == 0
            val isAtLastPage = pagerState.currentPage >= totalPages - 1
            showControls = isAtFirstPage || isAtLastPage
        }
    }

    // Restore explicit target reading position (Library / History / Offline jumps)
    LaunchedEffect(currentChapter.chapterUrl, targetPageRequest.first) {
        val targetPage = ReaderNavigationLogic.clampPageIndex(targetPageRequest.second, totalPages)
        listState.scrollToItem(targetPage)
        pagerState.scrollToPage(targetPage)
    }

    // Sync scroll position ONLY on actual mode switches (never overwrite initial target page!)
    var previousReaderMode by remember { mutableStateOf(readerMode) }
    LaunchedEffect(readerMode) {
        if (readerMode == previousReaderMode) return@LaunchedEffect
        previousReaderMode = readerMode
        zoomScale = ZoomMath.MIN_SCALE
        if (readerMode == ReaderMode.HORIZONTAL_PAGED) {
            val target = ReaderNavigationLogic.clampPageIndex(listState.firstVisibleItemIndex, totalPages)
            pagerState.scrollToPage(target)
        } else {
            val target = ReaderNavigationLogic.clampPageIndex(pagerState.currentPage, totalPages)
            listState.scrollToItem(target)
        }
    }

    val currentPageIndex by remember(readerMode, totalPages) {
        derivedStateOf {
            if (totalPages == 0) {
                0
            } else if (readerMode == ReaderMode.VERTICAL_WEBTOON) {
                (listState.firstVisibleItemIndex + 1).coerceIn(1, totalPages)
            } else {
                (pagerState.currentPage + 1).coerceIn(1, totalPages)
            }
        }
    }

    // Persist reading progress as user scrolls or navigates pages
    LaunchedEffect(currentPageIndex, currentChapter.chapterUrl) {
        if (currentPageIndex > 0) {
            viewModel.updateLastReadPage(currentPageIndex - 1)
        }
    }

    // Prefetch trigger near chapter end (last 2 pages)
    LaunchedEffect(currentPageIndex, readerPreferences.isPrefetchEnabled, currentChapter.chapterUrl) {
        if (
            ReaderNavigationLogic.shouldTriggerPrefetch(
                currentPageOneBased = currentPageIndex,
                totalPages = totalPages,
                isPrefetchEnabled = readerPreferences.isPrefetchEnabled
            )
        ) {
            viewModel.prefetchNextChapter()
        }
    }

    val hasNextChapter = !currentChapter.nextChapterUrl.isNullOrBlank()
    val hasPrevChapter = !currentChapter.prevChapterUrl.isNullOrBlank()
    val isNextPrefetched = prefetchState.status == PrefetchStatus.READY &&
        prefetchState.forChapterUrl == currentChapter.nextChapterUrl &&
        prefetchState.chapter != null

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(PureAmoledBlack)
    ) {
        if (readerMode == ReaderMode.VERTICAL_WEBTOON) {
            WebtoonReaderList(
                chapter = currentChapter,
                listState = listState,
                zoomScale = zoomScale,
                sessionRefreshKey = sessionRefreshKey,
                hasNextChapter = hasNextChapter,
                hasPrevChapter = hasPrevChapter,
                isNextPrefetched = isNextPrefetched,
                onScaleChange = { zoomScale = ZoomMath.clampScale(it) },
                onToggleHud = { showControls = !showControls },
                onInvalidateSingleImageCache = { url, pageIdx, onDone ->
                    viewModel.invalidateSingleImageCache(url, pageIdx, onDone)
                },
                onBypassSucceeded = { pageIdx -> viewModel.notifyImageBypassSucceeded(pageIdx) },
                onRefreshSession = { viewModel.refreshChapterSession() },
                onOpenInBrowser = { viewModel.openCurrentChapterInBrowser() },
                onNextChapter = { viewModel.loadNextChapter() },
                onPrevChapter = { viewModel.loadPrevChapter() },
                onBackToLibrary = { viewModel.setActiveTab(AppTab.LIBRARY) }
            )
        } else {
            PagedReaderView(
                chapter = currentChapter,
                pagerState = pagerState,
                readingDirection = readerPreferences.readingDirection,
                zoomScale = zoomScale,
                sessionRefreshKey = sessionRefreshKey,
                hasNextChapter = hasNextChapter,
                hasPrevChapter = hasPrevChapter,
                onScaleChange = { zoomScale = ZoomMath.clampScale(it) },
                onToggleHud = { showControls = !showControls },
                onAnimateToPage = { targetPage ->
                    coroutineScope.launch { pagerState.animateScrollToPage(targetPage) }
                },
                onNextChapter = { viewModel.loadNextChapter() },
                onPrevChapter = { viewModel.loadPrevChapter() },
                onInvalidateSingleImageCache = { url, pageIdx, onDone ->
                    viewModel.invalidateSingleImageCache(url, pageIdx, onDone)
                },
                onBypassSucceeded = { pageIdx -> viewModel.notifyImageBypassSucceeded(pageIdx) },
                onRefreshSession = { viewModel.refreshChapterSession() },
                onOpenInBrowser = { viewModel.openCurrentChapterInBrowser() }
            )
        }

        // Software Dimmer (up to 80%) + Warm Sepia Night Filter (pointer-pass-through)
        ReaderFilterOverlays(preferences = readerPreferences)

        // Floating Autoscroll Pill [ Pause | - | 1.0x | + | Stop ]
        AutoScrollFloatingPill(
            visible = readerPreferences.isAutoScrollEnabled &&
                isAutoScrolling &&
                readerMode == ReaderMode.VERTICAL_WEBTOON,
            speed = readerPreferences.autoScrollSpeed,
            showControls = showControls,
            onPause = { viewModel.toggleAutoScroll() },
            onSpeedChange = { viewModel.setAutoScrollSpeed(it) },
            onStop = { viewModel.setAutoScroll(false) },
            modifier = Modifier.align(Alignment.BottomCenter)
        )

        // Top Reader HUD Bar
        ReaderTopHudBar(
            visible = showControls,
            chapterTitle = currentChapter.title,
            currentPageOneBased = currentPageIndex,
            totalPages = totalPages,
            isOffline = currentChapter.isOffline,
            readerMode = readerMode,
            readingDirection = readerPreferences.readingDirection,
            isAutoScrollFeatureEnabled = readerPreferences.isAutoScrollEnabled,
            isAutoScrolling = isAutoScrolling,
            isDownloaded = isDownloaded,
            isBookmarked = isBookmarked,
            onBack = {
                viewModel.setAutoScroll(false)
                viewModel.setActiveTab(AppTab.LIBRARY)
            },
            onRefreshSession = { viewModel.refreshChapterSession() },
            onToggleAutoScroll = {
                if (readerMode != ReaderMode.VERTICAL_WEBTOON) {
                    viewModel.setReaderMode(ReaderMode.VERTICAL_WEBTOON)
                }
                viewModel.toggleAutoScroll()
            },
            onDownloadChapter = { viewModel.downloadCurrentChapter() },
            onToggleBookmark = { viewModel.toggleCurrentChapterBookmark() },
            onOpenSettings = { viewModel.setSettingsOpen(true) },
            modifier = Modifier.align(Alignment.TopCenter)
        )

        // Active Download Progress Banner
        ReaderDownloadProgressBanner(
            downloadState = downloadState,
            modifier = Modifier.align(Alignment.TopCenter)
        )

        // Bottom Reader Scrubber & Zoom Bar
        ReaderBottomHudBar(
            visible = showControls,
            currentPageOneBased = currentPageIndex,
            totalPages = totalPages,
            hasPrevChapter = hasPrevChapter,
            hasNextChapter = hasNextChapter,
            isNextPrefetched = isNextPrefetched,
            zoomScale = zoomScale,
            onJumpToPage = { targetZeroBased, animate ->
                coroutineScope.launch {
                    if (readerMode == ReaderMode.VERTICAL_WEBTOON) {
                        if (animate) listState.animateScrollToItem(targetZeroBased)
                        else listState.scrollToItem(targetZeroBased)
                    } else {
                        if (animate) pagerState.animateScrollToPage(targetZeroBased)
                        else pagerState.scrollToPage(targetZeroBased)
                    }
                }
            },
            onPrevChapter = { viewModel.loadPrevChapter() },
            onNextChapter = { viewModel.loadNextChapter() },
            onZoomChange = { zoomScale = ZoomMath.clampScale(it) },
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
private fun EmptyReaderPlaceholder(
    onOpenLibrary: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(PureAmoledBlack)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Kein Kapitel im Reader geladen",
                fontFamily = DisplayFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 20.sp,
                color = Color.White,
                textAlign = TextAlign.Center
            )
            Text(
                text = "Wähle ein Kapitel aus deiner Bibliothek oder extrahiere Bilder im Multi-Tab-Browser.",
                fontFamily = BodyFontFamily,
                fontSize = 13.sp,
                color = Color(0xFF9C95BA),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(6.dp))
            Button(
                onClick = onOpenLibrary,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentPrimary,
                    contentColor = Color.White
                ),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
                modifier = Modifier.height(44.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Bookmarks,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Zur Bibliothek",
                    fontFamily = BodyFontFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp
                )
            }
        }
    }
}
