package com.example.controlfree.widget

import com.example.controlfree.ui.todo.TodoSubTab

data class WidgetTodoNavigationRequest(
    val revision: Long,
    val subTab: TodoSubTab,
    val createTarget: WidgetCreateTarget? = null,
    val itemId: String? = null
)

internal data class WidgetTodoNavigationSnapshot(
    val revision: Long,
    val subTabValue: String,
    val createTargetValue: String?,
    val itemIdValue: String?
)

internal object WidgetTodoNavigationRoute {
    fun nextRequest(
        previous: WidgetTodoNavigationRequest?,
        subTabValue: String?,
        createTargetValue: String?,
        itemIdValue: String?
    ): WidgetTodoNavigationRequest? {
        val createTarget = WidgetCreateTarget.entries.firstOrNull {
            it.name == createTargetValue
        }
        val itemId = itemIdValue?.trim()?.takeIf(String::isNotEmpty)
        if (createTarget != null && itemId != null) return null

        val subTab = when (createTarget) {
            WidgetCreateTarget.TODO -> TodoSubTab.TODO
            WidgetCreateTarget.QUICK_NOTE -> TodoSubTab.QUICK_NOTE
            null -> TodoSubTab.fromNavigationValue(subTabValue)
        } ?: return null
        if (subTab !in SUPPORTED_SUB_TABS || (createTarget == null && itemId == null)) return null

        val revision = when (previous?.revision) {
            null, Long.MAX_VALUE -> 1L
            else -> previous.revision + 1L
        }
        return WidgetTodoNavigationRequest(
            revision = revision,
            subTab = subTab,
            createTarget = createTarget,
            itemId = itemId
        )
    }

    fun snapshot(request: WidgetTodoNavigationRequest): WidgetTodoNavigationSnapshot =
        WidgetTodoNavigationSnapshot(
            revision = request.revision,
            subTabValue = request.subTab.name,
            createTargetValue = request.createTarget?.name,
            itemIdValue = request.itemId
        )

    fun restore(snapshot: WidgetTodoNavigationSnapshot?): WidgetTodoNavigationRequest? {
        if (snapshot == null || snapshot.revision <= 0L) return null
        return nextRequest(
            previous = null,
            subTabValue = snapshot.subTabValue,
            createTargetValue = snapshot.createTargetValue,
            itemIdValue = snapshot.itemIdValue
        )?.copy(revision = snapshot.revision)
    }

    fun consume(
        current: WidgetTodoNavigationRequest?,
        consumed: WidgetTodoNavigationRequest
    ): WidgetTodoNavigationRequest? = current?.takeUnless { it == consumed }

    private val SUPPORTED_SUB_TABS = setOf(TodoSubTab.TODO, TodoSubTab.QUICK_NOTE)
}
