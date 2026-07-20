package com.magicvvu.fanzha.data.local

import android.content.Context
import com.magicvvu.fanzha.ui.viewmodels.UserProfile
import com.google.gson.Gson

/**
 * 本地持久化当前账户的展示用个人信息（头像、昵称等），与 [com.magicvvu.fanzha.ui.viewmodels.ProfileViewModel] 同步。
 */
object UserProfilePreferences {
    private const val PREFS = "user_profile_display"
    private const val KEY_JSON = "profile_json"

    private val gson = Gson()

    fun load(context: Context): UserProfile? {
        val json = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_JSON, null)
            ?: return null
        return try {
            gson.fromJson(json, UserProfile::class.java)
        } catch (_: Exception) {
            null
        }
    }

    fun save(context: Context, profile: UserProfile) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_JSON, gson.toJson(profile))
            .apply()
    }
}
