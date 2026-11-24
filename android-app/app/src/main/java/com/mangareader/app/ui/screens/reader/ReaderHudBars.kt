package com.mangareader.app.ui.screens.reader

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material.icons.outlined.ZoomOut
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.mangareader.app.data.model.DownloadState
import com.mangareader.app.data.model.ReaderMode
import com.mangareader.app.data.model.ReadingDirection
import com.mangareader.app.ui.theme.AccentPrimary
import com.mangareader.app.ui.theme.AccentPrimaryLight
import com.mangareader.app.ui.theme.AccentTertiary
import com.mangareader.app.ui.theme.BodyFontFamily
import com.mangareader.app.ui.theme.CardBorder
import com.mangareader.app.ui.theme.DarkInputBg
import com.mangareader.app.ui.theme.DarkSurfaceElevated
import com.mangareader.app.ui.theme.DarkSurfaceVariant
import com.mangareader.app.ui.theme.DisplayFontFamily
import com.mangareader.app.ui.theme.MonoFontFamily
import kotlin.math.roundToInt

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Synchronizes Android status & navigation bars with the reader HUD via [WindowInsetsControllerCompat]
 * for true AMOLED (#000000) immersive fullscreen, and guarantees restoration on disposal.
 */
@Composable
fun ReaderSystemBarsEffect(
    hasActiveChapter: Boolean,
    showHud: Boolean,
    isSettingsOpen: Boolean
) {
    val view = LocalView.current
    if (view.isInEditMode) return

    val showSystemBars = ReaderHudLogic.shouldShowSystemBars(
        hasActiveChapter = hasActiveChapter,
        showHud = showHud,
        isSettingsOpen = isSettingsOpen
    )

    LaunchedEffect(view, showSystemBars, hasActiveChapter) {
        val window = view.context.findActivity()?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && hasActiveChapter) {
            val attrs = window.attributes
            if (attrs.layoutInDisplayCutoutMode != WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES) {
                attrs.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                window.attributes = attrs
            }
        }

        if (showSystemBars) {
            controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    DisposableEffect(view) {
        onDispose {
            val window = view.context.findActivity()?.window ?: return@onDispose
            val controller = WindowCompat.getInsetsController(window, view)
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

/**
 * Reliably keeps the screen awake while actively reading or autoscrolling using both
 * [android.view.View.setKeepScreenOn] and [WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON].
 */
@Composable
fun ReaderKeepScreenOnEffect(keepScreenOn: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return

    DisposableEffect(view, keepScreenOn) {
        val window = view.context.findActivity()?.window
        view.keepScreenOn = keepScreenOn
        if (keepScreenOn) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            view.keepScreenOn = false
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

/**
 * Top AMOLED reader HUD bar displaying chapter title, page counter, reading mode metadata,
 * and quick actions (Refresh Session, Autoscroll, Offline Download, Bookmark, Settings).
 */
@Composable
fun ReaderTopHudBar(
    visible: Boolean,
    chapterTitle: String,
    currentPageOneBased: Int,
    totalPages: Int,
    isOffline: Boolean,
    readerMode: ReaderMode,
    readingDirection: ReadingDirection,
    isAutoScrollFeatureEnabled: Boolean,
    isAutoScrolling: Boolean,
    isDownloaded: Boolean,
    isBookmarked: Boolean,
    onBack: () -> Unit,
    onRefreshSession: () -> Unit,
    onToggleAutoScroll: () -> Unit,
    onDownloadChapter: () -> Unit,
    onToggleBookmark: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically(initialOffsetY = { -it }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { -it }),
        modifier = modifier
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
                    onClick = onBack
                )

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = chapterTitle,
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
                            text = "Seite $currentPageOneBased / $totalPages",
                            fontFamily = MonoFontFamily,
                            fontSize = 11.sp,
                            color = Color(0xFFD7CEFF)
                        )
                        if (readerMode == ReaderMode.HORIZONTAL_PAGED) {
                            Text(
                                text = "·",
                                fontSize = 11.sp,
                                color = Color(0xFF9E97BC)
                            )
                            Text(
                                text = if (readingDirection == ReadingDirection.RTL) "RTL" else "LTR",
                                fontFamily = MonoFontFamily,
                                fontSize = 11.sp,
                                color = Color(0xFF9E97BC)
                            )
                        }
                        if (isOffline) {
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
                    if (!isOffline) {
                        HudSquareButton(
                            icon = Icons.Outlined.Refresh,
                            contentDescription = "Sitzung erneuern",
                            onClick = onRefreshSession
                        )
                    }

                    if (isAutoScrollFeatureEnabled) {
                        HudSquareButton(
                            icon = if (isAutoScrolling) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                            contentDescription = if (isAutoScrolling) "Autoscroll pausieren" else "Autoscroll starten",
                            containerColor = if (isAutoScrolling) AccentPrimary else DarkSurfaceElevated,
                            borderColor = if (isAutoScrolling) AccentPrimaryLight else Color(0xFF272140),
                            iconTint = Color.White,
                            onClick = onToggleAutoScroll
                        )
                    }

                    if (!isOffline) {
                        HudSquareButton(
                            icon = if (isDownloaded) Icons.Outlined.CheckCircle else Icons.Outlined.Download,
                            contentDescription = if (isDownloaded) "Kapitel offline gespeichert" else "Kapitel herunterladen",
                            containerColor = if (isDownloaded) Color(0xFF064E3B) else DarkSurfaceElevated,
                            borderColor = if (isDownloaded) AccentTertiary else Color(0xFF272140),
                            iconTint = if (isDownloaded) Color(0xFF6EE7B7) else Color(0xFFD2CCE8),
                            onClick = onDownloadChapter
                        )
                    }

                    HudSquareButton(
                        icon = if (isBookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                        contentDescription = if (isBookmarked) "Lesezeichen entfernen" else "Lesezeichen setzen",
                        containerColor = if (isBookmarked) Color(0xFF2B1F54) else DarkSurfaceElevated,
                        borderColor = if (isBookmarked) AccentPrimaryLight else Color(0xFF272140),
                        iconTint = if (isBookmarked) Color(0xFFE5DCFF) else Color(0xFFD2CCE8),
                        onClick = onToggleBookmark
                    )

                    HudSquareButton(
                        icon = Icons.Outlined.Tune,
                        contentDescription = "Reader-Einstellungen öffnen",
                        iconTint = AccentPrimaryLight,
                        onClick = onOpenSettings
                    )
                }
            }
            HorizontalDivider(thickness = 1.dp, color = Color(0xFF1E1A30))
        }
    }
}

/**
 * Active offline download progress banner floating cleanly below the top bar.
 */
@Composable
fun ReaderDownloadProgressBanner(
    downloadState: DownloadState,
    modifier: Modifier = Modifier
) {
    val inProgress = downloadState as? DownloadState.InProgress ?: return
    Surface(
        modifier = modifier
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

/**
 * Bottom AMOLED reader HUD bar with page scrubber, chapter navigation, and zoom control pill.
 */
@Composable
fun ReaderBottomHudBar(
    visible: Boolean,
    currentPageOneBased: Int,
    totalPages: Int,
    hasPrevChapter: Boolean,
    hasNextChapter: Boolean,
    isNextPrefetched: Boolean,
    zoomScale: Float,
    onJumpToPage: (targetZeroBased: Int, animate: Boolean) -> Unit,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onZoomChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { it }),
        modifier = modifier
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
                            enabled = currentPageOneBased > 1,
                            onClick = {
                                val target = ReaderNavigationLogic.stepPageFromOneBased(
                                    currentPageOneBased = currentPageOneBased,
                                    delta = -1,
                                    totalPages = totalPages
                                )
                                onJumpToPage(target, true)
                            }
                        )

                        Slider(
                            value = (currentPageOneBased - 1).coerceIn(0, totalPages - 1).toFloat(),
                            onValueChange = { raw ->
                                val targetIdx = ReaderNavigationLogic.clampPageIndex(
                                    raw.roundToInt(),
                                    totalPages
                                )
                                onJumpToPage(targetIdx, false)
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
                            enabled = currentPageOneBased < totalPages,
                            onClick = {
                                val target = ReaderNavigationLogic.stepPageFromOneBased(
                                    currentPageOneBased = currentPageOneBased,
                                    delta = 1,
                                    totalPages = totalPages
                                )
                                onJumpToPage(target, true)
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
                            onClick = onPrevChapter
                        )

                        if (hasNextChapter && (isNextPrefetched || currentPageOneBased >= totalPages)) {
                            Surface(
                                onClick = onNextChapter,
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
                                onClick = onNextChapter
                            )
                        }
                    }

                    // Visible Zoom Control Pill [ - | 100% | + ] (tap % to reset to 100%)
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
                            val canZoomOut = zoomScale > ZoomMath.MIN_SCALE + 0.01f
                            val canZoomIn = zoomScale < ZoomMath.MAX_SCALE - 0.01f
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clickable(enabled = canZoomOut) {
                                        onZoomChange(ZoomMath.stepZoomOut(zoomScale))
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
                                text = ZoomMath.formatZoomPercent(zoomScale),
                                fontFamily = MonoFontFamily,
                                fontSize = 11.sp,
                                color = if (canZoomOut) AccentPrimaryLight else Color(0xFFD8D3EE),
                                modifier = Modifier
                                    .clickable(enabled = canZoomOut) {
                                        onZoomChange(ZoomMath.MIN_SCALE)
                                    }
                                    .padding(horizontal = 6.dp, vertical = 4.dp)
                            )

                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clickable(enabled = canZoomIn) {
                                        onZoomChange(ZoomMath.stepZoomIn(zoomScale))
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

@Composable
internal fun HudSquareButton(
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
