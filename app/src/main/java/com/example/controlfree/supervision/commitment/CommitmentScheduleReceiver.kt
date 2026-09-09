package com.example.controlfree.supervision.commitment

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class CommitmentScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!isSupportedAction(intent.action) || !isUserUnlocked(context)) return
        val pendingResult = goAsync()
        receiverScope.launch {
            try {
                withTimeout(RECEIVER_TIMEOUT_MILLIS) {
                    CommitmentRuntimeCoordinator(context.applicationContext).reconcile()
                }
            } catch (_: TimeoutCancellationException) {
                scheduleRetry(context)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                scheduleRetry(context)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_RECONCILE_COMMITMENTS =
            "com.example.controlfree.ACTION_RECONCILE_COMMITMENTS"
        private const val RECEIVER_TIMEOUT_MILLIS = 8_000L
        private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun requestReconciliation(context: Context, reason: String) {
            context.applicationContext.sendBroadcast(
                Intent(context.applicationContext, CommitmentScheduleReceiver::class.java).apply {
                    action = ACTION_RECONCILE_COMMITMENTS
                    putExtra("reason", reason.take(64))
                }
            )
        }

        internal fun isSupportedAction(action: String?): Boolean = when (action) {
            ACTION_RECONCILE_COMMITMENTS,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_USER_UNLOCKED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> true
            else -> false
        }

        private fun isUserUnlocked(context: Context): Boolean = try {
            context.getSystemService(UserManager::class.java)?.isUserUnlocked != false
        } catch (_: RuntimeException) {
            false
        }

        private fun scheduleRetry(context: Context) {
            try {
                CommitmentAlarmScheduler(context.applicationContext).scheduleRetry()
            } catch (_: RuntimeException) {
                // 下次进入 App、时间变化或重启仍会再次校准。
            }
        }
    }
}
