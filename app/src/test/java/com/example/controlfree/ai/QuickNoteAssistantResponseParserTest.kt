package com.example.controlfree.ai

import com.example.controlfree.todo.AnniversaryRepeatRule
import com.example.controlfree.todo.AnniversaryType
import com.example.controlfree.todo.LedgerCategory
import com.example.controlfree.todo.TodoRecurrenceType
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickNoteAssistantResponseParserTest {
    private val now = ZonedDateTime.of(2026, 7, 26, 9, 0, 0, 0, ZoneId.of("Asia/Shanghai"))

    @Test
    fun 严格协议可一次解析全部目标和建议() {
        val parsed = QuickNoteAssistantResponseParser.parse(VALID_RESPONSE, "整理今天的事情", now)

        assertNotNull(parsed)
        requireNotNull(parsed)
        assertEquals(1, parsed.ledgerEntries.size)
        assertEquals(LedgerCategory.FOOD, parsed.ledgerEntries.single().category)
        assertEquals(1, parsed.todoItems.size)
        assertEquals(listOf(10), parsed.todoItems.single().reminderMinutesBefore)
        assertEquals(TodoRecurrenceType.NONE, parsed.todoItems.single().recurrenceType)
        assertEquals(1, parsed.habits.size)
        assertEquals(listOf(7 * 60 + 30), parsed.habits.single().reminderMinutesOfDay)
        assertEquals(20, parsed.focusSessions.single().durationMinutes)
        assertEquals(AnniversaryRepeatRule.YEARLY, parsed.anniversaries.single().repeatRule)
        assertEquals(listOf("先完成报告再处理低优先级事项。"), parsed.advice)
        assertTrue(parsed.fingerprint.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun 多余字段和没有时间基准的提醒会被拒绝() {
        assertNull(
            QuickNoteAssistantResponseParser.parse(
                VALID_RESPONSE.replaceFirst(
                    "\"warnings\":[]",
                    "\"warnings\":[],\"unexpected\":true"
                ),
                "测试",
                now
            )
        )
        assertNull(
            QuickNoteAssistantResponseParser.parse(
                VALID_RESPONSE
                    .replace("\"due_at\":\"2026-07-27T07:00:00Z\"", "\"due_at\":\"\"")
                    .replace("\"start_at\":\"2026-07-27T06:00:00Z\"", "\"start_at\":\"\""),
                "测试",
                now
            )
        )
    }

    @Test
    fun 估算账目没有警告会被拒绝() {
        val invalid = VALID_RESPONSE
            .replace("\"is_estimated\":false", "\"is_estimated\":true")

        assertNull(QuickNoteAssistantResponseParser.parse(invalid, "测试", now))
    }

    @Test
    fun 远端待办不会把开始时间回填为截止时间() {
        val response = SINGLE_TODO_RESPONSE
            .replace("\"due_at\":\"2026-07-27T07:00:00Z\"", "\"due_at\":\"\"")
            .replace("\"duration_minutes\":60", "\"duration_minutes\":0")

        val todo = requireNotNull(
            QuickNoteAssistantResponseParser.parse(response, "下午3点去打球", now)
        ).todoItems.single()

        assertNull(todo.dueAtEpochMillis)
        assertNotNull(todo.scheduledStartEpochMillis)
        assertEquals(60, todo.durationMinutes)
    }

    @Test
    fun 远端模型把普通开始时间误填到截止字段时会被纠正() {
        val response = SINGLE_TODO_RESPONSE
            .replace("\"start_at\":\"2026-07-27T06:00:00Z\"", "\"start_at\":\"\"")
            .replace("\"duration_minutes\":60", "\"duration_minutes\":0")

        val todo = requireNotNull(
            QuickNoteAssistantResponseParser.parse(response, "下午3点去打球", now)
        ).todoItems.single()

        assertEquals(Instant.parse("2026-07-27T07:00:00Z").toEpochMilli(), todo.scheduledStartEpochMillis)
        assertNull(todo.dueAtEpochMillis)
        assertEquals(60, todo.durationMinutes)
    }

    @Test
    fun 远端模型同时填写开始和截止但原文无截止语义时清除截止() {
        val todo = requireNotNull(
            QuickNoteAssistantResponseParser.parse(
                SINGLE_TODO_RESPONSE,
                "下午2点开始写报告",
                now
            )
        ).todoItems.single()

        assertEquals(Instant.parse("2026-07-27T06:00:00Z").toEpochMilli(), todo.scheduledStartEpochMillis)
        assertNull(todo.dueAtEpochMillis)
    }

    @Test
    fun 原文明示截止时保留独立的开始和截止时间() {
        val todo = requireNotNull(
            QuickNoteAssistantResponseParser.parse(
                SINGLE_TODO_RESPONSE,
                "下午2点开始写报告，下午3点前提交",
                now
            )
        ).todoItems.single()

        assertEquals(Instant.parse("2026-07-27T06:00:00Z").toEpochMilli(), todo.scheduledStartEpochMillis)
        assertEquals(Instant.parse("2026-07-27T07:00:00Z").toEpochMilli(), todo.dueAtEpochMillis)
    }

    @Test
    fun 混合内容中其他分句的截止词不会污染待办时间() {
        val response = VALID_RESPONSE.replace(
            "\"due_at\":\"2026-07-27T07:00:00Z\"",
            "\"due_at\":\"\""
        )

        val todo = requireNotNull(
            QuickNoteAssistantResponseParser.parse(
                response,
                "午饭前买咖啡30元，下午2点去打球",
                now
            )
        ).todoItems.single()

        assertEquals(
            Instant.parse("2026-07-27T06:00:00Z").toEpochMilli(),
            todo.scheduledStartEpochMillis
        )
        assertNull(todo.dueAtEpochMillis)
    }

    @Test
    fun 远端时刻类型以目标时间为准而不是模型字段() {
        val pastResponse = VALID_RESPONSE
            .replace("2026-08-20T01:00:00Z", "2026-07-25T01:00:00Z")
        val futureResponse = VALID_RESPONSE
            .replace("\"type\":\"COUNTDOWN\"", "\"type\":\"COUNT_UP\"")
            .replace("\"repeat_rule\":\"YEARLY\"", "\"repeat_rule\":\"NONE\"")

        val past = requireNotNull(QuickNoteAssistantResponseParser.parse(pastResponse, "过去时刻", now))
        val future = requireNotNull(QuickNoteAssistantResponseParser.parse(futureResponse, "未来时刻", now))

        assertEquals(AnniversaryType.COUNT_UP, past.anniversaries.single().type)
        assertEquals(AnniversaryRepeatRule.NONE, past.anniversaries.single().repeatRule)
        assertEquals(AnniversaryType.COUNTDOWN, future.anniversaries.single().type)
    }

    private companion object {
        val SINGLE_TODO_RESPONSE = """
            {
              "schema":"controlfree.quick-note-assistant.v1",
              "ledger_entries":[],
              "todo_items":[{
                "title":"提交报告",
                "due_at":"2026-07-27T07:00:00Z",
                "start_at":"2026-07-27T06:00:00Z",
                "duration_minutes":60,
                "priority":2,
                "recurrence":"NONE",
                "reminder_minutes_before":[10]
              }],
              "habits":[],
              "focus_sessions":[],
              "anniversaries":[],
              "advice":[],
              "warnings":[]
            }
        """.trimIndent()

        val VALID_RESPONSE = """
            {
              "schema":"controlfree.quick-note-assistant.v1",
              "ledger_entries":[{
                "title":"午餐",
                "amount_fen":2800,
                "is_estimated":false,
                "direction":"EXPENSE",
                "category":"FOOD",
                "occurred_at":"2026-07-26T00:30:00Z",
                "confidence_percent":96,
                "emotion":"刚需",
                "necessity":"need",
                "note":"工作午餐"
              }],
              "todo_items":[{
                "title":"提交报告",
                "due_at":"2026-07-27T07:00:00Z",
                "start_at":"2026-07-27T06:00:00Z",
                "duration_minutes":60,
                "priority":2,
                "recurrence":"NONE",
                "reminder_minutes_before":[10]
              }],
              "habits":[{
                "name":"晨读",
                "recurrence":"DAILY",
                "weekdays":[],
                "reminder_times":["07:30"]
              }],
              "focus_sessions":[{
                "title":"阅读论文",
                "start_at":"",
                "duration_minutes":20
              }],
              "anniversaries":[{
                "title":"妈妈生日",
                "occurs_at":"2026-08-20T01:00:00Z",
                "type":"COUNTDOWN",
                "repeat_rule":"YEARLY",
                "reminder_minutes_before":[1440]
              }],
              "advice":["先完成报告再处理低优先级事项。"],
              "warnings":[]
            }
        """.trimIndent()
    }
}
