package com.magicvvu.fanzha.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * 高危安全系数时的系统震动：短促、多段脉冲。可重复调用以形成持续提醒。
 */
object LowSafetyVibration {

    /** 两次急促脉冲组之间的间隔（毫秒），波形本身约数百毫秒 */
    private const val REPEAT_GAP_MS = 2200L

    fun repeatGapMs(): Long = REPEAT_GAP_MS

    /** 播放一段急促震动（不判断安全系数，由调用方控制节奏） */
    fun playUrgentBurst(context: Context) {
        val app = context.applicationContext
        val vibrator = vibratorFrom(app) ?: return
        if (!vibrator.hasVibrator()) return

        vibrator.cancel()

        // 急促节奏：多段短振 + 短间隔（毫秒：等待, 振, 停, 振, …）
        val timings = longArrayOf(0, 55, 45, 55, 45, 55, 45, 55, 45, 90)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (vibrator.hasAmplitudeControl()) {
                val amplitudes = intArrayOf(0, 255, 0, 255, 0, 255, 0, 255, 0, 255)
                vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
            } else {
                vibrator.vibrate(VibrationEffect.createWaveform(timings, -1))
            }
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(timings, -1)
        }
    }

    fun cancel(context: Context) {
        val app = context.applicationContext
        vibratorFrom(app)?.cancel()
    }

    private fun vibratorFrom(context: Context): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }
}
