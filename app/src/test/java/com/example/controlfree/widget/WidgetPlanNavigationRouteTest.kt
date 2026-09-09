package com.example.controlfree.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WidgetPlanNavigationRouteTest {
    @Test
    fun createsRequestsForSupervisionAndFocusPlans() {
        val supervision = WidgetPlanNavigationRoute.nextRequest(
            previous = null,
            destinationValue = WidgetPlanDestination.MONITOR.name,
            planIdValue = " global-plan "
        )
        val focus = WidgetPlanNavigationRoute.nextRequest(
            previous = supervision,
            destinationValue = WidgetPlanDestination.FOCUS.name,
            planIdValue = "focus-plan"
        )

        assertEquals(WidgetPlanDestination.MONITOR, supervision?.destination)
        assertEquals("global-plan", supervision?.planId)
        assertEquals(1L, supervision?.revision)
        assertEquals(WidgetPlanDestination.FOCUS, focus?.destination)
        assertEquals(2L, focus?.revision)
    }

    @Test
    fun rejectsMissingPlanOrNonPlanDestination() {
        assertNull(
            WidgetPlanNavigationRoute.nextRequest(
                previous = null,
                destinationValue = WidgetPlanDestination.MONITOR.name,
                planIdValue = "  "
            )
        )
        assertNull(
            WidgetPlanNavigationRoute.nextRequest(
                previous = null,
                destinationValue = "TODO",
                planIdValue = "plan-1"
            )
        )
    }

    @Test
    fun snapshotRestoresAndConsumesExactlyOnce() {
        val pending = WidgetPlanNavigationRequest(
            revision = 9L,
            destination = WidgetPlanDestination.FOCUS,
            planId = "focus-9"
        )

        val restored = WidgetPlanNavigationRoute.restore(
            WidgetPlanNavigationRoute.snapshot(pending)
        )

        assertEquals(pending, restored)
        assertEquals(
            pending,
            WidgetPlanNavigationRoute.consume(pending, pending.copy(revision = 8L))
        )
        assertNull(WidgetPlanNavigationRoute.consume(pending, pending))
    }

    @Test
    fun rejectsCorruptSavedRequest() {
        assertNull(
            WidgetPlanNavigationRoute.restore(
                WidgetPlanNavigationSnapshot(
                    revision = -1L,
                    destinationValue = WidgetPlanDestination.MONITOR.name,
                    planIdValue = "plan"
                )
            )
        )
    }
}
