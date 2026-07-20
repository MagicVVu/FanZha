package com.magicvvu.fanzha.data.local

import android.content.Context

/**
 * 登录会话：保存后端用户主键，用于按账户查询 `intercept_call.user_id` 等。
 */
object AuthPreferences {
    private const val PREFS = "auth_session"
    private const val KEY_USER_ID = "user_id"

    fun saveUserId(context: Context, userId: Long) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_USER_ID, userId)
            .apply()
    }

    fun getUserId(context: Context): Long? {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.contains(KEY_USER_ID)) return null
        return p.getLong(KEY_USER_ID, -1L).takeIf { it > 0 }
    }

    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
