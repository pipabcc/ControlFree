package com.example.controlfree.ui.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.controlfree.supervision.SupervisionPlan
import com.example.controlfree.theme.BrandColors
import com.example.controlfree.ui.showWidgetPinResult
import com.example.controlfree.widget.ControlFreeWidgetPinning
import com.example.controlfree.widget.ControlFreeWidgetType
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val MINIMUM_RESERVATION_LEAD_MILLIS = 60_000L

@Composable
internal fun PlanActionCard(
    planId: String,
    busy: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    showEdit: Boolean = true,
    widgetType: ControlFreeWidgetType? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val context = LocalContext.current
    val cardShape = RoundedCornerShape(20.dp)
    val cardInteractionSource = remember(planId) { MutableInteractionSource() }
    Box(modifier = modifier.fillMaxWidth()) {
        Card(
            colors = CardDefaults.cardColors(containerColor = BrandColors.SurfaceCard),
            shape = cardShape,
            modifier = Modifier
                .fillMaxWidth()
                .clip(cardShape)
                .clickable(
                    interactionSource = cardInteractionSource,
                    indication = null,
                    enabled = !busy,
                    role = Role.Button,
                    onClick = onEdit
                ),
        ) {
            Column {
                content()
                if (widgetType != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        IconButton(
                            enabled = !busy,
                            onClick = {
                                val result = when (widgetType) {
                                    ControlFreeWidgetType.SUPERVISION ->
                                        ControlFreeWidgetPinning.requestSupervision(context, planId)
                                    ControlFreeWidgetType.FOCUS ->
                                        ControlFreeWidgetPinning.requestFocus(context, planId)
                                    ControlFreeWidgetType.TODO,
                                    ControlFreeWidgetType.QUICK_NOTE -> return@IconButton
                                }
                                context.showWidgetPinResult(result)
                            }
                        ) {
                            Icon(
                                Icons.Default.Widgets,
                                contentDescription = "添加桌面组件",
                                tint = BrandColors.Primary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun StablePlanEnabledSwitch(
    checked: Boolean,
    busy: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val interactionSource = remember { MutableInteractionSource() }
    // 固定布局和纯视觉 Switch 分离：保存时只隐藏绘制，避免尺寸跳变和旧轨道残影。
    Box(
        modifier = modifier
            .size(width = 52.dp, height = 48.dp)
            .toggleable(
                value = checked,
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled && !busy,
                role = Role.Switch,
                onValueChange = onCheckedChange
            ),
        contentAlignment = Alignment.Center
    ) {
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            modifier = Modifier.drawWithContent {
                if (!busy) drawContent()
            },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = BrandColors.Primary,
                uncheckedThumbColor = if (androidx.compose.foundation.isSystemInDarkTheme()) Color(0xFF94A3B8) else Color(0xFFFFFFFF),
                uncheckedTrackColor = if (androidx.compose.foundation.isSystemInDarkTheme()) Color(0xFF334155) else Color(0xFFE2E8F0),
                uncheckedBorderColor = Color.Transparent
            )
        )
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = BrandColors.PrimaryBright,
                strokeWidth = 2.dp
            )
        }
    }
}

internal inline fun runPlanInteractionIfIdle(
    busy: Boolean,
    action: () -> Unit
) {
    if (!busy) action()
}

@Composable
private fun PlanActionMenuItem(
    label: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    color: Color = BrandColors.TextPrimary
) {
    DropdownMenuItem(
        text = { Text(label, color = color, fontWeight = FontWeight.Medium) },
        leadingIcon = icon,
        onClick = onClick
    )
}

internal fun defaultScheduledEnableDateTime(
    nowEpochMillis: Long,
    zoneId: ZoneId
): LocalDateTime = Instant.ofEpochMilli(nowEpochMillis)
    .atZone(zoneId)
    .plusHours(1L)
    .withSecond(0)
    .withNano(0)
    .toLocalDateTime()

internal fun resolveScheduledEnableEpochMillis(
    date: LocalDate,
    time: LocalTime,
    zoneId: ZoneId
): Long = ZonedDateTime.of(date, time, zoneId).toInstant().toEpochMilli()

internal fun scheduledEnableValidationMessage(
    enableAtEpochMillis: Long,
    nowEpochMillis: Long
): String? = when {
    enableAtEpochMillis < 0L -> "预约时间无效"
    enableAtEpochMillis < nowEpochMillis + MINIMUM_RESERVATION_LEAD_MILLIS ->
        "请选择至少晚于当前时间 1 分钟的预约时间"
    else -> null
}

internal fun formatScheduledEnableAt(
    enableAtEpochMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault()
): String = Instant.ofEpochMilli(enableAtEpochMillis)
    .atZone(zoneId)
    .format(DateTimeFormatter.ofPattern("M月d日 E HH:mm", locale))
