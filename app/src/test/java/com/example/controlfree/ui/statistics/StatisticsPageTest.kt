package com.example.controlfree.ui.statistics

import org.junit.Assert.assertEquals
import org.junit.Test

class StatisticsPageTest {
    @Test
    fun `日程统计位于首位且标签名称稳定`() {
        assertEquals(
            listOf("日程统计", "使用统计", "监督统计"),
            StatisticsPage.entries.map(StatisticsPage::displayName)
        )
        assertEquals(StatisticsPage.CALENDAR, StatisticsPage.entries.first())
    }
}
