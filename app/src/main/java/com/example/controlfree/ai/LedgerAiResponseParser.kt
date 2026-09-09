package com.example.controlfree.ai

import com.example.controlfree.knowledge.StrictKnowledgeJson
import com.example.controlfree.knowledge.strictArray
import com.example.controlfree.knowledge.strictInt
import com.example.controlfree.knowledge.strictObject
import com.example.controlfree.knowledge.strictString
import com.example.controlfree.todo.LedgerCategory
import com.example.controlfree.todo.LedgerDirection
import com.example.controlfree.todo.LedgerAmountCodec
import java.time.Instant

data class ParsedLedgerEntry(
    val title: String,
    val amountFen: Long,
    val isEstimated: Boolean,
    val direction: LedgerDirection,
    val category: LedgerCategory,
    val emotion: String,
    val necessity: String,
    val note: String? = null,
    val occurredAtEpochMillis: Long = 0L,
    val confidence: Float = 0f
)

data class ParsedTodoItem(
    val content: String,
    val dueDateEpochMillis: Long?
)

data class ParsedTripleRoutingResult(
    val ledgerEntries: List<ParsedLedgerEntry>,
    val todoItems: List<ParsedTodoItem>,
    val tip: String?,
    val warnings: List<String> = emptyList()
)

internal object LedgerAiResponseParser {
    private val ROOT_KEYS = setOf("schema", "ledger_entries", "todo_items", "tip", "warnings")
    private val LEDGER_ENTRY_KEYS = setOf(
        "title",
        "amount_fen",
        "is_estimated",
        "direction",
        "category",
        "occurred_at",
        "confidence_percent",
        "emotion",
        "necessity"
    )
    private val LEDGER_ENTRY_KEYS_WITH_NOTE = LEDGER_ENTRY_KEYS + "note"
    private val TODO_ITEM_KEYS = setOf("content", "due_date_iso")
    private val VALID_EMOTIONS = setOf("冲动", "解压", "刚需", "社交", "自我投资")
    private val VALID_NECESSITIES = setOf("need", "want")

    private val LIMITS = StrictKnowledgeJson.Limits(
        maxJsonChars = 12_000,
        maxStringChars = 512,
        maxArrayItems = 20,
        maxDepth = 5
    )

    fun parse(raw: String): ParsedTripleRoutingResult? {
        val root = StrictKnowledgeJson.parseObject(raw, LIMITS) ?: return null
        if (root.keys != ROOT_KEYS || root.strictString("schema") != LEDGER_AI_SCHEMA) return null

        val ledgerEntries = root.strictArray("ledger_entries")?.map { value ->
            parseLedgerEntry(value) ?: return null
        } ?: return null
        val todoItems = root.strictArray("todo_items")?.map { value ->
            parseTodoItem(value) ?: return null
        } ?: return null
        val tipValue = root.strictString("tip")?.trim() ?: return null
        if (tipValue.length > MAX_TIP_CHARS) return null
        val warnings = root.strictArray("warnings")?.map { value ->
            val warning = (value as? StrictKnowledgeJson.Value.StringValue)?.value?.trim()
                ?.takeIf { it.isNotEmpty() && it.length <= MAX_WARNING_CHARS }
                ?: return null
            warning
        } ?: return null
        if (ledgerEntries.any(ParsedLedgerEntry::isEstimated) && warnings.isEmpty()) return null

        return ParsedTripleRoutingResult(
            ledgerEntries = ledgerEntries,
            todoItems = todoItems,
            tip = tipValue.ifEmpty { null },
            warnings = warnings
        )
    }

    private fun parseLedgerEntry(value: StrictKnowledgeJson.Value): ParsedLedgerEntry? {
        val entry = value.strictObject() ?: return null
        if (entry.keys != LEDGER_ENTRY_KEYS && entry.keys != LEDGER_ENTRY_KEYS_WITH_NOTE) return null

        val title = entry.strictString("title")?.trim()
            ?.takeIf { it.isNotEmpty() && it.length <= MAX_TITLE_CHARS }
            ?: return null
        val amountFen = entry.strictLong("amount_fen")
            ?.takeIf { LedgerAmountCodec.isValidFen(it) }
            ?: return null
        val isEstimated = entry.strictBoolean("is_estimated") ?: return null
        val direction = entry.strictString("direction")
            ?.let { LedgerDirection.fromStoredValue(it) }
            ?: return null
        val category = entry.strictString("category")
            ?.let { LedgerCategory.fromStoredValue(it) }
            ?: return null
        if (category.direction != direction) return null
        val occurredAtEpochMillis = entry.strictString("occurred_at")
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let(::parseInstantMillis)
            ?.takeIf { it >= 0L }
            ?: return null
        val confidencePercent = entry.strictInt("confidence_percent")
            ?.takeIf { it in 0..100 }
            ?: return null
        val emotion = entry.strictString("emotion")?.trim()
            ?.takeIf { it in VALID_EMOTIONS }
            ?: return null
        val necessity = entry.strictString("necessity")?.trim()
            ?.takeIf { it in VALID_NECESSITIES }
            ?: return null
        val note = entry["note"]?.let { value ->
            val normalized = (value as? StrictKnowledgeJson.Value.StringValue)?.value?.trim()
                ?: return null
            if (normalized.length > MAX_NOTE_CHARS) return null
            normalized.ifEmpty { null }
        }

        return ParsedLedgerEntry(
            title = title,
            amountFen = amountFen,
            isEstimated = isEstimated,
            direction = direction,
            category = category,
            emotion = emotion,
            necessity = necessity,
            note = note,
            occurredAtEpochMillis = occurredAtEpochMillis,
            confidence = confidencePercent / 100f
        )
    }

    private fun parseTodoItem(value: StrictKnowledgeJson.Value): ParsedTodoItem? {
        val item = value.strictObject() ?: return null
        if (item.keys != TODO_ITEM_KEYS) return null

        val content = item.strictString("content")?.trim()
            ?.takeIf { it.isNotEmpty() && it.length <= MAX_TODO_CONTENT_CHARS }
            ?: return null
        val rawDueDate = item.strictString("due_date_iso")?.trim() ?: return null
        val dueDateEpochMillis = rawDueDate.takeIf(String::isNotEmpty)
            ?.let(::parseInstantMillis)
            ?.takeIf { it >= 0L }
        if (rawDueDate.isNotEmpty() && dueDateEpochMillis == null) return null

        return ParsedTodoItem(
            content = content,
            dueDateEpochMillis = dueDateEpochMillis
        )
    }

    private fun parseInstantMillis(value: String): Long? =
        runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()

    private fun Map<String, StrictKnowledgeJson.Value>.strictLong(name: String): Long? =
        (this[name] as? StrictKnowledgeJson.Value.NumberValue)?.raw?.toLongOrNull()

    private fun Map<String, StrictKnowledgeJson.Value>.strictBoolean(name: String): Boolean? =
        (this[name] as? StrictKnowledgeJson.Value.BooleanValue)?.value

    private const val MAX_TITLE_CHARS = 20
    private const val MAX_NOTE_CHARS = 200
    private const val MAX_TODO_CONTENT_CHARS = 100
    private const val MAX_TIP_CHARS = 15
    private const val MAX_WARNING_CHARS = 100
}
