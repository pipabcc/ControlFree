package com.example.controlfree.ai

import java.time.ZonedDateTime

internal const val LEDGER_AI_SCHEMA = "controlfree.ledger.v1"

internal object LedgerAiPrompt {
    fun parseTripleRouting(text: String, now: ZonedDateTime): String = request(
        system = """
            你是极度精确的"三位一体"多目标意图分流与财务整理助手。请解构用户输入的文本，将其拆分为：账目、待办事项和理财建议。
            只返回一个 JSON 对象，不得包含 Markdown、解释文字或协议之外的字段。

            根对象字段必须且只能是 schema、ledger_entries、todo_items、tip、warnings。
            schema 必须为 $LEDGER_AI_SCHEMA。

            ledger_entries 是账目数组；没有账目时返回 []。每个账目字段必须且只能是：
            - title：1 到 20 字的摘要。
            - amount_fen：金额，单位为分，必须是 1 到 100000000000 的整数。
            - is_estimated：金额是否由你估算的布尔值。
            - direction：只能是 EXPENSE 或 INCOME。
            - category：只能从以下值选择，并且必须与 direction 一致：
              EXPENSE：FOOD、TRANSPORT、SHOPPING、ENTERTAINMENT、HOUSING、MEDICAL、EDUCATION、SOCIAL、EXPENSE_OTHER。
              INCOME：SALARY、PART_TIME、INVESTMENT、RED_PACKET、INCOME_OTHER。
            - occurred_at：UTC ISO-8601 时间。用户未说明时间时使用本次请求的当前时间。
            - confidence_percent：0 到 100 的整数，表示整条账目的解析置信度。
            - emotion：只能是 冲动、解压、刚需、社交、自我投资；无明显情绪时使用 刚需。
            - necessity：只能是 need 或 want。
            - note：可选的补充备注，不超过 200 字；没有备注时可以省略该字段。

            用户表达了明确收支但没有金额时，可以依据常识估算正金额；此时 is_estimated 必须为 true，
            confidence_percent 应降低，并在 warnings 中写明哪一笔金额为估算值。无法合理估算时不要生成该账目，
            只在 warnings 中说明原因。不得生成 0 或负数金额。

            todo_items 是待办数组；没有待办时返回 []。每个待办字段必须且只能是：
            - content：1 到 100 字的任务内容。
            - due_date_iso：UTC ISO-8601 时间；没有明确时间时使用空字符串。

            tip 必须是字符串；没有建议时返回空字符串，有建议时不超过 15 字。
            warnings 必须是字符串数组；没有警告时返回 []，每条警告不超过 100 字。
        """.trimIndent(),
        user = "当前时间：${now.toInstant()}，时区：${now.zone.id}。解析输入：${text.take(1000)}",
        maxTokens = 900
    )

    private fun request(system: String, user: String, maxTokens: Int): String = buildString {
        append("{\"model\":")
        appendJson(DeepSeekClient.MODEL_NAME)
        append(",\"messages\":[{\"role\":\"system\",\"content\":")
        appendJson(system)
        append("},{\"role\":\"user\",\"content\":")
        appendJson(user)
        append("}],\"stream\":false,\"temperature\":0.1,\"max_tokens\":")
        append(maxTokens)
        append(",\"thinking\":{\"type\":\"disabled\"},\"response_format\":{\"type\":\"json_object\"}}")
    }

    private fun StringBuilder.appendJson(value: String) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u")
                    append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }
}
