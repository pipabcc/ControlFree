package com.example.controlfree.supervision.commitment

import android.app.AlarmManager
import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommitmentScheduleReceiverTest {
    @Test
    fun `保存重启和墙钟变化都触发承诺校准`() {
        assertTrue(
            CommitmentScheduleReceiver.isSupportedAction(
                CommitmentScheduleReceiver.ACTION_RECONCILE_COMMITMENTS
            )
        )
        assertTrue(CommitmentScheduleReceiver.isSupportedAction(Intent.ACTION_BOOT_COMPLETED))
        assertTrue(CommitmentScheduleReceiver.isSupportedAction(Intent.ACTION_USER_UNLOCKED))
        assertTrue(
            CommitmentScheduleReceiver.isSupportedAction(Intent.ACTION_MY_PACKAGE_REPLACED)
        )
        assertTrue(CommitmentScheduleReceiver.isSupportedAction(Intent.ACTION_TIME_CHANGED))
        assertTrue(CommitmentScheduleReceiver.isSupportedAction(Intent.ACTION_TIMEZONE_CHANGED))
        assertTrue(
            CommitmentScheduleReceiver.isSupportedAction(
                AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
            )
        )
    }

    @Test
    fun `无关广播不会启动数据库与前台服务`() {
        assertFalse(CommitmentScheduleReceiver.isSupportedAction(null))
        assertFalse(CommitmentScheduleReceiver.isSupportedAction(Intent.ACTION_SCREEN_ON))
    }
}
