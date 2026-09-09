package com.example.controlfree.data

import java.time.LocalDate

internal enum class UsageTimelineEventType(val requiresPackage: Boolean) {
    ACTIVITY_RESUMED(true),
    ACTIVITY_PAUSED(true),
    END_OF_DAY(true),
    CONTINUE_PREVIOUS_DAY(true),
    USER_INTERACTION(true),
    SCREEN_INTERACTIVE(false),
    SCREEN_NON_INTERACTIVE(false),
    KEYGUARD_SHOWN(false),
    KEYGUARD_HIDDEN(false),
    DEVICE_SHUTDOWN(false),
    DEVICE_STARTUP(false);

    companion object {
        /**
         * Android 的 MOVE_TO_FOREGROUND/BACKGROUND 是 1/2 的旧别名；3/4 是隐藏但仍可能返回的
         * 跨日衔接事件。这里使用稳定的协议值，纯 Kotlin 测试无需依赖 Android stub。
         */
        fun fromAndroidEventType(eventType: Int): UsageTimelineEventType? = when (eventType) {
            1 -> ACTIVITY_RESUMED
            2 -> ACTIVITY_PAUSED
            3 -> END_OF_DAY
            4 -> CONTINUE_PREVIOUS_DAY
            7 -> USER_INTERACTION
            15 -> SCREEN_INTERACTIVE
            16 -> SCREEN_NON_INTERACTIVE
            17 -> KEYGUARD_SHOWN
            18 -> KEYGUARD_HIDDEN
            26 -> DEVICE_SHUTDOWN
            27 -> DEVICE_STARTUP
            else -> null
        }
    }
}

internal data class UsageTimelineEvent(
    val packageName: String?,
    val className: String?,
    val timestampMillis: Long,
    val type: UsageTimelineEventType
)

internal data class UsageDaySlice(
    val foregroundMillis: Long,
    val lastTimeUsedMillis: Long,
    val sessions: List<UsageSessionSlice>
)

internal data class UsageSessionSlice(
    val startMillis: Long,
    val endMillis: Long
) {
    init {
        require(endMillis > startMillis) { "使用片段结束时间必须晚于开始时间" }
    }

    val durationMillis: Long
        get() = endMillis - startMillis
}

internal data class UsagePackageTimeline(
    val days: Map<LocalDate, UsageDaySlice>
)

internal data class UsageTimelineAggregation(
    val packages: Map<String, UsagePackageTimeline>
)

/**
 * 从 UsageEvents 重建一条独占前台时间线。
 *
 * 任一时间片只归属于最近恢复的一个包；屏幕关闭、锁屏或设备关闭期间不累计。输入事件可包含
 * 统计窗口之前的回看数据，用于恢复窗口起点的前台状态。
 */
internal object UsageTimelineAggregator {
    private const val DISPLAY_SECOND_MILLIS = 1_000L

    fun aggregate(
        events: List<UsageTimelineEvent>,
        dayRanges: List<UsageDayRange>
    ): UsageTimelineAggregation {
        validateRanges(dayRanges)
        val windowStartMillis = dayRanges.first().startMillis
        val windowEndMillis = dayRanges.last().endMillis
        val orderedEvents = normalizeAndOrder(events)
        val focusState = FocusState()
        val mutableUsage = linkedMapOf<String, MutableMap<LocalDate, MutableUsage>>()
        var isDeviceAvailable = true
        var isScreenInteractive = true
        var isKeyguardShown = false
        var isFocusConfirmed = false
        // 代次只在真实语义边界变化；同包 Activity 的暂停/恢复不会自行切断连续性。
        var continuityGeneration = 0L
        var continuityPackageName: String? = null
        var cursorMillis = minOf(
            orderedEvents.firstOrNull()?.event?.timestampMillis ?: windowStartMillis,
            windowStartMillis
        )

        fun markContinuityBarrier() {
            continuityGeneration++
            continuityPackageName = null
        }

        fun preparePackageContinuity(packageName: String) {
            if (continuityPackageName?.let { it != packageName } == true) {
                markContinuityBarrier()
            }
            continuityPackageName = packageName
        }

        fun appendFocusedInterval(endMillis: Long) {
            if (
                endMillis <= cursorMillis ||
                !isDeviceAvailable ||
                !isScreenInteractive ||
                isKeyguardShown ||
                !isFocusConfirmed
            ) {
                return
            }
            val packageName = focusState.currentPackageName() ?: return
            continuityPackageName = packageName
            appendInterval(
                packageName = packageName,
                startMillis = cursorMillis,
                endMillis = endMillis,
                continuityGeneration = continuityGeneration,
                dayRanges = dayRanges,
                usage = mutableUsage
            )
        }

        for (indexedEvent in orderedEvents) {
            val event = indexedEvent.event
            if (event.timestampMillis > windowEndMillis) {
                appendFocusedInterval(windowEndMillis)
                cursorMillis = windowEndMillis
                break
            }
            if (event.timestampMillis > cursorMillis) {
                appendFocusedInterval(event.timestampMillis)
                cursorMillis = event.timestampMillis
            }

            when (event.type) {
                UsageTimelineEventType.ACTIVITY_RESUMED -> {
                    val packageName = requireNotNull(event.packageName)
                    preparePackageContinuity(packageName)
                    focusState.resume(
                        packageName = packageName,
                        className = event.className
                    )
                    isFocusConfirmed =
                        isDeviceAvailable && isScreenInteractive && !isKeyguardShown
                }
                UsageTimelineEventType.CONTINUE_PREVIOUS_DAY -> {
                    val packageName = requireNotNull(event.packageName)
                    markContinuityBarrier()
                    continuityPackageName = packageName
                    focusState.resume(
                        packageName = packageName,
                        className = event.className
                    )
                    isFocusConfirmed =
                        isDeviceAvailable && isScreenInteractive && !isKeyguardShown
                }
                UsageTimelineEventType.USER_INTERACTION -> {
                    val packageName = requireNotNull(event.packageName)
                    preparePackageContinuity(packageName)
                    focusState.confirm(
                        packageName = packageName,
                        className = event.className
                    )
                    isFocusConfirmed =
                        isDeviceAvailable && isScreenInteractive && !isKeyguardShown
                }
                UsageTimelineEventType.ACTIVITY_PAUSED -> {
                    focusState.pause(
                        packageName = requireNotNull(event.packageName),
                        className = event.className
                    )
                }
                UsageTimelineEventType.END_OF_DAY -> {
                    markContinuityBarrier()
                    focusState.pausePackage(requireNotNull(event.packageName))
                }
                UsageTimelineEventType.SCREEN_INTERACTIVE -> {
                    if (!isScreenInteractive || !isDeviceAvailable) markContinuityBarrier()
                    isDeviceAvailable = true
                    isScreenInteractive = true
                    isFocusConfirmed =
                        !isKeyguardShown && focusState.currentPackageName() != null
                }
                UsageTimelineEventType.SCREEN_NON_INTERACTIVE -> {
                    if (isScreenInteractive) markContinuityBarrier()
                    isScreenInteractive = false
                    isFocusConfirmed = false
                }
                UsageTimelineEventType.KEYGUARD_SHOWN -> {
                    if (!isKeyguardShown) markContinuityBarrier()
                    isKeyguardShown = true
                    isFocusConfirmed = false
                }
                UsageTimelineEventType.KEYGUARD_HIDDEN -> {
                    if (isKeyguardShown) markContinuityBarrier()
                    isKeyguardShown = false
                    isFocusConfirmed =
                        isDeviceAvailable &&
                            isScreenInteractive &&
                            focusState.currentPackageName() != null
                }
                UsageTimelineEventType.DEVICE_SHUTDOWN -> {
                    markContinuityBarrier()
                    isDeviceAvailable = false
                    isScreenInteractive = false
                    isKeyguardShown = true
                    isFocusConfirmed = false
                    focusState.clear()
                }
                UsageTimelineEventType.DEVICE_STARTUP -> {
                    markContinuityBarrier()
                    isDeviceAvailable = true
                    isScreenInteractive = false
                    isKeyguardShown = true
                    isFocusConfirmed = false
                    focusState.clear()
                }
            }
        }

        if (cursorMillis < windowEndMillis) appendFocusedInterval(windowEndMillis)
        return UsageTimelineAggregation(
            packages = mutableUsage.mapValues { (_, days) ->
                UsagePackageTimeline(
                    days = days.mapValues { (_, value) ->
                        UsageDaySlice(
                            foregroundMillis = value.foregroundMillis,
                            lastTimeUsedMillis = value.lastTimeUsedMillis,
                            sessions = value.sessions.toList()
                        )
                    }
                )
            }
        )
    }

    private fun normalizeAndOrder(
        events: List<UsageTimelineEvent>
    ): List<IndexedEvent> {
        val identities = hashSetOf<EventIdentity>()
        return events.withIndex().mapNotNull { indexed ->
            val event = indexed.value
            if (event.timestampMillis < 0L) return@mapNotNull null
            val packageName = event.packageName?.trim()?.takeIf(String::isNotEmpty)
            if (event.type.requiresPackage && packageName == null) return@mapNotNull null
            val normalized = event.copy(
                packageName = if (event.type.requiresPackage) packageName else null,
                className = if (event.type.requiresPackage) {
                    event.className?.trim()?.takeIf(String::isNotEmpty)
                } else {
                    null
                }
            )
            val identity = EventIdentity(
                packageName = normalized.packageName,
                className = normalized.className,
                timestampMillis = normalized.timestampMillis,
                type = normalized.type
            )
            if (!identities.add(identity)) return@mapNotNull null
            IndexedEvent(indexed.index, normalized)
        }.sortedWith(
            compareBy<IndexedEvent> { it.event.timestampMillis }
                .thenBy(IndexedEvent::originalIndex)
        )
    }

    private fun appendInterval(
        packageName: String,
        startMillis: Long,
        endMillis: Long,
        continuityGeneration: Long,
        dayRanges: List<UsageDayRange>,
        usage: MutableMap<String, MutableMap<LocalDate, MutableUsage>>
    ) {
        dayRanges.forEach { range ->
            val clippedStart = maxOf(startMillis, range.startMillis)
            val clippedEnd = minOf(endMillis, range.endMillis)
            if (clippedEnd <= clippedStart) return@forEach
            val packageDays = usage.getOrPut(packageName) { linkedMapOf() }
            val dayUsage = packageDays.getOrPut(range.date) { MutableUsage() }
            dayUsage.append(clippedStart, clippedEnd, continuityGeneration)
        }
    }

    private fun validateRanges(dayRanges: List<UsageDayRange>) {
        require(dayRanges.isNotEmpty()) { "统计日期不能为空" }
        dayRanges.zipWithNext().forEach { (previous, next) ->
            require(previous.date < next.date) { "统计日期必须严格递增" }
            require(previous.endMillis <= next.startMillis) { "统计日期区间不能重叠" }
        }
    }

    private class FocusState {
        private val activeActivities = linkedMapOf<ActivityKey, Long>()
        private var resumeOrder = 0L

        fun currentPackageName(): String? = activeActivities
            .maxByOrNull { it.value }
            ?.key
            ?.packageName

        fun resume(packageName: String, className: String?) {
            if (currentPackageName()?.let { it != packageName } == true) {
                activeActivities.keys.removeAll { it.packageName != packageName }
            }
            if (className == null) {
                activeActivities.keys.removeAll { it.packageName == packageName }
            } else {
                activeActivities.remove(ActivityKey(packageName, null))
            }
            resumeOrder = if (resumeOrder == Long.MAX_VALUE) 1L else resumeOrder + 1L
            activeActivities[ActivityKey(packageName, className)] = resumeOrder
        }

        fun pause(packageName: String, className: String?) {
            if (className == null) {
                pausePackage(packageName)
            } else {
                val removed = activeActivities.remove(ActivityKey(packageName, className))
                if (removed == null) activeActivities.remove(ActivityKey(packageName, null))
            }
        }

        fun confirm(packageName: String, className: String?) {
            if (currentPackageName() == packageName) return
            activeActivities.clear()
            resumeOrder = if (resumeOrder == Long.MAX_VALUE) 1L else resumeOrder + 1L
            activeActivities[ActivityKey(packageName, className)] = resumeOrder
        }

        fun pausePackage(packageName: String) {
            activeActivities.keys.removeAll { it.packageName == packageName }
        }

        fun clear() {
            activeActivities.clear()
        }
    }

    private data class ActivityKey(
        val packageName: String,
        // UsageEvents 的公开 API 不提供 Activity instanceId，只能采用类名作为保守 token。
        val className: String?
    )

    private data class IndexedEvent(
        val originalIndex: Int,
        val event: UsageTimelineEvent
    )

    private data class EventIdentity(
        val packageName: String?,
        val className: String?,
        val timestampMillis: Long,
        val type: UsageTimelineEventType
    )

    private class MutableUsage {
        var foregroundMillis: Long = 0L
            private set
        var lastTimeUsedMillis: Long = 0L
            private set
        val sessions = mutableListOf<UsageSessionSlice>()
        private var lastContinuityGeneration: Long? = null

        fun append(
            startMillis: Long,
            endMillis: Long,
            continuityGeneration: Long
        ) {
            if (endMillis <= startMillis) return
            val previous = sessions.lastOrNull()
            // 界面只展示到秒；同一秒内且无语义屏障的生命周期抖动视为连续使用。
            val isSameDisplayedSecond = previous != null &&
                startMillis / DISPLAY_SECOND_MILLIS ==
                previous.endMillis / DISPLAY_SECOND_MILLIS
            val canMerge = previous != null &&
                lastContinuityGeneration == continuityGeneration &&
                (startMillis <= previous.endMillis || isSameDisplayedSecond)
            if (canMerge) {
                checkNotNull(previous)
                val mergedEndMillis = maxOf(previous.endMillis, endMillis)
                foregroundMillis += mergedEndMillis - previous.endMillis
                sessions[sessions.lastIndex] = previous.copy(endMillis = mergedEndMillis)
            } else {
                sessions += UsageSessionSlice(startMillis, endMillis)
                foregroundMillis += endMillis - startMillis
            }
            lastContinuityGeneration = continuityGeneration
            lastTimeUsedMillis = maxOf(lastTimeUsedMillis, endMillis)
        }
    }
}
