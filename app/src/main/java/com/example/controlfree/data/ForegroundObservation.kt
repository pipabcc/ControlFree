package com.example.controlfree.data

enum class ForegroundEventKind {
    FOREGROUND,
    BACKGROUND
}

data class ForegroundAppEvent(
    val packageName: String,
    val timestampMillis: Long,
    val kind: ForegroundEventKind
)

enum class ForegroundObservationStatus {
    AVAILABLE,
    ACCESS_DENIED,
    QUERY_FAILED,
    TIMEOUT,
    CIRCUIT_OPEN
}

data class ForegroundObservation(
    val packageName: String?,
    val transitionTimestampMillis: Long,
    val newEvents: List<ForegroundAppEvent>,
    val status: ForegroundObservationStatus,
    /** 本次 UsageEvents 查询的墙钟终点，用于跨轮询结算完整事件区间。 */
    val observedAtEpochMillis: Long = transitionTimestampMillis
) {
    init {
        require(transitionTimestampMillis >= 0L) { "前台转场时间无效" }
        require(observedAtEpochMillis >= 0L) { "前台观察时间无效" }
    }

    val isAvailable: Boolean
        get() = status == ForegroundObservationStatus.AVAILABLE

    companion object {
        fun unavailable(status: ForegroundObservationStatus) = ForegroundObservation(
            packageName = null,
            transitionTimestampMillis = 0L,
            newEvents = emptyList(),
            status = status,
            observedAtEpochMillis = 0L
        )
    }
}

internal data class ForegroundReducerState(
    val currentPackageName: String? = null,
    val transitionTimestampMillis: Long = 0L,
    val recentEventKeys: List<ForegroundEventKey> = emptyList()
)

internal data class ForegroundReduction(
    val state: ForegroundReducerState,
    val newEvents: List<ForegroundAppEvent>
)

internal data class ForegroundEventKey(
    val packageName: String,
    val timestampMillis: Long,
    val kind: ForegroundEventKind
)

/**
 * Reduces overlapping UsageEvents queries without replaying the same transition.
 */
internal object ForegroundEventReducer {
    fun reduce(
        previous: ForegroundReducerState,
        queriedEvents: List<ForegroundAppEvent>
    ): ForegroundReduction {
        if (queriedEvents.isEmpty()) return ForegroundReduction(previous, emptyList())

        val knownKeys = previous.recentEventKeys.toMutableSet()
        val newEvents = ArrayList<ForegroundAppEvent>(queriedEvents.size)
        val newKeys = ArrayList<ForegroundEventKey>(queriedEvents.size)

        // 系统通常按时间返回事件；这里仍排序，确保厂商乱序和延迟测试结果稳定。
        queriedEvents.withIndex()
            .sortedWith(compareBy<IndexedValue<ForegroundAppEvent>> { it.value.timestampMillis }
                .thenBy { it.index })
            .forEach { indexed ->
                val event = indexed.value
                if (event.packageName.isBlank() || event.timestampMillis < 0L) return@forEach
                val key = ForegroundEventKey(
                    packageName = event.packageName,
                    timestampMillis = event.timestampMillis,
                    kind = event.kind
                )
                if (knownKeys.add(key)) {
                    newEvents += event
                    newKeys += key
                }
            }

        var currentPackage = previous.currentPackageName
        var transitionTimestamp = previous.transitionTimestampMillis
        newEvents.forEach { event ->
            if (event.timestampMillis < transitionTimestamp) return@forEach
            when (event.kind) {
                ForegroundEventKind.FOREGROUND -> {
                    currentPackage = event.packageName
                    transitionTimestamp = event.timestampMillis
                }

                ForegroundEventKind.BACKGROUND -> {
                    if (event.packageName == currentPackage) {
                        currentPackage = null
                        transitionTimestamp = event.timestampMillis
                    }
                }
            }
        }

        val retainedKeys = (previous.recentEventKeys + newKeys)
            .takeLast(MAX_RECENT_EVENT_KEYS)
        return ForegroundReduction(
            state = ForegroundReducerState(
                currentPackageName = currentPackage,
                transitionTimestampMillis = transitionTimestamp,
                recentEventKeys = retainedKeys
            ),
            newEvents = newEvents
        )
    }

    private const val MAX_RECENT_EVENT_KEYS = 256
}
