package com.example.controlfree.ai

import com.example.controlfree.productivity.quicknote.QuickNoteIntent
import com.example.controlfree.productivity.schedule.ScheduleCandidate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProductivityAiResponseParserTest {
    private val now = ZonedDateTime.of(2026, 7, 22, 10, 0, 0, 0, ZoneId.of("Asia/Shanghai"))

    @Test
    fun `严格解析合法闪念结果`() {
        val parsed = ProductivityAiResponseParser.parseQuickNote(
            raw = """{"schema":"controlfree.quick-note.v1","intent":"TODO","title":"完成报告","start_at":"2026-07-23T07:00:00Z","due_at":"2026-07-23T07:30:00Z","duration_minutes":30,"recurrence":"NONE","confidence_percent":92}""",
            originalText = "明天下午三点完成报告",
            now = now
        )

        assertEquals(QuickNoteIntent.TODO, parsed?.intent)
        assertEquals(30, parsed?.estimatedDurationMinutes)
    }

    @Test
    fun `拒绝额外字段和白名单外候选`() {
        val invalidNote = ProductivityAiResponseParser.parseQuickNote(
            raw = """{"schema":"controlfree.quick-note.v1","intent":"TODO","title":"报告","start_at":"","due_at":"","duration_minutes":0,"recurrence":"NONE","confidence_percent":80,"extra":true}""",
            originalText = "报告",
            now = now
        )
        val candidate = ScheduleCandidate("slot-1", 1_000L, 2_000L, 10, "本地")
        val invalidChoice = ProductivityAiResponseParser.parseScheduleChoice(
            """{"schema":"controlfree.schedule-choice.v1","candidate_id":"slot-2","reason":"更合适"}""",
            listOf(candidate)
        )

        assertNull(invalidNote)
        assertNull(invalidChoice)
    }

    @Test
    fun `只接受现有候选`() {
        val candidate = ScheduleCandidate("slot-1", 1_000L, 2_000L, 10, "本地")
        val choice = ProductivityAiResponseParser.parseScheduleChoice(
            """{"schema":"controlfree.schedule-choice.v1","candidate_id":"slot-1","reason":"符合你的历史高效时段"}""",
            listOf(candidate)
        )

        assertEquals(candidate, choice?.first)
    }
}
