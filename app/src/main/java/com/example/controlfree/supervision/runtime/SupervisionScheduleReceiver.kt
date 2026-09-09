package com.example.controlfree.supervision.runtime

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class SupervisionScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!isSupportedAction(intent.action)) return
        val pendingResult = goAsync()
        receiverScope.launch {
            try {
                withTimeout(RECEIVER_TIMEOUT_MILLIS) {
                    SupervisionScheduleCoordinator(context.applicationContext).reconcile()
                }
            } catch (_: TimeoutCancellationException) {
                try {
                    SupervisionAlarmScheduler(context.applicationContext).scheduleRetry()
                } catch (_: RuntimeException) {
                    // 后续打开 App、开机或时间变化时仍会再次校准。
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                try {
                    SupervisionAlarmScheduler(context.applicationContext).scheduleRetry()
                } catch (_: RuntimeException) {
                    // 后续打开 App、开机或时间变化时仍会再次校准。
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_RECONCILE_SCHEDULES =
            "com.example.controlfree.ACTION_RECONCILE_SUPERVISION_SCHEDULES"
        private const val RECEIVER_TIMEOUT_MILLIS = 8_000L
        private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun requestReconciliation(context: Context, reason: String) {
            context.applicationContext.sendBroadcast(
                Intent(context.applicationContext, SupervisionScheduleReceiver::class.java).apply {
                    action = ACTION_RECONCILE_SCHEDULES
                    putExtra("reason", reason.take(64))
                }
            )
        }

        internal fun isSupportedAction(action: String?): Boolean =
            action == ACTION_RECONCILE_SCHEDULES ||
                action == Intent.ACTION_TIME_CHANGED ||
                action == Intent.ACTION_TIMEZONE_CHANGED ||
                action == AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
    }
}
