package com.magicvvu.fanzha.security

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.util.Log

/**
 * 监听 [CallLog.Calls.CONTENT_URI]：仅在通话记录有变更后置位，配合 `_id` 水位只拉增量，
 * 避免定时同步反复扫描整页 CallLog 导致同一诈骗号码重复命中入库。
 */
object SecurityCallLogMonitor {

    private const val TAG = "CallLogMonitor"

    @Volatile
    private var registered: Boolean = false

    private var appContext: Context? = null
    private var observer: ContentObserver? = null

    @Volatile
    private var callDirty: Boolean = false

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
                    callDirty = true
                    onDirtyListener?.invoke()
                }

                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    callDirty = true
                    onDirtyListener?.invoke()
                }
            }
            resolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, obs)
            observer = obs
            appContext = app
            registered = true
            Log.d(TAG, "call log content observer registered")
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
            callDirty = false
            Log.d(TAG, "call log content observer unregistered")
        }
    }

    fun isCallDirty(): Boolean = callDirty

    fun clearDirtyAfterCallHandled() {
        callDirty = false
    }
}
