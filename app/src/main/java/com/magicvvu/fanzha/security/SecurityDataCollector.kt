package com.magicvvu.fanzha.security

import android.Manifest
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.CallLog
import android.provider.Telephony
import androidx.core.content.ContextCompat
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

object SecurityDataCollector {

    /**
     * 采集本机风险相关数据。
     *
     * @param includeInstalledApps 是否采集应用列表。应用扫描是最重的 IO（厂商 ROM 上可能数百 ms～数秒），
     * 由调用方按 TTL 控制频率，避免每次同步都扫全量导致“很慢”。
     */
    suspend fun collect(
        context: Context,
        includeInstalledApps: Boolean = true,
    ): SecurityCollectionPayload = withContext(Dispatchers.IO) {
        val readCallLog = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) ==
            PackageManager.PERMISSION_GRANTED
        val readSms = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED
        coroutineScope {
            val deviceIdDef = async { getOrCreateLocalDeviceId(context) }
            val clipboardDef = async { readClipboardPreview(context) }
            val smsDef = async { readSms(context) }
            val callDef = async { readCallLogs(context) }
            val appsDef = async { if (includeInstalledApps) readInstalledApps(context) else emptyList() }

            SecurityCollectionPayload(
                deviceId = deviceIdDef.await(),
                collectedAt = System.currentTimeMillis(),
                permissions = SecurityPermissionSnapshot(
                    readCallLogGranted = readCallLog,
                    readSmsGranted = readSms,
                ),
                clipboardTextPreview = clipboardDef.await(),
                smsList = smsDef.await(),
                callLogList = callDef.await(),
                installedApps = appsDef.await(),
            )
        }
    }

    private fun readClipboardPreview(context: Context): String? {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        if (!manager.hasPrimaryClip()) return null
        val clip = manager.primaryClip ?: return null
        val desc = manager.primaryClipDescription
        if (desc != null && !desc.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) &&
            !desc.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)
        ) {
            return null
        }
        val text = clip.getItemAt(0)?.coerceToText(context)?.toString()?.trim()
        if (text.isNullOrEmpty()) return null
        // 服务端需要“本次完整剪切板内容”用于入库（上限与后端一致：8000）。
        return text.take(8000)
    }

    private fun readSms(context: Context): List<SmsInfo> {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) return emptyList()

        val result = mutableListOf<SmsInfo>()
        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
        )
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            projection,
            null,
            null,
            "${Telephony.Sms.DATE} DESC",
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndex(Telephony.Sms._ID)
            val addressIdx = cursor.getColumnIndex(Telephony.Sms.ADDRESS)
            val bodyIdx = cursor.getColumnIndex(Telephony.Sms.BODY)
            val dateIdx = cursor.getColumnIndex(Telephony.Sms.DATE)

            while (cursor.moveToNext() && result.size < 50) {
                val body = if (bodyIdx >= 0) cursor.getString(bodyIdx) else null
                result += SmsInfo(
                    androidId = if (idIdx >= 0) cursor.getLong(idIdx) else -1L,
                    address = if (addressIdx >= 0) cursor.getString(addressIdx) else null,
                    bodyPreview = body?.take(200),
                    timestamp = if (dateIdx >= 0) cursor.getLong(dateIdx) else 0L,
                )
            }
        }
        return result
    }

    private fun readCallLogs(context: Context): List<CallInfo> {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) return emptyList()

        val result = mutableListOf<CallInfo>()
        val projection = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.TYPE,
            CallLog.Calls.DURATION,
            CallLog.Calls.DATE,
        )
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            projection,
            null,
            null,
            "${CallLog.Calls.DATE} DESC",
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndex(CallLog.Calls._ID)
            val numberIdx = cursor.getColumnIndex(CallLog.Calls.NUMBER)
            val typeIdx = cursor.getColumnIndex(CallLog.Calls.TYPE)
            val durationIdx = cursor.getColumnIndex(CallLog.Calls.DURATION)
            val dateIdx = cursor.getColumnIndex(CallLog.Calls.DATE)

            // 按时间倒序，优先覆盖最近通话（与特征库 fraud_phone 比对后命中才写入 intercept_call）
            while (cursor.moveToNext() && result.size < 80) {
                result += CallInfo(
                    androidId = if (idIdx >= 0) cursor.getLong(idIdx) else -1L,
                    number = if (numberIdx >= 0) cursor.getString(numberIdx) else null,
                    type = if (typeIdx >= 0) cursor.getInt(typeIdx) else 0,
                    durationSeconds = if (durationIdx >= 0) cursor.getLong(durationIdx) else 0L,
                    timestamp = if (dateIdx >= 0) cursor.getLong(dateIdx) else 0L,
                )
            }
        }
        return result
    }

    private fun readInstalledApps(context: Context): List<InstalledAppInfo> {
        val pm = context.packageManager

        // 优先读取系统可见的全部安装应用；若受包可见性限制，则回退为可启动应用列表。
        val allApps = runCatching {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        }.getOrElse {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(0)
        }

        val mappedAll = allApps.map { info ->
            toInstalledAppInfo(pm, info)
        }
        if (mappedAll.isNotEmpty()) return mappedAll.take(200)

        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val launchables = runCatching {
            pm.queryIntentActivities(launcherIntent, PackageManager.ResolveInfoFlags.of(0))
        }.getOrElse {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(launcherIntent, 0)
        }

        return launchables
            .mapNotNull { resolveInfo -> resolveInfo.activityInfo?.applicationInfo }
            .distinctBy { app -> app.packageName }
            .map { info -> toInstalledAppInfo(pm, info) }
            .take(200)
    }

    private fun toInstalledAppInfo(
        pm: PackageManager,
        info: ApplicationInfo,
    ): InstalledAppInfo {
        val isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        return InstalledAppInfo(
            packageName = info.packageName,
            appName = pm.getApplicationLabel(info).toString(),
            isSystemApp = isSystem,
        )
    }

    private fun getOrCreateLocalDeviceId(context: Context): String {
        val pref = context.getSharedPreferences("security_collection", Context.MODE_PRIVATE)
        val cached = pref.getString("device_id", null)
        if (!cached.isNullOrBlank()) return cached
        val created = UUID.randomUUID().toString()
        pref.edit().putString("device_id", created).apply()
        return created
    }
}
