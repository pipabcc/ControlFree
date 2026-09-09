package com.example.controlfree.ui.usage

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.controlfree.data.AppUsageDayDetails
import com.example.controlfree.theme.BrandColors
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.ceil

private const val MINUTE_MILLIS = 60_000L
private const val HOUR_MILLIS = 60L * MINUTE_MILLIS
private const val DAYS_IN_CHART = 7

/** 全机和单 App 的 7 日图共同使用此排名语义，避免两处配色逐渐分叉。 */
internal enum class UsageBarEmphasis {
    HIGHEST,
    SECOND,
    THIRD,
    STANDARD
}

internal data class UsageSevenDayChartDay(
    val date: LocalDate,
    val foregroundMillis: Long,
    val emphasis: UsageBarEmphasis
)

internal data class UsageSevenDayChartModel(
    val days: List<UsageSevenDayChartDay>,
    val totalMillis: Long,
    val axisTopMillis: Long
)

/**
 * 数值相同时按原日期顺序稳定排名，与既有全机 7 日横向柱图行为保持一致。
 */
internal fun resolveUsageBarEmphases(values: List<Long>): List<UsageBarEmphasis> {
    val normalizedValues = values.map { it.coerceAtLeast(0L) }
    val rankedIndices = normalizedValues.indices.sortedWith(
        compareByDescending<Int> { normalizedValues[it] }.thenBy { it }
    )
    val rankByIndex = rankedIndices.withIndex().associate { (rank, index) -> index to rank }
    return normalizedValues.indices.map { index ->
        when (rankByIndex.getValue(index)) {
            0 -> UsageBarEmphasis.HIGHEST
            1 -> UsageBarEmphasis.SECOND
            2 -> UsageBarEmphasis.THIRD
            else -> UsageBarEmphasis.STANDARD
        }
    }
}

internal fun buildUsageSevenDayChartModel(
    sourceDays: List<AppUsageDayDetails>
): UsageSevenDayChartModel {
    val latestDays = sourceDays
        .sortedBy(AppUsageDayDetails::date)
        .takeLast(DAYS_IN_CHART)
    val values = latestDays.map { it.foregroundMillis.coerceAtLeast(0L) }
    val emphases = resolveUsageBarEmphases(values)
    return UsageSevenDayChartModel(
        days = latestDays.mapIndexed { index, day ->
            UsageSevenDayChartDay(
                date = day.date,
                foregroundMillis = values[index],
                emphasis = emphases[index]
            )
        },
        totalMillis = values.sum(),
        axisTopMillis = resolveUsageChartAxisTopMillis(values.maxOrNull() ?: 0L)
    )
}

/** 选用易读的整刻度，使中间刻度不会出现难以扫读的零碎数值。 */
internal fun resolveUsageChartAxisTopMillis(maxMillis: Long): Long {
    if (maxMillis <= 0L) return HOUR_MILLIS
    val maxMinutes = ceil(maxMillis.toDouble() / MINUTE_MILLIS.toDouble()).toLong()
    val candidatesInMinutes = longArrayOf(
        10L,
        20L,
        30L,
        60L,
        120L,
        180L,
        240L,
        360L,
        480L,
        720L,
        1_440L
    )
    val topMinutes = candidatesInMinutes.firstOrNull { it >= maxMinutes }
        ?: (((maxMinutes + 359L) / 360L) * 360L)
    return topMinutes * MINUTE_MILLIS
}

internal fun formatUsageSevenDayTotal(milliseconds: Long): String {
    val totalMinutes = milliseconds.coerceAtLeast(0L) / MINUTE_MILLIS
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return when {
        hours > 0L && minutes > 0L -> "${hours}小时${minutes}分钟"
        hours > 0L -> "${hours}小时"
        else -> "${minutes}分钟"
    }
}

internal fun formatUsageChartAxisLabel(
    milliseconds: Long,
    useHourUnit: Boolean
): String {
    val minutes = milliseconds.coerceAtLeast(0L) / MINUTE_MILLIS
    if (!useHourUnit) return "${minutes}m"
    if (minutes == 0L) return "0h"
    if (minutes % 60L == 0L) return "${minutes / 60L}h"
    if (minutes % 30L == 0L) return "${minutes / 60L}.${if (minutes % 60L == 30L) 5 else 0}h"
    return "${minutes}m"
}

@Composable
internal fun usageBarColor(emphasis: UsageBarEmphasis): Color = when (emphasis) {
    UsageBarEmphasis.HIGHEST -> Color(0xFFE5A65D)
    UsageBarEmphasis.SECOND -> Color(0xFFF57C00)
    UsageBarEmphasis.THIRD -> Color(0xFF1E88E5)
    UsageBarEmphasis.STANDARD -> BrandColors.Primary.copy(alpha = 0.35f)
}

@Composable
internal fun UsageSevenDayChartCard(
    days: List<AppUsageDayDetails>,
    isLargeFont: Boolean
) {
    val model = remember(days) { buildUsageSevenDayChartModel(days) }
    val selectedDate = remember(days) { mutableStateOf<LocalDate?>(null) }
    val selectedDay = selectedDate.value?.let { date ->
        model.days.firstOrNull { it.date == date }
    }
    val displayMillis = selectedDay?.foregroundMillis ?: model.totalMillis
    val displayText = formatUsageSevenDayTotal(displayMillis)
    val chartDescription = remember(model) {
        buildString {
            append("最近七日前台使用统计，合计")
            append(formatUsageSevenDayTotal(model.totalMillis))
            model.days.forEach { day ->
                append("，")
                append(day.date.format(chartAccessibilityDateFormatter))
                append("使用")
                append(formatUsageSevenDayTotal(day.foregroundMillis))
            }
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = BrandColors.SurfaceCard),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 20.dp)
        ) {
            Text(
                text = buildAnnotatedString {
                    if (selectedDay == null) {
                        append("近7日已用：")
                    } else {
                        append(selectedDay.date.format(chartDateFormatter))
                        append("已用：")
                    }
                    withStyle(
                        SpanStyle(
                            color = BrandColors.TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    ) {
                        append(displayText)
                    }
                },
                color = BrandColors.TextSecondary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(20.dp))
            UsageSevenDayBarChart(
                model = model,
                isLargeFont = isLargeFont,
                contentDescription = chartDescription,
                selectedDate = selectedDay?.date,
                onDayClick = { date ->
                    selectedDate.value = if (selectedDate.value == date) null else date
                }
            )
        }
    }
}

@Composable
private fun UsageSevenDayBarChart(
    model: UsageSevenDayChartModel,
    isLargeFont: Boolean,
    contentDescription: String,
    selectedDate: LocalDate?,
    onDayClick: (LocalDate) -> Unit
) {
    val chartHeight = if (isLargeFont) 156.dp else 136.dp
    val axisWidth = 38.dp
    val axisTop = model.axisTopMillis.coerceAtLeast(1L)
    val useHourUnit = axisTop >= HOUR_MILLIS
    val axisLabelColor = BrandColors.TextTertiary
    val gridColor = BrandColors.OutlineSoft
    val trackColor = BrandColors.SurfaceMuted.copy(alpha = 0.18f)

    Column(
        modifier = Modifier.semantics { this.contentDescription = contentDescription }
    ) {
        Row(Modifier.fillMaxWidth().height(chartHeight)) {
            Box(Modifier.width(axisWidth).fillMaxHeight()) {
                Text(
                    text = formatUsageChartAxisLabel(axisTop, useHourUnit),
                    color = axisLabelColor,
                    fontSize = 9.sp,
                    modifier = Modifier.align(Alignment.TopEnd)
                )
                Text(
                    text = formatUsageChartAxisLabel(axisTop / 2L, useHourUnit),
                    color = axisLabelColor,
                    fontSize = 9.sp,
                    modifier = Modifier.align(Alignment.CenterEnd)
                )
                Text(
                    text = formatUsageChartAxisLabel(0L, useHourUnit),
                    color = axisLabelColor,
                    fontSize = 9.sp,
                    modifier = Modifier.align(Alignment.BottomEnd)
                )
            }
            Spacer(Modifier.width(8.dp))
            BoxWithConstraints(
                modifier = Modifier.fillMaxHeight().weight(1f)
            ) {
                val columnCount = model.days.size.coerceAtLeast(1)
                val barWidth = ((maxWidth / columnCount.toFloat()) * 0.44f)
                    .coerceIn(10.dp, 20.dp)
                Canvas(Modifier.fillMaxSize()) {
                    val dash = PathEffect.dashPathEffect(
                        floatArrayOf(4.dp.toPx(), 4.dp.toPx())
                    )
                    listOf(0f, size.height / 2f, (size.height - 1f).coerceAtLeast(0f))
                        .forEach { y ->
                            drawLine(
                                color = gridColor,
                                start = Offset(0f, y),
                                end = Offset(size.width, y),
                                strokeWidth = 0.75.dp.toPx(),
                                pathEffect = dash
                            )
                        }
                }
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    model.days.forEach { day ->
                        val fraction = (day.foregroundMillis.toFloat() / axisTop.toFloat())
                            .coerceIn(0f, 1f)
                        val visibleFraction = if (day.foregroundMillis > 0L) {
                            fraction.coerceAtLeast(0.018f)
                        } else {
                            0f
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clickable { onDayClick(day.date) }
                                .semantics {
                                    this.contentDescription = "${day.date.format(chartAccessibilityDateFormatter)}，使用${formatUsageSevenDayTotal(day.foregroundMillis)}"
                                },
                            contentAlignment = Alignment.BottomCenter
                        ) {
                            Box(
                                Modifier
                                    .width(barWidth)
                                    .fillMaxHeight()
                                    .background(trackColor, RoundedCornerShape(4.dp))
                            )
                            if (visibleFraction > 0f) {
                                Box(
                                    Modifier
                                        .width(barWidth)
                                        .fillMaxHeight(visibleFraction)
                                        .background(
                                            if (selectedDate == day.date) {
                                                BrandColors.Primary
                                            } else {
                                                usageBarColor(day.emphasis)
                                            },
                                            RoundedCornerShape(
                                                topStart = 4.dp,
                                                topEnd = 4.dp,
                                                bottomStart = 2.dp,
                                                bottomEnd = 2.dp
                                            )
                                        )
                                )
                            }
                        }
                    }
                }
                if (model.totalMillis <= 0L) {
                    Text(
                        text = "近 7 日暂无前台使用记录",
                        color = BrandColors.TextTertiary,
                        fontSize = 11.sp,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(axisWidth + 8.dp))
            model.days.forEach { day ->
                Text(
                    text = day.date.format(chartDateFormatter),
                    color = BrandColors.TextTertiary,
                    fontSize = 9.sp,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

private val chartDateFormatter = DateTimeFormatter.ofPattern("MM-dd")
private val chartAccessibilityDateFormatter = DateTimeFormatter.ofPattern("M月d日")
