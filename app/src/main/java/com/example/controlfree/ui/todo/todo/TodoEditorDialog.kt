@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.example.controlfree.ui.todo.todo

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.controlfree.ai.ProductivityAiCoordinator
import com.example.controlfree.data.AllowedApp
import com.example.controlfree.theme.BrandColors
import com.example.controlfree.ui.todo.commitment.CommitmentPolicyEditor
import com.example.controlfree.ui.todo.components.ItemReminderEditorPage
import com.example.controlfree.ui.todo.components.StableDateTimePickerPage
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun TodoEditorDialog(
    initialDraft: TodoEditorDraft,
    knownProjects: List<String>,
    onDismiss: () -> Unit,
    onSave: (TodoEditorDraft) -> Unit,
    onDeleteRequest: () -> Unit = {},
    supervisableApps: List<AllowedApp> = emptyList(),
    isLoadingSupervisableApps: Boolean = false,
    supervisableAppsLoadFailed: Boolean = false,
    onRetrySupervisableApps: () -> Unit = {},
    bottomActions: @Composable () -> Unit = {}
) {
    val zoneId = remember { ZoneId.systemDefault() }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val aiCoordinator = remember(context) { ProductivityAiCoordinator.getInstance(context) }
    var isBreakingDown by remember { mutableStateOf(false) }
    var title by remember(initialDraft.id) { mutableStateOf(initialDraft.title) }
    var description by remember(initialDraft.id) { mutableStateOf(initialDraft.description) }
    var project by remember(initialDraft.id) { mutableStateOf(initialDraft.project) }
    var dueAt by remember(initialDraft.id) { mutableStateOf(initialDraft.dueAtEpochMillis) }
    var scheduledStart by remember(initialDraft.id) {
        mutableStateOf(initialDraft.scheduledStartEpochMillis)
    }
    var scheduledEnd by remember(initialDraft.id) {
        mutableStateOf(initialDraft.scheduledEndEpochMillis)
    }
    var estimateText by remember(initialDraft.id) {
        mutableStateOf(initialDraft.estimatedFocusMinutes.toString())
    }
    var isImportant by remember(initialDraft.id) { mutableStateOf(initialDraft.isImportant) }
    var urgencyMode by remember(initialDraft.id) { mutableStateOf(initialDraft.urgencyMode) }
    var recurrenceType by remember(initialDraft.id) {
        mutableStateOf(initialDraft.recurrence.type)
    }
    var recurrenceInterval by remember(initialDraft.id) {
        mutableIntStateOf(initialDraft.recurrence.interval)
    }
    var recurrenceDaysMask by remember(initialDraft.id) {
        mutableIntStateOf(initialDraft.recurrence.weekdaysMask)
    }
    var recurrenceDayOfMonth by remember(initialDraft.id) {
        mutableIntStateOf(initialDraft.recurrence.dayOfMonth ?: 1)
    }
    var commitmentSettings by remember(initialDraft.id) {
        mutableStateOf(initialDraft.commitmentSettings)
    }
    var reminders by remember(initialDraft.id) {
        mutableStateOf(initialDraft.reminders)
    }
    var subtasks by remember(initialDraft.id) {
        mutableStateOf(initialDraft.subtasks)
    }
    var newSubtaskTitle by remember(initialDraft.id) { mutableStateOf("") }
    var showDeleteConfirmation by remember(initialDraft.id) { mutableStateOf(false) }
    var dateTimePickerRequest by remember(initialDraft.id) {
        mutableStateOf<TodoDateTimePickerRequest?>(null)
    }
    var isReminderEditorVisible by remember(initialDraft.id) { mutableStateOf(false) }
    var validationMessage by remember(initialDraft.id) { mutableStateOf<String?>(null) }
    val activeDateTimeRequest = dateTimePickerRequest
    val isPrimaryEditorPage = activeDateTimeRequest == null && !isReminderEditorVisible

    ModalBottomSheet(
        onDismissRequest = {
            when {
                activeDateTimeRequest != null -> dateTimePickerRequest = null
                isReminderEditorVisible -> isReminderEditorVisible = false
                else -> onDismiss()
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
                    text = when {
                        activeDateTimeRequest != null -> when (activeDateTimeRequest.target) {
                            TodoDateTimeTarget.DUE -> "截止时间"
                            TodoDateTimeTarget.START -> "开始时间"
                            TodoDateTimeTarget.END -> "结束时间"
                        }
                        isReminderEditorVisible -> "添加提醒"
                        initialDraft.id == null -> "新建待办"
                        else -> "编辑待办"
                    },
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = BrandColors.TextPrimary
                )

                if (isPrimaryEditorPage && initialDraft.id != null) {
                    IconButton(
                        onClick = { showDeleteConfirmation = true },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "删除待办",
                            tint = BrandColors.Danger
                        )
                    }
                } else if (!isPrimaryEditorPage) {
                    TextButton(onClick = {
                        when {
                            activeDateTimeRequest != null -> dateTimePickerRequest = null
                            isReminderEditorVisible -> isReminderEditorVisible = false
                        }
                    }) {
                        Text("返回")
                    }
                }
            }

            Box(modifier = Modifier.weight(1f, fill = false)) {
                when {
                    activeDateTimeRequest != null -> StableDateTimePickerPage(
                        initialEpochMillis = activeDateTimeRequest.initialEpochMillis,
                        zoneId = zoneId,
                        onCancel = { dateTimePickerRequest = null },
                        onSelected = { selectedEpochMillis ->
                            when (activeDateTimeRequest.target) {
                                TodoDateTimeTarget.DUE -> dueAt = selectedEpochMillis
                                TodoDateTimeTarget.START -> {
                                    val priorDuration =
                                        scheduleDurationMillis(scheduledStart, scheduledEnd)
                                            ?: DEFAULT_SCHEDULE_DURATION_MILLIS
                                    scheduledStart = selectedEpochMillis
                                    scheduledEnd = selectedEpochMillis + priorDuration
                                }
                                TodoDateTimeTarget.END -> scheduledEnd = selectedEpochMillis
                            }
                            dateTimePickerRequest = null
                        }
                    )

                    isReminderEditorVisible -> ItemReminderEditorPage(
                        onCancel = { isReminderEditorVisible = false },
                        onAdd = { reminder ->
                            reminders = reminders + reminder
                            isReminderEditorVisible = false
                        }
                    )

                    else -> LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        item {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                OutlinedTextField(
                                    value = title,
                                    onValueChange = { title = it.take(MAX_TITLE_LENGTH) },
                                    label = { Text("任务名称") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (isBreakingDown) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                            color = BrandColors.Primary
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text("AI 正在分析拆解任务...", color = BrandColors.TextSecondary, fontSize = 11.sp)
                                    } else {
                                        TextButton(
                                            onClick = {
                                                val currentTitle = title.trim()
                                                if (currentTitle.isNotBlank()) {
                                                    coroutineScope.launch {
                                                        isBreakingDown = true
                                                        try {
                                                            val parsedSubtasks = aiCoordinator.breakdownTask(currentTitle)
                                                            if (parsedSubtasks.isNotEmpty()) {
                                                                val baseSort = subtasks.maxOfOrNull { it.sortOrder } ?: 0
                                                                val newDrafts = parsedSubtasks.mapIndexed { idx, subtaskTitle ->
                                                                    TodoSubtaskDraft(
                                                                        id = java.util.UUID.randomUUID().toString(),
                                                                        title = subtaskTitle,
                                                                        parentSubtaskId = null,
                                                                        isCompleted = false,
                                                                        sortOrder = baseSort + (idx + 1) * 10
                                                                    )
                                                                }
                                                                subtasks = subtasks + newDrafts
                                                            } else {
                                                                val errMsg = "AI 无法为该任务生成子步骤，请尝试具体描述任务。"
                                                                validationMessage = errMsg
                                                                android.widget.Toast.makeText(context, errMsg, android.widget.Toast.LENGTH_LONG).show()
                                                            }
                                                        } catch (e: Exception) {
                                                            val errMsg = e.localizedMessage ?: e.message ?: "AI 拆解失败，请检查网络或配置。"
                                                            validationMessage = errMsg
                                                            android.widget.Toast.makeText(context, errMsg, android.widget.Toast.LENGTH_LONG).show()
                                                        } finally {
                                                            isBreakingDown = false
                                                        }
                                                    }
                                                } else {
                                                    val errMsg = "请先输入任务名称以便 AI 进行拆解。"
                                                    validationMessage = errMsg
                                                    android.widget.Toast.makeText(context, errMsg, android.widget.Toast.LENGTH_SHORT).show()
                                                }
                                            },
                                            modifier = Modifier.height(28.dp),
                                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                        ) {
                                             Text(
                                                 text = "AI",
                                                 fontSize = 11.sp,
                                                 fontWeight = FontWeight.Bold,
                                                 color = BrandColors.Primary
                                             )
                                            Spacer(Modifier.width(4.dp))
                                            Text("智能拆解", fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                        }
                        item {
                            EditorSubtaskSection(
                                subtasks = subtasks,
                                newTitle = newSubtaskTitle,
                                onNewTitleChange = { newSubtaskTitle = it },
                                onAdd = {
                                    val title = newSubtaskTitle.trim()
                                    if (title.isNotEmpty()) {
                                        subtasks = subtasks + TodoSubtaskDraft(
                                            id = java.util.UUID.randomUUID().toString(),
                                            title = title,
                                            sortOrder = subtasks.size
                                        )
                                        newSubtaskTitle = ""
                                    }
                                },
                                onDelete = { id ->
                                    subtasks = removeSubtaskBranch(subtasks, id)
                                },
                                onRename = { id, newTitle ->
                                    subtasks = subtasks.map {
                                        if (it.id == id) it.copy(title = newTitle) else it
                                    }
                                }
                            )
                        }

                        item {
                            OutlinedTextField(
                                value = project,
                                onValueChange = { project = it.take(MAX_PROJECT_LENGTH) },
                                label = { Text("项目") },
                                supportingText = {
                                    val suggestions = knownProjects
                                        .filter { it.isNotBlank() && !it.equals(project, ignoreCase = true) }
                                        .take(3)
                                    if (suggestions.isNotEmpty()) {
                                        Text(suggestions.joinToString(" · "))
                                    }
                                },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        item {
                            EditorSectionLabel("四象限")
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = isImportant, onCheckedChange = { isImportant = it })
                                Text("重要", modifier = Modifier.weight(1f))
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                TodoUrgencyMode.entries.forEach { mode ->
                                    FilterChip(
                                        selected = urgencyMode == mode,
                                        onClick = { urgencyMode = mode },
                                        label = { Text(mode.displayName) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                        item {
                            EditorSectionLabel("截止时间")
                            OptionalDateTimeRow(
                                value = dueAt,
                                emptyLabel = "添加截止时间",
                                zoneId = zoneId,
                                onPick = {
                                    dateTimePickerRequest = TodoDateTimePickerRequest(
                                        target = TodoDateTimeTarget.DUE,
                                        initialEpochMillis = dueAt ?: defaultDueTime(zoneId)
                                    )
                                },
                                onClear = { dueAt = null }
                            )
                        }
                        item {
                            EditorSectionLabel("日历排程")
                            OptionalDateTimeRow(
                                value = scheduledStart,
                                emptyLabel = "添加开始时间",
                                zoneId = zoneId,
                                onPick = {
                                    dateTimePickerRequest = TodoDateTimePickerRequest(
                                        target = TodoDateTimeTarget.START,
                                        initialEpochMillis = scheduledStart ?: defaultScheduleStart(zoneId)
                                    )
                                },
                                onClear = {
                                    scheduledStart = null
                                    scheduledEnd = null
                                }
                            )
                            if (scheduledStart != null) {
                                OptionalDateTimeRow(
                                    value = scheduledEnd,
                                    emptyLabel = "添加结束时间",
                                    zoneId = zoneId,
                                    onPick = {
                                        dateTimePickerRequest = TodoDateTimePickerRequest(
                                            target = TodoDateTimeTarget.END,
                                            initialEpochMillis = scheduledEnd
                                                ?: requireNotNull(scheduledStart) +
                                                DEFAULT_SCHEDULE_DURATION_MILLIS
                                        )
                                    },
                                    onClear = { scheduledEnd = null }
                                )
                            }
                        }
                        item {
                            ReminderSection(
                                reminders = reminders,
                                onRemindersChange = { reminders = it },
                                onAddReminderClick = { isReminderEditorVisible = true }
                            )
                        }
                        item {
                            EditorSectionLabel("预计专注")
                            NumericStepper(
                                valueText = estimateText,
                                suffix = "分钟",
                                range = MIN_FOCUS_MINUTES..MAX_FOCUS_MINUTES,
                                onValueTextChange = { estimateText = it }
                            )
                        }
                        item {
                            EditorSectionLabel("重复")
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                TodoRecurrenceType.entries.chunked(3).forEach { rowItems ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        rowItems.forEach { type ->
                                            FilterChip(
                                                selected = recurrenceType == type,
                                                onClick = { recurrenceType = type },
                                                label = { Text(type.displayName) },
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                        repeat(3 - rowItems.size) { androidx.compose.foundation.layout.Spacer(Modifier.weight(1f)) }
                                    }
                                }
                            }
                            if (recurrenceType in intervalRecurrenceTypes) {
                                NumericStepper(
                                    valueText = recurrenceInterval.toString(),
                                    suffix = recurrenceIntervalSuffix(recurrenceType),
                                    range = 1..365,
                                    onValueTextChange = { value ->
                                        recurrenceInterval = value.toIntOrNull()?.coerceIn(1, 365)
                                            ?: recurrenceInterval
                                    }
                                )
                            }
                            if (recurrenceType == TodoRecurrenceType.WEEKLY) {
                                WeekdaySelector(
                                    selectedMask = recurrenceDaysMask,
                                    onMaskChange = { recurrenceDaysMask = it }
                                )
                            }
                            if (recurrenceType == TodoRecurrenceType.MONTHLY) {
                                NumericStepper(
                                    valueText = recurrenceDayOfMonth.toString(),
                                    suffix = "日",
                                    range = 1..31,
                                    onValueTextChange = { value ->
                                        recurrenceDayOfMonth = value.toIntOrNull()?.coerceIn(1, 31)
                                            ?: recurrenceDayOfMonth
                                    }
                                )
                            }
                        }
                        item {
                            CommitmentPolicyEditor(
                                settings = commitmentSettings,
                                apps = supervisableApps,
                                isLoadingApps = isLoadingSupervisableApps,
                                appsLoadFailed = supervisableAppsLoadFailed,
                                onRetryApps = onRetrySupervisableApps,
                                onSettingsChange = { commitmentSettings = it }
                            )
                            if (commitmentSettings.enabled && dueAt == null) {
                                Text(
                                    "请先设置明确的截止时间",
                                    color = BrandColors.Danger,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                        item {
                            OutlinedTextField(
                                value = description,
                                onValueChange = { description = it.take(MAX_DESCRIPTION_LENGTH) },
                                label = { Text("备注") },
                                minLines = 2,
                                maxLines = 4,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        validationMessage?.let { message ->
                            item {
                                Text(
                                    message,
                                    color = BrandColors.Danger,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }

            if (isPrimaryEditorPage) {
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
                        onClick = {
                            val estimate = estimateText.toIntOrNull()
                            validationMessage = validateDraft(
                                title = title,
                                project = project,
                                scheduledStart = scheduledStart,
                                scheduledEnd = scheduledEnd,
                                estimate = estimate,
                                recurrenceType = recurrenceType,
                                recurrenceDaysMask = recurrenceDaysMask,
                                dueAt = dueAt,
                                commitmentSettings = commitmentSettings
                            )
                            if (validationMessage == null) {
                                onSave(
                                    initialDraft.copy(
                                        title = title.trim(),
                                        description = description.trim(),
                                        project = project.trim().ifEmpty { DEFAULT_PROJECT },
                                        dueAtEpochMillis = dueAt,
                                        scheduledStartEpochMillis = scheduledStart,
                                        scheduledEndEpochMillis = scheduledEnd,
                                        estimatedFocusMinutes = requireNotNull(estimate),
                                        isImportant = isImportant,
                                        urgencyMode = urgencyMode,
                                        recurrence = initialDraft.recurrence.copy(
                                            type = recurrenceType,
                                            interval = recurrenceInterval,
                                            weekdaysMask = if (recurrenceType == TodoRecurrenceType.WEEKLY) recurrenceDaysMask else 0,
                                            dayOfMonth = if (recurrenceType == TodoRecurrenceType.MONTHLY) recurrenceDayOfMonth else null
                                        ),
                                        commitmentSettings = commitmentSettings,
                                        reminders = reminders,
                                        subtasks = subtasks.mapIndexed { index, subtask ->
                                            subtask.copy(sortOrder = index)
                                        }
                                    )
                                )
                            }
                        },
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
                bottomActions()
            }
        }
    }
    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text("删除待办？") },
            text = { Text("将删除「${initialDraft.title}」及其全部子任务。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmation = false
                        onDeleteRequest()
                    }
                ) { Text("删除", color = BrandColors.Danger) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun EditorSubtaskSection(
    subtasks: List<TodoSubtaskDraft>,
    newTitle: String,
    onNewTitleChange: (String) -> Unit,
    onAdd: () -> Unit,
    onDelete: (String) -> Unit,
    onRename: (String, String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        EditorSectionLabel("子任务")
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = newTitle,
                onValueChange = { onNewTitleChange(it.take(MAX_SUBTASK_TITLE_LENGTH)) },
                placeholder = { Text("添加子任务") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = onAdd,
                enabled = newTitle.isNotBlank(),
                modifier = Modifier
                    .size(44.dp)
                    .background(BrandColors.PrimaryContainer, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "添加子任务",
                    tint = BrandColors.Primary
                )
            }
        }
        subtasks.sortedBy(TodoSubtaskDraft::sortOrder).forEach { subtask ->
            var showRenameDialog by remember { mutableStateOf(false) }
            var renameText by remember { mutableStateOf(subtask.title) }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BrandColors.SurfaceCard, RoundedCornerShape(12.dp))
                    .padding(start = 12.dp, top = 6.dp, end = 4.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = subtask.title,
                    modifier = Modifier.weight(1f),
                    color = BrandColors.TextPrimary,
                    fontSize = 14.sp,
                    maxLines = 2
                )
                IconButton(
                    onClick = {
                        renameText = subtask.title
                        showRenameDialog = true
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "编辑子任务 ${subtask.title}",
                        tint = BrandColors.TextTertiary,
                        modifier = Modifier.size(19.dp)
                    )
                }
                IconButton(onClick = { onDelete(subtask.id) }) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "删除子任务 ${subtask.title}",
                        tint = BrandColors.TextTertiary,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }

            if (showRenameDialog) {
                AlertDialog(
                    onDismissRequest = { showRenameDialog = false },
                    title = { Text("重命名子任务", fontWeight = FontWeight.Bold) },
                    text = {
                        OutlinedTextField(
                            value = renameText,
                            onValueChange = { renameText = it },
                            label = { Text("子任务标题") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                val t = renameText.trim()
                                if (t.isNotEmpty()) {
                                    onRename(subtask.id, t)
                                    showRenameDialog = false
                                }
                            }
                        ) {
                            Text("保存", fontWeight = FontWeight.Bold, color = BrandColors.Primary)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showRenameDialog = false }) {
                            Text("取消", color = BrandColors.TextSecondary)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun EditorSectionLabel(text: String) {
    Text(
        text = text,
        fontWeight = FontWeight.SemiBold,
        color = BrandColors.TextPrimary,
        modifier = Modifier.padding(bottom = 4.dp)
    )
}

@Composable
private fun OptionalDateTimeRow(
    value: Long?,
    emptyLabel: String,
    zoneId: ZoneId,
    onPick: () -> Unit,
    onClear: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedButton(onClick = onPick, modifier = Modifier.weight(1f)) {
            Text(value?.let { formatDateTime(it, zoneId) } ?: emptyLabel)
        }
        if (value != null) {
            TextButton(onClick = onClear) { Text("清除") }
        }
    }
}

@Composable
private fun NumericStepper(
    valueText: String,
    suffix: String,
    range: IntRange,
    onValueTextChange: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = {
            val current = valueText.toIntOrNull() ?: range.first
            onValueTextChange((current - 1).coerceIn(range).toString())
        }) {
            Icon(Icons.Default.Remove, contentDescription = "减少")
        }
        OutlinedTextField(
            value = valueText,
            onValueChange = { raw ->
                if (raw.length <= 3 && raw.all(Char::isDigit)) onValueTextChange(raw)
            },
            suffix = { Text(suffix) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = {
            val current = valueText.toIntOrNull() ?: range.first
            onValueTextChange((current + 1).coerceIn(range).toString())
        }) {
            Icon(Icons.Default.Add, contentDescription = "增加")
        }
    }
}

@Composable
private fun WeekdaySelector(selectedMask: Int, onMaskChange: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        weekdayLabels.forEachIndexed { index, label ->
            val flag = 1 shl index
            FilterChip(
                selected = selectedMask and flag != 0,
                onClick = { onMaskChange(selectedMask xor flag) },
                label = { Text(label) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

private fun validateDraft(
    title: String,
    project: String,
    scheduledStart: Long?,
    scheduledEnd: Long?,
    estimate: Int?,
    recurrenceType: TodoRecurrenceType,
    recurrenceDaysMask: Int,
    dueAt: Long?,
    commitmentSettings: com.example.controlfree.ui.todo.commitment.CommitmentEditorSettings
): String? = when {
    title.trim().isEmpty() -> "请输入任务名称"
    project.trim().isEmpty() -> "请输入项目名称"
    (scheduledStart == null) != (scheduledEnd == null) -> "排程必须同时设置开始和结束时间"
    scheduledStart != null && scheduledEnd != null && scheduledEnd <= scheduledStart ->
        "结束时间必须晚于开始时间"
    estimate == null || estimate !in MIN_FOCUS_MINUTES..MAX_FOCUS_MINUTES ->
        "预计专注时长必须为 1-180 分钟"
    recurrenceType == TodoRecurrenceType.WEEKLY && recurrenceDaysMask == 0 ->
        "每周重复至少选择一天"
    commitmentSettings.enabled && dueAt == null -> "防拖延监督必须设置截止时间"
    commitmentSettings.enabled && commitmentSettings.blockedPackages.isEmpty() ->
        "防拖延监督至少选择一个 App"
    else -> null
}

private fun formatDateTime(epochMillis: Long, zoneId: ZoneId): String =
    Instant.ofEpochMilli(epochMillis).atZone(zoneId).format(dateTimeFormatter)

private fun defaultDueTime(zoneId: ZoneId): Long =
    LocalDateTime.now(zoneId).plusDays(1).withHour(18).withMinute(0).withSecond(0).withNano(0)
        .atZone(zoneId).toInstant().toEpochMilli()

private fun defaultScheduleStart(zoneId: ZoneId): Long {
    val now = LocalDateTime.now(zoneId)
    val roundedMinute = ((now.minute / 15) + 1) * 15
    return now.withSecond(0).withNano(0).withMinute(0).plusMinutes(roundedMinute.toLong())
        .atZone(zoneId).toInstant().toEpochMilli()
}

private fun scheduleDurationMillis(start: Long?, end: Long?): Long? =
    if (start != null && end != null && end > start) end - start else null

private fun recurrenceIntervalSuffix(type: TodoRecurrenceType): String = when (type) {
    TodoRecurrenceType.DAILY,
    TodoRecurrenceType.AFTER_COMPLETION -> "天"
    TodoRecurrenceType.WEEKLY -> "周"
    TodoRecurrenceType.MONTHLY -> "月"
    else -> "次"
}

private val intervalRecurrenceTypes = setOf(
    TodoRecurrenceType.DAILY,
    TodoRecurrenceType.WEEKLY,
    TodoRecurrenceType.MONTHLY,
    TodoRecurrenceType.AFTER_COMPLETION
)

private val weekdayLabels = listOf("一", "二", "三", "四", "五", "六", "日")
private val dateTimeFormatter = DateTimeFormatter.ofPattern("M月d日 HH:mm")
private const val DEFAULT_SCHEDULE_DURATION_MILLIS = 60L * 60L * 1000L
private const val MAX_TITLE_LENGTH = 80
private const val MAX_SUBTASK_TITLE_LENGTH = 120
private const val MAX_DESCRIPTION_LENGTH = 1000
private const val MAX_PROJECT_LENGTH = 40

private enum class TodoDateTimeTarget {
    DUE,
    START,
    END
}

private data class TodoDateTimePickerRequest(
    val target: TodoDateTimeTarget,
    val initialEpochMillis: Long
)

@Composable
private fun ReminderSection(
    reminders: List<com.example.controlfree.todo.ItemReminder>,
    onRemindersChange: (List<com.example.controlfree.todo.ItemReminder>) -> Unit,
    onAddReminderClick: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        EditorSectionLabel("提醒")

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
