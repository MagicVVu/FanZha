package com.magicvvu.fanzha.sms

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.magicvvu.fanzha.MainActivity
import com.magicvvu.fanzha.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

data class SmsFraudCheckEvent(
    val sender: String,
    val message: String,
    val isFraud: Boolean,
    val receivedAtMillis: Long = System.currentTimeMillis(),
)

object SmsFraudResultBridge {

    private const val CHANNEL_ID = "sms_fraud_alert"
    private const val NOTIFICATION_ID = 9005

    private val _events = MutableSharedFlow<SmsFraudCheckEvent>(
        replay = 1,
        extraBufferCapacity = 8,
    )

    val events: SharedFlow<SmsFraudCheckEvent> = _events.asSharedFlow()

    fun publish(event: SmsFraudCheckEvent) {
        _events.tryEmit(event)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun startListening(context: Context) {
        scope.launch {
            events.collect { event ->
                // 仅作为事件桥接供前台 UI 使用；系统级通知统一由短信 Receiver 侧触发，避免重复弹窗/通知。
            }
        }
    }

    private fun showFraudNotification(context: Context, event: SmsFraudCheckEvent) {
        val appContext = context.applicationContext
        ensureChannel(appContext)

        if (ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        val tapIntent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val tapPi = PendingIntent.getActivity(
            appContext,
            NOTIFICATION_ID,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_small)
            .setContentTitle("诈骗短信提醒")
            .setContentText("您当前疑似收到诈骗短信")
            .setStyle(NotificationCompat.BigTextStyle().bigText("您当前疑似收到诈骗短信"))
            .setContentIntent(tapPi)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
            .setDefaults(NotificationCompat.DEFAULT_VIBRATE or NotificationCompat.DEFAULT_LIGHTS)

        NotificationManagerCompat.from(appContext).notify(NOTIFICATION_ID, builder.build())
    }

    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "诈骗短信提醒",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "短信诈骗预警通知"
            enableVibration(true)
        }
        nm.createNotificationChannel(channel)
    }
}