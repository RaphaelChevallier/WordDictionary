package com.maxfluency.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import com.maxfluency.app.data.Accent
import com.maxfluency.app.data.AppSettings
import com.maxfluency.app.data.ThemeMode

@Composable
fun MaxFluencyTheme(settings: AppSettings = AppSettings(), content: @Composable () -> Unit) {
    val dark = when (settings.theme) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val background = if (dark) Color(0xFF151515) else Color(0xFFFAFAF7)
    val foreground = if (dark) Color(0xFFF2F2ED) else Color(0xFF1B1B1B)
    val accent = when (settings.accent) {
        Accent.MONOCHROME -> foreground
        Accent.BLUE -> if (dark) Color(0xFFA8C3FF) else Color(0xFF2858A9)
        Accent.GREEN -> if (dark) Color(0xFFA0D5B6) else Color(0xFF286344)
        Accent.ROSE -> if (dark) Color(0xFFF0B4C7) else Color(0xFF993855)
    }
    val neutral = lerp(background, foreground, 0.075f)
    val scheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = accent,
        onPrimary = background,
        primaryContainer = lerp(background, accent, 0.13f),
        onPrimaryContainer = foreground,
        inversePrimary = background,
        secondary = accent,
        onSecondary = background,
        secondaryContainer = neutral,
        onSecondaryContainer = foreground,
        tertiary = accent,
        onTertiary = background,
        tertiaryContainer = neutral,
        onTertiaryContainer = foreground,
        background = background,
        onBackground = foreground,
        surface = background,
        onSurface = foreground,
        surfaceVariant = neutral,
        onSurfaceVariant = lerp(background, foreground, 0.68f),
        surfaceTint = Color.Transparent,
        outline = lerp(background, foreground, 0.45f),
        outlineVariant = lerp(background, foreground, 0.18f),
        inverseSurface = foreground,
        inverseOnSurface = background,
        surfaceDim = neutral,
        surfaceBright = background,
        surfaceContainerLowest = background,
        surfaceContainerLow = neutral,
        surfaceContainer = neutral,
        surfaceContainerHigh = neutral,
        surfaceContainerHighest = lerp(background, foreground, 0.12f),
    )
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(density.density, density.fontScale * settings.readingSize.scale),
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = Typography(
                headlineLarge = TextStyle(fontFamily = FontFamily.Serif, fontSize = 36.sp, lineHeight = 42.sp),
                headlineMedium = TextStyle(fontFamily = FontFamily.Serif, fontSize = 28.sp, lineHeight = 34.sp),
                bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 18.sp, lineHeight = 28.sp),
                bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 15.sp, lineHeight = 23.sp),
            ),
            content = content,
        )
    }
}
