package com.example.controlfree.boot

import android.app.Notification
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.UserManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.controlfree.BootReceiver
import com.example.controlfree.MonitorNotificationChannels

/**
 * 解锁前只维持一个最小前台进程；解锁后把恢复命令显式交给主进程并在有限窗口内重试。
 */
class BootRecoveryService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val scheduledWork = mutableListOf<Runnable>()
    private lateinit var hintStore: BootRecoveryHintStore
    private var receiverRegistered = false
    private var foregroundReady = false
    private var recoveryWindowStarted = false
    private var recoveryGeneration = 0L

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_USER_UNLOCKED,
                Intent.ACTION_USER_PRESENT -> scheduleUnlockedRecovery(intent.action.orEmpty())
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        hintStore = BootRecoveryHintStore(applicationContext)
        foregroundReady = try {
            promoteToForeground()
            true
        } catch (_: RuntimeException) {
            // 无法取得前台资格时立即退出，避免触发前台服务启动超时或崩溃循环。
            stopSelf()
            false
        }
        if (!foregroundReady) return
        registerUnlockReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foregroundReady) return START_NOT_STICKY
        if (isUserUnlocked()) {
            scheduleUnlockedRecovery(intent?.getStringExtra(EXTRA_SOURCE).orEmpty())
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        cancelScheduledWork()
        if (receiverRegistered) {
            try {
                unregisterReceiver(unlockReceiver)
            } catch (_: RuntimeException) {
                // 服务销毁时接收器可能已被系统清理。
            }
            receiverRegistered = false
        }
        super.onDestroy()
    }

    private fun promoteToForeground() {
        MonitorNotificationChannels.ensureCreated(this)
        val notification = NotificationCompat.Builder(
            this,
            MonitorNotificationChannels.SERVICE_CHANNEL_ID
        )
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("正在恢复开机监督")
            .setContentText("等待用户解锁后恢复监督与预约任务")
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun registerUnlockReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BOOT_COMPLETED)
            addAction(Intent.ACTION_USER_UNLOCKED)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        try {
            // 仅接收系统框架和本应用广播，避免外部应用伪造解锁事件延长恢复窗口。
            ContextCompat.registerReceiver(
                this,
                unlockReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        } catch (_: RuntimeException) {
            // onStartCommand 仍会主动检查解锁状态，静态 BootReceiver 也是独立兜底。
        }
    }

    private fun scheduleUnlockedRecovery(source: String) {
        if (!isUserUnlocked() || recoveryWindowStarted) return
        recoveryWindowStarted = true
        recoveryGeneration++
        val generation = recoveryGeneration
        cancelScheduledWork()
        val plan = BootRecoveryRetryPolicy.createPlan(hintStore.read())

        plan.dispatchDelaysMillis.forEachIndexed { index, delayMillis ->
            schedule(delayMillis) {
                if (generation != recoveryGeneration || !isUserUnlocked()) return@schedule
                dispatchRecovery(
                    source = source,
                    isRetry = index > 0
                )
            }
        }
        val stopDelay = plan.dispatchDelaysMillis.last() + plan.stopGraceMillis
        schedule(stopDelay) {
            if (generation == recoveryGeneration) stopAfterRecoveryWindow()
        }
    }

    private fun dispatchRecovery(source: String, isRetry: Boolean) {
        val action = if (isRetry) {
            BootRecoveryContract.ACTION_DIRECT_BOOT_RECOVERY_RETRY
        } else {
            BootRecoveryContract.ACTION_DIRECT_BOOT_RECOVERY
        }
        try {
            sendBroadcast(
                Intent(this, BootReceiver::class.java).apply {
                    this.action = action
                    putExtra(BootRecoveryContract.EXTRA_SOURCE, source)
                }
            )
        } catch (_: RuntimeException) {
            // 后续有界重试仍会继续；最终主界面恢复入口保持不变。
        }
    }

    private fun schedule(delayMillis: Long, action: () -> Unit) {
        val runnable = Runnable(action)
        scheduledWork += runnable
        handler.postDelayed(runnable, delayMillis)
    }

    private fun cancelScheduledWork() {
        scheduledWork.forEach(handler::removeCallbacks)
        scheduledWork.clear()
    }

    private fun stopAfterRecoveryWindow() {
        cancelScheduledWork()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun isUserUnlocked(): Boolean = try {
        getSystemService(UserManager::class.java)?.isUserUnlocked == true
    } catch (_: RuntimeException) {
        false
    }

    companion object {
        private const val NOTIFICATION_ID = 10_103
        private const val EXTRA_SOURCE = "boot_recovery_source"

        fun startIntent(context: Context, source: String): Intent =
            Intent(context, BootRecoveryService::class.java).apply {
                putExtra(EXTRA_SOURCE, source)
            }
    }
}

internal object BootRecoveryContract {
    const val ACTION_DIRECT_BOOT_RECOVERY =
        "com.example.controlfree.action.DIRECT_BOOT_RECOVERY"
    const val ACTION_DIRECT_BOOT_RECOVERY_RETRY =
        "com.example.controlfree.action.DIRECT_BOOT_RECOVERY_RETRY"
    const val EXTRA_SOURCE = "boot_recovery_dispatch_source"
}
