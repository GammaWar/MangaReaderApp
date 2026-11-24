package com.mangareader.app.ui.screens.reader

import com.mangareader.app.data.model.ReadingDirection
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pure Kotlin zoom & pan math used by [com.mangareader.app.ui.components.ZoomableBox]
 * and the reader HUD zoom controls.
 */
object ZoomMath {
    const val MIN_SCALE: Float = 1.0f
    const val MAX_SCALE: Float = 4.0f
    const val PAN_ACTIVATION_SCALE: Float = 1.05f
    const val ZOOM_STEP: Float = 0.25f

    fun clampScale(
        scale: Float,
        minScale: Float = MIN_SCALE,
        maxScale: Float = MAX_SCALE
    ): Float {
        if (!scale.isFinite()) return minScale
        val safeMin = minScale.coerceAtLeast(0.5f)
        val safeMax = maxScale.coerceAtLeast(safeMin)
        return scale.coerceIn(safeMin, safeMax)
    }

    /**
     * Called when a pinch gesture ends so near-1x scales snap cleanly back to [minScale]
     * without interfering with gradual pinch-out starts mid-gesture.
     */
    fun settleScale(
        scale: Float,
        minScale: Float = MIN_SCALE,
        maxScale: Float = MAX_SCALE
    ): Float {
        val clamped = clampScale(scale, minScale, maxScale)
        return if (clamped <= minScale + (PAN_ACTIVATION_SCALE - MIN_SCALE)) minScale else clamped
    }

    fun isPanningActive(scale: Float): Boolean = scale.isFinite() && scale > PAN_ACTIVATION_SCALE

    fun stepZoomIn(currentScale: Float, maxScale: Float = MAX_SCALE): Float {
        val stepped = ((currentScale / ZOOM_STEP).roundToInt() + 1) * ZOOM_STEP
        return clampScale(stepped, MIN_SCALE, maxScale)
    }

    fun stepZoomOut(currentScale: Float, minScale: Float = MIN_SCALE): Float {
        val stepped = ((currentScale / ZOOM_STEP).roundToInt() - 1) * ZOOM_STEP
        return clampScale(stepped, minScale, MAX_SCALE)
    }

    fun computeMaxPanOffset(
        viewportWidth: Float,
        viewportHeight: Float,
        scale: Float
    ): Pair<Float, Float> {
        if (!scale.isFinite() || scale <= MIN_SCALE || viewportWidth <= 0f || viewportHeight <= 0f) {
            return 0f to 0f
        }
        val maxX = ((viewportWidth * (scale - 1f)) / 2f).coerceAtLeast(0f)
        val maxY = ((viewportHeight * (scale - 1f)) / 2f).coerceAtLeast(0f)
        return maxX to maxY
    }

    fun clampPanOffset(
        offsetX: Float,
        offsetY: Float,
        viewportWidth: Float,
        viewportHeight: Float,
        scale: Float
    ): Pair<Float, Float> {
        if (!isPanningActive(scale)) return 0f to 0f
        val (maxX, maxY) = computeMaxPanOffset(viewportWidth, viewportHeight, scale)
        val safeX = if (offsetX.isFinite()) offsetX.coerceIn(-maxX, maxX) else 0f
        val safeY = if (offsetY.isFinite()) offsetY.coerceIn(-maxY, maxY) else 0f
        return safeX to safeY
    }

    fun formatZoomPercent(scale: Float): String {
        val clamped = clampScale(scale)
        return "${(clamped * 100f).roundToInt()}%"
    }
}

sealed class PagedTapAction {
    data class GoToPage(val pageIndex: Int) : PagedTapAction()
    data object LoadNextChapter : PagedTapAction()
    data object LoadPrevChapter : PagedTapAction()
    data object ToggleHud : PagedTapAction()
    data object None : PagedTapAction()
}

/**
 * Pure navigation & tap-zone calculations for Webtoon and RTL/LTR Paged reading modes.
 */
object ReaderNavigationLogic {
    const val LEFT_ZONE_FRACTION: Float = 0.22f
    const val RIGHT_ZONE_FRACTION: Float = 0.78f
    const val PREFETCH_REMAINING_PAGES_THRESHOLD: Int = 2

    fun resolvePagedTapAction(
        tapX: Float,
        viewportWidth: Float,
        currentPage: Int,
        totalPages: Int,
        direction: ReadingDirection,
        hasNextChapter: Boolean,
        hasPrevChapter: Boolean
    ): PagedTapAction {
        if (viewportWidth <= 0f || totalPages <= 0 || !tapX.isFinite()) {
            return PagedTapAction.ToggleHud
        }

        val relX = (tapX / viewportWidth).coerceIn(0f, 1f)
        val isRtl = direction == ReadingDirection.RTL

        val pageDelta = when {
            relX < LEFT_ZONE_FRACTION -> if (isRtl) 1 else -1
            relX > RIGHT_ZONE_FRACTION -> if (isRtl) -1 else 1
            else -> return PagedTapAction.ToggleHud
        }

        val targetPage = currentPage + pageDelta
        return when {
            targetPage in 0 until totalPages -> PagedTapAction.GoToPage(targetPage)
            targetPage >= totalPages -> {
                if (hasNextChapter) PagedTapAction.LoadNextChapter else PagedTapAction.None
            }
            else -> {
                if (hasPrevChapter) PagedTapAction.LoadPrevChapter else PagedTapAction.None
            }
        }
    }

    fun clampPageIndex(pageIndex: Int, totalPages: Int): Int {
        if (totalPages <= 0) return 0
        return pageIndex.coerceIn(0, totalPages - 1)
    }

    fun stepPageFromOneBased(currentPageOneBased: Int, delta: Int, totalPages: Int): Int {
        if (totalPages <= 0) return 0
        val currentZeroBased = (currentPageOneBased - 1).coerceIn(0, totalPages - 1)
        return (currentZeroBased + delta).coerceIn(0, totalPages - 1)
    }

    fun shouldTriggerPrefetch(
        currentPageOneBased: Int,
        totalPages: Int,
        isPrefetchEnabled: Boolean
    ): Boolean {
        if (!isPrefetchEnabled || totalPages <= 0) return false
        return currentPageOneBased >= (totalPages - PREFETCH_REMAINING_PAGES_THRESHOLD).coerceAtLeast(1)
    }
}

/**
 * Pure calculations for software dimmer (up to 80%), warm sepia night filter, and keepScreenOn.
 */
object ReaderFilterLogic {
    const val MIN_BRIGHTNESS_LEVEL: Float = 0.20f
    const val MAX_BRIGHTNESS_LEVEL: Float = 1.0f
    const val MAX_DIMMER_ALPHA: Float = 0.80f

    const val MIN_NIGHT_FILTER_STRENGTH: Float = 0.10f
    const val MAX_NIGHT_FILTER_STRENGTH: Float = 0.80f
    const val NIGHT_FILTER_ALPHA_MULTIPLIER: Float = 0.45f

    fun computeDimmerAlpha(enabled: Boolean, brightnessLevel: Float): Float {
        if (!enabled || !brightnessLevel.isFinite()) return 0f
        val clampedLevel = brightnessLevel.coerceIn(MIN_BRIGHTNESS_LEVEL, MAX_BRIGHTNESS_LEVEL)
        return (1f - clampedLevel).coerceIn(0f, MAX_DIMMER_ALPHA)
    }

    fun computeNightFilterAlpha(enabled: Boolean, strength: Float): Float {
        if (!enabled || !strength.isFinite()) return 0f
        val clampedStrength = strength.coerceIn(MIN_NIGHT_FILTER_STRENGTH, MAX_NIGHT_FILTER_STRENGTH)
        return clampedStrength * NIGHT_FILTER_ALPHA_MULTIPLIER
    }

    fun shouldKeepScreenOn(
        hasActiveChapter: Boolean,
        keepScreenOnPreference: Boolean,
        isAutoScrolling: Boolean
    ): Boolean = hasActiveChapter && (keepScreenOnPreference || isAutoScrolling)
}

/**
 * Pure calculations for smooth, refresh-rate-independent Webtoon autoscroll.
 */
object AutoScrollLogic {
    const val MIN_SPEED: Float = 0.25f
    const val MAX_SPEED: Float = 3.5f
    const val SPEED_STEP: Float = 0.25f
    const val BASE_DP_PER_SECOND: Float = 96f
    const val MAX_FRAME_DELTA_MS: Float = 64f

    val SPEED_PRESETS: List<Float> = listOf(0.5f, 1.0f, 1.5f, 2.0f, 3.0f)

    fun clampSpeed(speed: Float): Float {
        if (!speed.isFinite()) return 1.0f
        return speed.coerceIn(MIN_SPEED, MAX_SPEED)
    }

    fun nextPresetSpeed(currentSpeed: Float): Float {
        val clamped = clampSpeed(currentSpeed)
        return SPEED_PRESETS.firstOrNull { it > clamped + 0.01f } ?: SPEED_PRESETS.first()
    }

    fun stepSpeedUp(currentSpeed: Float): Float {
        val rounded = (clampSpeed(currentSpeed) / SPEED_STEP).roundToInt() * SPEED_STEP
        return clampSpeed(rounded + SPEED_STEP)
    }

    fun stepSpeedDown(currentSpeed: Float): Float {
        val rounded = (clampSpeed(currentSpeed) / SPEED_STEP).roundToInt() * SPEED_STEP
        return clampSpeed(rounded - SPEED_STEP)
    }

    fun computeFrameScrollDeltaPx(
        speed: Float,
        frameDeltaMs: Float,
        density: Float
    ): Float {
        if (!frameDeltaMs.isFinite() || frameDeltaMs <= 0f || !density.isFinite() || density <= 0f) {
            return 0f
        }
        val clampedDtSeconds = frameDeltaMs.coerceAtMost(MAX_FRAME_DELTA_MS) / 1000f
        val clampedSpeed = clampSpeed(speed)
        return BASE_DP_PER_SECOND * density * clampedSpeed * clampedDtSeconds
    }

    fun formatSpeedLabel(speed: Float): String {
        val clamped = clampSpeed(speed)
        val roundedTwoDecimals = (clamped * 100f).roundToInt() / 100f
        val oneDecimal = (roundedTwoDecimals * 10f).roundToInt() / 10f
        return if (abs(roundedTwoDecimals - oneDecimal) < 0.005f) {
            "${String.format(Locale.US, "%.1f", oneDecimal)}x"
        } else {
            "${String.format(Locale.US, "%.2f", roundedTwoDecimals)}x"
        }
    }
}

/**
 * Pure HUD & Android System-Bars visibility decisions.
 */
object ReaderHudLogic {

    fun resolveWebtoonHudVisibility(
        currentVisible: Boolean,
        isAtTop: Boolean,
        isAtBottom: Boolean,
        isScrolling: Boolean,
        isAutoScrolling: Boolean
    ): Boolean = when {
        isAtBottom -> true
        isAutoScrolling -> false
        isAtTop -> true
        isScrolling -> false
        else -> currentVisible
    }

    fun shouldShowSystemBars(
        hasActiveChapter: Boolean,
        showHud: Boolean,
        isSettingsOpen: Boolean
    ): Boolean {
        if (!hasActiveChapter) return true
        return showHud || isSettingsOpen
    }
}
