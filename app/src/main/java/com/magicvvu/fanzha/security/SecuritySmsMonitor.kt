package com.magicvvu.fanzha.security

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.util.Log

/**
 * 监听 [Telephony.Sms.CONTENT_URI] 变化：仅在「有新短信写入」后置位，供采集层像剪切板一样避免定时全表扫描。
 *
 */
object SecuritySmsMonitor {

    private const val TAG = "SmsMonitor"

    @Volatile
    private var registered: Boolean = false

    private var appContext: Context? = null
    private var observer: ContentObserver? = null

    @Volatile
    private var smsDirty: Boolean = false

    @Volatile
    private var onDirtyListener: (() -> Unit)? = null

    /**
     * 注册“发生变更时”的回调（可为空）。用于事件驱动触发增量上报，避免定时扫描。
     *
     * 约束：回调可能在主线程触发，应尽量短小（比如仅发出一次协程任务）。
     */
    fun setOnDirtyListener(listener: (() -> Unit)?) {
        onDirtyListener = listener
    }

    fun register(context: Context) {
        val app = context.applicationContext
        synchronized(this) {
            if (registered) return
            val resolver = app.contentResolver
            val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    smsDirty = true
                    onDirtyListener?.invoke()
                }

                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    smsDirty = true
                    onDirtyListener?.invoke()
                }
            }
            resolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, obs)
            observer = obs
            appContext = app
            registered = true
            Log.d(TAG, "sms content observer registered")
        }
    }

    fun unregister(context: Context) {
        val app = context.applicationContext
        synchronized(this) {
            if (!registered) return
            val ctx = appContext ?: app
            observer?.let { ctx.contentResolver.unregisterContentObserver(it) }
            observer = null
            appContext = null
            registered = false
            smsDirty = false
            Log.d(TAG, "sms content observer unregistered")
        }
    }

    fun isSmsDirty(): Boolean = smsDirty

    fun clearDirtyAfterSmsHandled() {
        smsDirty = false
    }
}
