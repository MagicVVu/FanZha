package com.magicvvu.fanzha.ui.theme

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

enum class AppFontSizeOption(val labelZh: String, val scaleFactor: Float) {
    Small("小", 0.88f),
    Standard("标准", 1f),
    Large("大", 1.12f),
    ExtraLarge("特大", 1.24f);

    companion object {
        fun fromOrdinal(ordinal: Int): AppFontSizeOption =
            entries.getOrElse(ordinal.coerceIn(0, entries.lastIndex)) { Standard }
    }
}

@Stable
class FontSizePreferenceController(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private var optionState by mutableStateOf(
        AppFontSizeOption.fromOrdinal(prefs.getInt(KEY_FONT_SIZE_ORDINAL, AppFontSizeOption.Standard.ordinal))
    )

    val option: AppFontSizeOption get() = optionState

    /** 与系统 fontScale、老年人模式系数相乘，作用于全应用 sp 文字。 */
    val scaleFactor: Float get() = optionState.scaleFactor

    fun setOption(option: AppFontSizeOption) {
        if (option == optionState) return
        optionState = option
        prefs.edit().putInt(KEY_FONT_SIZE_ORDINAL, option.ordinal).apply()
    }

    companion object {
        private const val PREFS_NAME = "app_font_size_prefs"
        private const val KEY_FONT_SIZE_ORDINAL = "font_size_ordinal"
    }
}

val LocalFontSizePreferenceController = staticCompositionLocalOf<FontSizePreferenceController> {
    error("LocalFontSizePreferenceController not provided — wrap root content in CompositionLocalProvider")
}
