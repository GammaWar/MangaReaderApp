package com.mangareader.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object AmoledPalette {
    val PureBlack = Color(0xFF000000)
    val Background = Color(0xFF0B0A12)
    val Surface = Color(0xFF141124)
    val SurfaceElevated = Color(0xFF1D1933)
    val BorderHairline = Color(0xFF27223D)
    val AccentPrimary = Color(0xFF7C4DFF)
    val AccentSoft = Color(0xFFBB86FC)
    val AccentCyan = Color(0xFF22D3EE)
    val TextPrimary = Color(0xFFF5F3FF)
    val TextSecondary = Color(0xFFA7A0C2)
    val TextMuted = Color(0xFF787196)
    val DangerBg = Color(0xFF2A1118)
    val DangerBorder = Color(0xFF7F1D1D)
    val DangerText = Color(0xFFFCA5A5)
    val WarningBg = Color(0xFF23180A)
    val WarningBorder = Color(0xFF78350F)
    val WarningText = Color(0xFFFCD34D)
}

private val AmoledColorScheme = darkColorScheme(
    primary = AmoledPalette.AccentSoft,
    onPrimary = AmoledPalette.PureBlack,
    primaryContainer = AmoledPalette.AccentPrimary,
    onPrimaryContainer = AmoledPalette.TextPrimary,
    secondary = AmoledPalette.AccentCyan,
    onSecondary = AmoledPalette.PureBlack,
    background = AmoledPalette.Background,
    onBackground = AmoledPalette.TextPrimary,
    surface = AmoledPalette.Surface,
    onSurface = AmoledPalette.TextPrimary,
    surfaceVariant = AmoledPalette.SurfaceElevated,
    onSurfaceVariant = AmoledPalette.TextSecondary,
    outline = AmoledPalette.BorderHairline,
    error = AmoledPalette.DangerText,
)

@Composable
fun MangaReaderTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AmoledColorScheme,
        content = content,
    )
}
