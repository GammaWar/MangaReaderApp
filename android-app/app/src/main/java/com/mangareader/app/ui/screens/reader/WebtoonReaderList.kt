package com.mangareader.app.ui.screens.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mangareader.app.data.model.Chapter
import com.mangareader.app.ui.components.ZoomableBox
import com.mangareader.app.ui.theme.AccentPrimary
import com.mangareader.app.ui.theme.BodyFontFamily
import com.mangareader.app.ui.theme.DisplayFontFamily
import com.mangareader.app.ui.theme.MonoFontFamily
import com.mangareader.app.ui.theme.PureAmoledBlack

/**
 * Seamless vertical Webtoon / Manhua reader rendered on a pure AMOLED (#000000) canvas.
 *
 * Each page is wrapped in [ZoomableBox] so single taps cleanly toggle the HUD without
 * double-firing on the parent list, and vertical drags flow unconsumed at 1.0x.
 */
@Composable
fun WebtoonReaderList(
    chapter: Chapter,
    listState: LazyListState,
    zoomScale: Float,
    sessionRefreshKey: Int,
    hasNextChapter: Boolean,
    hasPrevChapter: Boolean,
    isNextPrefetched: Boolean,
    onScaleChange: (Float) -> Unit,
    onToggleHud: () -> Unit,
    onInvalidateSingleImageCache: (imageUrl: String, pageIndex: Int, onDone: () -> Unit) -> Unit,
    onBypassSucceeded: (pageIndex: Int) -> Unit,
    onRefreshSession: () -> Unit,
    onOpenInBrowser: () -> Unit,
    onNextChapter: () -> Unit,
    onPrevChapter: () -> Unit,
    onBackToLibrary: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .background(PureAmoledBlack),
        contentPadding = PaddingValues(bottom = 110.dp)
    ) {
        itemsIndexed(
            items = chapter.images,
            key = { index, url -> "$index-$url" }
        ) { index, imageUrl ->
            ZoomableBox(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 280.dp),
                externalScale = zoomScale,
                onScaleChange = onScaleChange,
                onTap = onToggleHud
            ) {
                ReaderMangaPage(
                    pageIndex = index,
                    imageUrl = imageUrl,
                    chapterUrl = chapter.chapterUrl,
                    isOffline = chapter.isOffline,
                    sessionRefreshKey = sessionRefreshKey,
                    contentScale = ContentScale.FillWidth,
                    loadingHeightDp = 380,
                    onInvalidateSingleImageCache = onInvalidateSingleImageCache,
                    onBypassSucceeded = onBypassSucceeded,
                    onRefreshSession = onRefreshSession,
                    onOpenInBrowser = onOpenInBrowser,
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 280.dp)
                )
            }
        }

        item(key = "chapter_end_footer") {
            ChapterEndFooter(
                chapterTitle = chapter.title,
                hasNextChapter = hasNextChapter,
                hasPrevChapter = hasPrevChapter,
                isNextPrefetched = isNextPrefetched,
                onNextChapter = onNextChapter,
                onPrevChapter = onPrevChapter,
                onBackToLibrary = onBackToLibrary
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChapterEndFooter(
    chapterTitle: String,
    hasNextChapter: Boolean,
    hasPrevChapter: Boolean,
    isNextPrefetched: Boolean,
    onNextChapter: () -> Unit,
    onPrevChapter: () -> Unit,
    onBackToLibrary: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
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
