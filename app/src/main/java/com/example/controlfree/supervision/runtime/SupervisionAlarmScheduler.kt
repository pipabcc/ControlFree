package com.example.controlfree.supervision.runtime

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.Instant

enum class SupervisionAlarmPrecision {
    EXACT,
    INEXACT
}

class SupervisionAlarmScheduler(context: Context) {
    private val appContext = context.applicationContext
    private val alarmManager = requireNotNull(
        appContext.getSystemService(AlarmManager::class.java)
    ) { "AlarmManager unavailable" }

    fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || try {
            alarmManager.canScheduleExactAlarms()
        } catch (_: RuntimeException) {
            false
        }

    fun schedule(triggerAt: Instant): SupervisionAlarmPrecision {
        return scheduleWithOperation(triggerAt, boundaryPendingIntent())
    }

    fun scheduleRetry(delayMillis: Long = DEFAULT_RETRY_MILLIS): SupervisionAlarmPrecision =
        scheduleWithOperation(
            Instant.ofEpochMilli(
                System.currentTimeMillis() + delayMillis.coerceAtLeast(1_000L)
            ),
            retryPendingIntent()
        )

    private fun scheduleWithOperation(
        triggerAt: Instant,
        operation: PendingIntent
    ): SupervisionAlarmPrecision {
        val triggerAtMillis = triggerAt.toEpochMilli()
            .coerceAtLeast(System.currentTimeMillis() + 500L)
        return if (canScheduleExact()) {
            try {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    operation
                )
                SupervisionAlarmPrecision.EXACT
            } catch (_: RuntimeException) {
                scheduleInexact(triggerAtMillis, operation)
                SupervisionAlarmPrecision.INEXACT
            }
        } else {
            scheduleInexact(triggerAtMillis, operation)
            SupervisionAlarmPrecision.INEXACT
        }
    }

    fun cancel() {
        try {
            alarmManager.cancel(boundaryPendingIntent())
            alarmManager.cancel(retryPendingIntent())
        } catch (_: RuntimeException) {
            // 下次保存计划或系统广播仍会重新校准。
        }
    }

    fun cancelRetry() {
        try {
            alarmManager.cancel(retryPendingIntent())
        } catch (_: RuntimeException) {
            // 重复校准是幂等的，取消失败不会破坏监督状态。
        }
    }

    private fun scheduleInexact(triggerAtMillis: Long, operation: PendingIntent) {
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerAtMillis,
            operation
        )
    }

    private fun boundaryPendingIntent(): PendingIntent = reconciliationPendingIntent(
        BOUNDARY_REQUEST_CODE
    )

    private fun retryPendingIntent(): PendingIntent = reconciliationPendingIntent(
        RETRY_REQUEST_CODE
    )

    private fun reconciliationPendingIntent(requestCode: Int): PendingIntent = PendingIntent.getBroadcast(
        appContext,
        requestCode,
        Intent(appContext, SupervisionScheduleReceiver::class.java).apply {
            action = SupervisionScheduleReceiver.ACTION_RECONCILE_SCHEDULES
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private companion object {
        const val BOUNDARY_REQUEST_CODE = 7301
        const val RETRY_REQUEST_CODE = 7302
        const val DEFAULT_RETRY_MILLIS = 5L * 60L * 1_000L
    }
}
