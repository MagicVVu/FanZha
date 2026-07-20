package com.magicvvu.fanzha.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat

// Define the Light Color Scheme using the new Brand Colors
private val LightColorScheme = lightColorScheme(
    // Primary: BrandPrimaryBlue (#0462C2) - Main actions, active states, gradients
    primary = BrandPrimaryBlue,
    onPrimary = Color.White,
    primaryContainer = BrandSecondaryBlue, // Light blue background
    onPrimaryContainer = BrandPrimaryBlue,

    // Secondary: Teal (#29BFC0) - Safe states, distinct elements
    secondary = BrandTeal,
    onSecondary = Color.White,
    secondaryContainer = BrandTeal.copy(alpha = 0.1f),
    onSecondaryContainer = BrandTeal,

    // Tertiary: Orange (#F29949) - Accents, highlights
    tertiary = BrandOrange,
    onTertiary = Color.White,
    tertiaryContainer = BrandOrange.copy(alpha = 0.1f),
    onTertiaryContainer = BrandOrange,

    // Background & Surface
    background = LightBackground,    
    onBackground = LightTextPrimary, 
    surface = LightSurface,          
    onSurface = LightTextPrimary,    
    
    // Surface Variant (Input fields, dividers, secondary cards)
    surfaceVariant = Color(0xFFF1F5F9), // Slightly darker slate for variants
    onSurfaceVariant = LightTextSecondary, 

    // Error: Coral (#ED6160)
    error = BrandCoral,
    onError = Color.White,
    errorContainer = BrandCoral.copy(alpha = 0.1f),
    onErrorContainer = BrandCoral,
    
    // Outline
    outline = LightBorder,
    outlineVariant = Color(0xFFCBD5E1)
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF7AB8F0),
    onPrimary = Color(0xFF0A1628),
    primaryContainer = Color(0xFF1E3A5F),
    onPrimaryContainer = Color(0xFFD4E8FC),

    secondary = BrandTeal,
    onSecondary = Color(0xFF0A1C1C),
    secondaryContainer = Color(0xFF164E52),
    onSecondaryContainer = Color(0xFFB8F0F0),

    tertiary = BrandOrange,
    onTertiary = Color(0xFF1A0F06),
    tertiaryContainer = Color(0xFF5C3A12),
    onTertiaryContainer = Color(0xFFFFE2C4),

    background = Color(0xFF0F172A),
    onBackground = Color(0xFFF1F5F9),
    surface = Color(0xFF1E293B),
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = Color(0xFF334155),
    onSurfaceVariant = Color(0xFFCBD5E1),

    error = BrandCoral,
    onError = Color.White,
    errorContainer = Color(0xFF5C1F1F),
    onErrorContainer = Color(0xFFFFDAD6),

    outline = Color(0xFF64748B),
    outlineVariant = Color(0xFF475569),
)

/** 老年人模式：浅色高对比（文字与分割线更清晰） */
private val ElderlyLightHighContrastColorScheme = lightColorScheme(
    primary = BrandPrimaryBlue,
    onPrimary = Color.White,
    primaryContainer = BrandSecondaryBlue,
    onPrimaryContainer = Color(0xFF00254D),
    secondary = BrandTeal,
    onSecondary = Color.White,
    secondaryContainer = BrandTeal.copy(alpha = 0.2f),
    onSecondaryContainer = Color(0xFF002F30),
    tertiary = BrandOrange,
    onTertiary = Color.White,
    tertiaryContainer = BrandOrange.copy(alpha = 0.22f),
    onTertiaryContainer = Color(0xFF3D1F00),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF000000),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF000000),
    surfaceVariant = Color(0xFFE8EAED),
    onSurfaceVariant = Color(0xFF1A1A1A),
    error = Color(0xFFB00020),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD4),
    onErrorContainer = Color(0xFF410001),
    outline = Color(0xFF212121),
    outlineVariant = Color(0xFF616161),
)

/** 老年人模式：深色高对比 */
private val ElderlyDarkHighContrastColorScheme = darkColorScheme(
    primary = Color(0xFF9EC9FF),
    onPrimary = Color(0xFF001C3A),
    primaryContainer = Color(0xFF004A8C),
    onPrimaryContainer = Color(0xFFD8E9FF),
    secondary = BrandTeal,
    onSecondary = Color(0xFF001F20),
    secondaryContainer = Color(0xFF005456),
    onSecondaryContainer = Color(0xFFB6F5F6),
    tertiary = BrandOrange,
    onTertiary = Color(0xFF2B1400),
    tertiaryContainer = Color(0xFF6B3D0A),
    onTertiaryContainer = Color(0xFFFFE2C4),
    background = Color(0xFF000000),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF121212),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF2D2D2D),
    onSurfaceVariant = Color(0xFFE8E8E8),
    error = Color(0xFFFFB4A9),
    onError = Color(0xFF680003),
    errorContainer = Color(0xFF930006),
    onErrorContainer = Color(0xFFFFDAD4),
    outline = Color(0xFFBDBDBD),
    outlineVariant = Color(0xFF9E9E9E),
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = false,
    elderlyModeController: ElderlyModePreferenceController,
    fontSizeController: FontSizePreferenceController,
    content: @Composable () -> Unit,
) {
    val highContrast = elderlyModeController.isHighContrastEnabled
    val colorScheme = when {
        darkTheme && highContrast -> ElderlyDarkHighContrastColorScheme
        darkTheme -> DarkColorScheme
        highContrast -> ElderlyLightHighContrastColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
    }

    val baseDensity = LocalDensity.current
    val elderlyFontFactor = if (elderlyModeController.isLargeTextEnabled) 1.28f else 1f
    val appFontFactor = fontSizeController.scaleFactor
    val scaledDensity = remember(baseDensity, elderlyFontFactor, appFontFactor) {
        Density(baseDensity.density, baseDensity.fontScale * elderlyFontFactor * appFontFactor)
    }

    CompositionLocalProvider(LocalDensity provides scaledDensity) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content,
        )
    }
}
