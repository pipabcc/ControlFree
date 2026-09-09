package com.example.controlfree.ui.todo.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.controlfree.theme.BrandColors
import java.time.LocalDate
import java.time.YearMonth

internal val DEFAULT_CALENDAR_YEAR_RANGE: IntRange = 1900..2100

/**
 * 适配窄弹窗的七列日历。
 *
 * 日期网格完全按父容器宽度等分，不依赖 Material DatePicker 的推荐宽度，
 * 因而不会在 AlertDialog 中裁掉周五、周六。日期背景保持透明，仅选中日绘制圆形。
 */
@Composable
internal fun CompactCalendar(
    selectedDate: LocalDate,
    onDateSelected: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    initialDisplayedMonth: YearMonth = YearMonth.from(selectedDate),
    yearRange: IntRange = DEFAULT_CALENDAR_YEAR_RANGE
) {
    require(!yearRange.isEmpty()) { "年份范围不能为空" }

    var displayedMonth by remember(initialDisplayedMonth, yearRange) {
        mutableStateOf(coerceCalendarMonth(initialDisplayedMonth, yearRange))
    }
    var isEditingYearMonth by remember(initialDisplayedMonth) { mutableStateOf(false) }
    var yearInput by remember(initialDisplayedMonth) {
        mutableStateOf(displayedMonth.year.toString())
    }
    var monthInput by remember(initialDisplayedMonth) {
        mutableStateOf(displayedMonth.monthValue.toString())
    }
    val focusManager = LocalFocusManager.current
    val parsedInput = parseCalendarYearMonth(yearInput, monthInput, yearRange)
    val monthCells = remember(displayedMonth) { compactCalendarMonthCells(displayedMonth) }
    val firstAllowedMonth = remember(yearRange) { YearMonth.of(yearRange.first, 1) }
    val lastAllowedMonth = remember(yearRange) { YearMonth.of(yearRange.last, 12) }

    fun showYearMonthEditor() {
        yearInput = displayedMonth.year.toString()
        monthInput = displayedMonth.monthValue.toString()
        isEditingYearMonth = true
    }

    fun applyYearMonthInput() {
        val targetMonth = parseCalendarYearMonth(yearInput, monthInput, yearRange) ?: return
        displayedMonth = targetMonth
        isEditingYearMonth = false
        focusManager.clearFocus()
    }

    fun moveDisplayedMonth(monthOffset: Long) {
        val targetMonth = displayedMonth.plusMonths(monthOffset)
        if (targetMonth < firstAllowedMonth || targetMonth > lastAllowedMonth) return
        displayedMonth = targetMonth
        if (isEditingYearMonth) {
            yearInput = targetMonth.year.toString()
            monthInput = targetMonth.monthValue.toString()
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isEditingYearMonth) {
                CalendarNumberField(
                    value = yearInput,
                    onValueChange = { yearInput = it.filter(Char::isDigit).take(4) },
                    width = 76.dp,
                    suffix = "年",
                    isError = yearInput.isNotEmpty() && parsedInput == null,
                    contentDescription = "输入年份",
                    onDone = ::applyYearMonthInput
                )
                Spacer(Modifier.width(4.dp))
                CalendarNumberField(
                    value = monthInput,
                    onValueChange = { monthInput = it.filter(Char::isDigit).take(2) },
                    width = 48.dp,
                    suffix = "月",
                    isError = monthInput.isNotEmpty() && parsedInput == null,
                    contentDescription = "输入月份",
                    onDone = ::applyYearMonthInput
                )
                IconButton(
                    onClick = ::applyYearMonthInput,
                    enabled = parsedInput != null,
                    modifier = Modifier.size(CALENDAR_HEADER_ACTION_SIZE)
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "应用年月",
                        tint = BrandColors.Primary
                    )
                }
            } else {
                TextButton(onClick = ::showYearMonthEditor) {
                    Text(
                        text = "${displayedMonth.year}年${displayedMonth.monthValue}月",
                        color = BrandColors.Primary,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }

            Spacer(Modifier.weight(1f))
            IconButton(
                onClick = { moveDisplayedMonth(-1L) },
                enabled = displayedMonth > firstAllowedMonth,
                modifier = Modifier.size(CALENDAR_HEADER_ACTION_SIZE)
            ) {
                Icon(
                    imageVector = Icons.Default.ChevronLeft,
                    contentDescription = "上个月",
                    tint = BrandColors.Primary
                )
            }
            IconButton(
                onClick = { moveDisplayedMonth(1L) },
                enabled = displayedMonth < lastAllowedMonth,
                modifier = Modifier.size(CALENDAR_HEADER_ACTION_SIZE)
            ) {
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = "下个月",
                    tint = BrandColors.Primary
                )
            }
        }

        CalendarWeekdayHeader()
        monthCells.chunked(DAYS_PER_WEEK).forEach { week ->
            Row(modifier = Modifier.fillMaxWidth()) {
                week.forEach { date ->
                    CalendarDayCell(
                        date = date,
                        selectedDate = selectedDate,
                        onDateSelected = onDateSelected,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun CalendarWeekdayHeader() {
    Row(modifier = Modifier.fillMaxWidth()) {
        CHINESE_WEEKDAY_LABELS.forEach { label ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(CALENDAR_WEEKDAY_HEIGHT),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    color = BrandColors.TextSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun CalendarDayCell(
    date: LocalDate?,
    selectedDate: LocalDate,
    onDateSelected: (LocalDate) -> Unit,
    modifier: Modifier = Modifier
) {
    if (date == null) {
        Box(modifier = modifier.height(CALENDAR_DAY_CELL_HEIGHT))
        return
    }

    val isSelected = date == selectedDate
    val isToday = date == LocalDate.now()
    val indicatorModifier = Modifier
        .size(CALENDAR_SELECTED_DAY_SIZE)
        .then(
            if (isSelected) {
                Modifier.background(BrandColors.Primary, CircleShape)
            } else {
                Modifier
            }
        )
        .then(
            if (isToday && !isSelected) {
                Modifier.border(1.dp, BrandColors.Primary, CircleShape)
            } else {
                Modifier
            }
        )

    Box(
        modifier = modifier
            .height(CALENDAR_DAY_CELL_HEIGHT)
            .clickable(
                onClickLabel = "选择${date.year}年${date.monthValue}月${date.dayOfMonth}日"
            ) {
                onDateSelected(date)
            },
        contentAlignment = Alignment.Center
    ) {
        Box(modifier = indicatorModifier, contentAlignment = Alignment.Center) {
            Text(
                text = date.dayOfMonth.toString(),
                color = when {
                    isSelected -> Color.White
                    isToday -> BrandColors.Primary
                    else -> BrandColors.TextPrimary
                },
                fontSize = 14.sp,
                fontWeight = if (isSelected || isToday) FontWeight.Medium else FontWeight.Normal
            )
        }
    }
}

@Composable
private fun CalendarNumberField(
    value: String,
    onValueChange: (String) -> Unit,
    width: Dp,
    suffix: String,
    isError: Boolean,
    contentDescription: String,
    onDone: () -> Unit
) {
    val borderColor = if (isError) BrandColors.Danger else BrandColors.OutlineSoft
    Box(
        modifier = Modifier
            .width(width)
            .height(CALENDAR_NUMBER_FIELD_HEIGHT)
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .padding(horizontal = 3.dp)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            textStyle = LocalTextStyle.current.copy(
                color = BrandColors.TextPrimary,
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            ),
            cursorBrush = SolidColor(BrandColors.Primary),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            decorationBox = { innerTextField ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        innerTextField()
                    }
                    Text(
                        text = suffix,
                        color = BrandColors.TextSecondary,
                        fontSize = 12.sp
                    )
                }
            }
        )
    }
}

internal fun compactCalendarMonthCells(month: YearMonth): List<LocalDate?> {
    val leadingEmptyCellCount = month.atDay(1).dayOfWeek.value % DAYS_PER_WEEK
    val occupiedCellCount = leadingEmptyCellCount + month.lengthOfMonth()
    val totalCellCount =
        ((occupiedCellCount + DAYS_PER_WEEK - 1) / DAYS_PER_WEEK) * DAYS_PER_WEEK

    return List(totalCellCount) { index ->
        val dayOfMonth = index - leadingEmptyCellCount + 1
        if (dayOfMonth in 1..month.lengthOfMonth()) month.atDay(dayOfMonth) else null
    }
}

internal fun parseCalendarYearMonth(
    yearText: String,
    monthText: String,
    yearRange: IntRange = DEFAULT_CALENDAR_YEAR_RANGE
): YearMonth? {
    val year = yearText.toIntOrNull() ?: return null
    val month = monthText.toIntOrNull() ?: return null
    if (year !in yearRange || month !in 1..12) return null
    return YearMonth.of(year, month)
}

private fun coerceCalendarMonth(month: YearMonth, yearRange: IntRange): YearMonth = when {
    month.year < yearRange.first -> YearMonth.of(yearRange.first, 1)
    month.year > yearRange.last -> YearMonth.of(yearRange.last, 12)
    else -> month
}

private const val DAYS_PER_WEEK = 7
private val CHINESE_WEEKDAY_LABELS = listOf("日", "一", "二", "三", "四", "五", "六")
private val CALENDAR_HEADER_ACTION_SIZE = 40.dp
private val CALENDAR_NUMBER_FIELD_HEIGHT = 36.dp
private val CALENDAR_WEEKDAY_HEIGHT = 30.dp
private val CALENDAR_DAY_CELL_HEIGHT = 42.dp
private val CALENDAR_SELECTED_DAY_SIZE = 36.dp
