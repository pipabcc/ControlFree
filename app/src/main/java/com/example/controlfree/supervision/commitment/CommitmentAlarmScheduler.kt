package com.example.controlfree.supervision.commitment

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.controlfree.supervision.runtime.SupervisionAlarmPrecision

/** 独立请求码避免防拖延边界覆盖常规监督计划闹钟。 */
class CommitmentAlarmScheduler(context: Context) {
    private val appContext = context.applicationContext
    private val alarmManager = requireNotNull(
        appContext.getSystemService(AlarmManager::class.java)
    ) { "AlarmManager unavailable" }

    fun schedule(triggerAtEpochMillis: Long): SupervisionAlarmPrecision {
        require(triggerAtEpochMillis >= 0L) { "防拖延边界无效" }
        return scheduleWithOperation(triggerAtEpochMillis, boundaryPendingIntent())
    }

    fun scheduleRetry(delayMillis: Long = DEFAULT_RETRY_MILLIS): SupervisionAlarmPrecision =
        scheduleWithOperation(
            System.currentTimeMillis() + delayMillis.coerceAtLeast(MIN_ALARM_DELAY_MILLIS),
            retryPendingIntent()
        )

    fun cancelBoundary() {
        try {
            alarmManager.cancel(boundaryPendingIntent())
        } catch (_: RuntimeException) {
            // 保存策略、系统时间变化或重启都会重新校准。
        }
    }

    fun cancelRetry() {
        try {
            alarmManager.cancel(retryPendingIntent())
        } catch (_: RuntimeException) {
            // 重复校准幂等，取消失败不改变策略真值。
        }
    }

    private fun scheduleWithOperation(
        requestedEpochMillis: Long,
        operation: PendingIntent
    ): SupervisionAlarmPrecision {
        val triggerAt = requestedEpochMillis.coerceAtLeast(
            System.currentTimeMillis() + MIN_ALARM_DELAY_MILLIS
        )
        return if (canScheduleExact()) {
            try {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    operation
                )
                SupervisionAlarmPrecision.EXACT
            } catch (_: RuntimeException) {
                scheduleInexact(triggerAt, operation)
                SupervisionAlarmPrecision.INEXACT
            }
        } else {
            scheduleInexact(triggerAt, operation)
            SupervisionAlarmPrecision.INEXACT
        }
    }

    private fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || try {
            alarmManager.canScheduleExactAlarms()
        } catch (_: RuntimeException) {
            false
        }

    private fun scheduleInexact(triggerAtEpochMillis: Long, operation: PendingIntent) {
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerAtEpochMillis,
            operation
        )
    }

    private fun boundaryPendingIntent(): PendingIntent = pendingIntent(BOUNDARY_REQUEST_CODE)

    private fun retryPendingIntent(): PendingIntent = pendingIntent(RETRY_REQUEST_CODE)

    private fun pendingIntent(requestCode: Int): PendingIntent = PendingIntent.getBroadcast(
        appContext,
        requestCode,
        Intent(appContext, CommitmentScheduleReceiver::class.java).apply {
            action = CommitmentScheduleReceiver.ACTION_RECONCILE_COMMITMENTS
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private companion object {
        const val BOUNDARY_REQUEST_CODE = 7401
        const val RETRY_REQUEST_CODE = 7402
        const val MIN_ALARM_DELAY_MILLIS = 500L
        const val DEFAULT_RETRY_MILLIS = 5L * 60L * 1_000L
    }
}
