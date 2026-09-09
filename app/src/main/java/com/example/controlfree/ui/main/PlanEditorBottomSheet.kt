@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.controlfree.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.controlfree.theme.BrandColors
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.compose.runtime.DisposableEffect
import com.example.controlfree.data.UsageAccessManager
import com.example.controlfree.runtime.UsageAccessSettingsNavigator
import com.example.controlfree.runtime.UsageAccessSettingsDestination
import android.provider.Settings
import android.net.Uri
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import com.example.controlfree.ui.todo.components.StableDateTimePickerPage
import java.time.ZoneId

internal enum class PlanActivationMode {
    ENABLED,
    DISABLED,
    SCHEDULED,
    APP_TRIGGER
}

@Composable
internal fun PlanEditorBottomSheet(
    title: String,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    onDeleteRequest: (() -> Unit)? = null,
    saveLabel: String = "保存",
    content: @Composable ColumnScope.() -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = {
            if (!isSaving) {
                onBack?.invoke() ?: onDismiss()
            }
        },
        modifier = modifier.fillMaxWidth(),
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
                    text = title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = BrandColors.TextPrimary
                )
                when {
                    onBack != null -> TextButton(onClick = onBack, enabled = !isSaving) {
                        Text("返回")
                    }
                    onDeleteRequest != null -> IconButton(
                        onClick = onDeleteRequest,
                        enabled = !isSaving,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "删除任务",
                            tint = BrandColors.Danger
                        )
                    }
                }
            }

            content()

            if (onBack == null) {
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        enabled = !isSaving,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(11.dp)
                    ) {
                        Text("取消")
                    }
                    Button(
                        onClick = onSave,
                        enabled = !isSaving,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(11.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = BrandColors.Primary,
                            contentColor = Color.White
                        )
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = BrandColors.OnPrimary
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("保存中")
                        } else {
                            Text(saveLabel)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun PlanActivationSection(
    enabled: Boolean,
    scheduledEnableAtEpochMillis: Long?,
    exactAlarmNeedsAction: Boolean,
    onActivationChange: (enabled: Boolean, scheduledEnableAtEpochMillis: Long?) -> Unit,
    onEditReservation: () -> Unit,
    onOpenExactAlarmSettings: () -> Unit,
    modifier: Modifier = Modifier,
    zoneId: ZoneId = ZoneId.systemDefault(),
    triggerAppPackageNames: Set<String> = emptySet(),
    allowAppTrigger: Boolean = false,
    onEditTriggerApps: () -> Unit = {}
) {
    val context = LocalContext.current
    val usageAccessManager = remember { UsageAccessManager(context.applicationContext) }
    var hasUsageAccess by remember { mutableStateOf(usageAccessManager.hasUsageAccess()) }
    var hasOverlayPermission by remember { mutableStateOf(Settings.canDrawOverlays(context)) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasUsageAccess = usageAccessManager.hasUsageAccess()
                hasOverlayPermission = Settings.canDrawOverlays(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val mode = when {
        triggerAppPackageNames.isNotEmpty() -> PlanActivationMode.APP_TRIGGER
        enabled -> PlanActivationMode.ENABLED
        scheduledEnableAtEpochMillis != null -> PlanActivationMode.SCHEDULED
        else -> PlanActivationMode.DISABLED
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("启动方式", fontWeight = FontWeight.Bold)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            maxItemsInEachRow = 2
        ) {
            PlanActivationMode.entries
                .filter { it != PlanActivationMode.APP_TRIGGER || allowAppTrigger }
                .forEach { candidate ->
                FilterChip(
                    selected = mode == candidate,
                    onClick = {
                        when (candidate) {
                            PlanActivationMode.ENABLED -> onActivationChange(true, null)
                            PlanActivationMode.DISABLED -> onActivationChange(false, null)
                            PlanActivationMode.SCHEDULED -> {
                                val defaultDateTime = defaultScheduledEnableDateTime(
                                    System.currentTimeMillis(),
                                    zoneId
                                )
                                val scheduledAt = scheduledEnableAtEpochMillis
                                    ?: resolveScheduledEnableEpochMillis(
                                        date = defaultDateTime.toLocalDate(),
                                        time = defaultDateTime.toLocalTime(),
                                        zoneId = zoneId
                                    )
                                onActivationChange(false, scheduledAt)
                            }
                            PlanActivationMode.APP_TRIGGER -> onEditTriggerApps()
                        }
                    },
                    label = {
                        Text(
                            when (candidate) {
                                PlanActivationMode.ENABLED -> "立即启用"
                                PlanActivationMode.DISABLED -> "保持停用"
                                PlanActivationMode.SCHEDULED -> "预约开启"
                                PlanActivationMode.APP_TRIGGER -> "App 触发"
                            },
                            maxLines = 1
                        )
                    },
                    modifier = Modifier.width(148.dp)
                )
            }
        }

        if (mode == PlanActivationMode.APP_TRIGGER) {
            val hasWarning = !hasUsageAccess || !hasOverlayPermission
            Surface(
                color = if (hasWarning) BrandColors.Warning.copy(alpha = 0.1f) else BrandColors.SurfaceRaised,
                shape = RoundedCornerShape(10.dp),
                border = if (hasWarning) BorderStroke(1.dp, BrandColors.Warning) else null,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onEditTriggerApps)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("App 触发已布防", color = BrandColors.TextSecondary, fontSize = 12.sp)
                        Text(
                            "打开已选的 ${triggerAppPackageNames.size} 个 App 中的任意一个后开启监督",
                            fontWeight = FontWeight.Bold,
                            color = BrandColors.TextPrimary
                        )
                    }
                    Text("编辑", color = BrandColors.Primary, fontSize = 12.sp)
                }
            }
            if (!hasUsageAccess) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "使用情况访问权限未开启，功能将无法生效。",
                        color = BrandColors.Warning,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = {
                        try {
                            val dest = UsageAccessSettingsNavigator.open(context.packageName) { intent ->
                                context.startActivity(intent)
                                true
                            }
                            if (dest == UsageAccessSettingsDestination.UNAVAILABLE) {
                                context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                            }
                        } catch (_: RuntimeException) {
                            try {
                                context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                            } catch (_: RuntimeException) {}
                        }
                    }) {
                        Text("去设置")
                    }
                }
            }
            if (!hasOverlayPermission) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "悬浮窗权限未开启，无法在前台拦截 App。",
                        color = BrandColors.Warning,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = {
                        try {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                            )
                        } catch (_: RuntimeException) {
                            try {
                                context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
                            } catch (_: RuntimeException) {}
                        }
                    }) {
                        Text("去设置")
                    }
                }
            }
        }

        if (mode == PlanActivationMode.SCHEDULED && scheduledEnableAtEpochMillis != null) {
            Surface(
                color = BrandColors.SurfaceRaised,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onEditReservation)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Schedule,
                        contentDescription = null,
                        tint = BrandColors.Primary
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("预约时间", color = BrandColors.TextSecondary, fontSize = 12.sp)
                        Text(
                            formatScheduledEnableAt(scheduledEnableAtEpochMillis, zoneId),
                            fontWeight = FontWeight.Bold,
                            color = BrandColors.TextPrimary
                        )
                    }
                    Text("修改", color = BrandColors.Primary, fontSize = 12.sp)
                }
            }
            if (exactAlarmNeedsAction) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "精确定时权限未开启，系统可能延后执行预约。",
                        color = BrandColors.Warning,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onOpenExactAlarmSettings) {
                        Text("去设置")
                    }
                }
            }
        }
    }
}

@Composable
internal fun PlanReservationPickerPage(
    initialEpochMillis: Long,
    zoneId: ZoneId,
    onCancel: () -> Unit,
    onSelected: (Long) -> Unit
) {
    var validationMessage by remember(initialEpochMillis) { mutableStateOf<String?>(null) }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        validationMessage?.let { message ->
            Text(message, color = BrandColors.Danger, fontSize = 12.sp)
        }
        StableDateTimePickerPage(
            initialEpochMillis = initialEpochMillis,
            zoneId = zoneId,
            onCancel = onCancel,
            onSelected = { selectedEpochMillis ->
                val problem = scheduledEnableValidationMessage(
                    enableAtEpochMillis = selectedEpochMillis,
                    nowEpochMillis = System.currentTimeMillis()
                )
                if (problem == null) {
                    onSelected(selectedEpochMillis)
                } else {
                    validationMessage = problem
                }
            }
        )
    }
}
