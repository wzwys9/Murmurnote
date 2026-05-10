package app.murmurnote.android.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightScheme = lightColorScheme(
    primary = AccentGreen,
    onPrimary = BgWarm,
    primaryContainer = AccentGreen.copy(alpha = 0.12f),
    onPrimaryContainer = AccentInkLight,
    secondary = WarnLight,
    onSecondary = BgWarm,
    background = BgWarm,
    onBackground = InkLight,
    surface = SurfaceLight,
    onSurface = InkLight,
    surfaceVariant = SurfaceAltLight,
    onSurfaceVariant = InkSecondaryLight,
    error = DangerLight,
    onError = BgWarm,
    outline = InkTertiaryLight,
    outlineVariant = SurfaceSunkenLight
)

private val DarkScheme = darkColorScheme(
    primary = AccentGreenDark,
    onPrimary = BgDark,
    primaryContainer = AccentGreenDark.copy(alpha = 0.16f),
    onPrimaryContainer = AccentInkDark,
    secondary = WarnDark,
    onSecondary = BgDark,
    background = BgDark,
    onBackground = InkDark,
    surface = SurfaceDark,
    onSurface = InkDark,
    surfaceVariant = SurfaceAltDark,
    onSurfaceVariant = InkSecondaryDark,
    error = DangerDark,
    onError = BgDark,
    outline = InkTertiaryDark,
    outlineVariant = SurfaceSunkenDark
)

@Composable
fun MurmurnoteTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkScheme
        else -> LightScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            @Suppress("DEPRECATION")
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            @Suppress("DEPRECATION")
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = MurmurnoteTypography,
        shapes = MurmurnoteShapes,
        content = content
    )
}
