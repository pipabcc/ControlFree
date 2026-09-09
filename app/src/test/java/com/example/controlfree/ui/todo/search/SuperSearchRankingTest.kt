package com.example.controlfree.ui.todo.search

import com.example.controlfree.todo.TimeBlockEventEntity
import com.example.controlfree.ui.todo.TodoSubTab
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SuperSearchRankingTest {
    @Test
    fun `日程筛选紧邻待办且路由到独立日程标签`() {
        assertEquals(
            listOf("全部", "待办", "日程", "习惯", "闪记", "账本", "时刻"),
            SuperSearchFilter.entries.map(SuperSearchFilter::displayName)
        )
        assertEquals(TodoSubTab.CALENDAR, SuperSearchSource.CALENDAR.tab)
    }

    @Test
    fun allFilterIncludesCalendarResults() {
        val calendar = result(
            key = "calendar:event:1",
            source = SuperSearchSource.CALENDAR,
            title = "深度专注",
            text = "深度专注 25 分钟"
        )

        val ranked = rankSearchResults(listOf(calendar), "专注", SuperSearchFilter.ALL)

        assertEquals(listOf(calendar.copy(score = 200)), ranked)
    }

    @Test
    fun ranksExactTitleBeforePrefixAndBodyMatch() {
        val exact = result("todo:1", SuperSearchSource.TODO, "缴电费", "缴电费")
        val prefix = result("note:1", SuperSearchSource.QUICK_NOTE, "缴电费提醒", "缴电费提醒")
        val body = result("ledger:1", SuperSearchSource.LEDGER, "生活支出", "本月缴电费")

        val ranked = rankSearchResults(
            listOf(body, prefix, exact),
            "缴电费",
            SuperSearchFilter.ALL
        )

        assertEquals(listOf(exact.stableKey, prefix.stableKey, body.stableKey), ranked.map { it.stableKey })
    }

    @Test
    fun treatsSpecialCharactersAsLiteralTextAndHonorsFilter() {
        val calendar = result("calendar:1", SuperSearchSource.CALENDAR, "学习 C++ [基础]", "学习 C++ [基础]")
        val note = result("note:1", SuperSearchSource.QUICK_NOTE, "C++ [基础]", "C++ [基础]")

        val ranked = rankSearchResults(
            listOf(calendar, note),
            "C++ [",
            SuperSearchFilter.QUICK_NOTE
        )

        assertEquals(1, ranked.size)
        assertEquals(note.stableKey, ranked.single().stableKey)
        assertTrue(ranked.single().score > 0)
    }

    @Test
    fun `独立日程事件映射为可定位的搜索结果`() {
        val start = Instant.parse("2026-07-29T09:00:00Z").toEpochMilli()
        val event = TimeBlockEventEntity(
            id = "event-1",
            title = "项目复盘",
            description = "整理发布清单",
            startAtEpochMillis = start,
            endAtEpochMillis = start + 3_600_000L,
            project = "工作",
            priority = 2,
            isCompleted = false,
            completedAtEpochMillis = null,
            createdAtEpochMillis = start,
            updatedAtEpochMillis = start
        )

        val result = event.toCalendarSearchResult(ZoneId.of("UTC"))

        assertEquals("calendar:event:event-1", result.stableKey)
        assertEquals(SuperSearchSource.CALENDAR, result.source)
        assertEquals("event-1", result.entityId)
        assertEquals(LocalDate.of(2026, 7, 29), result.targetDate)
        assertTrue(result.searchableText.contains("整理发布清单"))
        assertTrue(result.metadata.contains("09:00–10:00"))
    }

    private fun result(
        key: String,
        source: SuperSearchSource,
        title: String,
        text: String
    ) = SuperSearchResult(
        stableKey = key,
        source = source,
        entityId = key,
        title = title,
        searchableText = text,
        snippet = null,
        metadata = "",
        timestampEpochMillis = 1L
    )
}
