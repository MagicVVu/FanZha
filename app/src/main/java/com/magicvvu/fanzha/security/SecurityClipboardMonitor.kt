package com.magicvvu.fanzha.security

import android.content.ClipboardManager
import android.content.Context
import android.util.Log

/**
 * 仅在系统通知「主剪切板已变化」后才认为需要读取剪切板，避免定时同步反复 [ClipboardManager] 读同一串关键词导致重复上报。
 *
 * 须在主线程 [register] / [unregister]（与 [ClipboardManager.addPrimaryClipChangedListener] 约定一致）。
 */
object SecurityClipboardMonitor {

    private const val TAG = "ClipboardMonitor"

    @Volatile
    private var registered: Boolean = false

    private var appContext: Context? = null
    private var listener: ClipboardManager.OnPrimaryClipChangedListener? = null

    @Volatile
    private var clipboardDirty: Boolean = false

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
            val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: run {
                Log.w(TAG, "ClipboardManager unavailable")
                return
            }
            val cb = ClipboardManager.OnPrimaryClipChangedListener {
                clipboardDirty = true
                onDirtyListener?.invoke()
            }
            cm.addPrimaryClipChangedListener(cb)
            listener = cb
            appContext = app
            registered = true
            Log.d(TAG, "primary clip listener registered")
        }
    }

    fun unregister(context: Context) {
        val app = context.applicationContext
        synchronized(this) {
            if (!registered) return
            val cm = (appContext ?: app).getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val cb = listener
            if (cm != null && cb != null) {
                cm.removePrimaryClipChangedListener(cb)
            }
            listener = null
            appContext = null
            registered = false
            clipboardDirty = false
            Log.d(TAG, "primary clip listener unregistered")
        }
    }

    /** 自上次 [clearDirtyAfterClipboardHandled] 以来是否发生过剪切板变更（由系统回调置位）。 */
    fun isClipboardDirty(): Boolean = clipboardDirty

    /** 在已读取并决定是否纳入本次采集后调用，避免同一次变更被多次处理。 */
    fun clearDirtyAfterClipboardHandled() {
        clipboardDirty = false
    }
}
