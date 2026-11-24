package com.mangareader.app.ui.screens.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.mangareader.app.data.model.ReaderPreferences
import com.mangareader.app.ui.theme.PureAmoledBlack

private val SepiaWarmOverlayColor = Color(0xFFE59866)

/**
 * Non-interactive display comfort overlays rendered over manga pages:
 * - Software Dimmer (up to 80% pure AMOLED black overlay for night reading below hardware minimum)
 * - Optional Warm Sepia / Blue-Light Night Filter
 *
 * Strictly pointer-pass-through (no clickable or pointerInput modifiers).
 */
@Composable
fun ReaderFilterOverlays(
    preferences: ReaderPreferences,
    modifier: Modifier = Modifier
) {
    val dimmerAlpha = ReaderFilterLogic.computeDimmerAlpha(
        enabled = preferences.isCustomBrightnessEnabled,
        brightnessLevel = preferences.brightnessLevel
    )
    val nightFilterAlpha = ReaderFilterLogic.computeNightFilterAlpha(
        enabled = preferences.isNightFilterEnabled,
        strength = preferences.nightFilterStrength
    )

    if (dimmerAlpha <= 0f && nightFilterAlpha <= 0f) return

    Box(modifier = modifier.fillMaxSize()) {
        if (dimmerAlpha > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(PureAmoledBlack.copy(alpha = dimmerAlpha))
            )
        }

        if (nightFilterAlpha > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(SepiaWarmOverlayColor.copy(alpha = nightFilterAlpha))
            )
        }
    }
}
