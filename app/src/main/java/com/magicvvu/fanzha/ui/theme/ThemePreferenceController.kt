package com.magicvvu.fanzha.ui.theme

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

@Stable
class ThemePreferenceController(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private var darkThemeState by mutableStateOf(prefs.getBoolean(KEY_DARK, false))

    val isDarkTheme: Boolean get() = darkThemeState

    fun setDarkTheme(enabled: Boolean) {
        if (enabled == darkThemeState) return
        darkThemeState = enabled
        prefs.edit().putBoolean(KEY_DARK, enabled).apply()
    }

    companion object {
        private const val PREFS_NAME = "app_theme_prefs"
        private const val KEY_DARK = "dark_theme"
    }
}

val LocalThemePreferenceController = staticCompositionLocalOf<ThemePreferenceController> {
    error("LocalThemePreferenceController not provided — wrap root content in CompositionLocalProvider")
}
