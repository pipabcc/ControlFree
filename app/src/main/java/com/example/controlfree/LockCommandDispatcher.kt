package com.example.controlfree

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.example.controlfree.security.CredentialStore

/**
 * 锁屏交互通过显式服务命令执行，避免悬浮层持有已销毁 Service 的回调。
 */
class LockCommandDispatcher(context: Context) {
    private val appContext = context.applicationContext
    private val credentials = CredentialStore(appContext)

    fun hasGesture(): Boolean = credentials.hasGesture()

    fun hasPassword(): Boolean = credentials.hasPassword()

    fun passwordLength(): Int? = credentials.getPasswordLength()

    fun requestAllowedApp(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        return startServiceCommand(MonitorService.ACTION_OPEN_ALLOWED_APP) {
            putExtra(MonitorService.EXTRA_PACKAGE_NAME, packageName)
            putExtra(
                MonitorService.EXTRA_LOCK_SESSION_ID,
                RuntimeLockTruthRegistry.currentSessionId()
            )
            putExtra(
                MonitorService.EXTRA_COMMAND_ELAPSED_MILLIS,
                SystemClock.elapsedRealtime()
            )
        }
    }

    private fun startServiceCommand(
        action: String,
        configure: Intent.() -> Unit = {}
    ): Boolean = try {
        ContextCompat.startForegroundService(
            appContext,
            Intent(appContext, MonitorService::class.java).apply {
                this.action = action
                configure()
            }
        )
        true
    } catch (_: RuntimeException) {
        false
    }
}
