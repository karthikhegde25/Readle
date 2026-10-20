package com.readle.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.readle.app.domain.AppTheme

/** Accessibility switches that components read to adapt (touch target size, motion). */
data class A11y(val largeTouchTargets: Boolean = false, val reducedMotion: Boolean = false)

val LocalA11y = compositionLocalOf { A11y() }
val LocalTokens = staticCompositionLocalOf { DesignTokens.of(dark = false, highContrast = false) }

private fun DesignTokens.toColorScheme(dark: Boolean): ColorScheme {
    val bg = Color(background)
    val surf = Color(surface)
    val variant = Color(surfaceVariant)
    val onSurf = Color(textPrimary)
    val onVariant = Color(textSecondary)
    val acc = Color(accent)
    return if (dark) darkColorScheme(
        primary = acc, onPrimary = Color(onAccent),
        primaryContainer = Color(accentContainer), onPrimaryContainer = Color(onAccentContainer),
        secondary = acc, onSecondary = Color(onAccent),
        secondaryContainer = Color(accentContainer), onSecondaryContainer = Color(onAccentContainer),
        background = bg, onBackground = onSurf,
        surface = surf, onSurface = onSurf,
        surfaceVariant = variant, onSurfaceVariant = onVariant,
        surfaceContainer = surf, surfaceContainerHigh = variant, surfaceContainerHighest = variant,
        surfaceContainerLow = bg, surfaceContainerLowest = bg,
        outline = Color(divider), outlineVariant = Color(divider),
        error = Color(error), onError = Color(0xFF601410)
    ) else lightColorScheme(
        primary = acc, onPrimary = Color(onAccent),
        primaryContainer = Color(accentContainer), onPrimaryContainer = Color(onAccentContainer),
        secondary = acc, onSecondary = Color(onAccent),
        secondaryContainer = Color(accentContainer), onSecondaryContainer = Color(onAccentContainer),
        background = bg, onBackground = onSurf,
        surface = surf, onSurface = onSurf,
        surfaceVariant = variant, onSurfaceVariant = onVariant,
        surfaceContainer = surf, surfaceContainerHigh = variant, surfaceContainerHighest = variant,
        surfaceContainerLow = bg, surfaceContainerLowest = bg,
        outline = Color(divider), outlineVariant = Color(divider),
        error = Color(error), onError = Color.White
    )
}

private val ReadleTypography = Typography(
    headlineMedium = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp)
)

private val ReadleShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)
)

@Composable
fun ReadleTheme(
    appTheme: AppTheme,
    highContrast: Boolean,
    largeTouchTargets: Boolean,
    reducedMotion: Boolean,
    content: @Composable () -> Unit
) {
    val dark = when (appTheme) {
        AppTheme.SYSTEM -> isSystemInDarkTheme()
        AppTheme.LIGHT -> false
        AppTheme.DARK -> true
    }
    val tokens = DesignTokens.of(dark, highContrast)
    val scheme = tokens.toColorScheme(dark)

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    CompositionLocalProvider(
        LocalTokens provides tokens,
        LocalA11y provides A11y(largeTouchTargets, reducedMotion)
    ) {
        MaterialTheme(colorScheme = scheme, typography = ReadleTypography, shapes = ReadleShapes, content = content)
    }
}
