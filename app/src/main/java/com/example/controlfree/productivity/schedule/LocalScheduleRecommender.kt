package com.example.controlfree.productivity.schedule

import com.example.controlfree.supervision.SupervisionPlan
import com.example.controlfree.supervision.SupervisionPlanType
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.ceil

data class FocusHistorySample(
    val startedAtEpochMillis: Long,
    val endedAtEpochMillis: Long,
    val completed: Boolean
)

data class HabitHistorySample(
    val checkedInAtEpochMillis: Long,
    val targetReached: Boolean,
    val wasScheduled: Boolean
)

data class BusyInterval(
    val startEpochMillis: Long,
    val endEpochMillis: Long
) {
    init {
        require(startEpochMillis >= 0L)
        require(endEpochMillis > startEpochMillis)
    }
}

data class ScheduleCandidate(
    val id: String,
    val startEpochMillis: Long,
    val endEpochMillis: Long,
    val score: Int,
    val reason: String
)

/**
 * 把会占用设备锁定通道的监督计划展开为绝对时间区间，供智能推荐提前避让。
 * App 独立监督不占用该通道，仍由监督仓库在最终写入时做权威冲突校验。
 */
internal object SupervisionBusyIntervalFactory {
    fun create(
        plans: Collection<SupervisionPlan>,
        startEpochMillis: Long,
        endExclusiveEpochMillis: Long,
        deviceZoneId: ZoneId
    ): List<BusyInterval> {
        require(startEpochMillis >= 0L)
        require(endExclusiveEpochMillis > startEpochMillis)

        val queryEnd = Instant.ofEpochMilli(endExclusiveEpochMillis)
        return plans.asSequence()
            .filter { plan ->
                plan.type == SupervisionPlanType.GLOBAL ||
                    plan.type == SupervisionPlanType.FOCUS
            }
            .mapNotNull { plan ->
                val effectiveStart = when {
                    plan.enabled -> startEpochMillis
                    plan.scheduledEnableAtEpochMillis != null -> maxOf(
                        startEpochMillis,
                        plan.scheduledEnableAtEpochMillis
                    )
                    else -> null
                }
                effectiveStart?.takeIf { it < endExclusiveEpochMillis }?.let { plan to it }
            }
            .flatMap { (plan, effectiveStartMillis) ->
                val oneTimeWindow = plan.oneTimeFocusWindow
                if (oneTimeWindow != null) {
                    val start = maxOf(effectiveStartMillis, oneTimeWindow.startEpochMillis)
                    val end = minOf(endExclusiveEpochMillis, oneTimeWindow.endEpochMillis)
                    if (end > start) sequenceOf(BusyInterval(start, end)) else emptySequence()
                } else {
                    weeklyIntervals(
                        plan = plan,
                        start = Instant.ofEpochMilli(effectiveStartMillis),
                        endExclusive = queryEnd,
                        deviceZoneId = deviceZoneId
                    ).asSequence()
                }
            }
            .sortedBy(BusyInterval::startEpochMillis)
            .toList()
            .mergeOverlapping()
    }

    private fun weeklyIntervals(
        plan: SupervisionPlan,
        start: Instant,
        endExclusive: Instant,
        deviceZoneId: ZoneId
    ): List<BusyInterval> = buildList {
        var cursor = start
        while (cursor < endExclusive) {
            val nextBoundary = plan.schedule.nextBoundaryAfter(cursor, deviceZoneId)
                ?.coerceAtMost(endExclusive)
                ?: endExclusive
            if (plan.schedule.isActiveAt(cursor, deviceZoneId) && nextBoundary > cursor) {
                add(
                    BusyInterval(
                        startEpochMillis = cursor.toEpochMilli(),
                        endEpochMillis = nextBoundary.toEpochMilli()
                    )
                )
            }
            if (nextBoundary >= endExclusive) break
            cursor = nextBoundary
        }
    }

    private fun List<BusyInterval>.mergeOverlapping(): List<BusyInterval> {
        if (size < 2) return this
        val merged = mutableListOf<BusyInterval>()
        forEach { interval ->
            val previous = merged.lastOrNull()
            if (previous == null || interval.startEpochMillis > previous.endEpochMillis) {
                merged += interval
            } else if (interval.endEpochMillis > previous.endEpochMillis) {
                merged[merged.lastIndex] = BusyInterval(
                    previous.startEpochMillis,
                    interval.endEpochMillis
                )
            }
        }
        return merged
    }
}

object LocalScheduleRecommender {
    private const val SLOT_MINUTES = 30L
    private const val MAX_CANDIDATES = 5
    private const val MAX_SEARCH_DAYS = 7L

    fun recommend(
        nowEpochMillis: Long,
        deadlineEpochMillis: Long?,
        durationMinutes: Int,
        focusHistory: Collection<FocusHistorySample>,
        busyIntervals: Collection<BusyInterval>,
        zoneId: ZoneId,
        habitHistory: Collection<HabitHistorySample> = emptyList(),
        preferredDayStart: LocalTime = LocalTime.of(8, 0),
        preferredDayEnd: LocalTime = LocalTime.of(22, 0)
    ): List<ScheduleCandidate> {
        require(nowEpochMillis >= 0L)
        require(deadlineEpochMillis == null || deadlineEpochMillis > nowEpochMillis)
        require(durationMinutes in 1..480)
        require(preferredDayStart < preferredDayEnd)

        val slotCount = ceil(durationMinutes / SLOT_MINUTES.toDouble()).toLong()
        val roundedDuration = Duration.ofMinutes(slotCount * SLOT_MINUTES)
        val now = Instant.ofEpochMilli(nowEpochMillis).atZone(zoneId)
        val horizonEnd = now.plusDays(MAX_SEARCH_DAYS)
        val requestedEnd = deadlineEpochMillis?.let { Instant.ofEpochMilli(it).atZone(zoneId) }
        val searchEnd = if (requestedEnd == null || requestedEnd.isAfter(horizonEnd)) {
            horizonEnd
        } else {
            requestedEnd
        }
        val slotEvidence = buildSlotEvidence(focusHistory, habitHistory, zoneId)
        val normalizedBusy = busyIntervals.sortedBy(BusyInterval::startEpochMillis)
        val candidates = mutableListOf<ScheduleCandidate>()

        var day = now.toLocalDate()
        while (!day.atStartOfDay(zoneId).isAfter(searchEnd) && candidates.size < 200) {
            var cursor = day.atTime(preferredDayStart).atZone(zoneId)
            val dayEnd = day.atTime(preferredDayEnd).atZone(zoneId)
            if (cursor.isBefore(now)) cursor = roundUpToSlot(now)
            while (!cursor.plus(roundedDuration).isAfter(dayEnd) && cursor.isBefore(searchEnd)) {
                val end = cursor.plus(roundedDuration)
                val startMillis = cursor.toInstant().toEpochMilli()
                val endMillis = end.toInstant().toEpochMilli()
                if (endMillis <= searchEnd.toInstant().toEpochMilli() && !overlaps(startMillis, endMillis, normalizedBusy)) {
                    val slotKey = SlotKey(cursor.dayOfWeek, cursor.hour, cursor.minute / 30)
                    val evidence = slotEvidence[slotKey]
                    val historicalScore = evidence?.score ?: 0
                    val proximityPenalty = Duration.between(now, cursor).toHours().coerceAtMost(168).toInt() / 6
                    val score = 60 + historicalScore - proximityPenalty
                    candidates += ScheduleCandidate(
                        id = "slot-${cursor.toInstant().toEpochMilli()}",
                        startEpochMillis = startMillis,
                        endEpochMillis = endMillis,
                        score = score,
                        reason = evidence?.reason ?: "这是截止前最早的空闲时段"
                    )
                }
                cursor = cursor.plusMinutes(SLOT_MINUTES)
            }
            day = day.plusDays(1)
        }

        return candidates.sortedWith(
            compareByDescending(ScheduleCandidate::score).thenBy(ScheduleCandidate::startEpochMillis)
        ).take(MAX_CANDIDATES)
    }

    private fun buildSlotEvidence(
        focusHistory: Collection<FocusHistorySample>,
        habitHistory: Collection<HabitHistorySample>,
        zoneId: ZoneId
    ): Map<SlotKey, SlotEvidence> {
        val evidenceBySlot = mutableMapOf<SlotKey, SlotEvidence>()
        focusHistory.asSequence()
            .filter { sample -> sample.endedAtEpochMillis > sample.startedAtEpochMillis }
            .groupBy { sample -> sample.startedAtEpochMillis.toSlotKey(zoneId) }
            .forEach { (slot, samples) ->
                val completed = samples.count(FocusHistorySample::completed)
                val averageMinutes = samples.sumOf { sample ->
                    (sample.endedAtEpochMillis - sample.startedAtEpochMillis) / 60_000L
                } / samples.size.coerceAtLeast(1)
                evidenceBySlot[slot] = SlotEvidence(
                    focusScore = completed * 12 + averageMinutes.coerceAtMost(120).toInt() / 10,
                    focusSampleCount = samples.size,
                    completedFocusCount = completed
                )
            }
        habitHistory.asSequence()
            .filter { sample -> sample.checkedInAtEpochMillis >= 0L }
            .groupBy { sample -> sample.checkedInAtEpochMillis.toSlotKey(zoneId) }
            .forEach { (slot, samples) ->
                val existing = evidenceBySlot[slot] ?: SlotEvidence()
                val targetReachedCount = samples.count(HabitHistorySample::targetReached)
                val scheduledTargetCount = samples.count { sample ->
                    sample.targetReached && sample.wasScheduled
                }
                val habitScore = samples.sumOf { sample ->
                    HABIT_CHECK_IN_SCORE +
                        if (sample.targetReached) HABIT_TARGET_REACHED_BONUS else 0
                } + scheduledTargetCount * HABIT_SCHEDULE_ADHERENCE_BONUS
                evidenceBySlot[slot] = existing.copy(
                    habitScore = habitScore,
                    habitCheckInCount = samples.size,
                    habitTargetReachedCount = targetReachedCount,
                    scheduledHabitTargetCount = scheduledTargetCount
                )
            }
        return evidenceBySlot.filterValues { evidence -> evidence.score > 0 }
    }

    private fun Long.toSlotKey(zoneId: ZoneId): SlotKey {
        val dateTime = Instant.ofEpochMilli(this).atZone(zoneId)
        return SlotKey(dateTime.dayOfWeek, dateTime.hour, dateTime.minute / 30)
    }

    private data class SlotEvidence(
        val focusScore: Int = 0,
        val focusSampleCount: Int = 0,
        val completedFocusCount: Int = 0,
        val habitScore: Int = 0,
        val habitCheckInCount: Int = 0,
        val habitTargetReachedCount: Int = 0,
        val scheduledHabitTargetCount: Int = 0
    ) {
        val score: Int
            get() = focusScore + habitScore

        val reason: String
            get() = when {
                completedFocusCount > 0 && habitTargetReachedCount > 0 ->
                    "这个时段同时匹配你过去完成专注和习惯达标的记录"
                completedFocusCount > 0 ->
                    "这个时段与你过去完成专注的高效时间接近"
                scheduledHabitTargetCount > 0 ->
                    "这个时段匹配你过去在计划日达标的习惯打卡时间"
                habitTargetReachedCount > 0 ->
                    "这个时段匹配你过去习惯达标的实际打卡时间"
                habitCheckInCount > 0 -> "这个时段匹配你过去的习惯打卡时间"
                focusSampleCount > 0 -> "这个时段与你过去的专注时间接近"
                else -> "这是截止前最早的空闲时段"
        }
    }

    private fun overlaps(start: Long, end: Long, busy: Collection<BusyInterval>): Boolean =
        busy.any { interval -> start < interval.endEpochMillis && end > interval.startEpochMillis }

    private fun roundUpToSlot(value: ZonedDateTime): ZonedDateTime {
        val minute = value.minute
        val minutesToAdd = when {
            value.second == 0 && value.nano == 0 && minute % 30 == 0 -> 0
            minute < 30 -> 30 - minute
            else -> 60 - minute
        }
        return value.plusMinutes(minutesToAdd.toLong()).withSecond(0).withNano(0)
    }

    private data class SlotKey(
        val dayOfWeek: DayOfWeek,
        val hour: Int,
        val halfHour: Int
    )

    private const val HABIT_CHECK_IN_SCORE = 2
    private const val HABIT_TARGET_REACHED_BONUS = 8
    private const val HABIT_SCHEDULE_ADHERENCE_BONUS = 4
}
