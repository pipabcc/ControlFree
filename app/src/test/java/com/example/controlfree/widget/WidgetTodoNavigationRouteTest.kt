package com.example.controlfree.widget

import com.example.controlfree.ui.todo.TodoSubTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WidgetTodoNavigationRouteTest {
    @Test
    fun createTargetDeterminesDestinationAndAdvancesRevision() {
        val previous = WidgetTodoNavigationRequest(7L, TodoSubTab.TODO, itemId = "old")

        val request = WidgetTodoNavigationRoute.nextRequest(
            previous = previous,
            subTabValue = TodoSubTab.TODO.name,
            createTargetValue = WidgetCreateTarget.QUICK_NOTE.name,
            itemIdValue = null
        )

        assertEquals(8L, request?.revision)
        assertEquals(TodoSubTab.QUICK_NOTE, request?.subTab)
        assertEquals(WidgetCreateTarget.QUICK_NOTE, request?.createTarget)
    }

    @Test
    fun itemRequestUsesListSubTabAndNormalizesId() {
        val request = WidgetTodoNavigationRoute.nextRequest(
            previous = null,
            subTabValue = TodoSubTab.TODO.name,
            createTargetValue = null,
            itemIdValue = "  todo-1  "
        )

        assertEquals(TodoSubTab.TODO, request?.subTab)
        assertEquals("todo-1", request?.itemId)
    }

    @Test
    fun rejectsAmbiguousOrUnsupportedRequests() {
        assertNull(
            WidgetTodoNavigationRoute.nextRequest(
                previous = null,
                subTabValue = TodoSubTab.TODO.name,
                createTargetValue = WidgetCreateTarget.TODO.name,
                itemIdValue = "todo-1"
            )
        )
        assertNull(
            WidgetTodoNavigationRoute.nextRequest(
                previous = null,
                subTabValue = TodoSubTab.HABIT.name,
                createTargetValue = null,
                itemIdValue = "habit-1"
            )
        )
    }

    @Test
    fun snapshotRestoresPendingRequestAcrossActivityRecreation() {
        val pending = WidgetTodoNavigationRequest(
            revision = 12L,
            subTab = TodoSubTab.QUICK_NOTE,
            createTarget = WidgetCreateTarget.QUICK_NOTE
        )

        val restored = WidgetTodoNavigationRoute.restore(
            WidgetTodoNavigationRoute.snapshot(pending)
        )

        assertEquals(pending, restored)
    }

    @Test
    fun consumesOnlyTheExactCurrentRequest() {
        val current = WidgetTodoNavigationRequest(
            revision = 4L,
            subTab = TodoSubTab.TODO,
            itemId = "todo-4"
        )
        val stale = current.copy(revision = 3L)

        assertEquals(current, WidgetTodoNavigationRoute.consume(current, stale))
        assertNull(WidgetTodoNavigationRoute.consume(current, current))
    }

    @Test
    fun rejectsCorruptSavedRequest() {
        assertNull(
            WidgetTodoNavigationRoute.restore(
                WidgetTodoNavigationSnapshot(
                    revision = 0L,
                    subTabValue = TodoSubTab.TODO.name,
                    createTargetValue = WidgetCreateTarget.TODO.name,
                    itemIdValue = null
                )
            )
        )
    }
}
