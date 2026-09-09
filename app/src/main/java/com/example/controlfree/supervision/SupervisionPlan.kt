package com.example.controlfree.supervision

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

private const val MINUTES_PER_DAY = 24 * 60
private const val MINUTES_PER_WEEK = 7 * MINUTES_PER_DAY

/**
 * 以分钟表示的本地半开时间段 `[startMinute, endMinute)`。
 *
 * 当结束分钟小于开始分钟时，时间段跨到次日；午夜结束统一编码为 `24:00`，
 * `00:00-24:00` 表示完整一天。
 * 跨午夜时间段归属于开始日，例如“周一 22:00-次日 07:00”只需选择周一。
 */
data class DailyTimeRange(
    val startMinute: Int,
    val endMinuteExclusive: Int
) {
    init {
        require(startMinute in 0 until MINUTES_PER_DAY) { "开始时间必须位于一天内" }
        require(endMinuteExclusive in 1..MINUTES_PER_DAY) { "结束时间必须位于一天内" }
        require(startMinute != endMinuteExclusive) { "开始和结束时间不能相同" }
    }

    val crossesMidnight: Boolean
        get() = endMinuteExclusive <= startMinute

    internal fun endOffsetFromStartDay(): Int =
        if (crossesMidnight) MINUTES_PER_DAY + endMinuteExclusive else endMinuteExclusive
}

/**
 * 每周重复的监督时间范围。
 *
 * 夏令时缺口中的边界会顺延到首个有效瞬间；重复本地时间采用“开始取较早偏移、
 * 结束取较晚偏移”，避免回拨小时出现监督空档。计划重叠采用本地周槽位判断，
 * 因此只比较相同时区的计划。
 */
class WeeklySchedule(
    val zoneId: ZoneId,
    activeDays: Set<DayOfWeek>,
    ranges: List<DailyTimeRange>,
    val zoneMode: ScheduleZoneMode = ScheduleZoneMode.FOLLOW_DEVICE
) {
    val activeDays: Set<DayOfWeek> = activeDays.toSet()
    val ranges: List<DailyTimeRange> = ranges.toList()

    init {
        require(activeDays.isNotEmpty()) { "至少选择一个星期" }
        require(ranges.isNotEmpty()) { "至少设置一个时间段" }
        require(!hasInternalOverlap()) { "同一计划内的时间段不能重叠" }
    }

    fun resolvedZoneId(deviceZoneId: ZoneId): ZoneId = when (zoneMode) {
        ScheduleZoneMode.FOLLOW_DEVICE -> deviceZoneId
        ScheduleZoneMode.FIXED -> zoneId
    }

    fun isActiveAt(instant: Instant, deviceZoneId: ZoneId = zoneId): Boolean {
        val effectiveZoneId = resolvedZoneId(deviceZoneId)
        val localDate = instant.atZone(effectiveZoneId).toLocalDate()
        return sequenceOf(localDate.minusDays(1), localDate).any { startDate ->
            startDate.dayOfWeek in activeDays && ranges.any { range ->
                intervalFor(startDate, range, effectiveZoneId).contains(instant)
            }
        }
    }

    /** 返回严格晚于 [instant] 的下一个开始或结束边界。 */
    fun nextBoundaryAfter(instant: Instant, deviceZoneId: ZoneId = zoneId): Instant? {
        return boundaryCandidatesAfter(instant, deviceZoneId).firstOrNull()
    }

    /** 返回监督启用状态真正发生变化的下一个边界，相邻时间段不会触发伪切换。 */
    fun nextStateChangeAfter(instant: Instant, deviceZoneId: ZoneId = zoneId): Instant? =
        boundaryCandidatesAfter(instant, deviceZoneId).firstOrNull { candidate ->
            isActiveAt(candidate.minusNanos(1L), deviceZoneId) !=
                isActiveAt(candidate, deviceZoneId)
        }

    private fun boundaryCandidatesAfter(instant: Instant, deviceZoneId: ZoneId): List<Instant> {
        val effectiveZoneId = resolvedZoneId(deviceZoneId)
        val localDate = instant.atZone(effectiveZoneId).toLocalDate()
        val candidates = linkedSetOf<Instant>()
        for (dayOffset in -1L..8L) {
            val startDate = localDate.plusDays(dayOffset)
            if (startDate.dayOfWeek !in activeDays) continue
            ranges.forEach { range ->
                val interval = intervalFor(startDate, range, effectiveZoneId)
                if (interval.start > instant) candidates += interval.start
                if (interval.endExclusive > instant) candidates += interval.endExclusive
            }
        }
        return candidates.sorted()
    }

    fun compareOverlap(
        other: WeeklySchedule,
        deviceZoneId: ZoneId = zoneId
    ): ScheduleOverlapComparison {
        val zonesComparable = when {
            zoneMode == ScheduleZoneMode.FOLLOW_DEVICE &&
                other.zoneMode == ScheduleZoneMode.FOLLOW_DEVICE ->
                resolvedZoneId(deviceZoneId) == other.resolvedZoneId(deviceZoneId)
            zoneMode == ScheduleZoneMode.FIXED && other.zoneMode == ScheduleZoneMode.FIXED ->
                zoneId == other.zoneId
            else -> false
        }
        if (!zonesComparable) return ScheduleOverlapComparison.DIFFERENT_TIME_ZONES
        val left = normalizedWeekSegments()
        val right = other.normalizedWeekSegments()
        val overlaps = left.any { first -> right.any(first::overlaps) }
        return if (overlaps) ScheduleOverlapComparison.OVERLAPS
        else ScheduleOverlapComparison.DISJOINT
    }

    private fun intervalFor(
        startDate: LocalDate,
        range: DailyTimeRange,
        effectiveZoneId: ZoneId
    ): InstantInterval {
        val startLocal = startDate.atStartOfDay().plusMinutes(range.startMinute.toLong())
        val endLocal = startDate.atStartOfDay()
            .plusMinutes(range.endOffsetFromStartDay().toLong())
        return InstantInterval(
            start = resolveBoundary(startLocal, effectiveZoneId, useLaterOffset = false),
            endExclusive = resolveBoundary(endLocal, effectiveZoneId, useLaterOffset = true)
        )
    }

    private fun resolveBoundary(
        localDateTime: LocalDateTime,
        effectiveZoneId: ZoneId,
        useLaterOffset: Boolean
    ): Instant {
        val rules = effectiveZoneId.rules
        val validOffsets = rules.getValidOffsets(localDateTime)
        return when {
            validOffsets.isNotEmpty() -> ZonedDateTime.ofLocal(
                localDateTime,
                effectiveZoneId,
                if (useLaterOffset) validOffsets.last() else validOffsets.first()
            ).toInstant()
            else -> {
                val transition = requireNotNull(rules.getTransition(localDateTime))
                transition.dateTimeAfter.atZone(effectiveZoneId).toInstant()
            }
        }
    }

    private fun hasInternalOverlap(): Boolean {
        val segments = normalizedWeekSegments().sortedBy(WeekSegment::start)
        return segments.zipWithNext().any { (first, second) -> first.endExclusive > second.start }
    }

    private fun normalizedWeekSegments(): List<WeekSegment> = buildList {
        activeDays.forEach { day ->
            val dayStart = (day.value - DayOfWeek.MONDAY.value) * MINUTES_PER_DAY
            ranges.forEach { range ->
                val start = dayStart + range.startMinute
                val end = dayStart + range.endOffsetFromStartDay()
                if (end <= MINUTES_PER_WEEK) {
                    add(WeekSegment(start, end))
                } else {
                    add(WeekSegment(start, MINUTES_PER_WEEK))
                    add(WeekSegment(0, end - MINUTES_PER_WEEK))
                }
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WeeklySchedule) return false
        return zoneId == other.zoneId && zoneMode == other.zoneMode &&
            activeDays == other.activeDays && ranges == other.ranges
    }

    override fun hashCode(): Int = 31 * (31 * (31 * zoneId.hashCode() + zoneMode.hashCode()) +
        activeDays.hashCode()) + ranges.hashCode()

    override fun toString(): String =
        "WeeklySchedule(zoneId=$zoneId, zoneMode=$zoneMode, activeDays=$activeDays, ranges=$ranges)"
}

enum class ScheduleZoneMode {
    FOLLOW_DEVICE,
    FIXED
}

enum class ScheduleOverlapComparison {
    OVERLAPS,
    DISJOINT,
    DIFFERENT_TIME_ZONES
}

enum class SupervisionPlanType {
    GLOBAL,
    APP,
    FOCUS
}

/**
 * 一次性专注锁的绝对时间窗，采用半开区间 `[start, end)`。
 *
 * 时间窗使用 epoch millis 保存，因而设备重启或时区变化不会把已经预约的
 * 瞬间错误地平移到另一周。周计划仍继续使用 [WeeklySchedule]。
 */
data class OneTimeFocusWindow(
    val startEpochMillis: Long,
    val endEpochMillis: Long
) {
    init {
        require(startEpochMillis >= 0L) { "一次性专注开始时间不能为负数" }
        require(endEpochMillis > startEpochMillis) { "一次性专注结束时间必须晚于开始时间" }
    }

    fun contains(epochMillis: Long): Boolean =
        epochMillis >= startEpochMillis && epochMillis < endEpochMillis

    fun overlaps(other: OneTimeFocusWindow): Boolean =
        startEpochMillis < other.endEpochMillis &&
            other.startEpochMillis < endEpochMillis
}

sealed interface SupervisionPolicy

data class GlobalCyclePolicy(
    val usageDuration: Duration,
    val lockDuration: Duration
) : SupervisionPolicy {
    init {
        require(usageDuration.isValidPolicyDuration()) { "玩机时长必须是 1-1440 分钟" }
        require(lockDuration.isValidPolicyDuration()) { "锁定时长必须是 1-1440 分钟" }
    }
}

data class FocusCyclePolicy(
    val lockDuration: Duration,
    val playDuration: Duration
) : SupervisionPolicy {
    init {
        require(lockDuration.isValidPolicyDuration()) { "专注锁定时长必须是 1-1440 分钟" }
        require(playDuration.isValidPolicyDuration()) { "专注玩机时长必须是 1-1440 分钟" }
    }
}

/** 一个 App 对应一条独立规则；多个 App 由多条计划分别监督。 */
data class AppRulePolicy(
    val packageName: String,
    val usageAllowance: Duration,
    val restDuration: Duration,
    val dailyUsageLimit: Duration = Duration.ofMinutes(1_440L),
    val disabledRanges: List<DailyTimeRange> = emptyList()
) : SupervisionPolicy {
    init {
        require(isValidSupervisionPackageName(packageName)) { "App 包名格式无效" }
        require(usageAllowance.isValidPolicyDuration()) { "App 可用时长必须是 1-1440 分钟" }
        require(restDuration.isValidPolicyDuration()) { "App 休息时长必须是 1-1440 分钟" }
        require(dailyUsageLimit.isValidPolicyDuration()) { "App 每日累计上限必须是 1-1440 分钟" }
        require(disabledRanges.distinct().size == disabledRanges.size) { "App 禁用时段不能重复" }
    }
}

data class SupervisionPlan(
    val id: String,
    val name: String,
    val type: SupervisionPlanType,
    val enabled: Boolean,
    val schedule: WeeklySchedule,
    val policy: SupervisionPolicy,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    /** 计划仍处于停用状态、但已预约自动开启时的触发时刻。 */
    val scheduledEnableAtEpochMillis: Long? = null,
    /** 仅 FOCUS 计划可设置；非空时计划只在此绝对时间窗内生效一次。 */
    val oneTimeFocusWindow: OneTimeFocusWindow? = null,
    /**
     * 当计划停用时，进入任一包名对应的 App 后自动启用计划。
     *
     * 这是持久的启动方式，与 [enabled] 正交：手动停用计划不会清除它，
     * 只有用户在编辑器改选其他启动方式才会清除。
     */
    val triggerAppPackageNames: Set<String> = emptySet()
) {
    init {
        require(id.isNotBlank()) { "计划 ID 不能为空" }
        require(id == id.trim()) { "计划 ID 不能包含首尾空白" }
        require(name.isNotBlank() && name == name.trim()) { "计划名称不能为空或包含首尾空白" }
        require(createdAtEpochMillis >= 0L) { "创建时间不能为负数" }
        require(updatedAtEpochMillis >= createdAtEpochMillis) { "更新时间不能早于创建时间" }
        require(scheduledEnableAtEpochMillis == null || scheduledEnableAtEpochMillis >= 0L) {
            "预约开启时间不能为负数"
        }
        require(type == SupervisionPlanType.FOCUS || oneTimeFocusWindow == null) {
            "只有专注计划可以设置一次性时间窗"
        }
        require(
            oneTimeFocusWindow == null ||
                scheduledEnableAtEpochMillis == null ||
                scheduledEnableAtEpochMillis == oneTimeFocusWindow.startEpochMillis
        ) {
            "一次性专注预约时间必须与时间窗开始时间一致"
        }
        require(type.matches(policy)) { "计划类型与监督策略不匹配" }
        require(triggerAppPackageNames.size <= MAX_PLAN_TRIGGER_APPS) {
            "每个任务最多添加 $MAX_PLAN_TRIGGER_APPS 个触发 App"
        }
        require(triggerAppPackageNames.all { packageName ->
            packageName == packageName.trim() && isValidSupervisionPackageName(packageName)
        }) { "触发 App 包名无效" }
        require(type == SupervisionPlanType.GLOBAL || triggerAppPackageNames.isEmpty()) {
            "只有定时监督任务可以设置 App 触发"
        }
        require(triggerAppPackageNames.isEmpty() || scheduledEnableAtEpochMillis == null) {
            "App 触发和预约开启不能同时选择"
        }
        val appPolicy = policy as? AppRulePolicy
        if (appPolicy != null && appPolicy.disabledRanges.isNotEmpty()) {
            WeeklySchedule(
                zoneId = schedule.zoneId,
                activeDays = schedule.activeDays,
                ranges = appPolicy.disabledRanges,
                zoneMode = schedule.zoneMode
            )
        }
    }
}

const val MAX_PLAN_TRIGGER_APPS = 32

fun SupervisionPlan.isOneTimeFocus(): Boolean =
    type == SupervisionPlanType.FOCUS && oneTimeFocusWindow != null

enum class PlanConflictReason {
    OVERLAPPING_TIME_RANGE,
    OVERLAPPING_APP_RULE,
    DIFFERENT_TIME_ZONES,
    DUPLICATE_PLAN_ID
}

data class SupervisionPlanConflict(
    val firstPlanId: String,
    val secondPlanId: String,
    val reason: PlanConflictReason
)

object SupervisionPlanConflictDetector {
    /** 只允许已启用的全局计划使用同一时区且时间范围互不重叠。 */
    fun findEnabledGlobalConflicts(
        plans: Collection<SupervisionPlan>,
        deviceZoneId: ZoneId = ZoneId.systemDefault()
    ): List<SupervisionPlanConflict> = findTimeRangeConflicts(
        plans.filter { it.enabled && it.type == SupervisionPlanType.GLOBAL },
        deviceZoneId
    )

    /** 全局监督和专注任务共用设备锁定通道，已启用时间范围不得重叠。 */
    fun findEnabledDeviceLockConflicts(
        plans: Collection<SupervisionPlan>,
        deviceZoneId: ZoneId = ZoneId.systemDefault()
    ): List<SupervisionPlanConflict> = findTimeRangeConflicts(
        plans.filter {
            it.enabled &&
                (it.type == SupervisionPlanType.GLOBAL || it.type == SupervisionPlanType.FOCUS)
        },
        deviceZoneId
    )

    private fun findTimeRangeConflicts(
        deviceLockPlans: List<SupervisionPlan>,
        deviceZoneId: ZoneId
    ): List<SupervisionPlanConflict> {
        return buildList {
            deviceLockPlans.forEachIndexed { index, first ->
                for (secondIndex in index + 1 until deviceLockPlans.size) {
                    val second = deviceLockPlans[secondIndex]
                    val reason = when (comparePlanOverlap(first, second, deviceZoneId)) {
                        ScheduleOverlapComparison.OVERLAPS ->
                            PlanConflictReason.OVERLAPPING_TIME_RANGE
                        ScheduleOverlapComparison.DIFFERENT_TIME_ZONES ->
                            PlanConflictReason.DIFFERENT_TIME_ZONES
                        ScheduleOverlapComparison.DISJOINT -> null
                    }
                    if (reason != null) {
                        add(SupervisionPlanConflict(first.id, second.id, reason))
                    }
                }
            }
        }
    }

    private fun comparePlanOverlap(
        first: SupervisionPlan,
        second: SupervisionPlan,
        deviceZoneId: ZoneId
    ): ScheduleOverlapComparison {
        val firstWindow = first.oneTimeFocusWindow
        val secondWindow = second.oneTimeFocusWindow
        return when {
            firstWindow != null && secondWindow != null ->
                if (firstWindow.overlaps(secondWindow)) {
                    ScheduleOverlapComparison.OVERLAPS
                } else {
                    ScheduleOverlapComparison.DISJOINT
                }

            firstWindow != null -> compareOneTimeWithWeekly(
                firstWindow,
                second.schedule,
                deviceZoneId
            )
            secondWindow != null -> compareOneTimeWithWeekly(
                secondWindow,
                first.schedule,
                deviceZoneId
            )
            else -> first.schedule.compareOverlap(second.schedule, deviceZoneId)
        }
    }

    /** 判断一次性时间窗是否与周计划的任一有效区间相交。 */
    private fun compareOneTimeWithWeekly(
        window: OneTimeFocusWindow,
        weeklySchedule: WeeklySchedule,
        deviceZoneId: ZoneId
    ): ScheduleOverlapComparison {
        val start = Instant.ofEpochMilli(window.startEpochMillis)
        val end = Instant.ofEpochMilli(window.endEpochMillis)
        if (weeklySchedule.isActiveAt(start, deviceZoneId)) {
            return ScheduleOverlapComparison.OVERLAPS
        }
        // 起点可能恰好落在某个区间的结束边界。此时第一个状态变化是“结束”，
        // 不能因此提前判定无冲突，还要继续检查窗口内后续的开始边界。
        var cursor = start.minusNanos(1L)
        while (true) {
            val nextChange = weeklySchedule.nextStateChangeAfter(cursor, deviceZoneId)
                ?: break
            if (nextChange >= end) break
            if (weeklySchedule.isActiveAt(nextChange, deviceZoneId)) {
                return ScheduleOverlapComparison.OVERLAPS
            }
            cursor = nextChange
        }
        return ScheduleOverlapComparison.DISJOINT
    }

    /** 同一 App 的两条启用计划不得在同一时段同时生效。 */
    fun findEnabledAppConflicts(
        plans: Collection<SupervisionPlan>,
        deviceZoneId: ZoneId = ZoneId.systemDefault()
    ): List<SupervisionPlanConflict> {
        val appPlans = plans.filter { it.enabled && it.type == SupervisionPlanType.APP }
        return buildList {
            appPlans.forEachIndexed { index, first ->
                val firstPackage = (first.policy as AppRulePolicy).packageName
                for (secondIndex in index + 1 until appPlans.size) {
                    val second = appPlans[secondIndex]
                    val secondPackage = (second.policy as AppRulePolicy).packageName
                    if (firstPackage != secondPackage) continue
                    val reason = when (
                        first.schedule.compareOverlap(second.schedule, deviceZoneId)
                    ) {
                        ScheduleOverlapComparison.OVERLAPS -> PlanConflictReason.OVERLAPPING_APP_RULE
                        ScheduleOverlapComparison.DIFFERENT_TIME_ZONES ->
                            PlanConflictReason.DIFFERENT_TIME_ZONES
                        ScheduleOverlapComparison.DISJOINT -> null
                    }
                    if (reason != null) {
                        add(SupervisionPlanConflict(first.id, second.id, reason))
                    }
                }
            }
        }
    }

    fun findDuplicateIds(plans: Collection<SupervisionPlan>): List<SupervisionPlanConflict> =
        plans.groupBy(SupervisionPlan::id)
            .filterValues { duplicates -> duplicates.size > 1 }
            .flatMap { (id, duplicates) ->
                List(duplicates.size - 1) {
                    SupervisionPlanConflict(id, id, PlanConflictReason.DUPLICATE_PLAN_ID)
                }
            }
}

private data class InstantInterval(
    val start: Instant,
    val endExclusive: Instant
) {
    fun contains(instant: Instant): Boolean = instant >= start && instant < endExclusive
}

private data class WeekSegment(
    val start: Int,
    val endExclusive: Int
) {
    init {
        require(start in 0 until MINUTES_PER_WEEK)
        require(endExclusive in 1..MINUTES_PER_WEEK)
        require(endExclusive > start)
    }

    fun overlaps(other: WeekSegment): Boolean =
        start < other.endExclusive && other.start < endExclusive
}

private val PACKAGE_NAME_PATTERN = Regex(
    "[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"
)

internal fun isValidSupervisionPackageName(packageName: String): Boolean =
    PACKAGE_NAME_PATTERN.matches(packageName)

private fun Duration.isValidPolicyDuration(): Boolean =
    !isZero &&
        !isNegative &&
        nano == 0 &&
        seconds % 60L == 0L &&
        seconds <= Duration.ofDays(1).seconds

private fun SupervisionPlanType.matches(policy: SupervisionPolicy): Boolean = when (this) {
    SupervisionPlanType.GLOBAL -> policy is GlobalCyclePolicy
    SupervisionPlanType.APP -> policy is AppRulePolicy
    SupervisionPlanType.FOCUS -> policy is FocusCyclePolicy
}
