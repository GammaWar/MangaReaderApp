package com.mangareader.app.ui.screens.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mangareader.app.data.model.ReaderMode
import com.mangareader.app.ui.theme.AccentPrimary
import com.mangareader.app.ui.theme.AccentPrimaryLight
import com.mangareader.app.ui.theme.BodyFontFamily
import com.mangareader.app.ui.theme.DangerBorder
import com.mangareader.app.ui.theme.DangerSurface
import com.mangareader.app.ui.theme.DangerText
import com.mangareader.app.ui.theme.MonoFontFamily
import kotlinx.coroutines.isActive

/**
 * Refresh-rate-independent, frame-paced continuous autoscroll driver for Webtoon mode.
 * Synchronizes directly with Compose's choreographer ([withFrameNanos]) for 60/90/120 Hz smoothness.
 */
@Composable
fun AutoScrollEffect(
    isAutoScrolling: Boolean,
    autoScrollSpeed: Float,
    readerMode: ReaderMode,
    listState: LazyListState,
    onReachedChapterEnd: () -> Unit
) {
    val density = LocalDensity.current.density
    val currentSpeed by rememberUpdatedState(autoScrollSpeed)
    val currentOnReachedEnd by rememberUpdatedState(onReachedChapterEnd)

    LaunchedEffect(isAutoScrolling, readerMode, listState, density) {
        if (!isAutoScrolling || readerMode != ReaderMode.VERTICAL_WEBTOON) return@LaunchedEffect

        var lastFrameNanos = withFrameNanos { it }
        while (isActive) {
            val frameNanos = withFrameNanos { it }
            val deltaMs = (frameNanos - lastFrameNanos) / 1_000_000f
            lastFrameNanos = frameNanos

            if (!listState.canScrollForward) {
                currentOnReachedEnd()
                break
            }

            val scrollDeltaPx = AutoScrollLogic.computeFrameScrollDeltaPx(
                speed = currentSpeed,
                frameDeltaMs = deltaMs,
                density = density
            )
            if (scrollDeltaPx > 0f) {
                listState.scrollBy(scrollDeltaPx)
            }
        }
    }
}

/**
 * Floating AMOLED Autoscroll Pill (`[ Pause | - | 1.0x | + | Stop ]`) rendered above the bottom edge.
 */
@Composable
fun AutoScrollFloatingPill(
    visible: Boolean,
    speed: Float,
    showControls: Boolean,
    onPause: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    val canSlowDown = speed > AutoScrollLogic.MIN_SPEED + 0.01f
    val canSpeedUp = speed < AutoScrollLogic.MAX_SPEED - 0.01f

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { it }),
        modifier = modifier
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
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Surface(
                    onClick = onPause,
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

                // Speed stepper & preset cycler [ - | 1.0x | + ]
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = Color(0xFF1F1A38),
                    border = BorderStroke(1.dp, Color(0xFF342B59)),
                    modifier = Modifier.height(34.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clickable(enabled = canSlowDown) {
                                    onSpeedChange(AutoScrollLogic.stepSpeedDown(speed))
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Remove,
                                contentDescription = "Autoscroll verlangsamen",
                                tint = AccentPrimaryLight.copy(alpha = if (canSlowDown) 1f else 0.35f),
                                modifier = Modifier.size(14.dp)
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier
                                .clickable {
                                    onSpeedChange(AutoScrollLogic.nextPresetSpeed(speed))
                                }
                                .padding(horizontal = 6.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Speed,
                                contentDescription = "Geschwindigkeit wechseln",
                                tint = AccentPrimaryLight,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = AutoScrollLogic.formatSpeedLabel(speed),
                                fontFamily = MonoFontFamily,
                                fontWeight = FontWeight.Medium,
                                fontSize = 12.sp,
                                color = Color(0xFFE5DCFF)
                            )
                        }

                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clickable(enabled = canSpeedUp) {
                                    onSpeedChange(AutoScrollLogic.stepSpeedUp(speed))
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Add,
                                contentDescription = "Autoscroll beschleunigen",
                                tint = AccentPrimaryLight.copy(alpha = if (canSpeedUp) 1f else 0.35f),
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }

                Surface(
                    onClick = onStop,
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
}
