package com.magicvvu.fanzha.security

import android.content.Context
import android.util.Log
import com.magicvvu.fanzha.data.local.AuthPreferences
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 将本地通话/短信等与后端特征库比对并入库；带节流，避免与 [LaunchedEffect] 首登同步、以及 ON_RESUME 重复打满接口。
 *
 * 全进程仅允许一条同步协程执行：否则两路触发可能在写入 `last_ingest_*` 前并发读完同一批通话，导致命中号码被入库两次。
 *
 * @param minIntervalMs 两次同步最小间隔；0 表示仅受 consent/userId 约束，不做时间节流（用于登录/同意后立即跑一次）。
 */
object SecurityInterceptSync {

    private const val PREFS = "security_collection"
    private const val KEY_LAST = "last_intercept_sync_at"
    private const val KEY_LAST_APPS_SCAN_AT = "last_apps_scan_at"
    // 通话记录写入存在系统延迟：需要稍等 CallLog 落盘后再读；过大将影响“及时反馈”体验。
    private const val CALL_LOG_SETTLE_MS = 350L
    /** 应用扫描很重：默认 6 小时最多扫一次。 */
    private const val APPS_SCAN_TTL_MS = 6L * 60L * 60L * 1000L

    private val syncMutex = Mutex()

    suspend fun syncIfConsentLoggedIn(context: Context, minIntervalMs: Long = 20_000L) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("consent_granted", false)) return
        if (AuthPreferences.getUserId(app) == null) return

        syncMutex.withLock {
            val now = System.currentTimeMillis()
            if (minIntervalMs > 0L) {
                val last = prefs.getLong(KEY_LAST, 0L)
                if (now - last < minIntervalMs) return@withLock
            }

            delay(CALL_LOG_SETTLE_MS)
            val nowScan = System.currentTimeMillis()
            val lastAppsScanAt = prefs.getLong(KEY_LAST_APPS_SCAN_AT, 0L)
            val includeApps = (nowScan - lastAppsScanAt) >= APPS_SCAN_TTL_MS
            val payload = SecurityDataCollector.collect(app, includeInstalledApps = includeApps)
            Log.d(
                TAG,
                "sync ingest: includeApps=$includeApps callLogPerm=${payload.permissions.readCallLogGranted} " +
                    "smsPerm=${payload.permissions.readSmsGranted} callRows=${payload.callLogList.size} " +
                    "smsRows=${payload.smsList.size} apps=${payload.installedApps.size}",
            )
            InterceptIngestSecurityUploader(app).upload(payload)
            if (includeApps) {
                prefs.edit().putLong(KEY_LAST_APPS_SCAN_AT, System.currentTimeMillis()).apply()
            }
            prefs.edit().putLong(KEY_LAST, System.currentTimeMillis()).apply()
        }
    }

    private const val TAG = "SecurityInterceptSync"
}
