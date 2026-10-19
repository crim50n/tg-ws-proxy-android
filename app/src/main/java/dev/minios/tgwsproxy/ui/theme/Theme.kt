package dev.minios.tgwsproxy.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

// Telegram-inspired colors
val TgBlue = Color(0xFF167DA7)
val TgBlueDark = Color(0xFF42677D)

val LightBackground = Color(0xFFF1F4F6)
val DarkBackground = Color(0xFF17212B)

val LightSurface = Color(0xFFFFFFFF)
val DarkSurface = Color(0xFF232E3C)

val LightSurfaceVariant = Color(0xFFE7EEF3)
val DarkSurfaceVariant = Color(0xFF2B3948)

val TextSecondaryLight = Color(0xFF5D6C78)
val TextSecondaryDark = Color(0xFFA8B8C7)

val StatusGreen: Color
    @Composable
    get() =
        if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
            Color(0xFF79D5A5)
        } else {
            Color(0xFF247548)
        }
val StatusRed: Color
    @Composable get() = MaterialTheme.colorScheme.error
val StatusOrange: Color
    @Composable
    get() =
        if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
            Color(0xFFF0BD70)
        } else {
            Color(0xFF8B5B13)
        }

private val LightColorScheme =
    lightColorScheme(
        primary = TgBlue,
        onPrimary = Color.White,
        primaryContainer = Color(0xFFDCEFF9),
        onPrimaryContainer = Color(0xFF145374),
        secondary = TgBlueDark,
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFDFEBF2),
        onSecondaryContainer = Color(0xFF284B60),
        tertiary = Color(0xFF286D82),
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFDCEFF3),
        onTertiaryContainer = Color(0xFF245563),
        background = LightBackground,
        surface = LightSurface,
        surfaceVariant = LightSurfaceVariant,
        surfaceDim = Color(0xFFDCE3E9),
        surfaceBright = LightSurface,
        surfaceContainerLowest = LightSurface,
        surfaceContainerLow = LightSurface,
        surfaceContainer = Color(0xFFF1F5F8),
        surfaceContainerHigh = Color(0xFFEAF0F5),
        surfaceContainerHighest = LightSurfaceVariant,
        onBackground = Color(0xFF17212B),
        onSurface = Color(0xFF17212B),
        onSurfaceVariant = TextSecondaryLight,
        outline = Color(0xFF738593),
        outlineVariant = Color(0xFFCFDCE5),
        surfaceTint = TgBlue,
        inverseSurface = DarkSurface,
        inverseOnSurface = Color(0xFFE8EEF4),
        inversePrimary = Color(0xFF6AB3E3),
    )

private val DarkColorScheme =
    darkColorScheme(
        primary = Color(0xFF6AB3E3),
        onPrimary = Color(0xFF102F43),
        primaryContainer = Color(0xFF25465E),
        onPrimaryContainer = Color(0xFFC5E7FA),
        secondary = Color(0xFFA5C8DD),
        onSecondary = Color(0xFF193546),
        secondaryContainer = Color(0xFF304657),
        onSecondaryContainer = Color(0xFFD5E7F2),
        tertiary = Color(0xFF8FCBD8),
        onTertiary = Color(0xFF103640),
        tertiaryContainer = Color(0xFF294953),
        onTertiaryContainer = Color(0xFFC7EAF1),
        background = DarkBackground,
        surface = DarkSurface,
        surfaceVariant = DarkSurfaceVariant,
        surfaceDim = DarkBackground,
        surfaceBright = Color(0xFF354658),
        surfaceContainerLowest = Color(0xFF111B25),
        surfaceContainerLow = DarkSurface,
        surfaceContainer = Color(0xFF273443),
        surfaceContainerHigh = DarkSurfaceVariant,
        surfaceContainerHighest = Color(0xFF324354),
        onBackground = Color(0xFFE8EEF4),
        onSurface = Color(0xFFE8EEF4),
        onSurfaceVariant = TextSecondaryDark,
        outline = Color(0xFF8195A6),
        outlineVariant = Color(0xFF3B4C5D),
        surfaceTint = Color(0xFF6AB3E3),
        inverseSurface = Color(0xFFE8EEF4),
        inverseOnSurface = DarkBackground,
        inversePrimary = TgBlue,
    )

private val AppTypography =
    Typography(
        titleLarge =
            TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
        titleMedium =
            TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp),
        titleSmall =
            TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 22.sp),
        labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    )

private val AppShapes =
    Shapes(
        small = RoundedCornerShape(10.dp),
        medium = RoundedCornerShape(14.dp),
        large = RoundedCornerShape(20.dp),
        extraLarge = RoundedCornerShape(24.dp),
    )

/** Standard switch colors matching Telegram style, adaptive to light/dark theme. */
@Composable
fun tgSwitchColors(): SwitchColors {
    val colors = MaterialTheme.colorScheme
    return SwitchDefaults.colors(
        checkedThumbColor = colors.onPrimary,
        checkedTrackColor = colors.primary,
        checkedBorderColor = colors.primary,
        uncheckedThumbColor = colors.outline,
        uncheckedTrackColor = colors.surfaceVariant,
        uncheckedBorderColor = colors.outline,
    )
}

@Composable
fun TgWsProxyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme =
        when {
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                val context = LocalContext.current
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }
            darkTheme -> DarkColorScheme
            else -> LightColorScheme
        }

    // Dynamically set navigation bar color to match theme background
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.primary.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isStatusBarContrastEnforced = false
                window.isNavigationBarContrastEnforced = false
            }
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightNavigationBars = !darkTheme
                // Choose the higher-contrast black or white system icons.
                isAppearanceLightStatusBars = colorScheme.primary.luminance() > 0.179f
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
