package com.example.controlfree

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SupervisionNotificationIndependenceTest {
    @Test
    fun `普通监督与 App 监督使用不同通知 ID`() {
        assertEquals(1001, MonitorService.NOTIFICATION_ID)
        assertEquals(1101, AppSupervisionService.NOTIFICATION_ID)
        assertNotEquals(MonitorService.NOTIFICATION_ID, AppSupervisionService.NOTIFICATION_ID)
    }
}
