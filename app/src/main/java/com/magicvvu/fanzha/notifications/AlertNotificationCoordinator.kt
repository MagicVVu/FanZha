package com.magicvvu.fanzha.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.magicvvu.fanzha.MainActivity
import com.magicvvu.fanzha.R
import com.magicvvu.fanzha.data.remote.AlertCommandPayload
import com.magicvvu.fanzha.data.remote.ApiClient
import com.magicvvu.fanzha.data.remote.ApiResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object AlertNotificationCoordinator {

    const val MESSAGE_FRAUD_SMS =
        "检测到您的被监护人（王芳）收到了诈骗短信，请您留意该家庭成员的行为"
    const val MESSAGE_INFO_LEAK =
        "您的关键信息可能泄露，强烈建议您打开反诈通app，并进行相关诈骗识别"
    const val MESSAGE_MILD_FRAUD_RISK_LEARNING =
        "您当前有轻微的被诈骗风险，建议您打开反诈通app进行相关反诈知识学习~"

    fun messageWardFraud(wardDisplayName: String): String =
        "您的被监护人（$wardDisplayName）疑似收到诈骗，请关注！"

    private const val CHANNEL_FRAUD_SMS = "alert_fraud_sms"
    private const val CHANNEL_INFO_LEAK = "alert_info_leak"
    private const val CHANNEL_WARD_FRAUD = "alert_ward_fraud"
    private const val CHANNEL_MILD_FRAUD_RISK = "home_mild_fraud_risk"

    private const val NOTIFY_ID_FRAUD_SMS = 9_001
    private const val NOTIFY_ID_INFO_LEAK = 9_002
    private const val NOTIFY_ID_WARD_FRAUD = 9_003
    private const val NOTIFY_ID_MILD_FRAUD_RISK = 9_004

    /** 首页安全系数 60–80 时由界面定时触发：系统通知（横幅/状态栏），与 [postHeadsUp] 一致为高重要性。 */
    fun postMildFraudRiskLearningReminder(context: Context) {
        postHeadsUp(
            context = context.applicationContext,
            channelId = CHANNEL_MILD_FRAUD_RISK,
            channelTitle = "轻微诈骗风险提示",
            notificationId = NOTIFY_ID_MILD_FRAUD_RISK,
            title = "安全提示",
            text = MESSAGE_MILD_FRAUD_RISK_LEARNING,
        )
    }

    suspend fun pollAndNotify(context: Context) {
        val app = context.applicationContext
        val api = ApiClient.alertCommandApi

        runCatching { api.fetchFraudSmsCommand() }.getOrNull()?.let { resp ->
            if (shouldFire(resp.body())) {
                postHeadsUp(
                    context = app,
                    channelId = CHANNEL_FRAUD_SMS,
                    channelTitle = "诈骗短信提醒",
                    notificationId = NOTIFY_ID_FRAUD_SMS,
                    title = "诈骗短信提醒",
                    text = MESSAGE_FRAUD_SMS,
                )
            }
        }

        runCatching { api.fetchInfoLeakCommand() }.getOrNull()?.let { resp ->
            if (shouldFire(resp.body())) {
                postHeadsUp(
                    context = app,
                    channelId = CHANNEL_INFO_LEAK,
                    channelTitle = "信息泄露提醒",
                    notificationId = NOTIFY_ID_INFO_LEAK,
                    title = "信息泄露提醒",
                    text = MESSAGE_INFO_LEAK,
                )
            }
        }

        runCatching { api.fetchWardFraudCommand() }.getOrNull()?.let { resp ->
            val envelope = resp.body()
            if (shouldFire(envelope)) {
                val name = envelope?.data?.wardName?.trim().orEmpty().ifBlank { "被监护人" }
                postHeadsUp(
                    context = app,
                    channelId = CHANNEL_WARD_FRAUD,
                    channelTitle = "家庭守护提醒",
                    notificationId = NOTIFY_ID_WARD_FRAUD,
                    title = "家庭守护提醒",
                    text = messageWardFraud(name),
                )
            }
        }
    }

    /**
     * 给短信识别链路直接调用：
     * Receiver 在 /check-sms 返回 isFraud=true 后，立刻发一条 heads-up 通知
     */
    fun notifyFraudSmsNow(
        context: Context,
        text: String = MESSAGE_FRAUD_SMS,
    ) {
        Log.d("SMS_FLOW", "notifyFraudSmsNow called")
        postHeadsUp(
            context = context.applicationContext,
            channelId = CHANNEL_FRAUD_SMS,
            channelTitle = "诈骗短信提醒",
            notificationId = NOTIFY_ID_FRAUD_SMS,
            title = "诈骗短信提醒",
            text = text,
        )
    }

    private val notifyScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun notifyFraudSmsDelayed(
        context: Context,
        delayMs: Long = 5_000L,
        text: String = MESSAGE_FRAUD_SMS,
    ) {
        Log.d("SMS_FLOW", "notifyFraudSmsDelayed scheduled, delayMs=$delayMs")
        val appContext = context.applicationContext
        notifyScope.launch {
            delay(delayMs)
            Log.d("SMS_FLOW", "notifyFraudSmsDelayed firing")
            postHeadsUp(
                context = appContext,
                channelId = CHANNEL_FRAUD_SMS,
                channelTitle = "诈骗短信提醒",
                notificationId = NOTIFY_ID_FRAUD_SMS,
                title = "诈骗短信提醒",
                text = text,
            )
        }
    }

    private fun shouldFire(body: ApiResponse<AlertCommandPayload>?): Boolean {
        if (body == null) return false
        if (!body.success) return false
        return body.data?.shouldNotify == true
    }

    private fun postHeadsUp(
        context: Context,
        channelId: String,
        channelTitle: String,
        notificationId: Int,
        title: String,
        text: String,
    ) {
        ensureChannel(context, channelId, channelTitle)

        val hasNotificationPermission =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

        Log.d("SMS_FLOW", "postHeadsUp permission=$hasNotificationPermission channelId=$channelId")

        if (!hasNotificationPermission) return

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val tapPi = PendingIntent.getActivity(
            context,
            notificationId,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification_small)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(tapPi)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
            .setDefaults(NotificationCompat.DEFAULT_VIBRATE or NotificationCompat.DEFAULT_LIGHTS)

        NotificationManagerCompat.from(context).notify(notificationId, builder.build())
        Log.d("SMS_FLOW", "postHeadsUp notify() done, id=$notificationId")
    }

    private fun ensureChannel(context: Context, channelId: String, title: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(channelId) != null) return
        val channel = NotificationChannel(
            channelId,
            title,
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "反诈通安全告警"
            enableVibration(true)
        }
        nm.createNotificationChannel(channel)
    }
}
