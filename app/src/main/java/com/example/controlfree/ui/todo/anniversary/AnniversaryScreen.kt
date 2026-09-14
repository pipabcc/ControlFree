package com.example.controlfree.ui.todo.anniversary

import android.app.NotificationManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.controlfree.theme.BrandColors
import com.example.controlfree.todo.AnniversaryItemEntity
import com.example.controlfree.todo.anniversary.runtime.AnniversaryNotificationPublisher
import com.example.controlfree.todo.anniversary.widget.AnniversaryWidgetProvider
import com.example.controlfree.ui.todo.components.CardActionHintStore
import com.example.controlfree.ui.todo.components.CardActionHintTab
import com.example.controlfree.ui.todo.components.TodoEmptyStatePanel
import com.example.controlfree.ui.todo.components.message
import com.example.controlfree.ui.todo.viewmodel.AnniversaryNotificationPermissionAction
import com.example.controlfree.ui.todo.viewmodel.AnniversaryViewModel
import com.example.controlfree.ui.todo.search.SearchNavigationRequest
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class AnniversarySaveSideEffects(
    val shouldRequestNotificationPermission: Boolean,
    val shouldOpenNotificationSettings: Boolean,
    val shouldRequestWidgetPin: Boolean
)

internal enum class AnniversaryNotificationAccess {
    AVAILABLE,
    RUNTIME_PERMISSION_REQUIRED,
    SYSTEM_SETTINGS_REQUIRED
}

internal fun anniversaryNotificationAccess(
    runtimePermissionRequired: Boolean,
    runtimePermissionGranted: Boolean,
    appNotificationsEnabled: Boolean,
    channelEnabled: Boolean
): AnniversaryNotificationAccess = when {
    runtimePermissionRequired && !runtimePermissionGranted ->
        AnniversaryNotificationAccess.RUNTIME_PERMISSION_REQUIRED
    !appNotificationsEnabled || !channelEnabled ->
        AnniversaryNotificationAccess.SYSTEM_SETTINGS_REQUIRED
    else -> AnniversaryNotificationAccess.AVAILABLE
}

internal fun anniversarySaveSideEffects(
    wasWidgetEnabled: Boolean,
    willWidgetBeEnabled: Boolean,
    willLockScreenBeEnabled: Boolean,
    notificationAccess: AnniversaryNotificationAccess
): AnniversarySaveSideEffects = AnniversarySaveSideEffects(
    shouldRequestNotificationPermission = willLockScreenBeEnabled &&
        notificationAccess == AnniversaryNotificationAccess.RUNTIME_PERMISSION_REQUIRED,
    shouldOpenNotificationSettings = willLockScreenBeEnabled &&
        notificationAccess == AnniversaryNotificationAccess.SYSTEM_SETTINGS_REQUIRED,
    shouldRequestWidgetPin = willWidgetBeEnabled && !wasWidgetEnabled
)

@Composable
fun AnniversaryScreen(
    viewModel: AnniversaryViewModel,
    searchNavigationRequest: SearchNavigationRequest? = null,
    onSearchNavigationConsumed: (SearchNavigationRequest) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val applicationContext = LocalContext.current.applicationContext
    val items by viewModel.anniversaries.collectAsStateWithLifecycle()
    val isAnniversaryDataLoaded by viewModel.isAnniversaryDataLoaded.collectAsStateWithLifecycle()
    // 屏幕级时钟只用于日期粒度（新建时的 today）；秒级倒计时由卡片内部时钟驱动，
    // 避免整页每秒重组。
    val now by produceState(initialValue = viewModel.clock.instant(), viewModel.clock) {
        while (true) {
            val waitMillis = 60_000L - viewModel.clock.millis().mod(60_000L)
            delay(waitMillis)
            value = viewModel.clock.instant()
        }
    }
    var editorDraft by rememberSaveable(stateSaver = NullableAnniversaryEditorDraftSaver) {
        mutableStateOf<AnniversaryEditorDraft?>(null)
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val cardHintStore = remember(applicationContext) {
        CardActionHintStore(applicationContext)
    }
    val notificationSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val enabled = currentAnniversaryNotificationAccess(applicationContext) ==
            AnniversaryNotificationAccess.AVAILABLE
        if (viewModel.resolveNotificationPermissionAction(enabled)) {
            editorDraft = null
            viewModel.notifyUser(
                if (enabled) "系统通知已开启，锁屏常驻已启用"
                else "系统通知仍未开启，锁屏常驻未启用"
            )
        }
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (viewModel.peekNotificationPermissionAction() == null) {
            return@rememberLauncherForActivityResult
        }
        when {
            !granted -> {
                viewModel.resolveNotificationPermissionAction(false)
                editorDraft = null
                viewModel.notifyUser("未授予通知权限，锁屏常驻未开启")
            }

            currentAnniversaryNotificationAccess(applicationContext) ==
                AnniversaryNotificationAccess.AVAILABLE -> {
                viewModel.resolveNotificationPermissionAction(true)
                editorDraft = null
            }

            else -> {
                notificationSettingsLauncher.launch(
                    anniversaryNotificationSettingsIntent(applicationContext)
                )
                viewModel.notifyUser("系统通知已关闭，请在设置中开启")
            }
        }
    }
    LaunchedEffect(viewModel, applicationContext) {
        if (
            viewModel.peekNotificationPermissionAction() != null &&
            currentAnniversaryNotificationAccess(applicationContext) ==
            AnniversaryNotificationAccess.AVAILABLE
        ) {
            viewModel.resolveNotificationPermissionAction(true)
            editorDraft = null
        }
        launch {
            viewModel.messages.collect { message ->
                snackbarHostState.showSnackbar(message)
                viewModel.consumeMessage(message)
            }
        }
        launch {
            viewModel.widgetPinRequests.collect { anniversaryId ->
                val requestAccepted = requestPinWidget(applicationContext)
                viewModel.completeWidgetPinRequest(anniversaryId, requestAccepted)
            }
        }
    }

    LaunchedEffect(items.isNotEmpty(), cardHintStore) {
        if (cardHintStore.shouldShowWhenReady(CardActionHintTab.ANNIVERSARY, items.isNotEmpty())) {
            snackbarHostState.showSnackbar(
                message = CardActionHintTab.ANNIVERSARY.message(),
                withDismissAction = true,
                duration = SnackbarDuration.Long
            )
            cardHintStore.markShown(CardActionHintTab.ANNIVERSARY)
        }
    }

    val sortedItems = remember(items) {
        items.sortedWith(
            compareByDescending<AnniversaryItemEntity> { it.isPinnedTop }
                .thenByDescending { it.createdAtEpochMillis }
        )
    }

    LaunchedEffect(searchNavigationRequest?.revision, items) {
        val request = searchNavigationRequest ?: return@LaunchedEffect
        val item = items.firstOrNull { it.id == request.entityId } ?: return@LaunchedEffect
        editorDraft = AnniversaryEditorDraft.fromEntity(item, viewModel.clock.zone)
        onSearchNavigationConsumed(request)
    }

    val deleteWithUndo: (AnniversaryItemEntity) -> Unit = { item ->
        coroutineScope.launch {
            val snapshot = viewModel.deleteAnniversaryForUndo(item.id)
            if (snapshot == null) {
                snackbarHostState.showSnackbar("删除失败，时刻可能已不存在")
                return@launch
            }
            val result = snackbarHostState.showSnackbar(
                message = "已删除「${item.title}」",
                actionLabel = "撤销",
                withDismissAction = true,
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) {
                val restored = withContext(NonCancellable) {
                    viewModel.restoreDeletedAnniversary(snapshot)
                }
                if (restored) snackbarHostState.showSnackbar("已撤销删除")
            }
        }
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
                                "时刻",
                                fontSize = 24.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = BrandColors.BackgroundTextPrimary
                            )
                            if (items.isNotEmpty()) {
                                Surface(
                                    color = BrandColors.BackgroundAccentContainer,
                                    shape = RoundedCornerShape(100.dp)
                                ) {
                                    Text(
                                        "${items.size}个时刻",
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
                            "时刻提醒，记录瞬间",
                            fontSize = 11.sp,
                            color = BrandColors.BackgroundTextSecondary
                        )
                    }

                }
            }
            if (items.isEmpty()) {
                item {
                    TodoEmptyStatePanel(
                        icon = Icons.Default.Event,
                        title = "时刻准备着，记录精彩瞬间",
                        description = "记录您的重要日子与倒计时",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        actionIcon = Icons.Default.Add,
                        actionLabel = "记录第一个时刻",
                        onAction = {
                            editorDraft = AnniversaryEditorDraft.create(
                                today = now.atZone(viewModel.clock.zone).toLocalDate(),
                                zoneId = viewModel.clock.zone
                            )
                        }
                    )
                }
            } else {
                items(sortedItems, key = AnniversaryItemEntity::id) { item ->
                    AnniversaryCard(
                        item = item,
                        viewModel = viewModel,
                        onEdit = {
                            editorDraft = AnniversaryEditorDraft.fromEntity(item, viewModel.clock.zone)
                        },
                        onPinChanged = { viewModel.setPinned(item.id, it) },
                        onWidgetChanged = { enabled ->
                            viewModel.setWidgetEnabled(
                                id = item.id,
                                enabled = enabled,
                                requestPinWhenEnabled = enabled
                            )
                        },
                        onLockChanged = { enabled ->
                            if (!enabled) {
                                viewModel.setLockScreenEnabled(item.id, false)
                            } else {
                                val action = AnniversaryNotificationPermissionAction.EnableExisting(item.id)
                                when (currentAnniversaryNotificationAccess(applicationContext)) {
                                    AnniversaryNotificationAccess.AVAILABLE ->
                                        viewModel.setLockScreenEnabled(item.id, true)
                                    AnniversaryNotificationAccess.RUNTIME_PERMISSION_REQUIRED -> {
                                        viewModel.stageNotificationPermissionAction(action)
                                        notificationPermissionLauncher.launch(POST_NOTIFICATIONS_PERMISSION)
                                    }
                                    AnniversaryNotificationAccess.SYSTEM_SETTINGS_REQUIRED -> {
                                        viewModel.stageNotificationPermissionAction(action)
                                        notificationSettingsLauncher.launch(
                                            anniversaryNotificationSettingsIntent(applicationContext)
                                        )
                                        viewModel.notifyUser("系统通知已关闭，请在设置中开启")
                                    }
                                }
                            }
                        }
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
                    onClick = {
                        editorDraft = AnniversaryEditorDraft.create(
                            today = now.atZone(viewModel.clock.zone).toLocalDate(),
                            zoneId = viewModel.clock.zone
                        )
                    },
                    shape = CircleShape,
                    containerColor = BrandColors.Primary.copy(alpha = 0.12f),
                    contentColor = BrandColors.Primary,
                    elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
                    modifier = Modifier.size(46.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "新建时刻",
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

    editorDraft?.let { draft ->
        AnniversaryEditorDialog(
            initialDraft = draft,
            lunarCalendar = viewModel.lunarCalendar,
            onDismiss = { editorDraft = null },
            onDeleteRequest = draft.id?.let { id ->
                {
                    items.firstOrNull { it.id == id }?.let { item ->
                        editorDraft = null
                        deleteWithUndo(item)
                    }
                }
            },
            onSave = { draft ->
                val existing = draft.id?.let { id -> items.firstOrNull { it.id == id } }
                cardHintStore.armForCreation(
                    tab = CardActionHintTab.ANNIVERSARY,
                    isDataLoaded = isAnniversaryDataLoaded,
                    wasEmpty = items.isEmpty(),
                    isNewItem = existing == null
                )
                val effects = anniversarySaveSideEffects(
                    wasWidgetEnabled = existing?.showOnWidget == true,
                    willWidgetBeEnabled = draft.showOnWidget,
                    willLockScreenBeEnabled = draft.showOnLockScreen,
                    notificationAccess = currentAnniversaryNotificationAccess(applicationContext)
                )
                val action = AnniversaryNotificationPermissionAction.SaveDraft(
                    draft = draft,
                    shouldRequestWidgetPin = effects.shouldRequestWidgetPin
                )
                when {
                    effects.shouldRequestNotificationPermission -> {
                        viewModel.stageNotificationPermissionAction(action)
                        notificationPermissionLauncher.launch(POST_NOTIFICATIONS_PERMISSION)
                    }
                    effects.shouldOpenNotificationSettings -> {
                        viewModel.stageNotificationPermissionAction(action)
                        notificationSettingsLauncher.launch(
                            anniversaryNotificationSettingsIntent(applicationContext)
                        )
                        viewModel.notifyUser("系统通知已关闭，请在设置中开启")
                    }
                    else -> {
                        viewModel.saveAnniversary(
                            draft = draft,
                            requestWidgetPinAfterSave = effects.shouldRequestWidgetPin
                        )
                        editorDraft = null
                    }
                }
            }
        )
    }
}

@Composable
private fun QuickActionButton(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String
) {
    val (checkedBg, checkedIconColor) = when (contentDescription) {
        "置顶" -> Color(0xFFFFEBEE) to Color(0xFFE53935)
        "桌面组件" -> Color(0xFFE8F5E9) to Color(0xFF43A047)
        "锁屏常驻" -> Color(0xFFE3F2FD) to Color(0xFF1E88E5)
        else -> BrandColors.Primary to Color.White
    }
    val buttonShape = RoundedCornerShape(6.dp)
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(buttonShape)
            .background(
                color = if (checked) checkedBg else BrandColors.SurfaceMuted,
                shape = buttonShape
            )
            .clickable { onCheckedChange(!checked) },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (checked) checkedIconColor else BrandColors.TextSecondary.copy(alpha = 0.6f),
            modifier = Modifier.size(12.dp)
        )
    }
}

@Composable
private fun StatusTag(text: String) {
    val (bgColor, textColor) = when (text) {
        "农历" -> Color(0xFFE3F2FD) to Color(0xFF1565C0)
        "公历" -> Color(0xFFFFF3E0) to Color(0xFFE65100)
        "单次" -> Color(0xFFECEFF1) to Color(0xFF37474F)
        "每月", "每年" -> Color(0xFFF3E5F5) to Color(0xFF6A1B9A)
        else -> BrandColors.SurfaceMuted to BrandColors.TextSecondary
    }
    Surface(
        color = bgColor,
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(0.5.dp, textColor.copy(alpha = 0.15f))
    ) {
        Text(
            text = text,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = textColor,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
        )
    }
}

private fun formattedTargetDate(item: AnniversaryItemEntity): String {
    if (item.isLunar) {
        return buildString {
            if (item.isLunarLeapMonth) append("闰")
            append("${item.sourceMonth}月${item.sourceDay}日")
        }
    } else {
        val monthAbbr = when (item.sourceMonth) {
            1 -> "JAN"
            2 -> "FEB"
            3 -> "MAR"
            4 -> "APR"
            5 -> "MAY"
            6 -> "JUN"
            7 -> "JUL"
            8 -> "AUG"
            9 -> "SEP"
            10 -> "OCT"
            11 -> "NOV"
            12 -> "DEC"
            else -> "MON"
        }
        return "$monthAbbr ${item.sourceDay}, ${item.sourceYear}"
    }
}

@Composable
private fun SparkStar(size: androidx.compose.ui.unit.Dp, color: Color, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier = modifier.size(size)) {
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(size.toPx() / 2f, 0f)
            quadraticTo(size.toPx() / 2f, size.toPx() / 2f, size.toPx(), size.toPx() / 2f)
            quadraticTo(size.toPx() / 2f, size.toPx() / 2f, size.toPx() / 2f, size.toPx())
            quadraticTo(size.toPx() / 2f, size.toPx() / 2f, 0f, size.toPx() / 2f)
            quadraticTo(size.toPx() / 2f, size.toPx() / 2f, size.toPx() / 2f, 0f)
            close()
        }
        drawPath(path, color)
    }
}

@Composable
private fun PillActionButton(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String
) {
    val activeBg = BrandColors.Primary.copy(alpha = 0.12f)
    val inactiveBg = BrandColors.OutlineSoft.copy(alpha = 0.15f)
    val activeColor = BrandColors.Primary
    val inactiveColor = BrandColors.TextSecondary

    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(if (checked) activeBg else inactiveBg)
            .border(
                width = 1.dp,
                color = if (checked) BrandColors.Primary.copy(alpha = 0.25f) else Color.Transparent,
                shape = CircleShape
            )
            .clickable { onCheckedChange(!checked) },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (checked) activeColor else inactiveColor,
            modifier = Modifier.size(15.dp)
        )
    }
}

private fun formatChineseLunarDate(lunarDate: com.example.controlfree.todo.ChineseLunarDate?): String {
    if (lunarDate == null) return ""
    val tianGan = listOf("甲", "乙", "丙", "丁", "戊", "己", "庚", "辛", "壬", "癸")
    val diZhi = listOf("子", "丑", "寅", "卯", "辰", "巳", "午", "未", "申", "酉", "戌", "亥")
    val tgIdx = (lunarDate.year - 4) % 10
    val dzIdx = (lunarDate.year - 4) % 12
    val tg = tianGan[if (tgIdx < 0) tgIdx + 10 else tgIdx]
    val dz = diZhi[if (dzIdx < 0) dzIdx + 12 else dzIdx]

    val monthNames = listOf("正", "二", "三", "四", "五", "六", "七", "八", "九", "十", "十一", "腊")
    val monthStr = (if (lunarDate.isLeapMonth) "闰" else "") + monthNames[(lunarDate.month - 1).coerceIn(0, 11)] + "月"

    val c1 = listOf("初", "十", "廿", "卅")
    val c2 = listOf("一", "二", "三", "四", "五", "六", "七", "八", "九", "十")
    val dayStr = when (lunarDate.day) {
        10 -> "初十"
        20 -> "二十"
        30 -> "三十"
        else -> {
            val d1 = lunarDate.day / 10
            val d2 = lunarDate.day % 10
            c1[d1.coerceIn(0, 3)] + c2[(d2 - 1).coerceIn(0, 9)]
        }
    }
    return "${tg}${dz}年 · ${monthStr}${dayStr}"
}

private fun targetFullLabel(item: AnniversaryItemEntity): String = buildString {
    append(item.sourceYear)
    append("年")
    append(item.sourceMonth)
    append("月")
    append(item.sourceDay)
    append("日 ")
    append("%02d:%02d:%02d".format(item.sourceHour, item.sourceMinute, item.sourceSecond))
}

private fun pillText(
    type: AnniversaryType,
    counter: AnniversaryCounter?,
    occurrence: AnniversaryOccurrence?,
    daysStr: String
): String {
    return when {
        occurrence == null -> "未知 · 0天"
        counter?.reached == true && type == AnniversaryType.COUNTDOWN && !occurrence.isRecurring -> "已到达 · 0天"
        type == AnniversaryType.COUNTDOWN -> "剩余 · ${daysStr}天"
        else -> "已过 · ${daysStr}天"
    }
}

@Composable
private fun AnniversaryCard(
    item: AnniversaryItemEntity,
    viewModel: AnniversaryViewModel,
    onEdit: () -> Unit,
    onPinChanged: (Boolean) -> Unit,
    onWidgetChanged: (Boolean) -> Unit,
    onLockChanged: (Boolean) -> Unit
) {
    // 卡片自持秒级时钟：倒计时显示精确到秒，但重组只限于本卡片
    val now by produceState(initialValue = viewModel.clock.instant(), viewModel.clock) {
        while (true) {
            val waitMillis = 1_000L - viewModel.clock.millis().mod(1_000L)
            delay(waitMillis)
            value = viewModel.clock.instant()
        }
    }
    val occurrence = remember(item, now.epochSecond) {
        runCatching { viewModel.occurrenceFor(item, now) }.getOrNull()
    }
    val type = AnniversaryType.fromStoredValue(item.type)
    val counter = occurrence?.let {
        AnniversaryCounterCalculator.calculate(type, it.instant, java.time.Clock.fixed(now, viewModel.clock.zone))
    }
    val isPinned = item.isPinnedTop
    val cardBorder = if (isPinned) {
        BorderStroke(1.5.dp, BrandColors.Primary.copy(alpha = 0.6f))
    } else {
        BorderStroke(1.dp, BrandColors.OutlineSoft.copy(alpha = 0.3f))
    }
    val cardShape = RoundedCornerShape(16.dp)

    val targetLocalDate = remember(item) {
        runCatching { java.time.LocalDate.of(item.sourceYear, item.sourceMonth, item.sourceDay) }.getOrNull()
    }
    val dayStr = "%02d".format(item.sourceDay)
    val monthStr = "${item.sourceMonth}月"
    val weekStr = remember(targetLocalDate) {
        targetLocalDate?.let {
            when (it.dayOfWeek) {
                java.time.DayOfWeek.MONDAY -> "星期一"
                java.time.DayOfWeek.TUESDAY -> "星期二"
                java.time.DayOfWeek.WEDNESDAY -> "星期三"
                java.time.DayOfWeek.THURSDAY -> "星期四"
                java.time.DayOfWeek.FRIDAY -> "星期五"
                java.time.DayOfWeek.SATURDAY -> "星期六"
                java.time.DayOfWeek.SUNDAY -> "星期日"
            }
        } ?: "星期日"
    }

    val lunarStr = remember(item, targetLocalDate) {
        if (item.isLunar) {
            val lunarDate = com.example.controlfree.todo.ChineseLunarDate(
                year = item.sourceYear,
                month = item.sourceMonth,
                day = item.sourceDay,
                isLeapMonth = item.isLunarLeapMonth
            )
            formatChineseLunarDate(lunarDate)
        } else {
            targetLocalDate?.let {
                val lunarDate = com.example.controlfree.todo.ChineseLunarCalendar.solarToLunar(it)
                formatChineseLunarDate(lunarDate)
            } ?: ""
        }
    }

    val daysStr = counter?.days?.toString() ?: "0"
    val statusBigText = when {
        counter?.reached == true && type == AnniversaryType.COUNTDOWN && !occurrence.isRecurring -> "已到达"
        type == AnniversaryType.COUNTDOWN -> "剩余 $daysStr 天"
        else -> "已过 $daysStr 天"
    }

    val statusSubText = when {
        occurrence == null -> "日期不可用"
        counter?.reached == true && type == AnniversaryType.COUNTDOWN && !occurrence.isRecurring -> "第 0 天 · 记录瞬间"
        type == AnniversaryType.COUNTDOWN -> "还有 ${counter?.format()} · 期待美好"
        else -> "已经 ${counter?.format()} · 温暖记忆"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .clickable { onEdit() },
        shape = cardShape,
        colors = CardDefaults.cardColors(
            containerColor = BrandColors.SurfaceCard
        ),
        border = cardBorder,
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isPinned) 2.dp else 0.dp
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            val headerBrush = Brush.linearGradient(
                colors = listOf(
                    BrandColors.Primary,
                    BrandColors.Primary.copy(alpha = 0.85f),
                    BrandColors.Primary.copy(alpha = 0.7f)
                )
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(headerBrush)
                    .padding(horizontal = 20.dp, vertical = 18.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text(
                            text = dayStr,
                            color = Color.White,
                            fontSize = 46.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.SansSerif
                        )
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .height(34.dp)
                                .background(Color.White.copy(alpha = 0.3f))
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = monthStr,
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = weekStr,
                                color = Color.White.copy(alpha = 0.75f),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .background(Color.White.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                                .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                                .padding(horizontal = 10.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "${item.sourceYear}",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        if (lunarStr.isNotEmpty()) {
                            Text(
                                text = lunarStr,
                                color = Color.White.copy(alpha = 0.75f),
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = item.title,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = BrandColors.TextPrimary,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    val pillColor = if (type == AnniversaryType.COUNTDOWN && counter?.reached != true) {
                        BrandColors.Secondary
                    } else {
                        BrandColors.Primary
                    }
                    Row(
                        modifier = Modifier
                            .background(pillColor.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                            .border(1.dp, pillColor.copy(alpha = 0.22f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 10.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(pillColor, CircleShape)
                        )
                        Text(
                            text = pillText(type, counter, occurrence, daysStr),
                            color = pillColor,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Event,
                        contentDescription = null,
                        tint = BrandColors.TextTertiary,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = targetFullLabel(item),
                        color = BrandColors.TextSecondary,
                        fontSize = 11.5.sp
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 20.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val gradientTextColors = if (type == AnniversaryType.COUNTDOWN && counter?.reached != true) {
                        listOf(BrandColors.Secondary, BrandColors.Secondary.copy(alpha = 0.7f))
                    } else {
                        listOf(BrandColors.Primary, BrandColors.Primary.copy(alpha = 0.7f))
                    }

                    SparkStar(
                        size = 14.dp,
                        color = gradientTextColors[0].copy(alpha = 0.7f),
                        modifier = Modifier.padding(end = 12.dp)
                    )

                    Text(
                        text = statusBigText,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Black,
                        style = TextStyle(
                            brush = Brush.linearGradient(colors = gradientTextColors)
                        ),
                        letterSpacing = 2.sp
                    )

                    SparkStar(
                        size = 10.dp,
                        color = gradientTextColors[0].copy(alpha = 0.5f),
                        modifier = Modifier.padding(start = 12.dp)
                    )
                }

                Text(
                    text = statusSubText,
                    color = BrandColors.TextSecondary,
                    fontSize = 11.5.sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    letterSpacing = 0.5.sp
                )

                Spacer(modifier = Modifier.height(14.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(BrandColors.OutlineSoft)
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatusTag(if (item.isLunar) "农历" else "公历")
                        StatusTag(
                            when (AnniversaryRepeatRule.fromStoredValue(item.repeatRule)) {
                                AnniversaryRepeatRule.NONE -> "单次"
                                AnniversaryRepeatRule.MONTHLY -> "每月"
                                AnniversaryRepeatRule.YEARLY -> "每年"
                            }
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PillActionButton(
                            checked = item.isPinnedTop,
                            onCheckedChange = onPinChanged,
                            icon = Icons.Default.PushPin,
                            contentDescription = "置顶"
                        )
                        PillActionButton(
                            checked = item.showOnWidget,
                            onCheckedChange = onWidgetChanged,
                            icon = Icons.Default.Widgets,
                            contentDescription = "桌面组件"
                        )
                        PillActionButton(
                            checked = item.showOnLockScreen,
                            onCheckedChange = onLockChanged,
                            icon = Icons.Default.NotificationsActive,
                            contentDescription = "锁屏常驻"
                        )
                    }
                }
            }
        }
    }
}

private fun targetLabel(item: AnniversaryItemEntity): String = buildString {
    if (item.isLunar) append("农历")
    append(item.sourceYear)
    append("年")
    if (item.isLunarLeapMonth) append("闰")
    append(item.sourceMonth)
    append("月")
    append(item.sourceDay)
    append("日 ")
    append("%02d:%02d:%02d".format(item.sourceHour, item.sourceMinute, item.sourceSecond))
}

private fun currentAnniversaryNotificationAccess(context: Context): AnniversaryNotificationAccess {
    val runtimePermissionRequired = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val runtimePermissionGranted = !runtimePermissionRequired ||
        ContextCompat.checkSelfPermission(context, POST_NOTIFICATIONS_PERMISSION) ==
        PackageManager.PERMISSION_GRANTED
    val appNotificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
    val channelEnabled = context.getSystemService(NotificationManager::class.java)
        ?.getNotificationChannel(AnniversaryNotificationPublisher.CHANNEL_ID)
        ?.importance != NotificationManager.IMPORTANCE_NONE
    return anniversaryNotificationAccess(
        runtimePermissionRequired = runtimePermissionRequired,
        runtimePermissionGranted = runtimePermissionGranted,
        appNotificationsEnabled = appNotificationsEnabled,
        channelEnabled = channelEnabled
    )
}

private fun anniversaryNotificationSettingsIntent(context: Context): Intent {
    val appNotificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
    val channelBlocked = context.getSystemService(NotificationManager::class.java)
        ?.getNotificationChannel(AnniversaryNotificationPublisher.CHANNEL_ID)
        ?.importance == NotificationManager.IMPORTANCE_NONE
    return if (appNotificationsEnabled && channelBlocked) {
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, AnniversaryNotificationPublisher.CHANNEL_ID)
    } else {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    }
}

private fun requestPinWidget(context: Context): Boolean = runCatching {
    val manager = context.getSystemService(AppWidgetManager::class.java) ?: return@runCatching false
    manager.isRequestPinAppWidgetSupported && manager.requestPinAppWidget(
        ComponentName(context, AnniversaryWidgetProvider::class.java),
        null,
        null
    )
}.getOrDefault(false)

private const val POST_NOTIFICATIONS_PERMISSION = "android.permission.POST_NOTIFICATIONS"
