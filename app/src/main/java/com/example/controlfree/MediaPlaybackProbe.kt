package com.example.controlfree

import android.content.Context
import android.media.AudioManager

/**
 * 读取系统当前是否存在普通媒体播放。
 *
 * 厂商音频服务可能在查询时抛出运行时异常；探针统一降级为“未检测到播放”，避免影响
 * 监督计时和锁层状态机。内部读取函数用于 JVM 测试覆盖异常边界。
 */
class MediaPlaybackProbe internal constructor(
    private val musicActiveReader: () -> Boolean
) {
    constructor(context: Context) : this(
        musicActiveReader = context.applicationContext
            .getSystemService(AudioManager::class.java)
            .let { manager ->
                { manager?.isMusicActive == true }
            }
    )

    fun probe(): MediaPlaybackProbeResult = try {
        MediaPlaybackProbeResult(
            isMediaPlaying = musicActiveReader(),
            isAvailable = true
        )
    } catch (_: SecurityException) {
        MediaPlaybackProbeResult(isMediaPlaying = false, isAvailable = false)
    } catch (_: RuntimeException) {
        MediaPlaybackProbeResult(isMediaPlaying = false, isAvailable = false)
    }

    fun isMediaPlaying(): Boolean = probe().isMediaPlaying
}

data class MediaPlaybackProbeResult(
    val isMediaPlaying: Boolean,
    val isAvailable: Boolean
)
