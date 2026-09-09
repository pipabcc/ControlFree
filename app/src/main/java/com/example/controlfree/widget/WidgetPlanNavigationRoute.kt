package com.example.controlfree.widget

enum class WidgetPlanDestination {
    MONITOR,
    FOCUS
}

data class WidgetPlanNavigationRequest(
    val revision: Long,
    val destination: WidgetPlanDestination,
    val planId: String
)

internal data class WidgetPlanNavigationSnapshot(
    val revision: Long,
    val destinationValue: String,
    val planIdValue: String
)

internal object WidgetPlanNavigationRoute {
    fun nextRequest(
        previous: WidgetPlanNavigationRequest?,
        destinationValue: String?,
        planIdValue: String?
    ): WidgetPlanNavigationRequest? {
        val destination = WidgetPlanDestination.entries.firstOrNull {
            it.name == destinationValue
        } ?: return null
        val planId = planIdValue
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it.length <= MAX_PLAN_ID_LENGTH }
            ?: return null
        val revision = when (previous?.revision) {
            null, Long.MAX_VALUE -> 1L
            else -> previous.revision + 1L
        }
        return WidgetPlanNavigationRequest(revision, destination, planId)
    }

    fun snapshot(request: WidgetPlanNavigationRequest): WidgetPlanNavigationSnapshot =
        WidgetPlanNavigationSnapshot(
            revision = request.revision,
            destinationValue = request.destination.name,
            planIdValue = request.planId
        )

    fun restore(snapshot: WidgetPlanNavigationSnapshot?): WidgetPlanNavigationRequest? {
        if (snapshot == null || snapshot.revision <= 0L) return null
        return nextRequest(
            previous = null,
            destinationValue = snapshot.destinationValue,
            planIdValue = snapshot.planIdValue
        )?.copy(revision = snapshot.revision)
    }

    fun consume(
        current: WidgetPlanNavigationRequest?,
        consumed: WidgetPlanNavigationRequest
    ): WidgetPlanNavigationRequest? = current?.takeUnless { it == consumed }

    private const val MAX_PLAN_ID_LENGTH = 256
}
