package com.magicvvu.fanzha.security

import android.content.Context
import android.util.Log
import com.magicvvu.fanzha.data.local.AuthPreferences
import com.magicvvu.fanzha.data.remote.ApiClient
import com.magicvvu.fanzha.data.remote.InterceptIngestBatchRequest
import com.magicvvu.fanzha.data.remote.InterceptIngestRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.provider.CallLog

/**
 * 将本地采集到的通话/短信/剪贴板/应用与后端特征库联动：
 * 仅当服务端判定命中诈骗特征时才写入拦截存储表，正常内容不落库。
 */
class InterceptIngestSecurityUploader(
    private val context: Context,
    private val maxPerCategory: Int = 12,
    /** 最近通话条数上限（按时间倒序），略大于其它类以便覆盖实机新来电 */
    private val maxCallEntriesForIngest: Int = 24,
) : SecurityDataUploader {

    private val prefs by lazy {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    override suspend fun upload(payload: SecurityCollectionPayload) {
        val userId = AuthPreferences.getUserId(context) ?: run {
            Log.d(TAG, "skip ingest: no logged-in userId")
            return
        }
        Log.d(
            TAG,
            "ingest start userId=$userId callLogPerm=${payload.permissions.readCallLogGranted} " +
                "smsPerm=${payload.permissions.readSmsGranted} calls=${payload.callLogList.size}",
        )
        if (payload.callLogList.isEmpty() && payload.permissions.readCallLogGranted) {
            Log.w(TAG, "通话权限已开但本次查询到 0 条记录（刚挂断可稍等再开屏，或确认系统通话记录里是否有该号码）")
        }
        withContext(Dispatchers.IO) {
            runCatching {
                val items = mutableListOf<InterceptIngestRequest>()
                var pendingClipboard: String? = null
                var pendingCallMaxTs: Long? = null
                var pendingSmsMaxTs: Long? = null
                var pendingUploadedApps: Set<String>? = null

                // 剪切板：仅在内容变化时上报；否则会在 ON_RESUME/定时同步下不断重复累计，导致 intercept_clipboard “莫名自增”。
                payload.clipboardTextPreview?.trim()?.takeIf { it.isNotEmpty() }?.let { clip ->
                    if (shouldIngestClipboard(userId, clip)) {
                        val channel = if (looksLikeHttpUrl(clip)) "LINK" else "CLIPBOARD"
                        items += InterceptIngestRequest(userId = userId, channel = channel, raw = clip)
                        pendingClipboard = clip
                    }
                }

                // 去重关键点：只上报“上次已处理时间点”之后的新通话/短信，避免 ON_RESUME 或定时同步重复入库。
                // 优先使用稳定游标：CallLog _ID；兼容旧水位线（timestamp）。
                val lastCallId = prefs.getLong(keyLastIngestedId(userId, "call"), -1L)
                val lastCallTs = prefs.getLong(keyLastIngestedTs(userId, "call"), 0L)
                val newCalls = payload.callLogList
                    .asSequence()
                    .filter { call ->
                        when {
                            lastCallId >= 0L && call.androidId >= 0L -> call.androidId > lastCallId
                            else -> call.timestamp > lastCallTs
                        }
                    }
                    // 只收录“新打入电话”（来电/未接），避免把本人拨出电话也当作拦截来源重复入库。
                    .filter { call ->
                        call.type == CallLog.Calls.INCOMING_TYPE || call.type == CallLog.Calls.MISSED_TYPE
                    }
                    .take(maxCallEntriesForIngest)
                    .toList()
                newCalls.forEach { call ->
                    val num = normalizePhoneForFeatureMatch(call.number?.trim().orEmpty())
                    if (num.isNotEmpty()) {
                        items += InterceptIngestRequest(userId = userId, channel = "PHONE", raw = num)
                    }
                }
                if (newCalls.isNotEmpty()) {
                    pendingCallMaxTs = newCalls.maxOf { it.timestamp }
                }
                val pendingCallMaxId = newCalls.maxOfOrNull { it.androidId }?.takeIf { it >= 0L }

                val lastSmsId = prefs.getLong(keyLastIngestedId(userId, "sms"), -1L)
                val lastSmsTs = prefs.getLong(keyLastIngestedTs(userId, "sms"), 0L)
                val newSms = payload.smsList
                    .asSequence()
                    .filter { sms ->
                        when {
                            lastSmsId >= 0L && sms.androidId >= 0L -> sms.androidId > lastSmsId
                            else -> sms.timestamp > lastSmsTs
                        }
                    }
                    .take(maxPerCategory)
                    .toList()
                newSms.forEach { sms ->
                    val body = sms.bodyPreview?.trim().orEmpty()
                    if (body.isEmpty()) return@forEach
                    val channel = if (looksLikeHttpUrl(body)) "LINK" else "SMS"
                    items += InterceptIngestRequest(
                        userId = userId,
                        channel = channel,
                        raw = body,
                        smsSenderNumber = sms.address?.trim().takeUnless { it.isNullOrEmpty() },
                    )
                }
                if (newSms.isNotEmpty()) {
                    pendingSmsMaxTs = newSms.maxOf { it.timestamp }
                }
                val pendingSmsMaxId = newSms.maxOfOrNull { it.androidId }?.takeIf { it >= 0L }

                // 可疑 APP：对每个包名做“仅上报一次（带周期性重扫）”，避免反复扫描导致 intercept_suspicious_app 增长。
                val alreadyUploaded = getUploadedAppPackages(userId).toMutableSet()
                if (shouldResetUploadedAppPackages(userId)) {
                    alreadyUploaded.clear()
                }
                payload.installedApps
                    .asSequence()
                    .filter { !it.isSystemApp }
                    .map { it.packageName.trim() to it.appName }
                    .filter { (pkg, _) -> pkg.isNotEmpty() && !alreadyUploaded.contains(pkg) }
                    .take(maxPerCategory)
                    .forEach { (pkg, name) ->
                        items += InterceptIngestRequest(
                            userId = userId,
                            channel = "APP",
                            raw = pkg,
                            appDisplayName = name.take(128),
                        )
                        alreadyUploaded.add(pkg)
                    }

                // 批量上报：一次 HTTP 往返
                if (items.isEmpty()) return@runCatching
                val resp = ApiClient.interceptIngestApi.ingestBatch(InterceptIngestBatchRequest(items = items))
                if (!resp.isSuccessful) {
                    Log.d(TAG, "ingestBatch HTTP ${resp.code()} size=${items.size}")
                    return@runCatching
                }
                // 成功后再更新水位线/去重状态，避免网络失败导致“跳过未上传的事件”
                pendingClipboard?.let { markClipboardIngested(userId, it) }
                pendingCallMaxTs?.let { prefs.edit().putLong(keyLastIngestedTs(userId, "call"), it).apply() }
                pendingCallMaxId?.let { prefs.edit().putLong(keyLastIngestedId(userId, "call"), it).apply() }
                pendingSmsMaxTs?.let { prefs.edit().putLong(keyLastIngestedTs(userId, "sms"), it).apply() }
                pendingSmsMaxId?.let { prefs.edit().putLong(keyLastIngestedId(userId, "sms"), it).apply() }
                pendingUploadedApps = alreadyUploaded
                pendingUploadedApps?.let { saveUploadedAppPackages(userId, it) }
            }.onFailure { e ->
                Log.w(TAG, "ingest batch failed: ${e.message}")
            }
        }
    }

    private fun looksLikeHttpUrl(s: String): Boolean {
        val t = s.trim()
        return t.startsWith("http://", ignoreCase = true) ||
            t.startsWith("https://", ignoreCase = true)
    }

    /** 与后端 fraud_phone 比对时统一为数字串，避免横线、空格、+86 等导致漏命中。 */
    private fun normalizePhoneForFeatureMatch(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        if (digits.length >= 7) return digits.take(20)
        return raw.take(20)
    }

    companion object {
        private const val TAG = "InterceptIngest"
        private const val PREFS = "security_collection"
        private const val APPS_UPLOADED_SET_TTL_MS = 7L * 24L * 60L * 60L * 1000L // 7 days

        private fun keyLastIngestedTs(userId: Long, kind: String): String =
            "last_ingest_${kind}_ts_u$userId"

        private fun keyLastIngestedId(userId: Long, kind: String): String =
            "last_ingest_${kind}_id_u$userId"

        private fun keyLastClipboardContent(userId: Long): String =
            "last_ingest_clipboard_content_u$userId"

        private fun keyUploadedAppPackages(userId: Long): String =
            "uploaded_app_pkgs_u$userId"

        private fun keyUploadedAppPackagesTs(userId: Long): String =
            "uploaded_app_pkgs_saved_at_u$userId"
    }

    private fun shouldIngestClipboard(userId: Long, content: String): Boolean {
        val last = prefs.getString(keyLastClipboardContent(userId), null)
        // 内容未变则跳过
        if (last != null && last == content) return false
        return true
    }

    private fun markClipboardIngested(userId: Long, content: String) {
        prefs.edit().putString(keyLastClipboardContent(userId), content).apply()
    }

    private fun shouldResetUploadedAppPackages(userId: Long): Boolean {
        val lastSavedAt = prefs.getLong(keyUploadedAppPackagesTs(userId), 0L)
        if (lastSavedAt <= 0L) return false
        return System.currentTimeMillis() - lastSavedAt >= APPS_UPLOADED_SET_TTL_MS
    }

    private fun getUploadedAppPackages(userId: Long): Set<String> {
        val set = prefs.getStringSet(keyUploadedAppPackages(userId), null) ?: return emptySet()
        // SharedPreferences 返回的是可变引用，复制一份避免并发修改异常
        return set.toSet()
    }

    private fun saveUploadedAppPackages(userId: Long, pkgs: Set<String>) {
        prefs.edit()
            .putStringSet(keyUploadedAppPackages(userId), pkgs)
            .putLong(keyUploadedAppPackagesTs(userId), System.currentTimeMillis())
            .apply()
    }
}
