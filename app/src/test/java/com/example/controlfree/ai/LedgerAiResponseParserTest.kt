package com.example.controlfree.ai

import com.example.controlfree.todo.LedgerCategory
import com.example.controlfree.todo.LedgerDirection
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerAiResponseParserTest {
    @Test
    fun `严格解析多账目待办提示与警告`() {
        val result = LedgerAiResponseParser.parse(
            """
            {
              "schema":"controlfree.ledger.v1",
              "ledger_entries":[
                {
                  "title":"午餐",
                  "amount_fen":2800,
                  "is_estimated":false,
                  "direction":"EXPENSE",
                  "category":"FOOD",
                  "occurred_at":"2026-07-23T04:00:00Z",
                  "confidence_percent":95,
                  "emotion":"刚需",
                  "necessity":"need"
                },
                {
                  "title":"工资",
                  "amount_fen":100000000000,
                  "is_estimated":false,
                  "direction":"INCOME",
                  "category":"SALARY",
                  "occurred_at":"2026-07-23T05:00:00Z",
                  "confidence_percent":100,
                  "emotion":"刚需",
                  "necessity":"need"
                }
              ],
              "todo_items":[
                {
                  "content":"月底核对账单",
                  "due_date_iso":"2026-07-31T12:00:00Z"
                }
              ],
              "tip":"量入为出",
              "warnings":[]
            }
            """.trimIndent()
        )

        assertNotNull(result)
        assertEquals(2, result?.ledgerEntries?.size)
        val expense = result!!.ledgerEntries.first()
        assertEquals(2_800L, expense.amountFen)
        assertEquals(LedgerDirection.EXPENSE, expense.direction)
        assertEquals(LedgerCategory.FOOD, expense.category)
        assertEquals(Instant.parse("2026-07-23T04:00:00Z").toEpochMilli(), expense.occurredAtEpochMillis)
        assertEquals(0.95f, expense.confidence, 0.0001f)
        assertEquals(100_000_000_000L, result.ledgerEntries.last().amountFen)
        assertEquals(1f, result.ledgerEntries.last().confidence, 0f)
        assertEquals(Instant.parse("2026-07-31T12:00:00Z").toEpochMilli(), result.todoItems.single().dueDateEpochMillis)
        assertEquals("量入为出", result.tip)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `保留AI估算并要求明确警告`() {
        val estimated = validSingleLedger(
            isEstimated = true,
            warnings = "[\"奶茶金额为估算值\"]"
        )
        val result = LedgerAiResponseParser.parse(estimated)

        assertEquals(true, result?.ledgerEntries?.single()?.isEstimated)
        assertEquals(listOf("奶茶金额为估算值"), result?.warnings)
        assertNull(
            LedgerAiResponseParser.parse(
                validSingleLedger(isEstimated = true, warnings = "[]")
            )
        )
    }

    @Test
    fun `拒绝错误schema缺失字段额外字段与JSON外围文本`() {
        val valid = validSingleLedger()

        assertNull(
            LedgerAiResponseParser.parse(
                valid.replace("controlfree.ledger.v1", "controlfree.ledger.v2")
            )
        )
        assertNull(
            LedgerAiResponseParser.parse(
                valid.replace(Regex(",\\s*\"warnings\":\\[\\]"), "")
            )
        )
        assertNull(
            LedgerAiResponseParser.parse(
                valid.replace("\"warnings\":[]", "\"warnings\":[],\"extra\":true")
            )
        )
        assertNull(LedgerAiResponseParser.parse("```json $valid ```"))
    }

    @Test
    fun `严格限制正整数金额上限`() {
        listOf("0", "-1", "12.5", "100000000001", "9223372036854775807").forEach { amount ->
            assertNull(
                "amount_fen=$amount 应被拒绝",
                LedgerAiResponseParser.parse(validSingleLedger(amountFen = amount))
            )
        }
    }

    @Test
    fun `拒绝收支分类不一致及未知枚举`() {
        assertNull(
            LedgerAiResponseParser.parse(
                validSingleLedger(direction = "INCOME", category = "FOOD")
            )
        )
        assertNull(
            LedgerAiResponseParser.parse(
                validSingleLedger(direction = "EXPENSE", category = "UNKNOWN")
            )
        )
        assertNull(
            LedgerAiResponseParser.parse(
                validSingleLedger(emotion = "随意")
            )
        )
        assertNull(
            LedgerAiResponseParser.parse(
                validSingleLedger(necessity = "maybe")
            )
        )
    }

    @Test
    fun `拒绝非法发生时间和越界置信度`() {
        assertNull(
            LedgerAiResponseParser.parse(
                validSingleLedger(occurredAt = "明天下午")
            )
        )
        listOf("-1", "101", "99.5").forEach { confidence ->
            assertNull(
                "confidence_percent=$confidence 应被拒绝",
                LedgerAiResponseParser.parse(validSingleLedger(confidencePercent = confidence))
            )
        }
    }

    @Test
    fun `严格校验待办截止时间与字段集合`() {
        assertNull(
            LedgerAiResponseParser.parse(
                responseWithTodo(dueDate = "not-an-instant")
            )
        )
        assertNull(
            LedgerAiResponseParser.parse(
                responseWithTodo(dueDate = "")
                    .replace("\"due_date_iso\":\"\"", "\"due_date_iso\":\"\",\"extra\":1")
            )
        )
        listOf("true", "false").forEach { legacyValue ->
            assertNull(
                "旧版 has_reminder=$legacyValue 不属于 controlfree.ledger.v1 严格待办字段",
                LedgerAiResponseParser.parse(
                    responseWithTodo(dueDate = "")
                        .replace(
                            "\"due_date_iso\":\"\"",
                            "\"due_date_iso\":\"\",\"has_reminder\":$legacyValue"
                        )
                )
            )
        }
        val withoutDueDate = LedgerAiResponseParser.parse(responseWithTodo(dueDate = ""))
        assertNotNull(withoutDueDate)
        assertNull(withoutDueDate!!.todoItems.single().dueDateEpochMillis)
    }

    @Test
    fun `严格校验tip和warnings`() {
        assertNull(
            LedgerAiResponseParser.parse(
                validSingleLedger(tip = "这是一条超过十五个字符且不应被接受的理财建议")
            )
        )
        assertNull(
            LedgerAiResponseParser.parse(
                validSingleLedger(warnings = "[\"\"]")
            )
        )
        assertNull(
            LedgerAiResponseParser.parse(
                validSingleLedger(warnings = "[1]")
            )
        )
    }

    @Test
    fun `备注字段可选但存在时会严格校验长度并保留`() {
        val withNote = validSingleLedger()
            .replace("\"necessity\":\"need\"", "\"necessity\":\"need\",\"note\":\"工作午餐\"")
        assertEquals("工作午餐", LedgerAiResponseParser.parse(withNote)?.ledgerEntries?.single()?.note)

        val tooLong = "x".repeat(201)
        assertNull(
            LedgerAiResponseParser.parse(
                validSingleLedger().replace(
                    "\"necessity\":\"need\"",
                    "\"necessity\":\"need\",\"note\":\"$tooLong\""
                )
            )
        )
    }

    @Test
    fun `提示词固定ledger v1并声明完整协议`() {
        val now = ZonedDateTime.of(2026, 7, 23, 12, 0, 0, 0, ZoneId.of("Asia/Shanghai"))
        val request = LedgerAiPrompt.parseTripleRouting("午餐 28 元", now)

        assertTrue(request.contains("controlfree.ledger.v1"))
        assertFalse(request.contains("controlfree.ledger-triple-routing.v1"))
        assertTrue(request.contains("occurred_at"))
        assertTrue(request.contains("confidence_percent"))
        assertTrue(request.contains("warnings"))
        assertTrue(request.contains("note"))
        assertFalse(request.contains("has_reminder"))
    }

    private fun validSingleLedger(
        amountFen: String = "1500",
        isEstimated: Boolean = false,
        direction: String = "EXPENSE",
        category: String = "FOOD",
        occurredAt: String = "2026-07-23T04:00:00Z",
        confidencePercent: String = "90",
        emotion: String = "刚需",
        necessity: String = "need",
        tip: String = "",
        warnings: String = "[]"
    ): String = """
        {
          "schema":"controlfree.ledger.v1",
          "ledger_entries":[{
            "title":"奶茶",
            "amount_fen":$amountFen,
            "is_estimated":$isEstimated,
            "direction":"$direction",
            "category":"$category",
            "occurred_at":"$occurredAt",
            "confidence_percent":$confidencePercent,
            "emotion":"$emotion",
            "necessity":"$necessity"
          }],
          "todo_items":[],
          "tip":"$tip",
          "warnings":$warnings
        }
    """.trimIndent()

    private fun responseWithTodo(dueDate: String): String = """
        {
          "schema":"controlfree.ledger.v1",
          "ledger_entries":[],
          "todo_items":[{
            "content":"核对账单",
            "due_date_iso":"$dueDate"
          }],
          "tip":"",
          "warnings":[]
        }
    """.trimIndent()
}
