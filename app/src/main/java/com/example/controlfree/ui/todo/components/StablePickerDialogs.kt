package com.example.controlfree.ui.todo.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.controlfree.theme.BrandColors
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

private enum class DateTimePickerStep {
    DATE,
    TIME
}

/**
 * 日期和时间选择内容页。
 *
 * 此组件本身不会创建 Dialog 窗口，调用方应把它放进原编辑弹窗中。
 * 这样二级页面只替换现有窗口的内容，遮罩、阴影和窗口宽度都不会丢失。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StableDateTimePickerPage(
    initialEpochMillis: Long,
    zoneId: ZoneId,
    onCancel: () -> Unit,
    onSelected: (Long) -> Unit
) {
    val initialDateTime = remember(initialEpochMillis, zoneId) {
        Instant.ofEpochMilli(initialEpochMillis).atZone(zoneId)
    }
    var selectedDate by remember(initialEpochMillis, zoneId) {
        mutableStateOf(initialDateTime.toLocalDate())
    }
    val timePickerState = rememberTimePickerState(
        initialHour = initialDateTime.hour,
        initialMinute = initialDateTime.minute,
        is24Hour = true
    )
    var step by remember(initialEpochMillis) { mutableStateOf(DateTimePickerStep.DATE) }
    var isInputMode by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = PICKER_PAGE_MAX_HEIGHT)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        when (step) {
            DateTimePickerStep.DATE -> CompactCalendar(
                selectedDate = selectedDate,
                onDateSelected = { selectedDate = it }
            )

            DateTimePickerStep.TIME -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (isInputMode) {
                        TimeInput(
                            state = timePickerState,
                            colors = TimePickerDefaults.colors(
                                timeSelectorSelectedContainerColor = getPickerContainerColor(),
                                timeSelectorUnselectedContainerColor = getPickerContainerColor(),
                                timeSelectorSelectedContentColor = BrandColors.Primary,
                                timeSelectorUnselectedContentColor = BrandColors.TextPrimary
                            )
                        )
                    } else {
                        TimePicker(
                            state = timePickerState,
                            colors = TimePickerDefaults.colors(
                                clockDialColor = getPickerContainerColor(),
                                clockDialSelectedContentColor = Color.White,
                                clockDialUnselectedContentColor = BrandColors.TextPrimary,
                                selectorColor = BrandColors.Primary,
                                periodSelectorBorderColor = BrandColors.Outline,
                                periodSelectorSelectedContainerColor = BrandColors.PrimaryContainer,
                                periodSelectorUnselectedContainerColor = Color.Transparent,
                                periodSelectorSelectedContentColor = BrandColors.Primary,
                                periodSelectorUnselectedContentColor = BrandColors.TextSecondary,
                                timeSelectorSelectedContainerColor = getPickerContainerColor(),
                                timeSelectorUnselectedContainerColor = getPickerContainerColor(),
                                timeSelectorSelectedContentColor = BrandColors.Primary,
                                timeSelectorUnselectedContentColor = BrandColors.TextPrimary
                            )
                        )
                    }
                }
            }
        }

        if (step == DateTimePickerStep.DATE) {
            PickerActions(
                modifier = Modifier.padding(horizontal = ALERT_DIALOG_TEXT_HORIZONTAL_PADDING),
                cancelLabel = "取消",
                confirmLabel = "下一步",
                confirmEnabled = true,
                onCancel = onCancel,
                onConfirm = {
                    step = DateTimePickerStep.TIME
                }
            )
        } else {
            // 时间设置界面：键盘输入在左（图标形式），上一步与确定在右并采用非挤压布局以防文字垂直折行
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ALERT_DIALOG_TEXT_HORIZONTAL_PADDING),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { isInputMode = !isInputMode }
                ) {
                    Icon(
                        imageVector = if (isInputMode) Icons.Default.Schedule else Icons.Default.Keyboard,
                        contentDescription = if (isInputMode) "表盘选择" else "键盘输入",
                        tint = BrandColors.Primary
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = { step = DateTimePickerStep.DATE }
                    ) {
                        Text("上一步", color = BrandColors.TextSecondary)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val selectedEpochMillis = selectedDate
                                .atTime(timePickerState.hour, timePickerState.minute)
                                .atZone(zoneId)
                                .toInstant()
                                .toEpochMilli()
                            onSelected(selectedEpochMillis)
                        }
                    ) {
                        Text("确定", maxLines = 1, softWrap = false)
                    }
                }
            }
        }
    }
}

/** 只替换原编辑弹窗内容的紧凑时间选择页。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StableTimePickerPage(
    initialHour: Int,
    initialMinute: Int,
    onCancel: () -> Unit,
    onSelected: (hour: Int, minute: Int) -> Unit
) {
    val timePickerState = rememberTimePickerState(
        initialHour = initialHour.coerceIn(0, 23),
        initialMinute = initialMinute.coerceIn(0, 59),
        is24Hour = true
    )
    var isInputMode by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            if (isInputMode) {
                TimeInput(
                    state = timePickerState,
                    colors = TimePickerDefaults.colors(
                        timeSelectorSelectedContainerColor = getPickerContainerColor(),
                        timeSelectorUnselectedContainerColor = getPickerContainerColor(),
                        timeSelectorSelectedContentColor = BrandColors.Primary,
                        timeSelectorUnselectedContentColor = BrandColors.TextPrimary
                    )
                )
            } else {
                TimePicker(
                    state = timePickerState,
                    colors = TimePickerDefaults.colors(
                        clockDialColor = getPickerContainerColor(),
                        clockDialSelectedContentColor = Color.White,
                        clockDialUnselectedContentColor = BrandColors.TextPrimary,
                        selectorColor = BrandColors.Primary,
                        periodSelectorBorderColor = BrandColors.Outline,
                        periodSelectorSelectedContainerColor = BrandColors.PrimaryContainer,
                        periodSelectorUnselectedContainerColor = Color.Transparent,
                        periodSelectorSelectedContentColor = BrandColors.Primary,
                        periodSelectorUnselectedContentColor = BrandColors.TextSecondary,
                        timeSelectorSelectedContainerColor = getPickerContainerColor(),
                        timeSelectorUnselectedContainerColor = getPickerContainerColor(),
                        timeSelectorSelectedContentColor = BrandColors.Primary,
                        timeSelectorUnselectedContentColor = BrandColors.TextPrimary
                    )
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ALERT_DIALOG_TEXT_HORIZONTAL_PADDING),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { isInputMode = !isInputMode }
            ) {
                Icon(
                    imageVector = if (isInputMode) Icons.Default.Schedule else Icons.Default.Keyboard,
                    contentDescription = if (isInputMode) "表盘选择" else "键盘输入",
                    tint = BrandColors.Primary
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onCancel) {
                    Text("取消", color = BrandColors.TextSecondary)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = { onSelected(timePickerState.hour, timePickerState.minute) }
                ) {
                    Text("确定", maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

/** 只替换原编辑弹窗内容的日期选择页。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StableDatePickerPage(
    initialSelectedDateMillis: Long,
    onCancel: () -> Unit,
    onSelected: (Long) -> Unit
) {
    var selectedDate by remember(initialSelectedDateMillis) {
        mutableStateOf(
            Instant.ofEpochMilli(initialSelectedDateMillis)
                .atZone(ZoneOffset.UTC)
                .toLocalDate()
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = PICKER_PAGE_MAX_HEIGHT)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CompactCalendar(
            selectedDate = selectedDate,
            onDateSelected = { selectedDate = it }
        )
        PickerActions(
            modifier = Modifier.padding(horizontal = ALERT_DIALOG_TEXT_HORIZONTAL_PADDING),
            cancelLabel = "取消",
            confirmLabel = "确定",
            confirmEnabled = true,
            onCancel = onCancel,
            onConfirm = {
                onSelected(
                    selectedDate
                        .atStartOfDay(ZoneOffset.UTC)
                        .toInstant()
                        .toEpochMilli()
                )
            }
        )
    }
}

@Composable
private fun PickerActions(
    modifier: Modifier = Modifier,
    cancelLabel: String,
    confirmLabel: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    confirmEnabled: Boolean = true
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(modifier = Modifier.weight(1f))
        TextButton(onClick = onCancel) {
            Text(cancelLabel)
        }
        Button(
            onClick = onConfirm,
            enabled = confirmEnabled
        ) {
            Text(confirmLabel)
        }
    }
}


private val PICKER_PAGE_MAX_HEIGHT = 560.dp
private val ALERT_DIALOG_TEXT_HORIZONTAL_PADDING = 24.dp

@Composable
private fun getPickerContainerColor(): Color {
    return if (BrandColors.isCustomBackground) {
        if (BrandColors.UsesDarkForeground) {
            androidx.compose.ui.graphics.lerp(Color.White, BrandColors.BackgroundAccent, 0.12f).copy(alpha = 0.96f)
        } else {
            androidx.compose.ui.graphics.lerp(Color(0xFF1C1C1E), BrandColors.BackgroundAccent, 0.15f).copy(alpha = 0.96f)
        }
    } else {
        BrandColors.SurfaceRaised
    }
}

