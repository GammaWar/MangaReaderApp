package com.mangareader.app.ui.screens.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import com.mangareader.app.data.model.Chapter
import com.mangareader.app.data.model.ReadingDirection
import com.mangareader.app.ui.components.ZoomableBox
import com.mangareader.app.ui.theme.PureAmoledBlack

/**
 * Horizontal paged Manga reader supporting RTL & LTR layouts, conflict-free 1x–4x zoom/pan,
 * and non-blocking 22% / 56% / 22% tap zones with instant HUD toggle (no 300 ms delay).
 */
@Composable
fun PagedReaderView(
    chapter: Chapter,
    pagerState: PagerState,
    readingDirection: ReadingDirection,
    zoomScale: Float,
    sessionRefreshKey: Int,
    hasNextChapter: Boolean,
    hasPrevChapter: Boolean,
    onScaleChange: (Float) -> Unit,
    onToggleHud: () -> Unit,
    onAnimateToPage: (Int) -> Unit,
    onNextChapter: () -> Unit,
    onPrevChapter: () -> Unit,
    onInvalidateSingleImageCache: (imageUrl: String, pageIndex: Int, onDone: () -> Unit) -> Unit,
    onBypassSucceeded: (pageIndex: Int) -> Unit,
    onRefreshSession: () -> Unit,
    onOpenInBrowser: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isRtl = readingDirection == ReadingDirection.RTL
    val totalPages = chapter.images.size

    HorizontalPager(
        state = pagerState,
        reverseLayout = isRtl,
        userScrollEnabled = !ZoomMath.isPanningActive(zoomScale),
        key = { index -> "$index-${chapter.images[index]}" },
        modifier = modifier
            .fillMaxSize()
            .background(PureAmoledBlack)
    ) { pageIndex ->
        val imageUrl = chapter.images[pageIndex]
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(PureAmoledBlack)
        ) {
            val fullWidth = constraints.maxWidth.toFloat()
            ZoomableBox(
                modifier = Modifier.fillMaxSize(),
                externalScale = zoomScale,
                onScaleChange = onScaleChange,
                onTapOffset = { tapOffset ->
                    when (
                        val action = ReaderNavigationLogic.resolvePagedTapAction(
                            tapX = tapOffset.x,
                            viewportWidth = fullWidth,
                            currentPage = pagerState.currentPage,
                            totalPages = totalPages,
                            direction = readingDirection,
                            hasNextChapter = hasNextChapter,
                            hasPrevChapter = hasPrevChapter
                        )
                    ) {
                        is PagedTapAction.GoToPage -> onAnimateToPage(action.pageIndex)
                        PagedTapAction.LoadNextChapter -> onNextChapter()
                        PagedTapAction.LoadPrevChapter -> onPrevChapter()
                        PagedTapAction.ToggleHud -> onToggleHud()
                        PagedTapAction.None -> Unit
                    }
                }
            ) {
                ReaderMangaPage(
                    pageIndex = pageIndex,
                    imageUrl = imageUrl,
                    chapterUrl = chapter.chapterUrl,
                    isOffline = chapter.isOffline,
                    sessionRefreshKey = sessionRefreshKey,
                    contentScale = ContentScale.Fit,
                    loadingHeightDp = null,
                    onInvalidateSingleImageCache = onInvalidateSingleImageCache,
                    onBypassSucceeded = onBypassSucceeded,
                    onRefreshSession = onRefreshSession,
                    onOpenInBrowser = onOpenInBrowser,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
