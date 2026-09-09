package com.example.controlfree.ui.todo.search

import com.example.controlfree.ui.todo.TodoSubTab
import java.time.LocalDate

enum class SuperSearchSource(
    val displayName: String,
    val tab: TodoSubTab
) {
    TODO("待办", TodoSubTab.TODO),
    CALENDAR("日程", TodoSubTab.CALENDAR),
    HABIT("习惯", TodoSubTab.HABIT),
    QUICK_NOTE("闪记", TodoSubTab.QUICK_NOTE),
    LEDGER("账本", TodoSubTab.LEDGER),
    ANNIVERSARY("时刻", TodoSubTab.ANNIVERSARY)
}

enum class SuperSearchFilter(val displayName: String) {
    ALL("全部"),
    TODO("待办"),
    CALENDAR("日程"),
    HABIT("习惯"),
    QUICK_NOTE("闪记"),
    LEDGER("账本"),
    ANNIVERSARY("时刻");

    fun accepts(source: SuperSearchSource): Boolean =
        this == ALL || name == source.name
}

data class SuperSearchResult(
    val stableKey: String,
    val source: SuperSearchSource,
    val entityId: String,
    val title: String,
    val searchableText: String,
    val snippet: String?,
    val metadata: String,
    val timestampEpochMillis: Long,
    val targetDate: LocalDate? = null,
    val score: Int = 0
)

data class SearchNavigationRequest(
    val revision: Long,
    val source: SuperSearchSource,
    val entityId: String,
    val targetDate: LocalDate? = null
) {
    val tab: TodoSubTab
        get() = source.tab
}
