package com.example.controlfree.ui.statistics

import org.junit.Assert.assertEquals
import org.junit.Test

class StatisticsPageTest {
    @Test
    fun `使用统计位于首位且标签名称稳定`() {
        assertEquals(
            listOf("使用统计", "监督统计", "日程统计"),
            StatisticsPage.entries.map(StatisticsPage::displayName)
        )
        assertEquals(StatisticsPage.APP_USAGE, StatisticsPage.entries.first())
    }
}
