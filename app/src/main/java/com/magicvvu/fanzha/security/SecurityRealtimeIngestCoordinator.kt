package com.magicvvu.fanzha.security

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 实时拦截数据收录协调器：
 * - 不使用定时扫描
 * - 依赖系统的 ContentObserver/剪切板监听回调触发增量上报
 * - 仅对“新增通话/短信/剪切板变更/新安装应用（由 uploader 去重）”进行处理
 */
object SecurityRealtimeIngestCoordinator {

    private const val TAG = "RealtimeIngest"

    @Volatile
    private var started: Boolean = false

    /**
     * 启动实时监听（需已同意且已登录）。调用方负责在登出/撤回时 stop。
     *
     * @param minIntervalMs 防抖窗口：短时间内多次变更（例如短信入库触发多次 onChange）只允许触发一次同步。
     */
    fun start(context: Context, scope: CoroutineScope, minIntervalMs: Long = 1_500L) {
        val app = context.applicationContext
        synchronized(this) {
            if (started) return
            started = true
        }

        var lastTriggerAt = 0L
        var runningJob: Job? = null

        fun trigger(reason: String) {
            val now = System.currentTimeMillis()
            if (now - lastTriggerAt < minIntervalMs) return
            lastTriggerAt = now
            if (runningJob?.isActive == true) return
            runningJob = scope.launch {
                Log.d(TAG, "trigger sync reason=$reason")
                // 事件驱动：不做节流（由本 coordinator 的 minIntervalMs 防抖 + sync 内 mutex 兜底）。
                SecurityInterceptSync.syncIfConsentLoggedIn(app, minIntervalMs = 0L)
                SecurityCallLogMonitor.clearDirtyAfterCallHandled()
                SecuritySmsMonitor.clearDirtyAfterSmsHandled()
                SecurityClipboardMonitor.clearDirtyAfterClipboardHandled()
            }
        }

        SecurityCallLogMonitor.setOnDirtyListener { trigger("call_log_changed") }
        SecuritySmsMonitor.setOnDirtyListener { trigger("sms_changed") }
        SecurityClipboardMonitor.setOnDirtyListener { trigger("clipboard_changed") }

        Log.d(TAG, "realtime ingest coordinator started")
    }

    /** 停止实时监听（登出/撤回同意时调用）。 */
    fun stop() {
        synchronized(this) {
            if (!started) return
            started = false
        }
        SecurityCallLogMonitor.setOnDirtyListener(null)
        SecuritySmsMonitor.setOnDirtyListener(null)
        SecurityClipboardMonitor.setOnDirtyListener(null)
        Log.d(TAG, "realtime ingest coordinator stopped")
    }
}

