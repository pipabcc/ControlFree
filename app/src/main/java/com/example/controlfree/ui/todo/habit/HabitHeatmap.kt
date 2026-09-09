package com.example.controlfree.ui.todo.habit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.controlfree.theme.BrandColors
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

@Composable
fun HabitProgressPanel(
    schedule: HabitSchedule,
    progress: List<HabitDayProgress>,
    today: LocalDate,
    habitColor: Color,
    onDateSelected: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    persistedBadgeTiers: Set<Int> = emptySet()
) {
    val streak = HabitProgressCalculator.calculateStreak(schedule, progress, today)
    val statistics = HabitProgressCalculator.calculateMonthStatistics(
        schedule,
        progress,
        YearMonth.from(today),
        today
    )
    val badges = resolveHabitBadgeTiers(streak.best, persistedBadgeTiers)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            ProgressMetric("当前连击", "${streak.current}${streak.unit.label}")
            ProgressMetric("最长连击", "${streak.best}${streak.unit.label}")
            ProgressMetric("本月完成", "${(statistics.completionRate * 100).toInt()}%")
        }
        LinearProgressIndicator(
            progress = { statistics.completionRate },
            modifier = Modifier.fillMaxWidth(),
            color = habitColor,
            trackColor = BrandColors.SurfaceRaised
        )
        HabitHeatmap(
            schedule = schedule,
            progress = progress,
            today = today,
            habitColor = habitColor,
            onDateSelected = onDateSelected
        )
        Text("里程碑", fontWeight = FontWeight.SemiBold, color = BrandColors.TextPrimary)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            STANDARD_HABIT_BADGES.forEach { badge ->
                BadgeTile(
                    badge = badge,
                    unlocked = badge.threshold in badges,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

internal fun resolveHabitBadgeTiers(
    calculatedBestStreak: Int,
    persistedBadgeTiers: Set<Int>
): Set<Int> {
    val supportedTiers = STANDARD_HABIT_BADGES.mapTo(linkedSetOf(), HabitBadge::threshold)
    return (HabitProgressCalculator.unlockedBadges(calculatedBestStreak) + persistedBadgeTiers)
        .filterTo(linkedSetOf()) { tier -> tier in supportedTiers }
}

@Composable
fun HabitHeatmap(
    schedule: HabitSchedule,
    progress: List<HabitDayProgress>,
    today: LocalDate,
    habitColor: Color,
    onDateSelected: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    weeks: Int = 16
) {
    val progressByDate = progress.associateBy(HabitDayProgress::date)
    val currentWeekStart = today.minusDays((today.dayOfWeek.value - DayOfWeek.MONDAY.value).toLong())
    val firstWeekStart = currentWeekStart.minusWeeks((weeks - 1).toLong())
    Column(modifier = modifier) {
        Text("近 ${weeks} 周", fontSize = 12.sp, color = BrandColors.TextSecondary)
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            repeat(weeks) { weekIndex ->
                val weekStart = firstWeekStart.plusWeeks(weekIndex.toLong())
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    repeat(7) { dayIndex ->
                        val date = weekStart.plusDays(dayIndex.toLong())
                        val record = progressByDate[date]
                        val isFuture = date.isAfter(today)
                        val isScheduled = HabitProgressCalculator.isScheduled(schedule, date)
                        val heatLevel = if (record == null) 0 else {
                            HabitProgressCalculator.heatLevel(
                                record.completionCount,
                                schedule.targetCountPerDay
                            )
                        }
                        val color = heatColor(
                            base = habitColor,
                            level = heatLevel,
                            scheduled = isScheduled,
                            future = isFuture
                        )
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .background(color, RoundedCornerShape(2.dp))
                                .semantics {
                                    contentDescription = buildString {
                                        append(date.format(DateTimeFormatter.ISO_LOCAL_DATE))
                                        append(if (isScheduled) "，计划日" else "，休息日")
                                        append("，完成 ${record?.completionCount ?: 0} 次")
                                    }
                                }
                                .clickable(enabled = !isFuture && isScheduled) {
                                    onDateSelected(date)
                                }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgressMetric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = BrandColors.TextPrimary)
        Text(label, fontSize = 11.sp, color = BrandColors.TextSecondary)
    }
}

@Composable
private fun BadgeTile(badge: HabitBadge, unlocked: Boolean, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.semantics {
            contentDescription = "${badge.title}，${if (unlocked) "已解锁" else "未解锁"}"
        },
        color = if (unlocked) BrandColors.WarningContainer else BrandColors.SurfaceRaised,
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                if (unlocked) Icons.Default.EmojiEvents else Icons.Outlined.Lock,
                contentDescription = null,
                tint = if (unlocked) BrandColors.Warning else BrandColors.TextTertiary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                badge.threshold.toString(),
                fontWeight = FontWeight.Bold,
                color = if (unlocked) BrandColors.Warning else BrandColors.TextTertiary
            )
            Text(
                badge.title,
                maxLines = 1,
                fontSize = 9.sp,
                color = BrandColors.TextSecondary
            )
        }
    }
}

@Composable
private fun heatColor(base: Color, level: Int, scheduled: Boolean, future: Boolean): Color {
    if (future) return BrandColors.SurfaceRaised.copy(alpha = 0.35f)
    if (level == 0) {
        return if (scheduled) BrandColors.SurfaceRaised else BrandColors.OutlineSoft.copy(alpha = 0.45f)
    }
    val alpha = when (level) {
        1 -> 0.30f
        2 -> 0.50f
        3 -> 0.72f
        else -> 1f
    }
    return base.copy(alpha = alpha)
}
