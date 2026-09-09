package com.example.controlfree.ui.todo.habit

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Switch
import androidx.compose.ui.unit.sp
import com.example.controlfree.todo.HabitTimeReminder
import com.example.controlfree.todo.ReminderChannel
import com.example.controlfree.todo.HabitIntervalReminder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.example.controlfree.data.AllowedApp
import com.example.controlfree.theme.BrandColors
import com.example.controlfree.ui.todo.commitment.CommitmentPolicyEditor
import com.example.controlfree.ui.todo.components.StableTimePickerPage
import java.time.DayOfWeek

@Composable
fun HabitEditorDialog(
    initialDraft: HabitEditorDraft,
    onDismiss: () -> Unit,
    onSave: (HabitEditorDraft) -> Unit,
    supervisableApps: List<AllowedApp> = emptyList(),
    isLoadingSupervisableApps: Boolean = false,
    supervisableAppsLoadFailed: Boolean = false,
    onRetrySupervisableApps: () -> Unit = {}
) {
    var draft by remember(initialDraft) { mutableStateOf(initialDraft) }
    var editorPage by remember(initialDraft.id) {
        mutableStateOf(HabitEditorPage.MAIN)
    }
    AlertDialog(
        onDismissRequest = {
            if (editorPage == HabitEditorPage.MAIN) {
                onDismiss()
            } else {
                editorPage = HabitEditorPage.MAIN
            }
        },
        modifier = Modifier.fillMaxWidth(),
        title = {
            Text(
                when (editorPage) {
                    HabitEditorPage.MAIN -> if (draft.id == null) "新建习惯" else "编辑习惯"
                    HabitEditorPage.COMMITMENT_DEADLINE -> "每日截止时间"
                    HabitEditorPage.TIMED_REMINDER -> "添加定时提醒"
                    HabitEditorPage.INTERVAL_REMINDER -> "设置循环提醒"
                }
            )
        },
        text = {
            when (editorPage) {
                HabitEditorPage.COMMITMENT_DEADLINE -> {
                    val initialMinute =
                        draft.commitmentSettings.localDeadlineMinute ?: DEFAULT_DEADLINE_MINUTE
                    StableTimePickerPage(
                        initialHour = initialMinute / MINUTES_PER_HOUR,
                        initialMinute = initialMinute % MINUTES_PER_HOUR,
                        onCancel = { editorPage = HabitEditorPage.MAIN },
                        onSelected = { hour, minute ->
                            draft = draft.copy(
                                commitmentSettings = draft.commitmentSettings.copy(
                                    localDeadlineMinute = hour * MINUTES_PER_HOUR + minute
                                )
                            )
                            editorPage = HabitEditorPage.MAIN
                        }
                    )
                }

                HabitEditorPage.TIMED_REMINDER -> StableTimePickerPage(
                    initialHour = DEFAULT_TIMED_REMINDER_HOUR,
                    initialMinute = 0,
                    onCancel = { editorPage = HabitEditorPage.MAIN },
                    onSelected = { hour, minute ->
                        val currentConfig = draft.reminderConfig
                        if (currentConfig.timedReminders.none {
                                it.hour == hour && it.minute == minute
                            }
                        ) {
                            draft = draft.copy(
                                reminderConfig = currentConfig.copy(
                                    timedReminders = currentConfig.timedReminders +
                                        HabitTimeReminder(hour, minute, ReminderChannel.BOTH)
                                )
                            )
                        }
                        editorPage = HabitEditorPage.MAIN
                    }
                )

                HabitEditorPage.INTERVAL_REMINDER -> {
                    val intervalReminder = draft.reminderConfig.intervalReminder
                    if (intervalReminder != null) {
                        HabitIntervalReminderPage(
                            initialReminder = intervalReminder,
                            onCancel = { editorPage = HabitEditorPage.MAIN },
                            onSave = { updatedReminder ->
                                draft = draft.copy(
                                    reminderConfig = draft.reminderConfig.copy(
                                        intervalReminder = updatedReminder
                                    )
                                )
                                editorPage = HabitEditorPage.MAIN
                            }
                        )
                    }
                }

                HabitEditorPage.MAIN -> Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { draft = draft.copy(name = it.take(40)) },
                    label = { Text("习惯名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                EditorLabel("图标")
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier.height(104.dp),
                    userScrollEnabled = false,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(HabitIconRegistry.options) { option ->
                        val selected = option.key == draft.iconKey
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .background(
                                    if (selected) BrandColors.PrimaryContainer else BrandColors.SurfaceRaised,
                                    CircleShape
                                )
                                .border(
                                    if (selected) 2.dp else 1.dp,
                                    if (selected) BrandColors.Primary else BrandColors.OutlineSoft,
                                    CircleShape
                                )
                                .clickable { draft = draft.copy(iconKey = option.key) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                option.imageVector,
                                contentDescription = option.label,
                                tint = if (selected) BrandColors.Primary else BrandColors.TextSecondary
                            )
                        }
                    }
                }
                EditorLabel("颜色")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    HABIT_COLORS.forEach { colorHex ->
                        val color = Color(android.graphics.Color.parseColor(colorHex))
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .background(color, CircleShape)
                                .border(
                                    if (draft.colorHex == colorHex) 3.dp else 1.dp,
                                    if (draft.colorHex == colorHex) BrandColors.TextPrimary else Color.Transparent,
                                    CircleShape
                                )
                                .clickable { draft = draft.copy(colorHex = colorHex) }
                        )
                    }
                }
                EditorLabel("频率")
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FrequencyChip("每天", HabitFrequencyType.DAILY, draft) { draft = it }
                        FrequencyChip("星期", HabitFrequencyType.SPECIFIC_WEEKDAYS, draft) { draft = it }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FrequencyChip("每周目标", HabitFrequencyType.WEEKLY_TARGET, draft) { draft = it }
                        FrequencyChip("间隔日", HabitFrequencyType.INTERVAL, draft) { draft = it }
                    }
                }
                when (draft.frequency) {
                    HabitFrequencyType.SPECIFIC_WEEKDAYS -> WeekdaySelector(
                        mask = draft.weekdaysMask,
                        onMaskChanged = { draft = draft.copy(weekdaysMask = it) }
                    )

                    HabitFrequencyType.WEEKLY_TARGET -> NumberStepper(
                        label = "每周完成天数",
                        value = draft.weeklyTargetDays,
                        range = 1..7,
                        onValueChanged = { draft = draft.copy(weeklyTargetDays = it) }
                    )

                    HabitFrequencyType.INTERVAL -> NumberStepper(
                        label = "每隔几天",
                        value = draft.intervalDays,
                        range = 1..365,
                        onValueChanged = { draft = draft.copy(intervalDays = it) }
                    )

                    HabitFrequencyType.DAILY -> Unit
                }
                NumberStepper(
                    label = "每天目标次数",
                    value = draft.targetCountPerDay,
                    range = 1..99,
                    onValueChanged = { draft = draft.copy(targetCountPerDay = it) }
                )
                CommitmentPolicyEditor(
                    settings = draft.commitmentSettings,
                    apps = supervisableApps,
                    isLoadingApps = isLoadingSupervisableApps,
                    appsLoadFailed = supervisableAppsLoadFailed,
                    onRetryApps = onRetrySupervisableApps,
                    onSettingsChange = { draft = draft.copy(commitmentSettings = it) },
                    deadlineLabel = draft.commitmentSettings.localDeadlineMinute
                        ?.let(::formatDeadlineMinute),
                    onDeadlineClick = {
                        editorPage = HabitEditorPage.COMMITMENT_DEADLINE
                    }
                )
                Spacer(modifier = Modifier.height(8.dp))
                HabitReminderSection(
                    config = draft.reminderConfig,
                    onConfigChange = { draft = draft.copy(reminderConfig = it) },
                    onAddTimedReminderClick = {
                        editorPage = HabitEditorPage.TIMED_REMINDER
                    },
                    onEditIntervalReminderClick = {
                        editorPage = HabitEditorPage.INTERVAL_REMINDER
                    }
                )
                if (draft.frequency == HabitFrequencyType.SPECIFIC_WEEKDAYS && draft.weekdaysMask == 0) {
                    Text("至少选择一个执行日", color = MaterialTheme.colorScheme.error)
                }
                if (draft.commitmentSettings.enabled && draft.commitmentSettings.localDeadlineMinute == null) {
                    Text("请选择每日截止时间", color = MaterialTheme.colorScheme.error)
                }
            }
            }
        },
        confirmButton = {
            if (editorPage == HabitEditorPage.MAIN) {
                Button(
                    onClick = { onSave(draft.copy(name = draft.name.trim())) },
                    enabled = draft.isValid
                ) { Text("保存") }
            }
        },
        dismissButton = {
            if (editorPage == HabitEditorPage.MAIN) {
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}

private fun formatDeadlineMinute(minuteOfDay: Int): String =
    "每日 %02d:%02d 前".format(minuteOfDay / 60, minuteOfDay % 60)

@Composable
private fun EditorLabel(text: String) {
    Text(text, fontWeight = FontWeight.SemiBold, color = BrandColors.TextSecondary)
}

@Composable
private fun FrequencyChip(
    label: String,
    type: HabitFrequencyType,
    draft: HabitEditorDraft,
    onDraftChanged: (HabitEditorDraft) -> Unit
) {
    FilterChip(
        selected = draft.frequency == type,
        onClick = { onDraftChanged(draft.copy(frequency = type)) },
        label = { Text(label) }
    )
}

@Composable
private fun WeekdaySelector(mask: Int, onMaskChanged: (Int) -> Unit) {
    val labels = listOf("一", "二", "三", "四", "五", "六", "日")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("执行日", color = BrandColors.TextSecondary)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            DayOfWeek.entries.forEachIndexed { index, day ->
                val dayMask = day.toMask()
                val isSelected = mask and dayMask != 0
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .width(36.dp)
                        .height(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primaryContainer
                            else BrandColors.SurfaceRaised
                        )
                        .border(
                            width = 1.dp,
                            color = if (isSelected) MaterialTheme.colorScheme.primary
                                    else BrandColors.OutlineSoft,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .clickable { onMaskChanged(mask xor dayMask) }
                ) {
                    Text(
                        text = labels[index],
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                                else BrandColors.TextPrimary
                    )
                }
            }
        }
    }
}

@Composable
private fun NumberStepper(
    label: String,
    value: Int,
    range: IntRange,
    onValueChanged: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = BrandColors.TextSecondary)
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { onValueChanged((value - 1).coerceIn(range)) },
                enabled = value > range.first
            ) { Icon(Icons.Default.Remove, contentDescription = "减少") }
            OutlinedTextField(
                value = value.toString(),
                onValueChange = { raw ->
                    raw.toIntOrNull()?.let { onValueChanged(it.coerceIn(range)) }
                },
                modifier = Modifier.width(72.dp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            IconButton(
                onClick = { onValueChanged((value + 1).coerceIn(range)) },
                enabled = value < range.last
            ) { Icon(Icons.Default.Add, contentDescription = "增加") }
        }
    }
}

@Composable
private fun HabitReminderSection(
    config: com.example.controlfree.todo.HabitReminderConfig,
    onConfigChange: (com.example.controlfree.todo.HabitReminderConfig) -> Unit,
    onAddTimedReminderClick: () -> Unit,
    onEditIntervalReminderClick: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("提醒设置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

        Text("定时提醒", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (config.timedReminders.isEmpty()) {
                Text("未设置定时提醒", fontSize = 12.sp, color = BrandColors.TextSecondary, modifier = Modifier.weight(1f))
            } else {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    config.timedReminders.forEach { time ->
                        val timeStr = "%02d:%02d".format(time.hour, time.minute)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .background(BrandColors.SurfaceRaised, RoundedCornerShape(8.dp))
                                .border(1.dp, BrandColors.OutlineSoft, RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(timeStr, fontSize = 11.sp, color = BrandColors.TextPrimary)
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Default.Remove,
                                contentDescription = "删除",
                                tint = BrandColors.TextSecondary,
                                modifier = Modifier
                                    .size(12.dp)
                                    .clickable {
                                        onConfigChange(config.copy(timedReminders = config.timedReminders - time))
                                    }
                            )
                        }
                    }
                }
            }

            IconButton(onClick = onAddTimedReminderClick) {
                Icon(Icons.Default.Add, contentDescription = "添加定时")
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("间隔循环提醒", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Switch(
                checked = config.intervalReminder != null,
                onCheckedChange = { checked ->
                    if (checked) {
                        onConfigChange(config.copy(
                            intervalReminder = HabitIntervalReminder(
                                intervalMinutes = 60,
                                channel = ReminderChannel.BOTH,
                                startHour = 8,
                                endHour = 22
                            )
                        ))
                    } else {
                        onConfigChange(config.copy(intervalReminder = null))
                    }
                }
            )
        }

        config.intervalReminder?.let { ir ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BrandColors.SurfaceRaised, RoundedCornerShape(12.dp))
                    .border(1.dp, BrandColors.OutlineSoft, RoundedCornerShape(12.dp))
                    .clickable(onClick = onEditIntervalReminderClick)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val unitStr = if (ir.intervalMinutes >= 60) "${ir.intervalMinutes / 60}小时" else "${ir.intervalMinutes}分钟"
                    Text("每隔 $unitStr 循环提醒", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text("生效时段: ${ir.startHour}:00 - ${ir.endHour}:00", fontSize = 11.sp, color = BrandColors.TextSecondary)
                }
                Icon(Icons.Default.Edit, contentDescription = "编辑", tint = BrandColors.Primary)
            }
        }
    }
}

@Composable
private fun HabitIntervalReminderPage(
    initialReminder: HabitIntervalReminder,
    onCancel: () -> Unit,
    onSave: (HabitIntervalReminder) -> Unit
) {
    var intervalText by remember(initialReminder) {
        mutableStateOf(initialReminder.intervalMinutes.toString())
    }
    var channel by remember(initialReminder) { mutableStateOf(initialReminder.channel) }
    var startHourText by remember(initialReminder) {
        mutableStateOf(initialReminder.startHour.toString())
    }
    var endHourText by remember(initialReminder) {
        mutableStateOf(initialReminder.endHour.toString())
    }
    val intervalMinutes = intervalText.toIntOrNull()
    val startHour = startHourText.toIntOrNull()
    val endHour = endHourText.toIntOrNull()
    val isValid = intervalMinutes != null && intervalMinutes > 0 &&
        startHour in 0..23 && endHour in 0..23

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 520.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "提醒间隔（分钟）",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        OutlinedTextField(
            value = intervalText,
            onValueChange = { input ->
                if (input.length <= 5 && input.all(Char::isDigit)) {
                    intervalText = input
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "生效时段",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            OutlinedTextField(
                value = startHourText,
                onValueChange = { input ->
                    if (input.length <= 2 && input.all(Char::isDigit)) {
                        startHourText = input
                    }
                },
                label = { Text("开始小时") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            OutlinedTextField(
                value = endHourText,
                onValueChange = { input ->
                    if (input.length <= 2 && input.all(Char::isDigit)) {
                        endHourText = input
                    }
                },
                label = { Text("结束小时") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
        }

        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "通知方式",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ReminderChannel.entries.forEach { reminderChannel ->
                val label = when (reminderChannel) {
                    ReminderChannel.NOTIFICATION -> "通知"
                    ReminderChannel.CARD -> "卡片"
                    ReminderChannel.BOTH -> "两者都有"
                }
                FilterChip(
                    selected = channel == reminderChannel,
                    onClick = { channel = reminderChannel },
                    label = { Text(label, fontSize = 11.sp) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onCancel) {
                Text("取消")
            }
            Button(
                enabled = isValid,
                onClick = {
                    onSave(
                        HabitIntervalReminder(
                            intervalMinutes = requireNotNull(intervalMinutes),
                            channel = channel,
                            startHour = requireNotNull(startHour),
                            endHour = requireNotNull(endHour)
                        )
                    )
                }
            ) {
                Text("保存")
            }
        }
    }
}

private enum class HabitEditorPage {
    MAIN,
    COMMITMENT_DEADLINE,
    TIMED_REMINDER,
    INTERVAL_REMINDER
}

private const val MINUTES_PER_HOUR = 60
private const val DEFAULT_DEADLINE_MINUTE = 20 * MINUTES_PER_HOUR
private const val DEFAULT_TIMED_REMINDER_HOUR = 8
