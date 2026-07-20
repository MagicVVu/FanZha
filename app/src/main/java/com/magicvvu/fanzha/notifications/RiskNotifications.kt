package com.magicvvu.fanzha.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

object RiskNotifications {

    private const val REQUEST_CODE = 0x52494B53 // "RIKS"
    /** 安全系数较低时的风险提示间隔（单次精确闹钟链式触发，约 20 秒一次） */
    private val intervalMs = 20_000L

    /**
     * 启动/刷新风险通知定时：先取消同 [PendingIntent] 的旧闹钟，再在 [intervalMs] 后触发一次；
     * [RiskNotificationReceiver] 展示通知后会再次调用本方法形成链式重复。
     */
    fun scheduleRiskNotifications(context: Context) {
        val appContext = context.applicationContext
        val intent = Intent(appContext, RiskNotificationReceiver::class.java).apply {
            action = ACTION_RISK_TICK
        }
        val pi = PendingIntent.getBroadcast(
            appContext,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(pi)

        val triggerAt = System.currentTimeMillis() + intervalMs
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    pi,
                )
            }

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT -> {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }

            else -> {
                @Suppress("DEPRECATION")
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        }
    }

    internal const val ACTION_RISK_TICK =
        "com.magicvvu.fanzha.notifications.ACTION_RISK_TICK"
}
