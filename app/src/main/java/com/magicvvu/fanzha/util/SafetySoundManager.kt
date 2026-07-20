package com.magicvvu.fanzha.util

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.magicvvu.fanzha.R

/**
 * 基于安全指数播放语音提醒：
 * - 30 ≤ 安全指数 < 60：播放 fanzhayuyin.mp3
 * - 安全指数 < 30：播放 gaoweibobao.mp3
 */
object SafetySoundManager {

    private const val REPEAT_GAP_MS = 11_000L

    private var soundPool: SoundPool? = null
    private var soundIdFair: Int = 0   // fanzhayuyin.mp3
    private var soundIdCritical: Int = 0 // gaoweibobao.mp3
    private var isLoaded = false

    fun repeatGapMs(): Long = REPEAT_GAP_MS

    fun initialize(context: Context) {
        if (soundPool != null) return
        soundPool = SoundPool.Builder()
            .setMaxStreams(2)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()
            .also { pool ->
                pool.setOnLoadCompleteListener { _, _, status ->
                    if (status == 0) isLoaded = true
                }
                soundIdFair = pool.load(context, R.raw.fanzhayuyin, 1)
                soundIdCritical = pool.load(context, R.raw.gaoweibobao, 1)
            }
    }

    fun playFairWarning(context: Context) {
        initialize(context.applicationContext)
        if (soundIdFair == 0) return
        soundPool?.play(soundIdFair, 1f, 1f, 1, 0, 1f)
    }

    fun playCriticalAlert(context: Context) {
        initialize(context.applicationContext)
        if (soundIdCritical == 0) return
        soundPool?.play(soundIdCritical, 1f, 1f, 1, 0, 1f)
    }

    fun cancel(context: Context) {
        soundPool?.autoPause()
    }

    fun release() {
        soundPool?.release()
        soundPool = null
        soundIdFair = 0
        soundIdCritical = 0
        isLoaded = false
    }
}
