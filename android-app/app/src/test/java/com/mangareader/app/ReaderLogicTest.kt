package com.mangareader.app

import com.mangareader.app.data.model.ReadingDirection
import com.mangareader.app.ui.screens.reader.AutoScrollLogic
import com.mangareader.app.ui.screens.reader.PagedTapAction
import com.mangareader.app.ui.screens.reader.ReaderFilterLogic
import com.mangareader.app.ui.screens.reader.ReaderHudLogic
import com.mangareader.app.ui.screens.reader.ReaderNavigationLogic
import com.mangareader.app.ui.screens.reader.ZoomMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderLogicTest {

    // --- ZoomMath ---

    @Test
    fun `ZoomMath clamps scale to 1x-4x and handles non-finite values safely`() {
        assertEquals(1.0f, ZoomMath.clampScale(0.2f), 0.0001f)
        assertEquals(1.0f, ZoomMath.clampScale(1.0f), 0.0001f)
        assertEquals(2.5f, ZoomMath.clampScale(2.5f), 0.0001f)
        assertEquals(4.0f, ZoomMath.clampScale(7.5f), 0.0001f)
        assertEquals(1.0f, ZoomMath.clampScale(Float.NaN), 0.0001f)
        assertEquals(1.0f, ZoomMath.clampScale(Float.POSITIVE_INFINITY), 0.0001f)
    }

    @Test
    fun `ZoomMath settleScale snaps near-1x back to 1x while preserving zoomed scales`() {
        assertEquals(1.0f, ZoomMath.settleScale(1.03f), 0.0001f)
        assertEquals(1.0f, ZoomMath.settleScale(1.05f), 0.0001f)
        assertEquals(1.12f, ZoomMath.settleScale(1.12f), 0.0001f)
        assertEquals(4.0f, ZoomMath.settleScale(5.0f), 0.0001f)

        assertFalse(ZoomMath.isPanningActive(1.0f))
        assertFalse(ZoomMath.isPanningActive(1.05f))
        assertTrue(ZoomMath.isPanningActive(1.06f))
        assertTrue(ZoomMath.isPanningActive(2.5f))
    }

    @Test
    fun `ZoomMath stepZoomIn and stepZoomOut advance cleanly in 25 percent increments`() {
        assertEquals(1.25f, ZoomMath.stepZoomIn(1.0f), 0.0001f)
        assertEquals(4.0f, ZoomMath.stepZoomIn(3.9f), 0.0001f)
        assertEquals(4.0f, ZoomMath.stepZoomIn(4.0f), 0.0001f)

        assertEquals(1.0f, ZoomMath.stepZoomOut(1.0f), 0.0001f)
        assertEquals(1.0f, ZoomMath.stepZoomOut(1.25f), 0.0001f)
        assertEquals(2.25f, ZoomMath.stepZoomOut(2.5f), 0.0001f)

        assertEquals("100%", ZoomMath.formatZoomPercent(1.0f))
        assertEquals("125%", ZoomMath.formatZoomPercent(1.25f))
        assertEquals("400%", ZoomMath.formatZoomPercent(4.5f))
    }

    @Test
    fun `ZoomMath computes and clamps pan offsets within scaled viewport bounds`() {
        assertEquals(0f to 0f, ZoomMath.computeMaxPanOffset(1000f, 2000f, 1.0f))
        assertEquals(500f to 1000f, ZoomMath.computeMaxPanOffset(1000f, 2000f, 2.0f))
        assertEquals(1500f to 3000f, ZoomMath.computeMaxPanOffset(1000f, 2000f, 4.0f))

        // At 1.0x or 1.05x pan is always reset to zero
        assertEquals(0f to 0f, ZoomMath.clampPanOffset(300f, -400f, 1000f, 2000f, 1.0f))
        assertEquals(0f to 0f, ZoomMath.clampPanOffset(300f, -400f, 1000f, 2000f, 1.05f))

        // At 2.0x offsets clamp to [-500, 500] and [-1000, 1000]
        assertEquals(500f to -1000f, ZoomMath.clampPanOffset(999f, -2500f, 1000f, 2000f, 2.0f))
        assertEquals(-250f to 420f, ZoomMath.clampPanOffset(-250f, 420f, 1000f, 2000f, 2.0f))
    }

    // --- ReaderNavigationLogic ---

    @Test
    fun `ReaderNavigationLogic resolves RTL tap zones for page turns, HUD, and chapter boundaries`() {
        // Center zone (22%..78%) always toggles HUD immediately
        assertEquals(
            PagedTapAction.ToggleHud,
            ReaderNavigationLogic.resolvePagedTapAction(
                tapX = 500f,
                viewportWidth = 1000f,
                currentPage = 2,
                totalPages = 10,
                direction = ReadingDirection.RTL,
                hasNextChapter = true,
                hasPrevChapter = true
            )
        )

        // RTL: left 22% advances to next page
        assertEquals(
            PagedTapAction.GoToPage(3),
            ReaderNavigationLogic.resolvePagedTapAction(
                tapX = 100f,
                viewportWidth = 1000f,
                currentPage = 2,
                totalPages = 10,
                direction = ReadingDirection.RTL,
                hasNextChapter = true,
                hasPrevChapter = true
            )
        )

        // RTL: right 22% goes to previous page
        assertEquals(
            PagedTapAction.GoToPage(1),
            ReaderNavigationLogic.resolvePagedTapAction(
                tapX = 900f,
                viewportWidth = 1000f,
                currentPage = 2,
                totalPages = 10,
                direction = ReadingDirection.RTL,
                hasNextChapter = true,
                hasPrevChapter = true
            )
        )

        // RTL on last page: left tap loads next chapter when available, None otherwise
        assertEquals(
            PagedTapAction.LoadNextChapter,
            ReaderNavigationLogic.resolvePagedTapAction(
                tapX = 100f,
                viewportWidth = 1000f,
                currentPage = 9,
                totalPages = 10,
                direction = ReadingDirection.RTL,
                hasNextChapter = true,
                hasPrevChapter = false
            )
        )
        assertEquals(
            PagedTapAction.None,
            ReaderNavigationLogic.resolvePagedTapAction(
                tapX = 100f,
                viewportWidth = 1000f,
                currentPage = 9,
                totalPages = 10,
                direction = ReadingDirection.RTL,
                hasNextChapter = false,
                hasPrevChapter = false
            )
        )

        // RTL on first page: right tap loads previous chapter when available
        assertEquals(
            PagedTapAction.LoadPrevChapter,
            ReaderNavigationLogic.resolvePagedTapAction(
                tapX = 900f,
                viewportWidth = 1000f,
                currentPage = 0,
                totalPages = 10,
                direction = ReadingDirection.RTL,
                hasNextChapter = true,
                hasPrevChapter = true
            )
        )
    }

    @Test
    fun `ReaderNavigationLogic resolves LTR tap zones accurately`() {
        // LTR: left 22% goes backward, right 22% goes forward
        assertEquals(
            PagedTapAction.GoToPage(1),
            ReaderNavigationLogic.resolvePagedTapAction(
                tapX = 100f,
                viewportWidth = 1000f,
                currentPage = 2,
                totalPages = 10,
                direction = ReadingDirection.LTR,
                hasNextChapter = true,
                hasPrevChapter = true
            )
        )
        assertEquals(
            PagedTapAction.GoToPage(3),
            ReaderNavigationLogic.resolvePagedTapAction(
                tapX = 900f,
                viewportWidth = 1000f,
                currentPage = 2,
                totalPages = 10,
                direction = ReadingDirection.LTR,
                hasNextChapter = true,
                hasPrevChapter = true
            )
        )
        assertEquals(
            PagedTapAction.LoadNextChapter,
            ReaderNavigationLogic.resolvePagedTapAction(
                tapX = 900f,
                viewportWidth = 1000f,
                currentPage = 9,
                totalPages = 10,
                direction = ReadingDirection.LTR,
                hasNextChapter = true,
                hasPrevChapter = false
            )
        )
        // Zero viewport width safely falls back to ToggleHud
        assertEquals(
            PagedTapAction.ToggleHud,
            ReaderNavigationLogic.resolvePagedTapAction(
                tapX = 100f,
                viewportWidth = 0f,
                currentPage = 0,
                totalPages = 5,
                direction = ReadingDirection.LTR,
                hasNextChapter = true,
                hasPrevChapter = true
            )
        )
    }

    @Test
    fun `ReaderNavigationLogic clamps page indices, scrubber steps, and prefetch threshold`() {
        assertEquals(0, ReaderNavigationLogic.clampPageIndex(-3, 10))
        assertEquals(9, ReaderNavigationLogic.clampPageIndex(42, 10))
        assertEquals(0, ReaderNavigationLogic.clampPageIndex(2, 0))

        assertEquals(0, ReaderNavigationLogic.stepPageFromOneBased(1, -1, 10))
        assertEquals(4, ReaderNavigationLogic.stepPageFromOneBased(6, -1, 10))
        assertEquals(6, ReaderNavigationLogic.stepPageFromOneBased(6, 1, 10))
        assertEquals(9, ReaderNavigationLogic.stepPageFromOneBased(10, 1, 10))

        assertFalse(ReaderNavigationLogic.shouldTriggerPrefetch(7, 10, true))
        assertTrue(ReaderNavigationLogic.shouldTriggerPrefetch(8, 10, true))
        assertTrue(ReaderNavigationLogic.shouldTriggerPrefetch(10, 10, true))
        assertFalse(ReaderNavigationLogic.shouldTriggerPrefetch(10, 10, false))
    }

    // --- ReaderFilterLogic ---

    @Test
    fun `ReaderFilterLogic caps dimmer at 80 percent, scales sepia filter, and checks keepScreenOn`() {
        assertEquals(0f, ReaderFilterLogic.computeDimmerAlpha(false, 0.2f), 0.0001f)
        assertEquals(0f, ReaderFilterLogic.computeDimmerAlpha(true, 1.0f), 0.0001f)
        assertEquals(0.40f, ReaderFilterLogic.computeDimmerAlpha(true, 0.60f), 0.0001f)
        // Even if brightnessLevel is below 0.20f, alpha never exceeds 0.80f (prevents blacked-out screen)
        assertEquals(0.80f, ReaderFilterLogic.computeDimmerAlpha(true, 0.0f), 0.0001f)
        assertEquals(0.80f, ReaderFilterLogic.computeDimmerAlpha(true, 0.20f), 0.0001f)

        assertEquals(0f, ReaderFilterLogic.computeNightFilterAlpha(false, 0.5f), 0.0001f)
        assertEquals(0.045f, ReaderFilterLogic.computeNightFilterAlpha(true, 0.01f), 0.0001f)
        assertEquals(0.18f, ReaderFilterLogic.computeNightFilterAlpha(true, 0.40f), 0.0001f)
        assertEquals(0.36f, ReaderFilterLogic.computeNightFilterAlpha(true, 1.5f), 0.0001f)

        assertFalse(ReaderFilterLogic.shouldKeepScreenOn(false, true, true))
        assertTrue(ReaderFilterLogic.shouldKeepScreenOn(true, true, false))
        assertTrue(ReaderFilterLogic.shouldKeepScreenOn(true, false, true))
        assertFalse(ReaderFilterLogic.shouldKeepScreenOn(true, false, false))
    }

    // --- AutoScrollLogic ---

    @Test
    fun `AutoScrollLogic cycles presets, steps smoothly, and computes frame-independent deltas`() {
        assertEquals(0.25f, AutoScrollLogic.clampSpeed(0.01f), 0.0001f)
        assertEquals(3.5f, AutoScrollLogic.clampSpeed(9.0f), 0.0001f)
        assertEquals(1.0f, AutoScrollLogic.clampSpeed(Float.NaN), 0.0001f)

        assertEquals(1.0f, AutoScrollLogic.nextPresetSpeed(0.5f), 0.0001f)
        assertEquals(1.5f, AutoScrollLogic.nextPresetSpeed(1.0f), 0.0001f)
        assertEquals(2.0f, AutoScrollLogic.nextPresetSpeed(1.5f), 0.0001f)
        assertEquals(3.0f, AutoScrollLogic.nextPresetSpeed(2.0f), 0.0001f)
        assertEquals(0.5f, AutoScrollLogic.nextPresetSpeed(3.0f), 0.0001f)

        assertEquals(1.25f, AutoScrollLogic.stepSpeedUp(1.0f), 0.0001f)
        assertEquals(0.75f, AutoScrollLogic.stepSpeedDown(1.0f), 0.0001f)
        assertEquals("1.0x", AutoScrollLogic.formatSpeedLabel(1.0f))
        assertEquals("1.25x", AutoScrollLogic.formatSpeedLabel(1.25f))

        // 2 frames at 120Hz (8.33ms) should scroll the same distance as 1 frame at 60Hz (16.66ms)
        val delta60 = AutoScrollLogic.computeFrameScrollDeltaPx(speed = 1.5f, frameDeltaMs = 16.66f, density = 3.0f)
        val delta120 = AutoScrollLogic.computeFrameScrollDeltaPx(speed = 1.5f, frameDeltaMs = 8.33f, density = 3.0f)
        assertEquals(delta60, delta120 * 2f, 0.01f)

        // Large stall frames are capped at 64ms so there is never a giant scroll jump
        val stalled = AutoScrollLogic.computeFrameScrollDeltaPx(speed = 1.0f, frameDeltaMs = 5000f, density = 2.0f)
        val capped = AutoScrollLogic.computeFrameScrollDeltaPx(speed = 1.0f, frameDeltaMs = 64f, density = 2.0f)
        assertEquals(capped, stalled, 0.0001f)
        assertEquals(0f, AutoScrollLogic.computeFrameScrollDeltaPx(speed = 1.0f, frameDeltaMs = 0f, density = 2.0f), 0.0001f)
    }

    // --- ReaderHudLogic ---

    @Test
    fun `ReaderHudLogic resolves HUD and System-Bars visibility consistently`() {
        // Bottom of chapter always shows HUD, even if autoscrolling just ended
        assertTrue(
            ReaderHudLogic.resolveWebtoonHudVisibility(
                currentVisible = false,
                isAtTop = false,
                isAtBottom = true,
                isScrolling = true,
                isAutoScrolling = true
            )
        )
        // Starting autoscroll from top keeps HUD hidden for immersive reading
        assertFalse(
            ReaderHudLogic.resolveWebtoonHudVisibility(
                currentVisible = true,
                isAtTop = true,
                isAtBottom = false,
                isScrolling = false,
                isAutoScrolling = true
            )
        )
        // Manual rest at top shows HUD
        assertTrue(
            ReaderHudLogic.resolveWebtoonHudVisibility(
                currentVisible = false,
                isAtTop = true,
                isAtBottom = false,
                isScrolling = false,
                isAutoScrolling = false
            )
        )
        // Scrolling mid-chapter hides HUD
        assertFalse(
            ReaderHudLogic.resolveWebtoonHudVisibility(
                currentVisible = true,
                isAtTop = false,
                isAtBottom = false,
                isScrolling = true,
                isAutoScrolling = false
            )
        )

        // System bars stay visible on empty state or when settings sheet is open, otherwise track HUD
        assertTrue(ReaderHudLogic.shouldShowSystemBars(hasActiveChapter = false, showHud = false, isSettingsOpen = false))
        assertFalse(ReaderHudLogic.shouldShowSystemBars(hasActiveChapter = true, showHud = false, isSettingsOpen = false))
        assertTrue(ReaderHudLogic.shouldShowSystemBars(hasActiveChapter = true, showHud = true, isSettingsOpen = false))
        assertTrue(ReaderHudLogic.shouldShowSystemBars(hasActiveChapter = true, showHud = false, isSettingsOpen = true))
    }
}
