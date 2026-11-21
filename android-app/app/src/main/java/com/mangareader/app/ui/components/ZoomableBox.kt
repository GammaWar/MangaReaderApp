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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import kotlin.math.abs

@Composable
fun ZoomableBox(
    modifier: Modifier = Modifier,
    maxScale: Float = 4f,
    minScale: Float = 1f,
    externalScale: Float? = null,
    onScaleChange: ((Float) -> Unit)? = null,
    onTap: (() -> Unit)? = null,
    onTapOffset: ((Offset) -> Unit)? = null,
    content: @Composable () -> Unit
) {
    var scale by remember { mutableFloatStateOf(externalScale?.coerceIn(minScale, maxScale) ?: 1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val currentOnScaleChange by rememberUpdatedState(onScaleChange)
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnTapOffset by rememberUpdatedState(onTapOffset)

    LaunchedEffect(externalScale) {
        if (externalScale != null) {
            val clamped = externalScale.coerceIn(minScale, maxScale)
            if (abs(clamped - scale) > 0.001f) {
                scale = clamped
                if (scale <= 1.05f) {
                    offset = Offset.Zero
                }
            }
        }
    }

    Box(
        modifier = modifier
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
                    val downTime = System.currentTimeMillis()
                    val downPosition = down.position
                    var movedPastSlop = false
                    var multiTouchDetected = false

                    while (true) {
                        val event = awaitPointerEvent(pass = PointerEventPass.Main)
                        val pressedChanges = event.changes.filter { it.pressed }

                        if (pressedChanges.isEmpty()) {
                            // Touch released without dragging
                            val elapsed = System.currentTimeMillis() - downTime
                            if (!movedPastSlop && !multiTouchDetected && elapsed < 350) {
                                currentOnTap?.invoke()
                                currentOnTapOffset?.invoke(downPosition)
                            }
                            break
                        }

                        if (pressedChanges.size >= 2) {
                            multiTouchDetected = true
                            movedPastSlop = true

                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()

                            val newScale = (scale * zoomChange).coerceIn(minScale, maxScale)
                            scale = newScale

                            if (scale > 1.05f) {
                                val maxOffsetX = (size.width * (scale - 1)) / 2f
                                val maxOffsetY = (size.height * (scale - 1)) / 2f
                                offset = Offset(
                                    x = (offset.x + panChange.x).coerceIn(-maxOffsetX, maxOffsetX),
                                    y = (offset.y + panChange.y).coerceIn(-maxOffsetY, maxOffsetY)
                                )
                            } else {
                                scale = 1f
                                offset = Offset.Zero
                            }
                            currentOnScaleChange?.invoke(scale)

                            event.changes.forEach { it.consume() }
                        } else if (pressedChanges.size == 1) {
                            val change = pressedChanges.first()
                            val dragDistance = (change.position - downPosition).getDistance()
                            if (dragDistance > 18f) {
                                movedPastSlop = true
                            }

                            if (scale > 1.05f) {
                                val pan = change.positionChange()
                                val maxOffsetX = (size.width * (scale - 1)) / 2f
                                val maxOffsetY = (size.height * (scale - 1)) / 2f
                                offset = Offset(
                                    x = (offset.x + pan.x).coerceIn(-maxOffsetX, maxOffsetX),
                                    y = (offset.y + pan.y).coerceIn(-maxOffsetY, maxOffsetY)
                                )
                                change.consume()
                            }
                            // When scale <= 1.05f, drags are unconsumed and pass to parent Pager/LazyColumn
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
