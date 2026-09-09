package com.example.controlfree.ui.todo.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.controlfree.todo.ItemReminder
import com.example.controlfree.todo.ReminderChannel

/**
 * 待办和时刻共用的提醒编辑内容页。
 *
 * 不创建新的 AlertDialog，避免打开提醒页时底层窗口的遮罩和阴影短暂消失。
 */
@Composable
internal fun ItemReminderEditorPage(
    onCancel: () -> Unit,
    onAdd: (ItemReminder) -> Unit
) {
    var selectedMinutes by remember { mutableStateOf(DEFAULT_REMINDER_MINUTES) }
    var channel by remember { mutableStateOf(ReminderChannel.BOTH) }
    var isCustomInput by remember { mutableStateOf(false) }
    var customText by remember { mutableStateOf("") }
    var customUnit by remember { mutableStateOf(ReminderOffsetUnit.MINUTE) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 520.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "提醒时间",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            REMINDER_PRESETS.forEach { (minutes, label) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            selectedMinutes = minutes
                            isCustomInput = false
                        }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = selectedMinutes == minutes && !isCustomInput,
                        onClick = {
                            selectedMinutes = minutes
                            isCustomInput = false
                        }
                    )
                    Text(label)
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isCustomInput = true }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = isCustomInput,
                    onClick = { isCustomInput = true }
                )
                Text("自定义前提醒…")
            }
        }

        if (isCustomInput) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = customText,
                    onValueChange = { input ->
                        if (input.length <= MAX_CUSTOM_INPUT_LENGTH && input.all(Char::isDigit)) {
                            customText = input
                        }
                    },
                    label = { Text("时间数值") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                Column {
                    ReminderOffsetUnit.entries.forEach { unit ->
                        Row(
                            modifier = Modifier.clickable { customUnit = unit },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = customUnit == unit,
                                onClick = { customUnit = unit }
                            )
                            Text(unit.label, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))
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
                FilterChip(
                    selected = channel == reminderChannel,
                    onClick = { channel = reminderChannel },
                    label = {
                        Text(
                            reminderChannel.displayName(),
                            fontSize = 11.sp
                        )
                    },
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
                enabled = !isCustomInput || customText.isNotBlank(),
                onClick = {
                    val finalMinutes = if (isCustomInput) {
                        customText.toLongOrNull()
                            ?.times(customUnit.minuteMultiplier)
                            ?.coerceIn(0L, Int.MAX_VALUE.toLong())
                            ?.toInt()
                            ?: return@Button
                    } else {
                        selectedMinutes
                    }
                    onAdd(ItemReminder(finalMinutes, channel))
                }
            ) {
                Text("添加")
            }
        }
    }
}

private fun ReminderChannel.displayName(): String = when (this) {
    ReminderChannel.NOTIFICATION -> "下拉通知"
    ReminderChannel.CARD -> "卡片弹窗"
    ReminderChannel.BOTH -> "两者都有"
}

private enum class ReminderOffsetUnit(
    val label: String,
    val minuteMultiplier: Long
) {
    MINUTE("分钟", 1L),
    HOUR("小时", 60L),
    DAY("天", 1_440L)
}

private val REMINDER_PRESETS = listOf(
    0 to "准时",
    10 to "10 分钟前",
    15 to "15 分钟前",
    30 to "30 分钟前",
    60 to "1 小时前",
    120 to "2 小时前",
    1_440 to "1 天前",
    2_880 to "2 天前",
    10_080 to "1 周前"
)

private const val DEFAULT_REMINDER_MINUTES = 10
private const val MAX_CUSTOM_INPUT_LENGTH = 6
