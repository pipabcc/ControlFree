package com.example.controlfree.todo

import com.example.controlfree.knowledge.StrictKnowledgeJson

// 账目来源，用于区分用户手动录入与 AI 整理结果。
enum class LedgerEntrySourceType(val storedValue: String) {
    AI("AI"),
    MANUAL("MANUAL");

    companion object {
        fun fromStoredValue(value: String): LedgerEntrySourceType? =
            values().firstOrNull { it.storedValue == value }
    }
}

// 收支方向
enum class LedgerDirection(val storedValue: String) {
    EXPENSE("EXPENSE"),   // 支出
    INCOME("INCOME");     // 收入

    companion object {
        fun fromStoredValue(value: String): LedgerDirection? =
            values().firstOrNull { it.storedValue == value }

        fun fromString(value: String): LedgerDirection =
            fromStoredValue(value) ?: EXPENSE
    }
}

// 账单分类
enum class LedgerCategory(
    val storedValue: String,
    val displayName: String,
    val emoji: String,
    val direction: LedgerDirection
) {
    // 支出分类
    FOOD("FOOD", "餐饮", "🍜", LedgerDirection.EXPENSE),
    TRANSPORT("TRANSPORT", "交通", "🚗", LedgerDirection.EXPENSE),
    SHOPPING("SHOPPING", "购物", "🛍️", LedgerDirection.EXPENSE),
    ENTERTAINMENT("ENTERTAINMENT", "娱乐", "🎮", LedgerDirection.EXPENSE),
    HOUSING("HOUSING", "居住", "🏠", LedgerDirection.EXPENSE),
    MEDICAL("MEDICAL", "医疗", "💊", LedgerDirection.EXPENSE),
    EDUCATION("EDUCATION", "教育", "📚", LedgerDirection.EXPENSE),
    SOCIAL("SOCIAL", "人情", "💝", LedgerDirection.EXPENSE),
    EXPENSE_OTHER("EXPENSE_OTHER", "其他", "📦", LedgerDirection.EXPENSE),
    
    // 收入分类
    SALARY("SALARY", "工资", "💰", LedgerDirection.INCOME),
    PART_TIME("PART_TIME", "兼职", "💼", LedgerDirection.INCOME),
    INVESTMENT("INVESTMENT", "理财", "📈", LedgerDirection.INCOME),
    RED_PACKET("RED_PACKET", "红包", "🧧", LedgerDirection.INCOME),
    INCOME_OTHER("INCOME_OTHER", "其他", "💵", LedgerDirection.INCOME);

    companion object {
        fun fromStoredValue(value: String): LedgerCategory? =
            values().firstOrNull { it.storedValue == value }

        fun fromString(value: String): LedgerCategory =
            fromStoredValue(value) ?: EXPENSE_OTHER
    }
}

// DAO 需要的临时汇总数据结构
data class DirectionTotal(
    val direction: String,
    val total: Long
)

data class CategoryTotal(
    val category: String,
    val total: Long
)

// 月度统计汇总
data class LedgerMonthSummary(
    val totalExpense: Long,
    val totalIncome: Long,
    val balance: Long,
    val categoryTotals: List<CategoryTotal>
)

/**
 * Repository 接收的账本写入命令。金额始终是分，避免在数据层重新引入浮点数。
 */
data class LedgerEntryDraft(
    val title: String,
    val amountFen: Long,
    val direction: LedgerDirection,
    val category: LedgerCategory,
    val occurredAtEpochMillis: Long,
    val isEstimated: Boolean,
    val aiConfidence: Float,
    val emotion: String,
    val necessity: String,
    val note: String? = null,
    val warnings: List<String> = emptyList()
)

data class LedgerTodoDraft(
    val content: String,
    val dueDateEpochMillis: Long? = null
)

data class LedgerRoutingSaveResult(
    val batchId: String,
    val ledgerEntryIds: List<String>,
    val todoIds: List<String>,
    val wasCreated: Boolean
)

/** 严格把用户输入的元转换成分；不允许舍入或溢出静默发生。 */
object LedgerAmountCodec {
    private const val MAX_AMOUNT_FEN = 100_000_000_000L

    fun isValidFen(amountFen: Long): Boolean = amountFen in 1..MAX_AMOUNT_FEN

    fun parseFen(text: String): Long? = runCatching {
        val normalized = text.trim()
        if (normalized.isEmpty() || !DECIMAL_PATTERN.matches(normalized)) {
            return null
        }
        val decimal = java.math.BigDecimal(normalized)
            .setScale(2, java.math.RoundingMode.UNNECESSARY)
        val fen = decimal.movePointRight(2).longValueExact()
        fen.takeIf(::isValidFen)
    }.getOrNull()

    fun formatFen(amountFen: Long): String =
        java.math.BigDecimal.valueOf(amountFen, 2).toPlainString()

    private val DECIMAL_PATTERN = Regex("\\d+(?:\\.\\d{1,2})?")
}

internal object LedgerWarningCodec {
    fun encode(warnings: List<String>): String? {
        val normalized = warnings.map { it.trim() }.filter { it.isNotEmpty() }
        if (normalized.isEmpty()) return null
        return normalized.joinToString(prefix = "[", postfix = "]", separator = ",") {
            "\"${escape(it)}\""
        }
    }

    /**
     * 校验并解码已落库的警告 JSON。账目实体也可能来自编辑/恢复入口，不能只依赖
     * AI 解析器保证格式，否则手工写入会绕过协议校验。
     */
    fun decode(encoded: String): List<String>? {
        if (encoded.length > MAX_JSON_LENGTH) return null
        val root = StrictKnowledgeJson.parseObject(
            "{\"warnings\":$encoded}",
            StrictKnowledgeJson.Limits(
                maxJsonChars = MAX_JSON_LENGTH + 16,
                maxStringChars = MAX_WARNING_LENGTH,
                maxArrayItems = MAX_WARNING_COUNT,
                maxDepth = 3
            )
        ) ?: return null
        val values = (root["warnings"] as? StrictKnowledgeJson.Value.ArrayValue)?.values
            ?: return null
        return values.map { value ->
            (value as? StrictKnowledgeJson.Value.StringValue)?.value
                ?.trim()
                ?.takeIf { it.isNotEmpty() && it.length <= MAX_WARNING_LENGTH }
                ?: return null
        }
    }

    private fun escape(value: String): String = buildString(value.length + 8) {
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
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
    }

    private const val MAX_WARNING_COUNT = 20
    private const val MAX_WARNING_LENGTH = 100
    private const val MAX_JSON_LENGTH = 4_096
}
