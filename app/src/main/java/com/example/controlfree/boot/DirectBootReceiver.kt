package com.example.controlfree.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * 独立的解锁前入口。这里禁止访问 Room、普通 SharedPreferences 或主监督对象。
 */
class DirectBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return

        try {
            ContextCompat.startForegroundService(
                context.applicationContext,
                BootRecoveryService.startIntent(
                    context = context.applicationContext,
                    source = Intent.ACTION_LOCKED_BOOT_COMPLETED
                )
            )
        } catch (_: RuntimeException) {
            // 系统若拒绝本次启动，解锁后的 BOOT_COMPLETED/USER_UNLOCKED 仍会走主恢复入口。
        }
    }
}
