package com.example.controlfree.todo.anniversary.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.example.controlfree.todo.TodoRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AnniversaryAlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == ACTION_DISMISS_PERSISTENT) {
            // 用户左滑清除常驻通知：同步关闭该时刻的“通知常驻”开关，
            // 否则下一次同步会立刻把通知重新发出来。
            val anniversaryId = intent.getStringExtra("anniversary_id") ?: return
            val pendingResult = goAsync()
            receiverScope.launch {
                try {
                    TodoRepository.getInstance(context.applicationContext)
                        .setAnniversaryLockScreenEnabled(anniversaryId, false)
                    AnniversaryRuntimeCoordinator(context.applicationContext).refresh()
                } catch (_: Exception) {
                } finally {
                    pendingResult.finish()
                }
            }
            return
        }
        if (action == ACTION_DISMISS_ALERT) {
            val anniversaryId = intent.getStringExtra("anniversary_id")
            val celebrationId = intent.getStringExtra("celebration_id")
            
            // 取消对应的系统提醒通知
            if (anniversaryId != null) {
                runCatching {
                    val notificationManager = NotificationManagerCompat.from(context.applicationContext)
                    notificationManager.cancel(anniversaryId, NOTIFICATION_ALERT_ID)
                }
            }

            // 在协程里消费 Celebration，使应用内不再弹出
            if (celebrationId != null) {
                val pendingResult = goAsync()
                receiverScope.launch {
                    try {
                        val repository = TodoRepository.getInstance(context.applicationContext)
                        repository.consumeCelebration(celebrationId, System.currentTimeMillis())
                    } catch (_: Exception) {
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }

    companion object {
        const val ACTION_DISMISS_ALERT = "com.example.controlfree.action.DISMISS_ANNIVERSARY_ALERT"
        const val ACTION_DISMISS_PERSISTENT =
            "com.example.controlfree.action.DISMISS_ANNIVERSARY_PERSISTENT"
        const val NOTIFICATION_ALERT_ID = 7463
        private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
