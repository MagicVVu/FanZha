package com.magicvvu.fanzha.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.magicvvu.fanzha.data.local.AuthPreferences
import com.magicvvu.fanzha.data.remote.NetworkModule
import com.magicvvu.fanzha.data.repository.AssistantRepository
import com.magicvvu.fanzha.notifications.AlertNotificationCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class IncomingSmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        Log.d("SMS_FLOW", "onReceive called, action=${intent?.action}")

        if (intent?.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            Log.d("SMS_FLOW", "skip: action is not SMS_RECEIVED_ACTION")
            return
        }

        val appContext = context.applicationContext

        val prefs = appContext.getSharedPreferences("security_collection", Context.MODE_PRIVATE)
        val consentGranted = prefs.getBoolean("consent_granted", false)
        val userId = AuthPreferences.getUserId(appContext)

        Log.d("SMS_FLOW", "consentGranted=$consentGranted, userId=$userId")

        if (!consentGranted || userId == null) {
            Log.d("SMS_FLOW", "skip: consent=$consentGranted, userId=$userId")
            return
        }

        val smsMessages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        Log.d("SMS_FLOW", "smsMessages.size=${smsMessages.size}")

        if (smsMessages.isEmpty()) {
            Log.d("SMS_FLOW", "skip: smsMessages is empty")
            return
        }

        val sender = smsMessages.firstOrNull()?.originatingAddress.orEmpty().trim()
        val message = smsMessages.joinToString(separator = "") { it.messageBody.orEmpty() }.trim()

        Log.d("SMS_FLOW", "parsed sender=$sender")
        Log.d("SMS_FLOW", "parsed message=$message")

        if (message.isBlank()) {
            Log.d("SMS_FLOW", "skip: message is blank")
            return
        }

        val pendingResult = goAsync()
        Log.d("SMS_FLOW", "goAsync acquired")

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        scope.launch {
            try {
                Log.d("SMS_FLOW", "creating AssistantRepository, baseUrl=${NetworkModule.BASE_URL}")

                val repository = AssistantRepository(
                    api = NetworkModule.assistantApi,
                    streamClient = NetworkModule.streamClient,
                    baseUrl = NetworkModule.BASE_URL,
                )

                Log.d("SMS_FLOW", "start check-sms request, sender=$sender")

                val response = withTimeout(8_000L) {
                    repository.checkSms(
                        sender = sender,
                        message = message,
                    )
                }

                Log.d("SMS_FLOW", "check-sms response received, isFraud=${response.isFraud}")

                SmsFraudResultBridge.publish(
                    SmsFraudCheckEvent(
                        sender = sender,
                        message = message,
                        isFraud = response.isFraud,
                    )
                )

                Log.d("SMS_FLOW", "bridge publish success, isFraud=${response.isFraud}")

                if (response.isFraud) {
                    AlertNotificationCoordinator.notifyFraudSmsDelayed(appContext, delayMs = 5_000L)
                    Log.d("SMS_FLOW", "fraud sms notification posted")
                } else {
                    Log.d("SMS_FLOW", "not fraud, skip notification")
                }
            } catch (e: Exception) {
                Log.e("SMS_FLOW", "check-sms failed", e)
            } finally {
                Log.d("SMS_FLOW", "receiver async finishing")
                pendingResult.finish()
                scope.cancel()
                Log.d("SMS_FLOW", "receiver scope cancelled")
            }
        }
    }
}
