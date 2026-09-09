package com.example.controlfree.data

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process

class UsageAccessManager(private val context: Context) : ForegroundObservationSource {
    private val usageStatsManager =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    private val stateLock = Any()
    private var stateRevision = 0L
    private var observationWindowAnchorMillis: Long? = null
    private var reducerState = ForegroundReducerState()
    private var lastEventQueryMillis = 0L

    fun hasUsageAccess(): Boolean = try {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        ) == AppOpsManager.MODE_ALLOWED
    } catch (_: RuntimeException) {
        false
    }

    override fun beginObservationWindow(anchorWallMillis: Long) {
        require(anchorWallMillis >= 0L) { "anchorWallMillis must not be negative" }
        synchronized(stateLock) {
            stateRevision = nextRevision(stateRevision)
            observationWindowAnchorMillis = anchorWallMillis
            reducerState = ForegroundReducerState()
            lastEventQueryMillis = 0L
        }
    }

    override fun resetObservationWindow() {
        synchronized(stateLock) {
            stateRevision = nextRevision(stateRevision)
            observationWindowAnchorMillis = null
            reducerState = ForegroundReducerState()
            lastEventQueryMillis = 0L
        }
    }

    override fun captureObservationLease(): Long = synchronized(stateLock) {
        check(observationWindowAnchorMillis != null) { "Observation window is not active" }
        stateRevision
    }

    /** 同步读取系统 UsageStats，仅应由 [ForegroundObservationWorker] 的查询线程调用。 */
    override fun getForegroundObservation(
        nowMillis: Long,
        observationLease: Long
    ): ForegroundObservation {
        require(nowMillis >= 0L) { "nowMillis must not be negative" }
        val querySnapshot = snapshotForQuery(nowMillis, observationLease)
            ?: return ForegroundObservation.unavailable(ForegroundObservationStatus.QUERY_FAILED)
        if (!hasUsageAccess()) {
            clearQueryStateIfCurrent(querySnapshot)
            return ForegroundObservation.unavailable(ForegroundObservationStatus.ACCESS_DENIED)
        }

        val queryStart = if (querySnapshot.lastQueryMillis > 0L) {
            (querySnapshot.lastQueryMillis - EVENT_OVERLAP_MILLIS)
                .coerceAtLeast(querySnapshot.anchorMillis)
        } else {
            querySnapshot.anchorMillis
        }
        val events = try {
            usageStatsManager.queryEvents(queryStart, nowMillis)
        } catch (_: RuntimeException) {
            clearQueryStateIfCurrent(querySnapshot)
            return ForegroundObservation.unavailable(ForegroundObservationStatus.QUERY_FAILED)
        }
        val event = UsageEvents.Event()
        val queriedEvents = mutableListOf<ForegroundAppEvent>()

        try {
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                normalizeEvent(event)?.let(queriedEvents::add)
            }
        } catch (_: RuntimeException) {
            clearQueryStateIfCurrent(querySnapshot)
            return ForegroundObservation.unavailable(ForegroundObservationStatus.QUERY_FAILED)
        }

        val reduction = ForegroundEventReducer.reduce(querySnapshot.reducerState, queriedEvents)
        val wasCommitted = synchronized(stateLock) {
            if (stateRevision != querySnapshot.revision) {
                false
            } else {
                reducerState = reduction.state
                lastEventQueryMillis = nowMillis
                stateRevision = nextRevision(stateRevision)
                true
            }
        }
        if (!wasCommitted) {
            return ForegroundObservation.unavailable(ForegroundObservationStatus.QUERY_FAILED)
        }
        return ForegroundObservation(
            packageName = reduction.state.currentPackageName,
            transitionTimestampMillis = reduction.state.transitionTimestampMillis,
            newEvents = reduction.newEvents,
            status = ForegroundObservationStatus.AVAILABLE,
            observedAtEpochMillis = nowMillis
        )
    }

    fun getForegroundObservation(): ForegroundObservation =
        getForegroundObservation(System.currentTimeMillis())

    fun getForegroundObservation(nowMillis: Long): ForegroundObservation {
        val lease = try {
            captureObservationLease()
        } catch (_: IllegalStateException) {
            beginObservationWindow(nowMillis.coerceAtLeast(0L))
            captureObservationLease()
        }
        return getForegroundObservation(nowMillis, lease)
    }

    fun getForegroundPackage(nowMillis: Long = System.currentTimeMillis()): String? =
        getForegroundObservation(nowMillis).packageName

    private fun normalizeEvent(event: UsageEvents.Event): ForegroundAppEvent? {
        val kind = when (event.eventType) {
            UsageEvents.Event.MOVE_TO_FOREGROUND -> ForegroundEventKind.FOREGROUND
            UsageEvents.Event.MOVE_TO_BACKGROUND -> ForegroundEventKind.BACKGROUND
            else -> return null
        }
        val packageName = event.packageName?.takeIf(String::isNotBlank) ?: return null
        return ForegroundAppEvent(
            packageName = packageName,
            timestampMillis = event.timeStamp,
            kind = kind
        )
    }

    private fun snapshotForQuery(
        nowMillis: Long,
        expectedRevision: Long
    ): QuerySnapshot? = synchronized(stateLock) {
        if (stateRevision != expectedRevision) return@synchronized null
        val currentAnchor = observationWindowAnchorMillis
        if (
            currentAnchor == null ||
            currentAnchor > nowMillis ||
            lastEventQueryMillis > nowMillis
        ) {
            stateRevision = nextRevision(stateRevision)
            observationWindowAnchorMillis = currentAnchor?.let { minOf(it, nowMillis) } ?: nowMillis
            reducerState = ForegroundReducerState()
            lastEventQueryMillis = 0L
            return@synchronized null
        }
        QuerySnapshot(
            revision = stateRevision,
            anchorMillis = requireNotNull(observationWindowAnchorMillis),
            lastQueryMillis = lastEventQueryMillis,
            reducerState = reducerState
        )
    }

    private fun clearQueryStateIfCurrent(snapshot: QuerySnapshot) {
        synchronized(stateLock) {
            if (stateRevision != snapshot.revision) return
            reducerState = ForegroundReducerState()
            lastEventQueryMillis = 0L
            stateRevision = nextRevision(stateRevision)
        }
    }

    companion object {
        private const val EVENT_OVERLAP_MILLIS = 1_000L

        private fun nextRevision(current: Long): Long =
            if (current == Long.MAX_VALUE) 1L else current + 1L
    }

    private data class QuerySnapshot(
        val revision: Long,
        val anchorMillis: Long,
        val lastQueryMillis: Long,
        val reducerState: ForegroundReducerState
    )
}
