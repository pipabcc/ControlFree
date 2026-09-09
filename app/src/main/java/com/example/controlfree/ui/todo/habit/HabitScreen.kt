package com.example.controlfree.ui.todo.habit

import android.Manifest
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NoteAlt
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.controlfree.theme.BrandColors
import com.example.controlfree.todo.HabitItemEntity
import com.example.controlfree.todo.HabitRecordEntity
import com.example.controlfree.todo.CommitmentSourceType
import com.example.controlfree.todo.habit.runtime.HabitReminderNotificationPublisher
import com.example.controlfree.todo.habit.runtime.isHabitReminderNotificationAccessAvailable
import com.example.controlfree.todo.habit.runtime.formattedTime
import com.example.controlfree.ui.todo.commitment.CommitmentEditorSettings
import com.example.controlfree.ui.todo.components.TodoEmptyStatePanel
import com.example.controlfree.ui.todo.viewmodel.HabitViewModel
import com.example.controlfree.ui.todo.search.SearchNavigationRequest
import com.example.controlfree.ui.main.RuntimePermissionRequestHistory
import com.example.controlfree.ui.main.findActivity
import com.example.controlfree.ui.main.shouldOpenApplicationPermissionSettings
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun HabitScreen(
    viewModel: HabitViewModel,
    searchNavigationRequest: SearchNavigationRequest? = null,
    onSearchNavigationConsumed: (SearchNavigationRequest) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val habits by viewModel.habits.collectAsStateWithLifecycle()
    val records by viewModel.allRecords.collectAsStateWithLifecycle()
    val unlockedBadgeTiers by viewModel.unlockedHabitBadgeTiers.collectAsStateWithLifecycle()
    val commitmentPolicies by viewModel.commitmentPolicies.collectAsStateWithLifecycle()
    val supervisableApps by viewModel.supervisableApps.collectAsStateWithLifecycle()
    val isLoadingApps by viewModel.isLoadingSupervisableApps.collectAsStateWithLifecycle()
    val appsLoadFailed by viewModel.supervisableAppsLoadFailed.collectAsStateWithLifecycle()
    val reminderSettings by viewModel.reminderSettings.collectAsStateWithLifecycle()
    val today = LocalDate.now(viewModel.clock)
    val zoneId = viewModel.clock.zone
    var editorDraft by remember { mutableStateOf<HabitEditorDraft?>(null) }
    var selectedHabitId by remember { mutableStateOf<String?>(null) }
    var recordDraft by remember { mutableStateOf<Pair<HabitItemEntity, LocalDate>?>(null) }
    var notificationAccessRevision by remember { mutableIntStateOf(0) }
    val snackbarHostState = remember { SnackbarHostState() }
    val permissionRequestHistory = remember {
        RuntimePermissionRequestHistory(context.applicationContext)
    }
    val hasNotificationAccess = remember(notificationAccessRevision) {
        hasHabitReminderNotificationAccess(context.applicationContext)
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            permissionRequestHistory.clearRequested(Manifest.permission.POST_NOTIFICATIONS)
            viewModel.refreshReminderSchedule()
        }
        notificationAccessRevision++
        coroutineScope.launch {
            snackbarHostState.showSnackbar(
                if (granted) "习惯提醒通知已开启" else "未开启通知权限，提醒将无法显示"
            )
        }
    }
    val notificationSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        notificationAccessRevision++
        viewModel.refreshReminderSchedule()
    }

    fun reportNotificationSettingsFailure() {
        coroutineScope.launch {
            snackbarHostState.showSnackbar("无法打开通知设置，请在系统设置中手动处理")
        }
    }

    fun openNotificationSettings() {
        HabitReminderNotificationPublisher(context.applicationContext).ensureChannel()
        val notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        val settingsIntent = if (
            notificationsEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
        ) {
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                putExtra(
                    Settings.EXTRA_CHANNEL_ID,
                    HabitReminderNotificationPublisher.CHANNEL_ID
                )
            }
        } else {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            }
        }
        try {
            notificationSettingsLauncher.launch(settingsIntent)
        } catch (_: RuntimeException) {
            try {
                notificationSettingsLauncher.launch(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                )
            } catch (_: RuntimeException) {
                reportNotificationSettingsFailure()
            }
        }
    }

    fun requestNotificationAccess() {
        val permissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (permissionGranted) {
            openNotificationSettings()
            return
        }
        val shouldShowRationale = context.findActivity()?.let { activity ->
            ActivityCompat.shouldShowRequestPermissionRationale(
                activity,
                Manifest.permission.POST_NOTIFICATIONS
            )
        } == true
        if (
            shouldOpenApplicationPermissionSettings(
                wasRequested = permissionRequestHistory.wasRequested(
                    Manifest.permission.POST_NOTIFICATIONS
                ),
                shouldShowRationale = shouldShowRationale
            )
        ) {
            try {
                notificationSettingsLauncher.launch(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                )
            } catch (_: RuntimeException) {
                reportNotificationSettingsFailure()
            }
        } else {
            permissionRequestHistory.markRequested(Manifest.permission.POST_NOTIFICATIONS)
            try {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } catch (_: RuntimeException) {
                reportNotificationSettingsFailure()
            }
        }
    }
    LaunchedEffect(Unit) {
        viewModel.messages.collect(snackbarHostState::showSnackbar)
    }
    LaunchedEffect(habits) {
        if (selectedHabitId !in habits.map { it.id }) selectedHabitId = habits.firstOrNull()?.id
    }
    val selectedHabit = habits.firstOrNull { it.id == selectedHabitId }
    val recordsByHabit = records.groupBy(HabitRecordEntity::habitId)
    val commitmentsByHabit = remember(commitmentPolicies) {
        commitmentPolicies
            .filter { it.policy.sourceType == CommitmentSourceType.HABIT.storedValue }
            .associate { relation ->
                relation.policy.sourceId to CommitmentEditorSettings(
                    enabled = relation.policy.enabled,
                    localDeadlineMinute = relation.policy.localDeadlineMinute,
                    graceMinutes = relation.policy.graceMinutes,
                    maxLockMinutes = relation.policy.maxLockMinutes,
                    blockedPackages = relation.blockedApps.mapTo(linkedSetOf()) { it.packageName }
                )
            }
    }

    LaunchedEffect(searchNavigationRequest?.revision, habits, commitmentsByHabit) {
        val request = searchNavigationRequest ?: return@LaunchedEffect
        val habit = habits.firstOrNull { it.id == request.entityId } ?: return@LaunchedEffect
        selectedHabitId = habit.id
        editorDraft = HabitEditorDraft.fromEntity(
            habit,
            zoneId,
            commitmentsByHabit[habit.id] ?: CommitmentEditorSettings()
        )
        onSearchNavigationConsumed(request)
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = BrandColors.PageBackground,
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { contentPadding ->
        Box(Modifier.fillMaxSize().padding(contentPadding)) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 72.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                "习惯",
                                fontSize = 24.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = BrandColors.BackgroundTextPrimary
                            )
                            if (habits.isNotEmpty()) {
                                Surface(
                                    color = BrandColors.BackgroundAccentContainer,
                                    shape = RoundedCornerShape(100.dp)
                                ) {
                                    Text(
                                        "${habits.size}个习惯",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = BrandColors.BackgroundAccent,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.size(4.dp))
                        Text(
                            "按自己的节奏持续积累",
                            fontSize = 11.sp,
                            color = BrandColors.BackgroundTextSecondary
                        )
                    }

                }
            }
            item {
                HabitReminderSettingsCard(
                    enabled = reminderSettings.enabled,
                    formattedTime = reminderSettings.formattedTime(),
                    hasNotificationAccess = hasNotificationAccess,
                    onEnabledChange = { enabled ->
                        viewModel.setReminderEnabled(enabled)
                        if (enabled && !hasNotificationAccess) requestNotificationAccess()
                    },
                    onTimeClick = {
                        TimePickerDialog(
                            context,
                            { _, hour, minute ->
                                viewModel.setReminderMinute(hour * 60 + minute)
                            },
                            reminderSettings.reminderMinute / 60,
                            reminderSettings.reminderMinute % 60,
                            true
                        ).show()
                    },
                    onNotificationAccessClick = ::requestNotificationAccess
                )
            }
            item {
                Text(
                    "习惯库",
                    fontWeight = FontWeight.SemiBold,
                    color = BrandColors.BackgroundTextPrimary
                )
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(HabitTemplateCatalog.templates, key = HabitTemplate::id) { template ->
                        OutlinedButton(
                            onClick = { editorDraft = HabitEditorDraft.fromTemplate(template, today) },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                HabitIconRegistry.iconFor(template.iconKey),
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.size(5.dp))
                            Text(template.name)
                        }
                    }
                }
            }
            if (selectedHabit != null) {
                item(key = "progress-${selectedHabit.id}") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "${selectedHabit.name}统计",
                                fontWeight = FontWeight.SemiBold,
                                color = BrandColors.BackgroundTextPrimary
                            )
                            TextButton(
                                onClick = { recordDraft = selectedHabit to today.minusDays(1) }
                            ) {
                                Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.size(4.dp))
                                Text("补签")
                            }
                        }
                        HabitProgressPanel(
                            schedule = selectedHabit.toSchedule(zoneId),
                            progress = recordsByHabit[selectedHabit.id].orEmpty().mapNotNull {
                                it.toDayProgress()
                            },
                            today = today,
                            habitColor = parseHabitColor(selectedHabit.colorHex),
                            persistedBadgeTiers = unlockedBadgeTiers[selectedHabit.id].orEmpty(),
                            onDateSelected = { date -> recordDraft = selectedHabit to date }
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    HorizontalDivider(color = BrandColors.BackgroundDivider)
                }
            }
            if (habits.isEmpty()) {
                item {
                    TodoEmptyStatePanel(
                        icon = Icons.Default.TrackChanges,
                        title = "开始第一个习惯",
                        description = "从习惯库选择一个项目，或新建自定义习惯",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        actionIcon = Icons.Default.Add,
                        actionLabel = "新建自定义习惯",
                        onAction = { editorDraft = HabitEditorDraft(startDate = today) }
                    )
                }
            } else {
                items(habits, key = HabitItemEntity::id) { habit ->
                    val todayRecord = recordsByHabit[habit.id]
                        ?.firstOrNull { it.completedDate == today.toString() }
                    HabitRow(
                        habit = habit,
                        todayRecord = todayRecord,
                        selected = habit.id == selectedHabitId,
                        scheduledToday = HabitProgressCalculator.isScheduled(habit.toSchedule(zoneId), today),
                        onSelect = { selectedHabitId = habit.id },
                        onCheckIn = { viewModel.checkIn(habit.id, today, null, false) },
                        onEdit = {
                            editorDraft = HabitEditorDraft.fromEntity(
                                habit,
                                zoneId,
                                commitmentsByHabit[habit.id] ?: CommitmentEditorSettings()
                            )
                        },
                        onNote = { recordDraft = habit to today },
                        onBackfill = { recordDraft = habit to today.minusDays(1) },
                        onArchive = { viewModel.archiveHabit(habit.id) }
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(modifier = Modifier.weight(4f))
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center
            ) {
                FloatingActionButton(
                    onClick = { editorDraft = HabitEditorDraft(startDate = today) },
                    shape = CircleShape,
                    containerColor = BrandColors.Primary.copy(alpha = 0.12f),
                    contentColor = BrandColors.Primary,
                    elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
                    modifier = Modifier.size(46.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "新建习惯",
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

    editorDraft?.let { draft ->
        HabitEditorDialog(
            initialDraft = draft,
            onDismiss = { editorDraft = null },
            onSave = {
                viewModel.saveHabit(it)
                editorDraft = null
            },
            supervisableApps = supervisableApps,
            isLoadingSupervisableApps = isLoadingApps,
            supervisableAppsLoadFailed = appsLoadFailed,
            onRetrySupervisableApps = viewModel::retrySupervisableApps
        )
    }
    recordDraft?.let { (habit, initialDate) ->
        HabitRecordDialog(
            habit = habit,
            initialDate = initialDate,
            today = today,
            records = recordsByHabit[habit.id].orEmpty(),
            onDismiss = { recordDraft = null },
            onIncrement = { date, note ->
                viewModel.checkIn(habit.id, date, note, date != today)
            },
            onDecrement = { date -> viewModel.decrementCheckIn(habit.id, date) },
            onSaveNote = { date, note -> viewModel.saveRecordNote(habit.id, date, note) }
        )
    }
}

@Composable
private fun HabitReminderSettingsCard(
    enabled: Boolean,
    formattedTime: String,
    hasNotificationAccess: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onTimeClick: () -> Unit,
    onNotificationAccessClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = BrandColors.SurfaceCard),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.NotificationsActive,
                    contentDescription = null,
                    tint = BrandColors.Primary
                )
                Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text("每日提醒", fontWeight = FontWeight.SemiBold, color = BrandColors.TextPrimary)
                    Text(
                        if (enabled) "每天 $formattedTime 汇总未达标习惯" else "提醒已关闭",
                        fontSize = 12.sp,
                        color = BrandColors.TextSecondary
                    )
                }
                TextButton(onClick = onTimeClick, enabled = enabled) {
                    Text(formattedTime)
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = BrandColors.Primary,
                        uncheckedThumbColor = if (androidx.compose.foundation.isSystemInDarkTheme()) Color(0xFF94A3B8) else Color(0xFFFFFFFF),
                        uncheckedTrackColor = if (androidx.compose.foundation.isSystemInDarkTheme()) Color(0xFF334155) else Color(0xFFE2E8F0),
                        uncheckedBorderColor = Color.Transparent
                    )
                )
            }
            if (enabled && !hasNotificationAccess) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "需要通知权限才能显示提醒",
                        modifier = Modifier.weight(1f),
                        fontSize = 12.sp,
                        color = BrandColors.TextSecondary
                    )
                    TextButton(onClick = onNotificationAccessClick) {
                        Text("开启通知")
                    }
                }
            }
        }
    }
}

@Composable
private fun HabitRow(
    habit: HabitItemEntity,
    todayRecord: HabitRecordEntity?,
    selected: Boolean,
    scheduledToday: Boolean,
    onSelect: () -> Unit,
    onCheckIn: () -> Unit,
    onEdit: () -> Unit,
    onNote: () -> Unit,
    onBackfill: () -> Unit,
    onArchive: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val color = parseHabitColor(habit.colorHex)
    val count = todayRecord?.completionCount ?: 0
    val completed = count >= habit.targetCountPerDay
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) BrandColors.PrimaryContainer else BrandColors.SurfaceCard
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(color.copy(alpha = 0.16f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    HabitIconRegistry.iconFor(habit.iconRes),
                    contentDescription = null,
                    tint = color
                )
            }
            Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    habit.name,
                    fontWeight = FontWeight.SemiBold,
                    color = BrandColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    if (scheduledToday) "今日 $count/${habit.targetCountPerDay}" else "今日休息",
                    fontSize = 11.sp,
                    color = BrandColors.TextSecondary
                )
                Spacer(Modifier.height(5.dp))
                LinearProgressIndicator(
                    progress = { (count.toFloat() / habit.targetCountPerDay.coerceAtLeast(1)).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = color,
                    trackColor = BrandColors.SurfaceRaised
                )
            }
            Button(
                onClick = onCheckIn,
                enabled = scheduledToday && !completed,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp)
            ) {
                Icon(
                    if (completed) Icons.Default.Check else Icons.Default.Add,
                    contentDescription = if (completed) "今日已完成" else "打卡",
                    modifier = Modifier.size(18.dp)
                )
                if (habit.targetCountPerDay > 1) {
                    Spacer(Modifier.size(4.dp))
                    Text("$count/${habit.targetCountPerDay}")
                }
            }
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "更多操作")
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("编辑") },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        onClick = { showMenu = false; onEdit() }
                    )
                    DropdownMenuItem(
                        text = { Text("心得") },
                        leadingIcon = { Icon(Icons.Default.NoteAlt, contentDescription = null) },
                        onClick = { showMenu = false; onNote() }
                    )
                    DropdownMenuItem(
                        text = { Text("补签") },
                        leadingIcon = { Icon(Icons.Default.History, contentDescription = null) },
                        onClick = { showMenu = false; onBackfill() }
                    )
                    DropdownMenuItem(
                        text = { Text("归档") },
                        leadingIcon = { Icon(Icons.Default.Archive, contentDescription = null) },
                        onClick = { showMenu = false; onArchive() }
                    )
                }
            }
        }
    }
}

@Composable
private fun HabitRecordDialog(
    habit: HabitItemEntity,
    initialDate: LocalDate,
    today: LocalDate,
    records: List<HabitRecordEntity>,
    onDismiss: () -> Unit,
    onIncrement: (LocalDate, String?) -> Unit,
    onDecrement: (LocalDate) -> Unit,
    onSaveNote: (LocalDate, String?) -> Unit
) {
    var date by remember(initialDate) { mutableStateOf(initialDate.coerceAtMost(today)) }
    var showDatePicker by remember { mutableStateOf(false) }
    val record = records.firstOrNull { it.completedDate == date.toString() }
    var note by remember(date, record?.note) { mutableStateOf(record?.note.orEmpty()) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (date == today) "今日记录" else "补签记录") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { showDatePicker = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.History, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text(date.toString())
                }
                Text(
                    "完成 ${record?.completionCount ?: 0}/${habit.targetCountPerDay}",
                    color = BrandColors.TextSecondary
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { onDecrement(date) },
                        enabled = (record?.completionCount ?: 0) > 0,
                        modifier = Modifier.weight(1f)
                    ) { Text("撤销一次") }
                    Button(
                        onClick = { onIncrement(date, note.ifBlank { null }) },
                        enabled = (record?.completionCount ?: 0) < habit.targetCountPerDay,
                        modifier = Modifier.weight(1f)
                    ) { Text("打卡一次") }
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(500) },
                    label = { Text("心得") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSaveNote(date, note.ifBlank { null })
                onDismiss()
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
    if (showDatePicker) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = date.toUtcMillis())
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let(::localDateFromUtcMillis)?.let {
                        date = it.coerceAtMost(today)
                    }
                    showDatePicker = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("取消") } }
        ) { DatePicker(state = pickerState) }
    }
}

private fun parseHabitColor(value: String): Color = runCatching {
    Color(android.graphics.Color.parseColor(value))
}.getOrDefault(Color(0xFF087939))

private fun LocalDate.toUtcMillis(): Long = atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()

private fun localDateFromUtcMillis(value: Long): LocalDate =
    java.time.Instant.ofEpochMilli(value).atZone(java.time.ZoneOffset.UTC).toLocalDate()

private fun hasHabitReminderNotificationAccess(context: Context): Boolean {
    val permissionRequired = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val permissionGranted = !permissionRequired ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
    val notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
    val channelsSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
    if (!channelsSupported) {
        return isHabitReminderNotificationAccessAvailable(
            notificationsEnabled = notificationsEnabled,
            runtimePermissionRequired = permissionRequired,
            runtimePermissionGranted = permissionGranted,
            notificationChannelsSupported = false,
            channelDisabled = false
        )
    }
    val manager = context.getSystemService(NotificationManager::class.java) ?: return false
    val channel = manager.getNotificationChannel(HabitReminderNotificationPublisher.CHANNEL_ID)
    return isHabitReminderNotificationAccessAvailable(
        notificationsEnabled = notificationsEnabled,
        runtimePermissionRequired = permissionRequired,
        runtimePermissionGranted = permissionGranted,
        notificationChannelsSupported = true,
        channelDisabled = channel?.importance == NotificationManager.IMPORTANCE_NONE
    )
}
