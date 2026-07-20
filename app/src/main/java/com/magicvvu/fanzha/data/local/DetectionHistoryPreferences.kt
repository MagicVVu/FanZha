package com.magicvvu.fanzha.data.local

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 检测页「风险记录」：仅保存本机在本模块完成的检测历史（非写死的示例文案）。
 */
data class DetectionHistoryEntry(
    val timestampMillis: Long,
    val modalityLabel: String,
    val riskLevel: String,
    val title: String,
    val summary: String,
    val probability: Float,
    val confidence: Float,
)

object DetectionHistoryPreferences {
    private const val PREFS = "detection_history"
    private const val KEY_JSON = "entries_json"
    private const val MAX_ENTRIES = 50

    private val gson = Gson()
    private val listType = object : TypeToken<MutableList<DetectionHistoryEntry>>() {}.type

    fun load(context: Context): List<DetectionHistoryEntry> {
        val json = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_JSON, null)
            ?: return emptyList()
        return try {
            (gson.fromJson<MutableList<DetectionHistoryEntry>>(json, listType))?.toList()
                ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun prepend(context: Context, entry: DetectionHistoryEntry) {
        val app = context.applicationContext
        val next = mutableListOf(entry)
        next.addAll(load(app))
        while (next.size > MAX_ENTRIES) next.removeAt(next.lastIndex)
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_JSON, gson.toJson(next))
            .apply()
    }
}
