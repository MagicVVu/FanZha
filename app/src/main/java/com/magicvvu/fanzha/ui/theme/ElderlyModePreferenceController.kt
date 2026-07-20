package com.magicvvu.fanzha.ui.theme

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

@Stable
class ElderlyModePreferenceController(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private var elderlyModeOn by mutableStateOf(prefs.getBoolean(KEY_ELDERLY_MODE, false))
    private var largeTextOn by mutableStateOf(prefs.getBoolean(KEY_LARGE_TEXT, false))
    private var highContrastOn by mutableStateOf(prefs.getBoolean(KEY_HIGH_CONTRAST, false))

    val isElderlyModeEnabled: Boolean get() = elderlyModeOn
    val isLargeTextEnabled: Boolean get() = largeTextOn
    val isHighContrastEnabled: Boolean get() = highContrastOn

    fun setElderlyModeEnabled(enabled: Boolean) {
        if (enabled == elderlyModeOn) return
        elderlyModeOn = enabled
        // 老年人模式：一并开启大字号与高对比度；关闭时全部恢复默认
        if (enabled) {
            largeTextOn = true
            highContrastOn = true
        } else {
            largeTextOn = false
            highContrastOn = false
        }
        prefs.edit()
            .putBoolean(KEY_ELDERLY_MODE, enabled)
            .putBoolean(KEY_LARGE_TEXT, largeTextOn)
            .putBoolean(KEY_HIGH_CONTRAST, highContrastOn)
            .apply()
    }

    fun setLargeTextEnabled(enabled: Boolean) {
        if (enabled == largeTextOn) return
        largeTextOn = enabled
        prefs.edit().putBoolean(KEY_LARGE_TEXT, enabled).apply()
    }

    fun setHighContrastEnabled(enabled: Boolean) {
        if (enabled == highContrastOn) return
        highContrastOn = enabled
        prefs.edit().putBoolean(KEY_HIGH_CONTRAST, enabled).apply()
    }

    companion object {
        private const val PREFS_NAME = "app_elderly_mode_prefs"
        private const val KEY_ELDERLY_MODE = "elderly_mode"
        private const val KEY_LARGE_TEXT = "large_text"
        private const val KEY_HIGH_CONTRAST = "high_contrast"
    }
}

val LocalElderlyModePreferenceController = staticCompositionLocalOf<ElderlyModePreferenceController> {
    error("LocalElderlyModePreferenceController not provided — wrap root content in CompositionLocalProvider")
}
