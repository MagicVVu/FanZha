package com.magicvvu.fanzha.security

/** 实机敏感权限是否已授予（用于展示与排查采集是否为空）。 */
data class SecurityPermissionSnapshot(
    val readCallLogGranted: Boolean,
    val readSmsGranted: Boolean,
)

data class SmsInfo(
    /** {@link android.provider.Telephony.Sms#_ID}，用于稳定水位线；部分机型仅靠 {@code DATE} 会漂移导致同条短信被反复上报。 */
    val androidId: Long = -1L,
    val address: String?,
    /** 与后端 `MAX_TEXT` 对齐的截断正文（当前最多 8000），供特征比对入库。 */
    val bodyPreview: String?,
    val timestamp: Long,
)

data class CallInfo(
    /** {@link android.provider.CallLog.Calls#_ID} */
    val androidId: Long = -1L,
    val number: String?,
    val type: Int,
    val durationSeconds: Long,
    val timestamp: Long,
)

data class InstalledAppInfo(
    val packageName: String,
    val appName: String,
    val isSystemApp: Boolean,
)

data class SecurityCollectionPayload(
    val deviceId: String,
    val collectedAt: Long,
    val permissions: SecurityPermissionSnapshot,
    val clipboardTextPreview: String?,
    val smsList: List<SmsInfo>,
    val callLogList: List<CallInfo>,
    val installedApps: List<InstalledAppInfo>,
)
