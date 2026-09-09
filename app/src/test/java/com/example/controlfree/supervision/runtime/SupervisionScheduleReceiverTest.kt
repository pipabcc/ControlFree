package com.example.controlfree.supervision.runtime

import android.app.AlarmManager
import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 系统时钟、时区和精确闹钟权限变化都必须重新校准计划。
 * 这里只验证接收器的纯动作路由，实际广播投递由设备侧测试覆盖。
 */
class SupervisionScheduleReceiverTest {
    @Test
    fun `时间变化和时区变化会触发重新校准`() {
        assertTrue(SupervisionScheduleReceiver.isSupportedAction(Intent.ACTION_TIME_CHANGED))
        assertTrue(SupervisionScheduleReceiver.isSupportedAction(Intent.ACTION_TIMEZONE_CHANGED))
    }

    @Test
    fun `精确闹钟权限变化会触发重新校准`() {
        assertTrue(
            SupervisionScheduleReceiver.isSupportedAction(
                AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
            )
        )
    }

    @Test
    fun `无关广播和空动作不会触发重新校准`() {
        assertFalse(SupervisionScheduleReceiver.isSupportedAction(null))
        assertFalse(
            SupervisionScheduleReceiver.isSupportedAction(Intent.ACTION_SCREEN_ON)
        )
        assertFalse(
            SupervisionScheduleReceiver.isSupportedAction("com.example.controlfree.UNKNOWN")
        )
    }
}
