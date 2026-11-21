package com.mangareader.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material.icons.outlined.ZoomOut
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.mangareader.app.MangaReaderApplication
import com.mangareader.app.data.model.AppTab
import com.mangareader.app.data.model.DownloadState
import com.mangareader.app.data.model.PrefetchStatus
import com.mangareader.app.data.model.ReaderMode
import com.mangareader.app.data.model.ReadingDirection
import com.mangareader.app.ui.components.ImageErrorView
import com.mangareader.app.ui.components.ZoomableBox
import com.mangareader.app.ui.theme.AccentPrimary
import com.mangareader.app.ui.theme.AccentPrimaryLight
import com.mangareader.app.ui.theme.AccentTertiary
import com.mangareader.app.ui.theme.BodyFontFamily
import com.mangareader.app.ui.theme.CardBorder
import com.mangareader.app.ui.theme.DangerBorder
import com.mangareader.app.ui.theme.DangerSurface
import com.mangareader.app.ui.theme.DangerText
import com.mangareader.app.ui.theme.DarkInputBg
import com.mangareader.app.ui.theme.DarkSurfaceElevated
import com.mangareader.app.ui.theme.DarkSurfaceVariant
import com.mangareader.app.ui.theme.DisplayFontFamily
import com.mangareader.app.ui.theme.MonoFontFamily
import com.mangareader.app.ui.theme.PureAmoledBlack
import com.mangareader.app.ui.theme.TextSecondary
import com.mangareader.app.ui.viewmodel.MangaViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

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

    val readerPreferences by viewModel.readerPreferences.collectAsState()
    val isAutoScrolling by viewModel.isAutoScrolling.collectAsState()
    val bookmarks by viewModel.bookmarks.collectAsState()
    val prefetchState by viewModel.prefetchState.collectAsState()
    val targetPageRequest by viewModel.targetPageRequest.collectAsState()
    val sessionRefreshKey by viewModel.sessionRefreshTrigger.collectAsState()

    val isBookmarked = remember(bookmarks, chapter?.chapterUrl) {
        chapter?.let { ch -> bookmarks.any { it.chapterUrl == ch.chapterUrl } } ?: false
    }
    var showControls by remember { mutableStateOf(false) }

    if (chapter == null || chapter!!.images.isEmpty()) {
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
                    onClick = { viewModel.setActiveTab(AppTab.LIBRARY) },
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
        return
    }

    val currentChapter = chapter!!
    val totalPages = currentChapter.images.size
    val isRtl = readerPreferences.readingDirection == ReadingDirection.RTL
    val isDownloaded = remember(downloadedChapters, currentChapter.chapterUrl, currentChapter.isOffline) {
        currentChapter.isOffline || downloadedChapters.any { it.sourceUrl == currentChapter.chapterUrl }
    }

    var zoomScale by remember(currentChapter.chapterUrl) { mutableFloatStateOf(1.0f) }

    val listState = rememberLazyListState()
    val pagerState = rememberPagerState(pageCount = { totalPages })

    // Keep screen turned on when enabled or autoscrolling
    val currentView = LocalView.current
    DisposableEffect(currentView, readerPreferences.keepScreenOn, isAutoScrolling) {
        currentView.keepScreenOn = readerPreferences.keepScreenOn || isAutoScrolling
        onDispose {
            currentView.keepScreenOn = false
        }
    }

    // Continuous Autoscroll execution with automatic stop at end of chapter
    LaunchedEffect(isAutoScrolling, readerPreferences.autoScrollSpeed, readerMode) {
        if (isAutoScrolling && readerMode == ReaderMode.VERTICAL_WEBTOON) {
            while (isActive) {
                if (!listState.canScrollForward) {
                    viewModel.setAutoScroll(false)
                    break
                }
                val scrollDelta = 2.2f * readerPreferences.autoScrollSpeed
                listState.scrollBy(scrollDelta)
                delay(16)
            }
        }
    }

    // Auto-show bars at beginning/end, auto-hide when scrolling
    LaunchedEffect(listState, totalPages) {
        snapshotFlow {
            val isAtTop = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
            val isAtBottom = (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= totalPages
            val isScrolling = listState.isScrollInProgress
            Triple(isAtTop, isAtBottom, isScrolling)
        }.collect { (isAtTop, isAtBottom, isScrolling) ->
            if (isAtTop || isAtBottom) {
                showControls = true
            } else if (isScrolling) {
                showControls = false
            }
        }
    }

    LaunchedEffect(pagerState.currentPage, totalPages) {
        if (readerMode == ReaderMode.HORIZONTAL_PAGED) {
            val isAtFirstPage = pagerState.currentPage == 0
            val isAtLastPage = pagerState.currentPage >= totalPages - 1
            showControls = isAtFirstPage || isAtLastPage
        }
    }

    // Restore target reading position (respects explicit target jumps from Library/History/Offline)
    LaunchedEffect(currentChapter.chapterUrl, targetPageRequest.first) {
        val targetPage = targetPageRequest.second.coerceIn(0, (totalPages - 1).coerceAtLeast(0))
        listState.scrollToItem(targetPage)
        pagerState.scrollToPage(targetPage)
    }

    // Sync scroll position when switching between Webtoon and Paged mode
    LaunchedEffect(readerMode) {
        if (readerMode == ReaderMode.HORIZONTAL_PAGED) {
            val target = listState.firstVisibleItemIndex.coerceIn(0, (totalPages - 1).coerceAtLeast(0))
            pagerState.scrollToPage(target)
        } else {
            val target = pagerState.currentPage.coerceIn(0, (totalPages - 1).coerceAtLeast(0))
            listState.scrollToItem(target)
        }
    }

    val currentPageIndex by remember(readerMode, totalPages) {
        derivedStateOf {
            if (totalPages == 0) 0
            else if (readerMode == ReaderMode.VERTICAL_WEBTOON) {
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
        if (readerPreferences.isPrefetchEnabled && totalPages > 0 && currentPageIndex >= totalPages - 2) {
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
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        showControls = !showControls
                    },
                contentPadding = PaddingValues(bottom = 110.dp)
            ) {
                itemsIndexed(currentChapter.images, key = { index, url -> "$index-$url" }) { index, imageUrl ->
                    ZoomableBox(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 280.dp),
                        externalScale = zoomScale,
                        onScaleChange = { zoomScale = it },
                        onTap = { showControls = !showControls }
                    ) {
                        ReaderMangaPage(
                            pageIndex = index,
                            imageUrl = imageUrl,
                            chapterUrl = currentChapter.chapterUrl,
                            isOffline = currentChapter.isOffline,
                            sessionRefreshKey = sessionRefreshKey,
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 280.dp),
                            loadingHeightDp = 380,
                            viewModel = viewModel
                        )
                    }
                }

                item {
                    ChapterEndFooter(
                        chapterTitle = currentChapter.title,
                        hasNextChapter = hasNextChapter,
                        hasPrevChapter = hasPrevChapter,
                        isNextPrefetched = isNextPrefetched,
                        onNextChapter = { viewModel.loadNextChapter() },
                        onPrevChapter = { viewModel.loadPrevChapter() },
                        onBackToLibrary = { viewModel.setActiveTab(AppTab.LIBRARY) }
                    )
                }
            }
        } else {
            // Horizontal Paged Manga Reader supporting RTL & LTR + non-blocking tap zones
            HorizontalPager(
                state = pagerState,
                reverseLayout = isRtl,
                modifier = Modifier.fillMaxSize()
            ) { pageIndex ->
                val imageUrl = currentChapter.images[pageIndex]
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val fullWidth = constraints.maxWidth.toFloat()
                    ZoomableBox(
                        modifier = Modifier.fillMaxSize(),
                        externalScale = zoomScale,
                        onScaleChange = { zoomScale = it },
                        onTapOffset = { tapOffset ->
                            if (tapOffset.x < fullWidth * 0.22f) {
                                val delta = if (isRtl) 1 else -1
                                val target = pagerState.currentPage + delta
                                if (target in 0 until totalPages) {
                                    coroutineScope.launch { pagerState.animateScrollToPage(target) }
                                } else if (target >= totalPages) {
                                    viewModel.loadNextChapter()
                                }
                            } else if (tapOffset.x > fullWidth * 0.78f) {
                                val delta = if (isRtl) -1 else 1
                                val target = pagerState.currentPage + delta
                                if (target in 0 until totalPages) {
                                    coroutineScope.launch { pagerState.animateScrollToPage(target) }
                                } else if (target >= totalPages) {
                                    viewModel.loadNextChapter()
                                }
                            } else {
                                showControls = !showControls
                            }
                        }
                    ) {
                        ReaderMangaPage(
                            pageIndex = pageIndex,
                            imageUrl = imageUrl,
                            chapterUrl = currentChapter.chapterUrl,
                            isOffline = currentChapter.isOffline,
                            sessionRefreshKey = sessionRefreshKey,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                            loadingHeightDp = null,
                            viewModel = viewModel
                        )
                    }
                }
            }
        }

        // --- Software Dimming Overlay ---
        if (readerPreferences.isCustomBrightnessEnabled && readerPreferences.brightnessLevel < 1f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        PureAmoledBlack.copy(
                            alpha = (1f - readerPreferences.brightnessLevel).coerceIn(0f, 0.80f)
                        )
                    )
            )
        }

        // --- Night / Sepia Warm Filter Overlay ---
        if (readerPreferences.isNightFilterEnabled) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFFE59866).copy(alpha = readerPreferences.nightFilterStrength * 0.45f))
            )
        }

        // --- Floating Autoscroll Pill (GitHub ReaderView style: Pause | Speed | Close) ---
        AnimatedVisibility(
            visible = readerPreferences.isAutoScrollEnabled && isAutoScrolling && readerMode == ReaderMode.VERTICAL_WEBTOON,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = if (showControls) 114.dp else 24.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = Color(0xFF151126).copy(alpha = 0.95f),
                border = BorderStroke(1.dp, Color(0xFF3C3366)),
                tonalElevation = 6.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        onClick = { viewModel.toggleAutoScroll() },
                        shape = RoundedCornerShape(999.dp),
                        color = AccentPrimary,
                        modifier = Modifier.height(34.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            modifier = Modifier.padding(horizontal = 12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Pause,
                                contentDescription = "Autoscroll pausieren",
                                tint = Color.White,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = "Pause",
                                fontFamily = BodyFontFamily,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.sp,
                                color = Color.White
                            )
                        }
                    }

                    Surface(
                        onClick = {
                            val nextSpeed = when {
                                readerPreferences.autoScrollSpeed < 1.0f -> 1.0f
                                readerPreferences.autoScrollSpeed < 1.5f -> 1.5f
                                readerPreferences.autoScrollSpeed < 2.0f -> 2.0f
                                readerPreferences.autoScrollSpeed < 3.0f -> 3.0f
                                else -> 0.5f
                            }
                            viewModel.setAutoScrollSpeed(nextSpeed)
                        },
                        shape = RoundedCornerShape(999.dp),
                        color = Color(0xFF1F1A38),
                        border = BorderStroke(1.dp, Color(0xFF342B59)),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            modifier = Modifier.padding(horizontal = 12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Speed,
                                contentDescription = "Geschwindigkeit",
                                tint = AccentPrimaryLight,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = "${String.format(Locale.US, "%.1f", readerPreferences.autoScrollSpeed)}x",
                                fontFamily = MonoFontFamily,
                                fontWeight = FontWeight.Medium,
                                fontSize = 12.sp,
                                color = Color(0xFFE5DCFF)
                            )
                        }
                    }

                    Surface(
                        onClick = { viewModel.setAutoScroll(false) },
                        shape = CircleShape,
                        color = DangerSurface,
                        border = BorderStroke(1.dp, DangerBorder),
                        modifier = Modifier.size(34.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = "Autoscroll beenden",
                                tint = DangerText,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                }
            }
        }

        // --- Top Reader HUD Bar (GitHub ReaderView style) ---
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn() + slideInVertically(initialOffsetY = { -it }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { -it }),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DarkInputBg.copy(alpha = 0.95f))
                    .statusBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    HudSquareButton(
                        icon = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "Zurück zur Bibliothek",
                        onClick = {
                            viewModel.setAutoScroll(false)
                            viewModel.setActiveTab(AppTab.LIBRARY)
                        }
                    )

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = currentChapter.title,
                            fontFamily = DisplayFontFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Text(
                                text = "Seite $currentPageIndex / $totalPages",
                                fontFamily = MonoFontFamily,
                                fontSize = 11.sp,
                                color = Color(0xFFD7CEFF)
                            )
                            if (currentChapter.isOffline) {
                                Text(
                                    text = "·",
                                    fontSize = 11.sp,
                                    color = Color(0xFF9E97BC)
                                )
                                Text(
                                    text = "Offline",
                                    fontFamily = BodyFontFamily,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 11.sp,
                                    color = Color(0xFF6EE7B7)
                                )
                            }
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (!currentChapter.isOffline) {
                            HudSquareButton(
                                icon = Icons.Outlined.Refresh,
                                contentDescription = "Sitzung erneuern",
                                onClick = { viewModel.refreshChapterSession() }
                            )
                        }

                        if (readerPreferences.isAutoScrollEnabled) {
                            HudSquareButton(
                                icon = if (isAutoScrolling) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                                contentDescription = if (isAutoScrolling) "Autoscroll pausieren" else "Autoscroll starten",
                                containerColor = if (isAutoScrolling) AccentPrimary else DarkSurfaceElevated,
                                borderColor = if (isAutoScrolling) AccentPrimaryLight else Color(0xFF272140),
                                iconTint = Color.White,
                                onClick = {
                                    if (readerMode != ReaderMode.VERTICAL_WEBTOON) {
                                        viewModel.setReaderMode(ReaderMode.VERTICAL_WEBTOON)
                                    }
                                    viewModel.toggleAutoScroll()
                                }
                            )
                        }

                        if (!currentChapter.isOffline) {
                            HudSquareButton(
                                icon = if (isDownloaded) Icons.Outlined.CheckCircle else Icons.Outlined.Download,
                                contentDescription = if (isDownloaded) "Kapitel offline gespeichert" else "Kapitel herunterladen",
                                containerColor = if (isDownloaded) Color(0xFF064E3B) else DarkSurfaceElevated,
                                borderColor = if (isDownloaded) AccentTertiary else Color(0xFF272140),
                                iconTint = if (isDownloaded) Color(0xFF6EE7B7) else Color(0xFFD2CCE8),
                                onClick = { viewModel.downloadCurrentChapter() }
                            )
                        }

                        HudSquareButton(
                            icon = if (isBookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                            contentDescription = if (isBookmarked) "Lesezeichen entfernen" else "Lesezeichen setzen",
                            containerColor = if (isBookmarked) Color(0xFF2B1F54) else DarkSurfaceElevated,
                            borderColor = if (isBookmarked) AccentPrimaryLight else Color(0xFF272140),
                            iconTint = if (isBookmarked) Color(0xFFE5DCFF) else Color(0xFFD2CCE8),
                            onClick = { viewModel.toggleCurrentChapterBookmark() }
                        )

                        HudSquareButton(
                            icon = Icons.Outlined.Tune,
                            contentDescription = "Reader-Einstellungen öffnen",
                            iconTint = AccentPrimaryLight,
                            onClick = { viewModel.setSettingsOpen(true) }
                        )
                    }
                }
                HorizontalDivider(thickness = 1.dp, color = Color(0xFF1E1A30))
            }
        }

        // Download Progress Indicator
        if (downloadState is DownloadState.InProgress) {
            val inProgress = downloadState as DownloadState.InProgress
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 68.dp, start = 16.dp, end = 16.dp)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = DarkSurfaceVariant,
                border = BorderStroke(1.dp, CardBorder)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Lade '${inProgress.title}' herunter...",
                            fontFamily = BodyFontFamily,
                            fontSize = 12.sp,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${inProgress.current} / ${inProgress.total}",
                            fontFamily = MonoFontFamily,
                            fontWeight = FontWeight.Medium,
                            fontSize = 12.sp,
                            color = AccentPrimaryLight
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { inProgress.progress },
                        modifier = Modifier.fillMaxWidth(),
                        color = AccentTertiary,
                        trackColor = Color(0xFF241F3A)
                    )
                }
            }
        }

        // --- Bottom Reader Scrubber & Zoom Bar (GitHub ReaderView style) ---
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DarkInputBg.copy(alpha = 0.95f))
                    .navigationBarsPadding()
            ) {
                HorizontalDivider(thickness = 1.dp, color = Color(0xFF1E1A30))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (totalPages > 1) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            HudSquareButton(
                                icon = Icons.Outlined.ChevronLeft,
                                contentDescription = "Vorherige Seite",
                                sizeDp = 34,
                                enabled = currentPageIndex > 1,
                                onClick = {
                                    val target = (currentPageIndex - 2).coerceIn(0, totalPages - 1)
                                    coroutineScope.launch {
                                        if (readerMode == ReaderMode.VERTICAL_WEBTOON) {
                                            listState.animateScrollToItem(target)
                                        } else {
                                            pagerState.animateScrollToPage(target)
                                        }
                                    }
                                }
                            )

                            Slider(
                                value = (currentPageIndex - 1).coerceIn(0, totalPages - 1).toFloat(),
                                onValueChange = { raw ->
                                    val targetIdx = raw.roundToInt().coerceIn(0, totalPages - 1)
                                    coroutineScope.launch {
                                        if (readerMode == ReaderMode.VERTICAL_WEBTOON) {
                                            listState.scrollToItem(targetIdx)
                                        } else {
                                            pagerState.scrollToPage(targetIdx)
                                        }
                                    }
                                },
                                valueRange = 0f..(totalPages - 1).toFloat(),
                                colors = SliderDefaults.colors(
                                    thumbColor = AccentPrimaryLight,
                                    activeTrackColor = AccentPrimary,
                                    inactiveTrackColor = Color(0xFF241F3A)
                                ),
                                modifier = Modifier.weight(1f)
                            )

                            HudSquareButton(
                                icon = Icons.Outlined.ChevronRight,
                                contentDescription = "Nächste Seite",
                                sizeDp = 34,
                                enabled = currentPageIndex < totalPages,
                                onClick = {
                                    val target = currentPageIndex.coerceIn(0, totalPages - 1)
                                    coroutineScope.launch {
                                        if (readerMode == ReaderMode.VERTICAL_WEBTOON) {
                                            listState.animateScrollToItem(target)
                                        } else {
                                            pagerState.animateScrollToPage(target)
                                        }
                                    }
                                }
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Chapter Prev / Next Controls
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            HudSquareButton(
                                icon = Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = "Vorheriges Kapitel",
                                sizeDp = 34,
                                enabled = hasPrevChapter,
                                onClick = { viewModel.loadPrevChapter() }
                            )

                            if (hasNextChapter && (isNextPrefetched || currentPageIndex >= totalPages)) {
                                Surface(
                                    onClick = { viewModel.loadNextChapter() },
                                    shape = RoundedCornerShape(10.dp),
                                    color = AccentPrimary,
                                    modifier = Modifier.height(34.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        modifier = Modifier.padding(horizontal = 10.dp)
                                    ) {
                                        Text(
                                            text = if (isNextPrefetched) "Nächstes Kap. (bereit)" else "Nächstes Kap.",
                                            fontFamily = BodyFontFamily,
                                            fontWeight = FontWeight.Medium,
                                            fontSize = 11.sp,
                                            color = Color.White,
                                            maxLines = 1
                                        )
                                        Icon(
                                            imageVector = Icons.Outlined.ChevronRight,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            } else {
                                HudSquareButton(
                                    icon = Icons.AutoMirrored.Outlined.ArrowForward,
                                    contentDescription = "Nächstes Kapitel",
                                    sizeDp = 34,
                                    enabled = hasNextChapter,
                                    iconTint = AccentPrimaryLight,
                                    onClick = { viewModel.loadNextChapter() }
                                )
                            }
                        }

                        // Visible Zoom Control Pill [ - | 100% | + ]
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = DarkSurfaceElevated,
                            border = BorderStroke(1.dp, Color(0xFF272140)),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 3.dp)
                            ) {
                                val canZoomOut = zoomScale > 1.0f
                                val canZoomIn = zoomScale < 4.0f
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clickable(enabled = canZoomOut) {
                                            zoomScale = (zoomScale - 0.25f).coerceAtLeast(1.0f)
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.ZoomOut,
                                        contentDescription = "Verkleinern",
                                        tint = Color(0xFFCFCBE2).copy(alpha = if (canZoomOut) 1f else 0.35f),
                                        modifier = Modifier.size(15.dp)
                                    )
                                }

                                Text(
                                    text = "${(zoomScale * 100).roundToInt()}%",
                                    fontFamily = MonoFontFamily,
                                    fontSize = 11.sp,
                                    color = Color(0xFFD8D3EE),
                                    modifier = Modifier.padding(horizontal = 6.dp)
                                )

                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clickable(enabled = canZoomIn) {
                                            zoomScale = (zoomScale + 0.25f).coerceAtMost(4.0f)
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.ZoomIn,
                                        contentDescription = "Vergrößern",
                                        tint = Color(0xFFCFCBE2).copy(alpha = if (canZoomIn) 1f else 0.35f),
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HudSquareButton(
    icon: ImageVector,
    contentDescription: String,
    sizeDp: Int = 38,
    enabled: Boolean = true,
    containerColor: Color = DarkSurfaceElevated,
    borderColor: Color = Color(0xFF272140),
    iconTint: Color = Color(0xFFD2CCE8),
    onClick: () -> Unit
) {
    Surface(
        onClick = { if (enabled) onClick() },
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        color = containerColor.copy(alpha = if (enabled) 1f else 0.5f),
        border = BorderStroke(1.dp, borderColor.copy(alpha = if (enabled) 1f else 0.4f)),
        modifier = Modifier.size(sizeDp.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = iconTint.copy(alpha = if (enabled) 1f else 0.35f),
                modifier = Modifier.size((sizeDp * 0.47f).dp)
            )
        }
    }
}

@Composable
private fun ReaderMangaPage(
    pageIndex: Int,
    imageUrl: String,
    chapterUrl: String,
    isOffline: Boolean,
    sessionRefreshKey: Int,
    contentScale: ContentScale,
    loadingHeightDp: Int?,
    viewModel: MangaViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var retryKey by remember(imageUrl) { mutableIntStateOf(0) }
    var bypassNonce by remember(imageUrl) { mutableIntStateOf(0) }
    var pendingBypassNotify by remember(imageUrl) { mutableStateOf(false) }

    val imageModel = remember(imageUrl, isOffline, retryKey, bypassNonce, sessionRefreshKey) {
        if (isOffline) {
            File(imageUrl)
        } else {
            val forceFresh = retryKey > 0 || bypassNonce > 0 || sessionRefreshKey > 0
            ImageRequest.Builder(context)
                .data(imageUrl)
                .crossfade(false) // Flackerfreies Rendering ohne Aufblitzen
                .setHeader("Referer", chapterUrl)
                .setParameter(MangaReaderApplication.PARAM_IMAGE_URL, imageUrl)
                .setParameter("attempt_nonce", retryKey + bypassNonce * 1000 + sessionRefreshKey * 10_000)
                .apply {
                    if (bypassNonce > 0 || sessionRefreshKey > 0) {
                        setHeader(MangaReaderApplication.HEADER_BYPASS_CACHE, "1")
                    }
                    if (forceFresh) {
                        diskCachePolicy(CachePolicy.WRITE_ONLY)
                        memoryCachePolicy(CachePolicy.WRITE_ONLY)
                    } else {
                        diskCachePolicy(CachePolicy.ENABLED)
                        memoryCachePolicy(CachePolicy.ENABLED)
                    }
                }
                .build()
        }
    }

    SubcomposeAsyncImage(
        model = imageModel,
        contentDescription = "Seite ${pageIndex + 1}",
        modifier = modifier,
        contentScale = contentScale,
        onSuccess = {
            if (pendingBypassNotify) {
                pendingBypassNotify = false
                viewModel.notifyImageBypassSucceeded(pageIndex)
            }
        },
        onError = {
            pendingBypassNotify = false
        },
        loading = {
            val boxModifier = if (loadingHeightDp != null) {
                Modifier
                    .fillMaxWidth()
                    .height(loadingHeightDp.dp)
                    .background(PureAmoledBlack)
            } else {
                Modifier
                    .fillMaxSize()
                    .background(PureAmoledBlack)
            }
            Box(
                modifier = boxModifier,
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(26.dp),
                        color = AccentPrimaryLight,
                        trackColor = Color(0xFF2A2444),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = if (bypassNonce > 0) {
                            "Seite ${pageIndex + 1} · Cache-Bypass aktiv…"
                        } else {
                            "Seite ${pageIndex + 1} wird geladen…"
                        },
                        fontFamily = MonoFontFamily,
                        fontSize = 11.sp,
                        color = Color(0xFF8D86A8)
                    )
                }
            }
        },
        error = { state ->
            Box(
                modifier = if (loadingHeightDp != null) Modifier.fillMaxWidth() else Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                ImageErrorView(
                    pageIndex = pageIndex,
                    imageUrl = imageUrl,
                    throwable = state.result.throwable,
                    onRetry = { retryKey++ },
                    onCacheBypass = {
                        pendingBypassNotify = true
                        viewModel.invalidateSingleImageCache(imageUrl, pageIndex) {
                            bypassNonce++
                        }
                    },
                    onRefreshSession = { viewModel.refreshChapterSession() },
                    onOpenInBrowser = { viewModel.openCurrentChapterInBrowser() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChapterEndFooter(
    chapterTitle: String,
    hasNextChapter: Boolean,
    hasPrevChapter: Boolean,
    isNextPrefetched: Boolean,
    onNextChapter: () -> Unit,
    onPrevChapter: () -> Unit,
    onBackToLibrary: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 20.dp),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF110E1E),
        border = BorderStroke(1.dp, Color(0xFF241F3A))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Ende von $chapterTitle",
                fontFamily = MonoFontFamily,
                fontSize = 11.sp,
                color = Color(0xFF9C95BA),
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = when {
                    isNextPrefetched -> "Folgekapitel vorgeladen — sofort bereit"
                    hasNextChapter -> "Nächstes Kapitel verfügbar"
                    else -> "Neuestes Kapitel erreicht"
                },
                fontFamily = DisplayFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 17.sp,
                color = Color.White,
                textAlign = TextAlign.Center
            )

            Text(
                text = when {
                    isNextPrefetched -> "Durch Automatisches Pre-Fetching startet das nächste Kapitel ohne Ladezeit."
                    hasNextChapter -> "Lade das nächste Kapitel direkt im Reader."
                    else -> "Für diese Serie wurde kein weiteres Folgekapitel erkannt."
                },
                fontFamily = BodyFontFamily,
                fontSize = 12.sp,
                color = Color(0xFFA39CC0),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(6.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (hasPrevChapter) {
                    Surface(
                        onClick = onPrevChapter,
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF18142C),
                        border = BorderStroke(1.dp, Color(0xFF2D274A)),
                        modifier = Modifier.height(42.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(horizontal = 14.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ChevronLeft,
                                contentDescription = null,
                                tint = Color(0xFFD8D3EE),
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Vorheriges Kapitel",
                                fontFamily = BodyFontFamily,
                                fontWeight = FontWeight.Medium,
                                fontSize = 13.sp,
                                color = Color(0xFFD8D3EE)
                            )
                        }
                    }
                }

                if (hasNextChapter) {
                    Button(
                        onClick = onNextChapter,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AccentPrimary,
                            contentColor = Color.White
                        ),
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 0.dp),
                        modifier = Modifier.height(42.dp)
                    ) {
                        Text(
                            text = if (isNextPrefetched) "Nächstes Kapitel (bereit)" else "Nächstes Kapitel",
                            fontFamily = BodyFontFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Outlined.ChevronRight,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                } else {
                    Surface(
                        onClick = onBackToLibrary,
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF1B1633),
                        border = BorderStroke(1.dp, Color(0xFF342C57)),
                        modifier = Modifier.height(42.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(horizontal = 18.dp)
                        ) {
                            Text(
                                text = "Zur Bibliothek",
                                fontFamily = BodyFontFamily,
                                fontWeight = FontWeight.Medium,
                                fontSize = 13.sp,
                                color = Color.White
                            )
                        }
                    }
                }
            }
        }
    }
}
