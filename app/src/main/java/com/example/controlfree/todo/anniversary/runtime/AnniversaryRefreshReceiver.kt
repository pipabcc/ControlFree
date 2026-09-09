package com.example.controlfree.todo.anniversary.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AnniversaryRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!isSupportedAction(intent.action)) return
        val pendingResult = goAsync()
        receiverScope.launch {
            try {
                AnniversaryRuntimeCoordinator(context).refresh()
            } catch (_: RuntimeException) {
                // 系统广播失败时保留数据库状态；下次打开应用或系统广播会再次校准。
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun isSupportedAction(action: String?): Boolean = action in setOf(
        ACTION_REFRESH,
        Intent.ACTION_BOOT_COMPLETED,
        Intent.ACTION_USER_UNLOCKED,
        Intent.ACTION_MY_PACKAGE_REPLACED,
        Intent.ACTION_TIME_CHANGED,
        Intent.ACTION_TIMEZONE_CHANGED
    )

    companion object {
        const val ACTION_REFRESH = "com.example.controlfree.action.REFRESH_ANNIVERSARIES"
        private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
