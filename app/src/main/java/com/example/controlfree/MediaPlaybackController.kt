package com.example.controlfree

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import androidx.core.content.ContextCompat

/**
 * 锁定期间持有瞬时音频焦点，使遵守 Android 音频焦点规范的媒体播放器暂停。
 */
class MediaPlaybackController(private val context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val pauseDispatchPolicy = MediaPauseDispatchPolicy()

    // 静音控制相关变量
    private var originalVolume: Int? = null
    private var isMuteLocked = false
    private var isVolumeReceiverRegistered = false

    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "android.media.VOLUME_CHANGED_ACTION") {
                val streamType = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1)
                if (streamType == AudioManager.STREAM_MUSIC) {
                    val streamValue = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1)
                    if (streamValue > 0 && isMuteLocked) {
                        audioManager?.let { manager ->
                            try {
                                if (originalVolume == null) {
                                    val prevVolume = intent.getIntExtra("android.media.EXTRA_PREV_VOLUME_STREAM_VALUE", -1)
                                    if (prevVolume > 0) {
                                        originalVolume = prevVolume
                                    }
                                }
                                manager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                            } catch (_: Exception) {}
                        }
                    }
                }
            }
        }
    }

    private fun registerVolumeReceiver() {
        if (isVolumeReceiverRegistered) return
        try {
            val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
            ContextCompat.registerReceiver(
                context,
                volumeReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
            isVolumeReceiverRegistered = true
        } catch (_: Exception) {}
    }

    private fun unregisterVolumeReceiver() {
        if (!isVolumeReceiverRegistered) return
        try {
            context.unregisterReceiver(volumeReceiver)
            isVolumeReceiverRegistered = false
        } catch (_: Exception) {}
    }

    private fun muteMediaVolume(manager: AudioManager) {
        isMuteLocked = true
        registerVolumeReceiver()
        try {
            val currentVol = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (currentVol > 0) {
                if (originalVolume == null) {
                    originalVolume = currentVol
                }
                manager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
            }
        } catch (_: Exception) {}
    }

    private fun restoreMediaVolume(manager: AudioManager) {
        isMuteLocked = false
        unregisterVolumeReceiver()
        try {
            val volToRestore = originalVolume
            if (volToRestore != null) {
                manager.setStreamVolume(AudioManager.STREAM_MUSIC, volToRestore, 0)
                originalVolume = null
            }
        } catch (_: Exception) {}
    }

    fun enforceMuteIfNecessary() {
        val manager = audioManager ?: return
        if (isMuteLocked) {
            muteMediaVolume(manager)
        }
    }
    private val retainedReplayHandler = Handler(Looper.getMainLooper())
    private val retainedReplayGuard = MediaReplayGuard()
    private var retainedReplayProbe: MediaPlaybackProbe? = null
    private val retainedReplayRunnable = object : Runnable {
        override fun run() {
            val probe = retainedReplayProbe ?: return
            val nowElapsedMillis = SystemClock.elapsedRealtime()
            if (retainedReplayGuard.shouldProbe(nowElapsedMillis)) {
                val action = retainedReplayGuard.recordProbeResult(
                    nowElapsedMillis = nowElapsedMillis,
                    isMediaPlaying = probe.isMediaPlaying(),
                    quietProbeIntervalMillis = RETAINED_QUIET_PROBE_INTERVAL_MILLIS
                )
                if (action == MediaReplayAction.REAPPLY_MEDIA_PAUSE) {
                    enforceMediaPauseDuringLock()
                }
            }
            retainedReplayGuard.nextProbeDelayMillis(SystemClock.elapsedRealtime())
                ?.let { delay -> retainedReplayHandler.postDelayed(this, delay.coerceAtLeast(1L)) }
        }
    }
    private val focusRequest = AudioFocusRequest.Builder(
        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
    )
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
                .build()
        )
        .setAcceptsDelayedFocusGain(false)
        .setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener { change ->
            when (change) {
                AudioManager.AUDIOFOCUS_GAIN -> {
                    if (isFocusRequestActive) hasAudioFocus = true
                }
                AudioManager.AUDIOFOCUS_LOSS -> {
                    hasAudioFocus = false
                    isFocusRequestActive = false
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> hasAudioFocus = false
            }
        }
        .build()

    private var hasAudioFocus = false
    private var isFocusRequestActive = false

    fun pauseMediaDuringLock(forceMediaCommand: Boolean = false): Boolean {
        val manager = audioManager ?: return false
        val enteredLockStage = pauseDispatchPolicy.enterLockStage()
        val dispatchedPause = forceMediaCommand || enteredLockStage
        if (dispatchedPause) safelyDispatchPause(manager)
        muteMediaVolume(manager)
        // 暂时失去焦点时保留原请求，系统会在可用时回调 GAIN；重复申请会与迟到回调竞态。
        if (isFocusRequestActive) return dispatchedPause
        isFocusRequestActive = true
        hasAudioFocus = try {
            manager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } catch (_: SecurityException) {
            false
        } catch (_: RuntimeException) {
            false
        }
        if (!hasAudioFocus) isFocusRequestActive = false
        return dispatchedPause
    }

    /** 强制再次发送暂停，并确认锁定所需的独占焦点请求仍处于活动状态。 */
    fun enforceMediaPauseDuringLock(): Boolean =
        pauseMediaDuringLock(forceMediaCommand = true)

    /**
     * 通话期间仍暂停普通媒体，但不能与系统通话争夺独占音频焦点。
     */
    fun pauseMediaDuringCall(forceMediaCommand: Boolean = false): Boolean {
        val manager = audioManager ?: return false
        val enteredLockStage = pauseDispatchPolicy.enterLockStage()
        val dispatchedPause = forceMediaCommand || enteredLockStage
        if (dispatchedPause) safelyDispatchPause(manager)
        muteMediaVolume(manager)
        if (isFocusRequestActive) {
            isFocusRequestActive = false
            hasAudioFocus = false
            safelyAbandonFocus(manager)
        }
        return dispatchedPause
    }

    fun allowMediaPlayback() {
        stopRetainedReplayProtection()
        pauseDispatchPolicy.allowMediaPlayback()
        audioManager?.let { restoreMediaVolume(it) }
        if (!isFocusRequestActive) return
        isFocusRequestActive = false
        hasAudioFocus = false
        audioManager?.let(::safelyAbandonFocus)
    }

    /** Service 被系统销毁但锁资源仍保留时，维持一个可取消的低频重播保护。 */
    fun startRetainedReplayProtection(probe: MediaPlaybackProbe) {
        retainedReplayHandler.removeCallbacks(retainedReplayRunnable)
        retainedReplayProbe = probe
        val nowElapsedMillis = SystemClock.elapsedRealtime()
        retainedReplayGuard.setEnabled(
            shouldEnable = true,
            nowElapsedMillis = nowElapsedMillis
        )
        retainedReplayGuard.nextProbeDelayMillis(nowElapsedMillis)
            ?.let { delay ->
                retainedReplayHandler.postDelayed(retainedReplayRunnable, delay.coerceAtLeast(1L))
            }
    }

    private fun stopRetainedReplayProtection() {
        retainedReplayHandler.removeCallbacks(retainedReplayRunnable)
        retainedReplayProbe = null
        retainedReplayGuard.disable()
    }

    private fun safelyAbandonFocus(manager: AudioManager) {
        try {
            manager.abandonAudioFocusRequest(focusRequest)
        } catch (_: SecurityException) {
            // 权限被系统撤销时只降级媒体暂停能力，不影响监督计时。
        } catch (_: RuntimeException) {
            // 音频服务异常时只降级媒体暂停能力，不影响监督计时。
        }
    }

    private fun safelyDispatchPause(manager: AudioManager) {
        safelyDispatchMediaKey(manager, KeyEvent.ACTION_DOWN)
        safelyDispatchMediaKey(manager, KeyEvent.ACTION_UP)
    }

    private fun safelyDispatchMediaKey(manager: AudioManager, action: Int) {
        try {
            manager.dispatchMediaKeyEvent(
                KeyEvent(action, KeyEvent.KEYCODE_MEDIA_PAUSE)
            )
        } catch (_: SecurityException) {
            // 部分系统限制全局媒体键；独占音频焦点仍作为主要暂停通道。
        } catch (_: RuntimeException) {
            // 媒体会话服务异常不能阻塞监督状态机。
        }
    }

    private companion object {
        const val RETAINED_QUIET_PROBE_INTERVAL_MILLIS = 8_000L
    }
}
