package com.example.controlfree.supervision.app

import com.example.controlfree.data.ForegroundAppEvent
import com.example.controlfree.data.ForegroundEventKind
import java.util.ArrayDeque

/**
 * 在两次前台轮询之间补齐没有被轮询端点看见的完整短会话。
 *
 * UsageStats 查询通常带有重叠窗口，因此同一个事件可能在相邻查询中出现多次；此外，
 * 某些设备返回的事件顺序也不稳定。本跟踪器只接受严格落在同一对采样端点之间的会话，
 * 并以事件身份去重。端点已经采到目标包时，区间内该包的额外会话全部忽略，避免与
 * [AppSupervisionEngine] 的端点计时重复扣减。
 *
 * 调用方应把返回值作为可信的、已经按亮屏条件筛选后的额外前台时长传给引擎。跟踪器
 * 要求区间两端均为可用且可交互观察；观察不可用时会重置连续性，不会跨故障窗口结算。
 */
class AppSupervisionTransientSessionTracker(
    private val maxSampleCount: Int = DEFAULT_MAX_SAMPLE_COUNT,
    private val maxEventCount: Int = DEFAULT_MAX_EVENT_COUNT
) {
    private val samples = ArrayDeque<Sample>()
    private val eventsByKey = LinkedHashMap<EventKey, TrackedEvent>()
    private val emittedSessions = HashSet<SessionKey>()
    private var nextSequence = 0L
    private var eventFloorMillis = 0L

    init {
        require(maxSampleCount >= 2) { "采样端点窗口至少需要两个端点" }
        require(maxEventCount >= 2) { "事件窗口至少需要两个事件" }
    }

    /**
     * 记录一次可用观察，并返回本次新发现的额外前台时长（按包名聚合）。
     *
     * [events] 可以是当前查询窗口中的全部事件，也可以是查询层输出的增量事件；重复
     * 事件不会重复结算。首次记录只建立基线，不会产生额外时长。
     */
    fun recordObservation(
        sampledAtEpochMillis: Long,
        foregroundPackage: String?,
        isInteractive: Boolean,
        events: Collection<ForegroundAppEvent> = emptyList(),
        isAvailable: Boolean = true
    ): Map<String, Long> {
        require(sampledAtEpochMillis >= 0L) { "采样时间无效" }
        val normalizedForeground = normalizePackage(foregroundPackage)
        require(isAvailable || normalizedForeground == null) {
            "不可用观察不能携带前台包名"
        }

        val sample = Sample(
            timestampMillis = sampledAtEpochMillis,
            foregroundPackage = normalizedForeground,
            isInteractive = isInteractive,
            isAvailable = isAvailable
        )
        val latest = samples.peekLast()
        if (latest != null && sampledAtEpochMillis < latest.timestampMillis) {
            // 墙钟回退后，旧端点之间的间隔不再可解释；切断连续性，避免负数或跨时代扣减。
            reset()
            samples.addLast(sample)
            return emptyMap()
        }

        if (!isAvailable) {
            // 失败查询不能证明端点状态，也不能把故障前后的事件拼成一个可扣减会话。
            reset()
            samples.addLast(sample)
            return emptyMap()
        }

        if (latest != null && sampledAtEpochMillis == latest.timestampMillis) {
            samples.removeLast()
        }
        samples.addLast(sample)
        ingest(events)
        trimHistory()
        return settleCompletedSessions()
    }

    /** 清除端点、事件和已结算会话；通常在监督窗口切换或服务恢复时调用。 */
    fun reset() {
        samples.clear()
        eventsByKey.clear()
        emittedSessions.clear()
        nextSequence = 0L
        eventFloorMillis = 0L
    }

    private fun ingest(events: Collection<ForegroundAppEvent>) {
        events.forEach { rawEvent ->
            val packageName = normalizePackage(rawEvent.packageName) ?: return@forEach
            if (rawEvent.timestampMillis < eventFloorMillis) return@forEach
            val event = ForegroundAppEvent(
                packageName = packageName,
                timestampMillis = rawEvent.timestampMillis,
                kind = rawEvent.kind
            )
            val key = EventKey(
                packageName = event.packageName,
                timestampMillis = event.timestampMillis,
                kind = event.kind
            )
            if (eventsByKey.containsKey(key)) return@forEach
            val sequence = nextSequence
            nextSequence = if (nextSequence == Long.MAX_VALUE) {
                Long.MAX_VALUE
            } else {
                nextSequence + 1L
            }
            eventsByKey[key] = TrackedEvent(event, sequence)
        }
    }

    private fun settleCompletedSessions(): Map<String, Long> {
        if (samples.size < 2) return emptyMap()
        val orderedSamples = samples.toList()
        val orderedEvents = eventsByKey.values.sortedWith(
            compareBy<TrackedEvent> { it.event.timestampMillis }
                .thenBy(TrackedEvent::sequence)
        )
        val additionalByPackage = linkedMapOf<String, Long>()
        var activePackage: String? = null
        var activeStartMillis = 0L

        fun closeActive(endMillis: Long) {
            val packageName = activePackage ?: return
            val session = SessionKey(packageName, activeStartMillis, endMillis)
            activePackage = null
            if (endMillis <= activeStartMillis) return
            val interval = findContainingInterval(
                startMillis = activeStartMillis,
                endMillis = endMillis,
                orderedSamples = orderedSamples
            ) ?: return
            val startSample = orderedSamples[interval]
            val endSample = orderedSamples[interval + 1]
            if (
                !startSample.isAvailable ||
                !endSample.isAvailable ||
                !startSample.isInteractive ||
                !endSample.isInteractive ||
                packageName == startSample.foregroundPackage ||
                packageName == endSample.foregroundPackage ||
                !emittedSessions.add(session)
            ) {
                return
            }
            val durationMillis = endMillis - activeStartMillis
            val previous = additionalByPackage[packageName] ?: 0L
            additionalByPackage[packageName] = saturatedAdd(previous, durationMillis)
        }

        orderedEvents.forEach { tracked ->
            val event = tracked.event
            when (event.kind) {
                ForegroundEventKind.FOREGROUND -> {
                    when {
                        activePackage == null -> {
                            activePackage = event.packageName
                            activeStartMillis = event.timestampMillis
                        }

                        activePackage == event.packageName -> {
                            // 同包 Activity 切换不应伪造一次新会话；没有 className 信息时保守合并。
                        }

                        else -> {
                            closeActive(event.timestampMillis)
                            activePackage = event.packageName
                            activeStartMillis = event.timestampMillis
                        }
                    }
                }

                ForegroundEventKind.BACKGROUND -> {
                    if (activePackage == event.packageName) {
                        closeActive(event.timestampMillis)
                    }
                }
            }
        }
        return additionalByPackage
    }

    private fun findContainingInterval(
        startMillis: Long,
        endMillis: Long,
        orderedSamples: List<Sample>
    ): Int? {
        if (endMillis <= startMillis) return null
        for (index in 0 until orderedSamples.lastIndex) {
            val start = orderedSamples[index].timestampMillis
            val end = orderedSamples[index + 1].timestampMillis
            if (startMillis > start && endMillis < end) return index
        }
        return null
    }

    private fun trimHistory() {
        while (samples.size > maxSampleCount) {
            samples.removeFirst()
            val floor = samples.peekFirst()?.timestampMillis ?: return
            eventFloorMillis = maxOf(eventFloorMillis, floor)
            eventsByKey.entries.removeIf { it.value.event.timestampMillis <= floor }
            emittedSessions.removeIf { it.endMillis <= floor }
        }
        if (eventsByKey.size <= maxEventCount) return
        val orderedKeys = eventsByKey.entries.sortedWith(
            compareBy<Map.Entry<EventKey, TrackedEvent>> { it.value.event.timestampMillis }
                .thenBy { it.value.sequence }
        )
        val removeCount = eventsByKey.size - maxEventCount
        orderedKeys.take(removeCount).forEach { eventsByKey.remove(it.key) }
        val retainedFirst = eventsByKey.values.minOfOrNull { it.event.timestampMillis }
        if (retainedFirst != null) {
            eventFloorMillis = maxOf(eventFloorMillis, retainedFirst)
            emittedSessions.removeIf { it.endMillis <= eventFloorMillis }
        }
    }

    private fun normalizePackage(packageName: String?): String? =
        packageName?.trim()?.takeIf(String::isNotEmpty)

    private fun saturatedAdd(left: Long, right: Long): Long = when {
        right <= 0L -> left
        Long.MAX_VALUE - left < right -> Long.MAX_VALUE
        else -> left + right
    }

    private data class Sample(
        val timestampMillis: Long,
        val foregroundPackage: String?,
        val isInteractive: Boolean,
        val isAvailable: Boolean
    )

    private data class EventKey(
        val packageName: String,
        val timestampMillis: Long,
        val kind: ForegroundEventKind
    )

    private data class TrackedEvent(
        val event: ForegroundAppEvent,
        val sequence: Long
    )

    private data class SessionKey(
        val packageName: String,
        val startMillis: Long,
        val endMillis: Long
    )

    private companion object {
        const val DEFAULT_MAX_SAMPLE_COUNT = 32
        const val DEFAULT_MAX_EVENT_COUNT = 512
    }
}
