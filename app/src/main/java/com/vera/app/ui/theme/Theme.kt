package com.vera.app.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Cyber Electric Blue & Turquoise Dark Color Scheme matching the VERA Shield Logo
val VeraDarkColorScheme = darkColorScheme(
    primary = VeraCyan,
    onPrimary = VeraBackground,
    primaryContainer = VeraSurfaceContainer,
    onPrimaryContainer = VeraCyanUltraLight,

    secondary = VeraSecondary,
    onSecondary = VeraBackground,
    secondaryContainer = VeraSecondaryContainer,
    onSecondaryContainer = VeraCyanLight,

    tertiary = VeraTertiary,
    onTertiary = VeraBackground,
    tertiaryContainer = VeraTertiaryContainer,
    onTertiaryContainer = VeraCyanUltraLight,

    background = VeraBackground,
    onBackground = VeraTextPrimary,

    surface = VeraSurface,
    onSurface = VeraTextPrimary,
    surfaceVariant = VeraSurfaceVariant,
    onSurfaceVariant = VeraTextSecondary,
    surfaceContainer = VeraSurfaceContainer,

    outline = VeraOutline,
    outlineVariant = VeraOutline.copy(alpha = 0.5f),

    error = VeraError,
    onError = VeraTextPrimary,
    errorContainer = VeraErrorContainer,
    onErrorContainer = Color(0xFFFF8A80)
)

@Composable
fun VeraTheme(
    // We intentionally disable Android 12 wallpaper dynamicColor so VERA's signature high-tech
    // glowing blue/turquoise brand aesthetics are consistently applied across all devices.
    content: @Composable () -> Unit
) {
    val colorScheme = VeraDarkColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                window.statusBarColor = colorScheme.background.toArgb()
                window.navigationBarColor = colorScheme.background.toArgb()
                val insetsController = WindowCompat.getInsetsController(window, view)
                insetsController.isAppearanceLightStatusBars = false
                insetsController.isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
