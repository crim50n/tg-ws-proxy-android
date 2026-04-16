package dev.minios.tgwsproxy.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Telegram-inspired colors
val TgBlue = Color(0xFF3390EC)
val TgBlueDark = Color(0xFF2B7CD3)

val LightBackground = Color(0xFFFFFFFF)
val DarkBackground = Color(0xFF1E1E1E)

val LightSurface = Color(0xFFF0F2F5)
val DarkSurface = Color(0xFF2B2B2B)

val LightSurfaceVariant = Color(0xFFE3E5E8)
val DarkSurfaceVariant = Color(0xFF3A3A3A)

val TextSecondaryLight = Color(0xFF707579)
val TextSecondaryDark = Color(0xFFAAAAAA)

val StatusGreen = Color(0xFF4CAF50)
val StatusRed = Color(0xFFF44336)
val StatusOrange = Color(0xFFFF9800)

private val LightColorScheme = lightColorScheme(
    primary = TgBlue,
    onPrimary = Color.White,
    primaryContainer = TgBlue.copy(alpha = 0.12f),
    onPrimaryContainer = TgBlue,
    secondary = TgBlueDark,
    background = LightBackground,
    surface = LightSurface,
    surfaceVariant = LightSurfaceVariant,
    onBackground = Color.Black,
    onSurface = Color.Black,
    onSurfaceVariant = TextSecondaryLight,
    outline = Color(0xFFD6D9DC),
)

private val DarkColorScheme = darkColorScheme(
    primary = TgBlue,
    onPrimary = Color.White,
    primaryContainer = TgBlue.copy(alpha = 0.12f),
    onPrimaryContainer = TgBlue,
    secondary = TgBlueDark,
    background = DarkBackground,
    surface = DarkSurface,
    surfaceVariant = DarkSurfaceVariant,
    onBackground = Color.White,
    onSurface = Color.White,
    onSurfaceVariant = TextSecondaryDark,
    outline = Color(0xFF3A3A3A),
)

/**
 * Standard switch colors matching Telegram style, adaptive to light/dark theme.
 */
@Composable
fun tgSwitchColors(): SwitchColors {
    val dark = isSystemInDarkTheme()
    return SwitchDefaults.colors(
        checkedThumbColor = Color.White,
        checkedTrackColor = TgBlue,
        checkedBorderColor = TgBlue,
        uncheckedThumbColor = if (dark) Color(0xFFBBBBBB) else Color(0xFFFFFFFF),
        uncheckedTrackColor = if (dark) Color(0xFF4A4A4A) else Color(0xFFD6D9DC),
        uncheckedBorderColor = if (dark) Color(0xFF6B6B6B) else Color(0xFFBBBBBB),
    )
}

@Composable
fun TgWsProxyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    // Dynamically set navigation bar color to match theme background
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
