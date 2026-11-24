package com.mangareader.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = AccentPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF261E47),
    onPrimaryContainer = Color(0xFFE9DEFF),
    secondary = AccentSecondary,
    onSecondary = DarkBackground,
    tertiary = AccentTertiary,
    onTertiary = DarkBackground,
    background = PureAmoledBlack,
    onBackground = TextPrimary,
    surface = DarkSurface,
    onSurface = TextPrimary,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    outline = TextTertiary,
    outlineVariant = CardBorder,
    error = ErrorColor,
    onError = Color.White
)

@Composable
fun MangaReaderAppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = DarkColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            @Suppress("DEPRECATION")
            window?.statusBarColor = PureAmoledBlack.toArgb()
            @Suppress("DEPRECATION")
            window?.navigationBarColor = PureAmoledBlack.toArgb()
            window?.let {
                val controller = WindowCompat.getInsetsController(it, view)
                controller.isAppearanceLightStatusBars = false
                controller.isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
