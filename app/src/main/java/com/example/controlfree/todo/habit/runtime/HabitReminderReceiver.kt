package com.example.controlfree.todo.habit.runtime

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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class HabitReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!isSupportedAction(intent.action) || !isUserUnlocked(context)) return
        val appContext = context.applicationContext
        val pendingResult = goAsync()
        receiverScope.launch {
            try {
                withTimeout(RECEIVER_TIMEOUT_MILLIS) {
                    withReconciliationLock {
                        HabitReminderRuntimeCoordinator(appContext).reconcile(
                            deliverReminder = intent.action == ACTION_DELIVER_REMINDER
                        )
                    }
                }
            } catch (_: TimeoutCancellationException) {
                scheduleNextBestEffort(appContext)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                scheduleNextBestEffort(appContext)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_DELIVER_REMINDER =
            "com.example.controlfree.action.DELIVER_HABIT_REMINDER"
        const val ACTION_RECONCILE_REMINDERS =
            "com.example.controlfree.action.RECONCILE_HABIT_REMINDERS"
        private const val RECEIVER_TIMEOUT_MILLIS = 8_000L
        private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val reconciliationMutex = Mutex()

        fun requestReconciliation(context: Context, reason: String) {
            val appContext = context.applicationContext
            appContext.sendBroadcast(
                Intent(appContext, HabitReminderReceiver::class.java).apply {
                    action = ACTION_RECONCILE_REMINDERS
                    putExtra("reason", reason.take(64))
                }
            )
        }

        internal fun isSupportedAction(action: String?): Boolean = when (action) {
            ACTION_DELIVER_REMINDER,
            ACTION_RECONCILE_REMINDERS,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_USER_UNLOCKED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> true
            else -> false
        }

        /** 同一进程内的系统广播和设置变更必须按顺序重排，避免旧设置覆盖新设置。 */
        internal suspend fun <T> withReconciliationLock(block: suspend () -> T): T =
            reconciliationMutex.withLock { block() }

        private fun isUserUnlocked(context: Context): Boolean = try {
            context.getSystemService(UserManager::class.java)?.isUserUnlocked != false
        } catch (_: RuntimeException) {
            false
        }

        private fun scheduleNextBestEffort(context: Context) {
            // 超时任务可能仍占用锁；不抢锁时直接交给持锁任务收尾，避免并发覆盖。
            if (!reconciliationMutex.tryLock()) return
            runCatching {
                try {
                    val settings = HabitReminderPreferences(context).read()
                    HabitReminderAlarmScheduler(context).scheduleNext(settings)
                } finally {
                    reconciliationMutex.unlock()
                }
            }
        }
    }
}
