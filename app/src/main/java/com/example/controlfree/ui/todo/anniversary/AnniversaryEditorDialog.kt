@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.example.controlfree.ui.todo.anniversary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.controlfree.theme.BrandColors
import com.example.controlfree.ui.todo.components.ItemReminderEditorPage
import com.example.controlfree.ui.todo.components.StableDatePickerPage
import android.os.Build
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import java.time.LocalDate

@Composable
fun AnniversaryEditorDialog(
    initialDraft: AnniversaryEditorDraft,
    lunarCalendar: LunarCalendarConverter,
    onDismiss: () -> Unit,
    onDeleteRequest: (() -> Unit)? = null,
    onSave: (AnniversaryEditorDraft) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var draft by rememberSaveable(initialDraft, stateSaver = AnniversaryEditorDraftSaver) {
        mutableStateOf(initialDraft)
    }
    var editorPage by remember(initialDraft.id) {
        mutableStateOf(AnniversaryEditorPage.MAIN)
    }
    var showDeleteConfirmation by remember(initialDraft.id) { mutableStateOf(false) }
    var isOverlayPermissionGranted by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                android.provider.Settings.canDrawOverlays(context)
            } else {
                true
            }
        )
    }
    LaunchedEffect(Unit) {
        while (true) {
            isOverlayPermissionGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                android.provider.Settings.canDrawOverlays(context)
            } else {
                true
            }
            kotlinx.coroutines.delay(1000)
        }
    }
    val validationError = remember(draft) {
        if (!draft.isValid) null else runCatching {
            AnniversaryOccurrenceResolver(lunarCalendar).anchorInstant(draft.toSpec())
        }.exceptionOrNull()?.message
    }

    ModalBottomSheet(
        onDismissRequest = {
            if (editorPage == AnniversaryEditorPage.MAIN) {
                onDismiss()
            } else {
                editorPage = AnniversaryEditorPage.MAIN
            }
        },
        modifier = Modifier.fillMaxWidth(),
        containerColor = BrandColors.Surface,
        contentColor = BrandColors.TextPrimary,
        tonalElevation = 0.dp,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .size(36.dp, 4.dp)
                    .background(BrandColors.OutlineSoft, CircleShape)
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = when (editorPage) {
                        AnniversaryEditorPage.MAIN -> {
                            if (initialDraft.id == null) "新建时刻" else "编辑时刻"
                        }
                        AnniversaryEditorPage.SOLAR_DATE -> "选择日期"
                        AnniversaryEditorPage.ADD_REMINDER -> "添加提醒"
                    },
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = BrandColors.TextPrimary
                )
                
                if (editorPage == AnniversaryEditorPage.MAIN && initialDraft.id != null) {
                    IconButton(onClick = { showDeleteConfirmation = true }) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "删除时刻",
                            tint = BrandColors.Danger
                        )
                    }
                } else if (editorPage != AnniversaryEditorPage.MAIN) {
                    TextButton(onClick = { editorPage = AnniversaryEditorPage.MAIN }) {
                        Text("返回")
                    }
                }
            }

            Box(modifier = Modifier.weight(1f, fill = false)) {
                when (editorPage) {
                    AnniversaryEditorPage.SOLAR_DATE -> {
                        val initialDate = runCatching {
                            LocalDate.of(draft.year, draft.month, draft.day)
                        }.getOrDefault(LocalDate.now())
                        StableDatePickerPage(
                            initialSelectedDateMillis = initialDate.toUtcDatePickerMillis(),
                            onCancel = { editorPage = AnniversaryEditorPage.MAIN },
                            onSelected = { selectedDateMillis ->
                                val selectedDate =
                                    datePickerMillisToLocalDate(selectedDateMillis)
                                draft = draft.copy(
                                    year = selectedDate.year,
                                    month = selectedDate.monthValue,
                                    day = selectedDate.dayOfMonth
                                )
                                editorPage = AnniversaryEditorPage.MAIN
                            }
                        )
                    }

                    AnniversaryEditorPage.ADD_REMINDER -> ItemReminderEditorPage(
                        onCancel = { editorPage = AnniversaryEditorPage.MAIN },
                        onAdd = { reminder ->
                            draft = draft.copy(reminders = draft.reminders + reminder)
                            editorPage = AnniversaryEditorPage.MAIN
                        }
                    )

                    AnniversaryEditorPage.MAIN -> Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 480.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = draft.title,
                            onValueChange = { draft = draft.copy(title = it.take(60)) },
                            label = { Text("名称") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = draft.type == AnniversaryType.COUNTDOWN,
                                onClick = { draft = draft.copy(type = AnniversaryType.COUNTDOWN) },
                                label = { Text("倒数") }
                            )
                            FilterChip(
                                selected = draft.type == AnniversaryType.COUNT_UP,
                                onClick = {
                                    draft = draft.copy(
                                        type = AnniversaryType.COUNT_UP,
                                        repeatRule = AnniversaryRepeatRule.NONE
                                    )
                                },
                                label = { Text("正数") }
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = draft.calendarType == AnniversaryCalendarType.SOLAR,
                                onClick = {
                                    draft = draft.copy(
                                        calendarType = AnniversaryCalendarType.SOLAR,
                                        isLunarLeapMonth = false
                                    )
                                },
                                label = { Text("公历") }
                            )
                            FilterChip(
                                selected = draft.calendarType == AnniversaryCalendarType.LUNAR,
                                onClick = { draft = draft.copy(calendarType = AnniversaryCalendarType.LUNAR) },
                                label = { Text("农历") }
                            )
                        }
                        if (draft.calendarType == AnniversaryCalendarType.SOLAR) {
                            OutlinedTextField(
                                value = "%04d-%02d-%02d".format(draft.year, draft.month, draft.day),
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("日期") },
                                trailingIcon = {
                                    IconButton(
                                        onClick = { editorPage = AnniversaryEditorPage.SOLAR_DATE }
                                    ) {
                                        Icon(Icons.Default.CalendarMonth, contentDescription = "选择日期")
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                NumericField("年", draft.year, 1901..2099, Modifier.weight(1.5f)) {
                                    draft = draft.copy(year = it)
                                }
                                NumericField("月", draft.month, 1..12, Modifier.weight(1f)) {
                                    draft = draft.copy(month = it)
                                }
                                NumericField("日", draft.day, 1..30, Modifier.weight(1f)) {
                                    draft = draft.copy(day = it)
                                }
                            }
                            SettingSwitch(
                                label = "闰月",
                                checked = draft.isLunarLeapMonth,
                                onCheckedChange = { draft = draft.copy(isLunarLeapMonth = it) }
                            )
                        }
                        Text("时间", color = BrandColors.TextSecondary)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            NumericField("时", draft.hour, 0..23, Modifier.weight(1f)) {
                                draft = draft.copy(hour = it)
                            }
                            NumericField("分", draft.minute, 0..59, Modifier.weight(1f)) {
                                draft = draft.copy(minute = it)
                            }
                            NumericField("秒", draft.second, 0..59, Modifier.weight(1f)) {
                                draft = draft.copy(second = it)
                            }
                        }
                        if (draft.type == AnniversaryType.COUNTDOWN) {
                            Text("重复", color = BrandColors.TextSecondary)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                AnniversaryRepeatRule.entries.forEach { rule ->
                                    FilterChip(
                                        selected = draft.repeatRule == rule,
                                        onClick = { draft = draft.copy(repeatRule = rule) },
                                        label = {
                                            Text(
                                                when (rule) {
                                                    AnniversaryRepeatRule.NONE -> "不重复"
                                                    AnniversaryRepeatRule.MONTHLY -> "每月"
                                                    AnniversaryRepeatRule.YEARLY -> "每年"
                                                }
                                            )
                                        }
                                    )
                                }
                            }
                        }
                        SettingSwitch("置顶", draft.isPinned) { draft = draft.copy(isPinned = it) }
                        SettingSwitch("桌面组件", draft.showOnWidget) { draft = draft.copy(showOnWidget = it) }
                        SettingSwitch("下拉通知", draft.showOnLockScreen) { draft = draft.copy(showOnLockScreen = it) }
                        Text("时区：${draft.zoneId}", color = BrandColors.TextTertiary)
                        Spacer(modifier = Modifier.height(8.dp))
                        ReminderSection(
                            reminders = draft.reminders,
                            onRemindersChange = { draft = draft.copy(reminders = it) },
                            onAddReminderClick = {
                                editorPage = AnniversaryEditorPage.ADD_REMINDER
                            }
                        )
                        
                        Spacer(Modifier.height(4.dp))
                        
                        Surface(
                            color = if (isOverlayPermissionGranted) BrandColors.Primary.copy(alpha = 0.08f) else BrandColors.Danger.copy(alpha = 0.08f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (isOverlayPermissionGranted) "✅ 悬浮窗权限：已就绪" else "⚠️ 悬浮窗权限：未开启",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isOverlayPermissionGranted) BrandColors.Primary else BrandColors.Danger
                                    )
                                    if (!isOverlayPermissionGranted) {
                                        TextButton(
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                            onClick = {
                                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                                    val intent = android.content.Intent(
                                                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                        android.net.Uri.parse("package:${context.packageName}")
                                                    )
                                                    context.startActivity(intent)
                                                }
                                            }
                                        ) {
                                            Text("去开启", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = BrandColors.Danger)
                                        }
                                    }
                                }
                                Text(
                                    text = "说明：自启动与后台无限制活动需您在系统“设置 -> 应用管理 -> 省电策略”中人工确认开启，以防后台通知被系统拦截。",
                                    fontSize = 9.sp,
                                    lineHeight = 13.sp,
                                    color = BrandColors.TextSecondary
                                )
                            }
                        }

                        if (validationError != null) {
                            Text(validationError, color = BrandColors.Danger)
                        }
                    }
                }
            }

            if (editorPage == AnniversaryEditorPage.MAIN) {
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(11.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = BrandColors.TextSecondary),
                        border = BorderStroke(1.dp, BrandColors.OutlineSoft)
                    ) {
                        Text("取消")
                    }
                    Button(
                        enabled = draft.isValid && validationError == null,
                        onClick = { onSave(draft.copy(title = draft.title.trim())) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(11.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = BrandColors.Primary,
                            contentColor = BrandColors.OnPrimary
                        )
                    ) {
                        Text("保存")
                    }
                }
            }
        }
    }

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            containerColor = BrandColors.OverlaySurface,
            title = { Text("删除时刻？") },
            text = { Text("“${initialDraft.title}”及其提醒设置将被删除。") },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirmation = false
                        onDeleteRequest?.invoke()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BrandColors.Danger)
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun NumericField(
    label: String,
    value: Int,
    range: IntRange,
    modifier: Modifier,
    onValueChanged: (Int) -> Unit
) {
    var textState by remember { mutableStateOf(value.toString()) }
    
    LaunchedEffect(value) {
        if (value != textState.toIntOrNull()) {
            textState = value.toString()
        }
    }
    
    OutlinedTextField(
        value = textState,
        onValueChange = { input ->
            val digits = input.filter { it.isDigit() }
            val parsed = digits.toIntOrNull()
            when {
                digits.isEmpty() -> {
                    textState = ""
                    onValueChanged(0)
                }
                parsed == null || parsed > range.last -> {
                    // 超出上限忽略
                }
                else -> {
                    textState = digits
                    onValueChanged(parsed)
                }
            }
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier.onFocusChanged { state ->
            if (state.isFocused) {
                if (textState == "0") {
                    textState = ""
                }
            } else {
                if (textState.isEmpty()) {
                    textState = "0"
                    onValueChanged(0)
                }
            }
        }
    )
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ReminderSection(
    reminders: List<com.example.controlfree.todo.ItemReminder>,
    onRemindersChange: (List<com.example.controlfree.todo.ItemReminder>) -> Unit,
    onAddReminderClick: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("提醒", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (reminders.isEmpty()) {
                Text("未设置提醒", fontSize = 12.sp, color = BrandColors.TextSecondary, modifier = Modifier.weight(1f))
            } else {
                Row(
                    modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    reminders.forEach { reminder ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .background(BrandColors.SurfaceRaised, RoundedCornerShape(8.dp))
                                .border(1.dp, BrandColors.OutlineSoft, RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(formatReminderText(reminder), fontSize = 11.sp, color = BrandColors.TextPrimary)
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Default.Remove,
                                contentDescription = "删除",
                                tint = BrandColors.TextSecondary,
                                modifier = Modifier
                                    .size(12.dp)
                                    .clickable { onRemindersChange(reminders - reminder) }
                            )
                        }
                    }
                }
            }

            IconButton(onClick = onAddReminderClick) {
                Icon(Icons.Default.Add, contentDescription = "添加提醒")
            }
        }
    }
}

private fun formatReminderText(reminder: com.example.controlfree.todo.ItemReminder): String {
    val timeStr = when {
        reminder.minutesBefore == 0 -> "准时"
        reminder.minutesBefore % 10080 == 0 -> "${reminder.minutesBefore / 10080}周前"
        reminder.minutesBefore % 1440 == 0 -> "${reminder.minutesBefore / 1440}天前"
        reminder.minutesBefore % 60 == 0 -> "${reminder.minutesBefore / 60}小时前"
        else -> "${reminder.minutesBefore}分钟前"
    }
    val channelStr = when (reminder.channel) {
        com.example.controlfree.todo.ReminderChannel.NOTIFICATION -> "通知"
        com.example.controlfree.todo.ReminderChannel.CARD -> "卡片"
        com.example.controlfree.todo.ReminderChannel.BOTH -> "通知&卡片"
    }
    return "$timeStr ($channelStr)"
}

private enum class AnniversaryEditorPage {
    MAIN,
    SOLAR_DATE,
    ADD_REMINDER
}
