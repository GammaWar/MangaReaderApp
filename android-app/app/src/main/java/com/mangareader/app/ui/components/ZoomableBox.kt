package com.mangareader.app.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import com.mangareader.app.ui.screens.reader.ZoomMath
import kotlin.math.abs

/**
 * Conflict-free pinch-to-zoom (1.0x–4.0x) and pan container for manga & webtoon pages.
 *
 * Design guarantees:
 * - Multi-touch pinch scales smoothly from 1.0x without mid-gesture snapping; upon release,
 *   scales <= 1.05x settle cleanly back to 1.0x with zero offset.
 * - Single-finger drags are consumed ONLY when zoomed (> 1.05x). At 1.0x, single-finger drags
 *   remain unconsumed so parent [androidx.compose.foundation.lazy.LazyColumn] and
 *   [androidx.compose.foundation.pager.HorizontalPager] scroll/swipe without conflict.
 * - Single taps invoke [onTap] / [onTapOffset] immediately (no 300 ms double-tap delay),
 *   and are suppressed if a child interactive control (e.g. ImageErrorView button) consumed the tap.
 */
@Composable
fun ZoomableBox(
    modifier: Modifier = Modifier,
    maxScale: Float = ZoomMath.MAX_SCALE,
    minScale: Float = ZoomMath.MIN_SCALE,
    externalScale: Float? = null,
    clipBounds: Boolean = true,
    onScaleChange: ((Float) -> Unit)? = null,
    onTap: (() -> Unit)? = null,
    onTapOffset: ((Offset) -> Unit)? = null,
    content: @Composable () -> Unit
) {
    var scale by remember {
        mutableFloatStateOf(
            ZoomMath.clampScale(externalScale ?: minScale, minScale, maxScale)
        )
    }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    val currentOnScaleChange by rememberUpdatedState(onScaleChange)
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnTapOffset by rememberUpdatedState(onTapOffset)

    LaunchedEffect(externalScale, minScale, maxScale) {
        if (externalScale != null) {
            val clamped = ZoomMath.clampScale(externalScale, minScale, maxScale)
            if (abs(clamped - scale) > 0.001f) {
                scale = clamped
                val (clampedX, clampedY) = ZoomMath.clampPanOffset(
                    offsetX = offset.x,
                    offsetY = offset.y,
                    viewportWidth = containerSize.width.toFloat(),
                    viewportHeight = containerSize.height.toFloat(),
                    scale = scale
                )
                offset = Offset(clampedX, clampedY)
            }
        }
    }

    Box(
        modifier = modifier
            .then(if (clipBounds) Modifier.clipToBounds() else Modifier)
            .onSizeChanged { newSize ->
                containerSize = newSize
                val (clampedX, clampedY) = ZoomMath.clampPanOffset(
                    offsetX = offset.x,
                    offsetY = offset.y,
                    viewportWidth = newSize.width.toFloat(),
                    viewportHeight = newSize.height.toFloat(),
                    scale = scale
                )
                offset = Offset(clampedX, clampedY)
            }
            .pointerInput(minScale, maxScale) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
                    val downTime = System.currentTimeMillis()
                    val downPosition = down.position
                    var movedPastSlop = false
                    var multiTouchDetected = false
                    var consumedByChild = down.isConsumed

                    while (true) {
                        val event = awaitPointerEvent(pass = PointerEventPass.Main)
                        val pressedChanges = event.changes.filter { it.pressed }

                        if (event.changes.any { it.isConsumed }) {
                            consumedByChild = true
                        }

                        if (pressedChanges.isEmpty()) {
                            if (multiTouchDetected) {
                                val settled = ZoomMath.settleScale(scale, minScale, maxScale)
                                if (abs(settled - scale) > 0.001f) {
                                    scale = settled
                                    val (cx, cy) = ZoomMath.clampPanOffset(
                                        offsetX = offset.x,
                                        offsetY = offset.y,
                                        viewportWidth = size.width.toFloat(),
                                        viewportHeight = size.height.toFloat(),
                                        scale = scale
                                    )
                                    offset = Offset(cx, cy)
                                    currentOnScaleChange?.invoke(scale)
                                }
                            } else {
                                val elapsed = System.currentTimeMillis() - downTime
                                if (!movedPastSlop && !consumedByChild && elapsed < 350L) {
                                    currentOnTap?.invoke()
                                    currentOnTapOffset?.invoke(downPosition)
                                }
                            }
                            break
                        }

                        if (pressedChanges.size >= 2) {
                            multiTouchDetected = true
                            movedPastSlop = true

                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()

                            val newScale = ZoomMath.clampScale(scale * zoomChange, minScale, maxScale)
                            scale = newScale

                            val (maxX, maxY) = ZoomMath.computeMaxPanOffset(
                                viewportWidth = size.width.toFloat(),
                                viewportHeight = size.height.toFloat(),
                                scale = scale
                            )
                            offset = if (maxX > 0f || maxY > 0f) {
                                Offset(
                                    x = (offset.x + panChange.x).coerceIn(-maxX, maxX),
                                    y = (offset.y + panChange.y).coerceIn(-maxY, maxY)
                                )
                            } else {
                                Offset.Zero
                            }
                            currentOnScaleChange?.invoke(scale)

                            event.changes.forEach { it.consume() }
                        } else if (pressedChanges.size == 1) {
                            val change = pressedChanges.first()
                            val dragDistance = (change.position - downPosition).getDistance()
                            if (dragDistance > 18f) {
                                movedPastSlop = true
                            }

                            if (ZoomMath.isPanningActive(scale)) {
                                val pan = change.positionChange()
                                val (clampedX, clampedY) = ZoomMath.clampPanOffset(
                                    offsetX = offset.x + pan.x,
                                    offsetY = offset.y + pan.y,
                                    viewportWidth = size.width.toFloat(),
                                    viewportHeight = size.height.toFloat(),
                                    scale = scale
                                )
                                offset = Offset(clampedX, clampedY)
                                change.consume()
                            }
                            // At scale <= 1.05f, single-finger drags stay unconsumed for LazyColumn / HorizontalPager
                        }
                    }
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            }
    ) {
        content()
    }
}
