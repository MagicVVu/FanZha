package com.magicvvu.fanzha.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread

class RiskNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != RiskNotifications.ACTION_RISK_TICK) return

        val pendingResult = goAsync()
        val appContext = context.applicationContext
        thread {
            try {
                runBlocking {
                    AlertNotificationCoordinator.pollAndNotify(appContext)
                }
            } finally {
                RiskNotifications.scheduleRiskNotifications(appContext)
                pendingResult.finish()
            }
        }
    }
}
