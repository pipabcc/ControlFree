package com.example.controlfree.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.controlfree.diagnostics.SelfCheckItemKey
import com.example.controlfree.diagnostics.SelfCheckItemResult
import com.example.controlfree.diagnostics.SelfCheckItemStatus
import com.example.controlfree.diagnostics.SelfCheckOverallStatus
import com.example.controlfree.diagnostics.SelfCheckReason
import com.example.controlfree.diagnostics.SelfCheckReport
import com.example.controlfree.theme.BrandColors

@Composable
fun DiagnosticsCard(
    isChecking: Boolean,
    lastReport: SelfCheckReport?,
    onRunSelfCheck: () -> Unit,
    onClearDiagnostics: () -> Unit
) {
}

@Composable
fun SelfCheckReportDialog(
    report: SelfCheckReport,
    onDismiss: () -> Unit,
    onOpenBackgroundSettings: () -> Unit,
    onOpenBatterySettings: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自检结果", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                Text(
                    report.overallStatus.summaryText(),
                    color = report.overallStatus.summaryColor(),
                    fontWeight = FontWeight.Bold
                )
                report.items.forEach { item ->
                    SelfCheckResultRow(
                        item = item,
                        onOpenBackgroundSettings = onOpenBackgroundSettings,
                        onOpenBatterySettings = onOpenBatterySettings
                    )
                }
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("完成") } }
    )
}

@Composable
fun ClearDiagnosticsConfirmationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("清空本地诊断？", fontWeight = FontWeight.Bold) },
        text = { Text("只会删除脱敏诊断事件，不会影响监督设置、密码或使用统计。") },
        confirmButton = { Button(onClick = onConfirm) { Text("确认清空") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun SelfCheckResultRow(
    item: SelfCheckItemResult,
    onOpenBackgroundSettings: () -> Unit,
    onOpenBatterySettings: () -> Unit
) {
    val statusColor = item.status.statusColor()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(BrandColors.SurfaceRaised, RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                item.key.titleText(),
                color = BrandColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Text(item.status.statusText(), color = statusColor, fontSize = 11.sp)
        }
        Spacer(Modifier.size(4.dp))
        Text(item.reason.detailText(), color = BrandColors.TextSecondary, fontSize = 11.sp)
        if (
            item.key == SelfCheckItemKey.OEM_AUTO_START ||
            item.key == SelfCheckItemKey.OEM_BACKGROUND_ACTIVITY ||
            item.key == SelfCheckItemKey.BACKGROUND_RESTRICTION ||
            item.key == SelfCheckItemKey.BATTERY_OPTIMIZATION
        ) {
            Spacer(Modifier.size(7.dp))
            OutlinedButton(
                onClick = if (item.key == SelfCheckItemKey.BATTERY_OPTIMIZATION) {
                    onOpenBatterySettings
                } else {
                    onOpenBackgroundSettings
                }
            ) {
                Icon(Icons.Default.Settings, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(5.dp))
                Text(
                    if (item.key == SelfCheckItemKey.BATTERY_OPTIMIZATION) {
                        "设置后台无限制"
                    } else {
                        "打开应用设置"
                    }
                )
            }
        }
    }
}

private fun SelfCheckOverallStatus.summaryText(): String = when (this) {
    SelfCheckOverallStatus.HEALTHY -> "自动检测项目均正常"
    SelfCheckOverallStatus.IN_PROGRESS -> "部分项目仍在检测"
    SelfCheckOverallStatus.NEEDS_ATTENTION -> "存在建议处理或人工确认项目"
    SelfCheckOverallStatus.FAILED -> "发现影响监督可靠性的异常"
}

@Composable
private fun SelfCheckOverallStatus.summaryColor(): Color = when (this) {
    SelfCheckOverallStatus.HEALTHY -> BrandColors.Success
    SelfCheckOverallStatus.IN_PROGRESS -> BrandColors.Primary
    SelfCheckOverallStatus.NEEDS_ATTENTION -> BrandColors.Warning
    SelfCheckOverallStatus.FAILED -> BrandColors.Danger
}

private fun SelfCheckItemStatus.statusText(): String = when (this) {
    SelfCheckItemStatus.PASSED -> "通过"
    SelfCheckItemStatus.IN_PROGRESS -> "检测中"
    SelfCheckItemStatus.WARNING -> "需关注"
    SelfCheckItemStatus.FAILED -> "异常"
    SelfCheckItemStatus.MANUAL_CONFIRMATION_REQUIRED -> "人工确认"
    SelfCheckItemStatus.NOT_APPLICABLE -> "不适用"
}

@Composable
private fun SelfCheckItemStatus.statusColor(): Color = when (this) {
    SelfCheckItemStatus.PASSED -> BrandColors.Success
    SelfCheckItemStatus.IN_PROGRESS -> BrandColors.Primary
    SelfCheckItemStatus.WARNING,
    SelfCheckItemStatus.MANUAL_CONFIRMATION_REQUIRED -> BrandColors.Warning
    SelfCheckItemStatus.FAILED -> BrandColors.Danger
    SelfCheckItemStatus.NOT_APPLICABLE -> BrandColors.TextTertiary
}

private fun SelfCheckItemKey.titleText(): String = when (this) {
    SelfCheckItemKey.USAGE_ACCESS_PERMISSION -> "使用情况访问权限"
    SelfCheckItemKey.OVERLAY_PERMISSION -> "悬浮显示权限"
    SelfCheckItemKey.PHONE_STATE_PERMISSION -> "电话状态权限"
    SelfCheckItemKey.NOTIFICATION_PERMISSION -> "通知运行权限"
    SelfCheckItemKey.NOTIFICATIONS_ENABLED -> "应用通知总开关"
    SelfCheckItemKey.SERVICE_NOTIFICATION_CHANNEL -> "监督服务通知频道"
    SelfCheckItemKey.LOCK_RECOVERY_NOTIFICATION_CHANNEL -> "锁定恢复通知频道"
    SelfCheckItemKey.FULL_SCREEN_INTENT_PERMISSION -> "全屏通知权限"
    SelfCheckItemKey.BATTERY_OPTIMIZATION -> "电池优化限制"
    SelfCheckItemKey.USAGE_STATS_HEALTH -> "UsageStats 实际查询"
    SelfCheckItemKey.SERVICE_HEARTBEAT -> "监督服务心跳"
    SelfCheckItemKey.SNAPSHOT_RECOVERY -> "监督快照与恢复保护"
    SelfCheckItemKey.ALLOWLIST_CACHE -> "白名单缓存完整性"
    SelfCheckItemKey.MEDIA_REPLAY_GUARD -> "锁定媒体守卫"
    SelfCheckItemKey.DIAGNOSTIC_STORAGE -> "本地诊断存储"
    SelfCheckItemKey.BOOT_RECEIVER -> "开机恢复接收器"
    SelfCheckItemKey.BACKGROUND_RESTRICTION -> "Android 后台限制"
    SelfCheckItemKey.OEM_AUTO_START -> "厂商自启动开关"
    SelfCheckItemKey.OEM_BACKGROUND_ACTIVITY -> "厂商后台活动开关"
}

private fun SelfCheckReason.detailText(): String = when (this) {
    SelfCheckReason.AVAILABLE -> "状态正常。"
    SelfCheckReason.NOT_APPLICABLE -> "当前系统或设备无需检查此项。"
    SelfCheckReason.PERMISSION_DENIED -> "权限或系统开关未开启。"
    SelfCheckReason.PERMISSION_STATE_UNKNOWN -> "系统暂时无法可靠读取此项状态。"
    SelfCheckReason.CAPABILITY_DEGRADED -> "功能已开启，但系统级别不足，建议恢复推荐设置。"
    SelfCheckReason.RECOMMENDED_PERMISSION_DENIED -> "建议开启，作为锁层异常时的备用保护。"
    SelfCheckReason.BATTERY_RESTRICTION_PRESENT ->
        "电池策略尚未设为“不限制”，系统可能停止监督服务。"
    SelfCheckReason.USAGE_STATS_NOT_CHECKED -> "实际查询尚未完成。"
    SelfCheckReason.USAGE_STATS_NO_DATA -> "查询成功，但最近窗口没有可用事件。"
    SelfCheckReason.USAGE_STATS_WORKER_RECOVERED ->
        "监督服务曾替换卡死的查询线程，目前已恢复；建议继续观察。"
    SelfCheckReason.USAGE_STATS_TIMED_OUT -> "系统查询超时，观察线程会自动替换。"
    SelfCheckReason.USAGE_STATS_CIRCUIT_OPEN -> "连续查询失败，熔断器正在冷却。"
    SelfCheckReason.USAGE_STATS_QUERY_FAILED -> "实际 UsageStats 查询失败。"
    SelfCheckReason.MONITORING_INACTIVE -> "当前未开启监督，无需检查服务心跳。"
    SelfCheckReason.SERVICE_NOT_RUNNING -> "监督状态仍活动，但服务当前不可用。"
    SelfCheckReason.CRITICAL_RECEIVER_MISSING ->
        "关键广播接收器注册失败，Home 回锁或锁页通信可能失效。"
    SelfCheckReason.SCREEN_RECEIVER_MISSING ->
        "亮熄屏广播接收器注册失败，当前会依赖定时校准兜底。"
    SelfCheckReason.PERSISTENCE_UNHEALTHY -> "监督进度最近一次写入失败。"
    SelfCheckReason.TIMER_UNHEALTHY -> "监督计时循环最近发生运行异常。"
    SelfCheckReason.CALL_STATE_MONITOR_UNAVAILABLE ->
        "电话监听注册失败，来电或通话界面可能无法自动放行。"
    SelfCheckReason.HEARTBEAT_MISSING -> "没有发现监督服务心跳。"
    SelfCheckReason.HEARTBEAT_STALE -> "监督服务心跳已经过期。"
    SelfCheckReason.HEARTBEAT_INVALID -> "监督服务心跳时间无效。"
    SelfCheckReason.RECOVERY_NOT_REQUIRED -> "当前没有需要恢复的监督任务。"
    SelfCheckReason.RECOVERY_FALLBACK_ARMED -> "恢复保护已启用，将优先保持锁定。"
    SelfCheckReason.SNAPSHOT_MISSING -> "监督活动但找不到有效进度快照。"
    SelfCheckReason.SNAPSHOT_CORRUPTED -> "监督进度快照校验失败。"
    SelfCheckReason.SNAPSHOT_READ_FAILED -> "无法读取监督进度快照。"
    SelfCheckReason.RECOVERY_GUARD_DISARMED -> "监督活动但恢复保护状态不一致。"
    SelfCheckReason.ALLOWLIST_UNINITIALIZED -> "白名单尚未完成首次加载。"
    SelfCheckReason.ALLOWLIST_LOADING -> "白名单正在刷新。"
    SelfCheckReason.ALLOWLIST_READY -> "App 与电话白名单分区均完整。"
    SelfCheckReason.ALLOWLIST_STALE -> "继续使用上次成功快照，最近刷新失败。"
    SelfCheckReason.ALLOWLIST_FAILED -> "白名单首次加载失败，已按失败关闭处理。"
    SelfCheckReason.ALLOWLIST_PARTITION_MISSING -> "App 或电话白名单分区不完整。"
    SelfCheckReason.MEDIA_GUARD_NOT_REQUIRED -> "当前阶段不需要媒体重播守卫。"
    SelfCheckReason.MEDIA_GUARD_NOT_CHECKED -> "媒体守卫已启用，等待首次探测。"
    SelfCheckReason.MEDIA_GUARD_DISABLED -> "锁定阶段媒体守卫没有启用。"
    SelfCheckReason.MEDIA_PROBE_UNAVAILABLE -> "系统媒体播放状态暂时不可读取。"
    SelfCheckReason.MEDIA_GUARD_FAILED -> "媒体守卫运行状态异常。"
    SelfCheckReason.STORAGE_NOT_CHECKED -> "私有存储探针尚未完成。"
    SelfCheckReason.STORAGE_READ_FAILED -> "私有诊断文件读取失败。"
    SelfCheckReason.STORAGE_WRITE_FAILED -> "私有诊断目录写入失败。"
    SelfCheckReason.STORAGE_NOT_PRIVATE -> "诊断目录不在应用私有 noBackup 目录。"
    SelfCheckReason.STORAGE_SIZE_LIMIT_EXCEEDED -> "诊断日志超过 512 KiB 上限。"
    SelfCheckReason.BOOT_RECEIVER_MISSING -> "安装包未声明开机恢复接收器。"
    SelfCheckReason.BOOT_RECEIVER_DISABLED -> "开机恢复接收器已被禁用。"
    SelfCheckReason.BOOT_RECEIVER_STATE_UNKNOWN -> "系统暂时无法读取开机恢复接收器状态。"
    SelfCheckReason.CURRENT_BOOT_EVIDENCE_MISSING -> "本次开机未发现实际启动广播证据。"
    SelfCheckReason.BOOT_COUNT_UNAVAILABLE -> "系统无法读取本次开机编号，需重启后人工确认。"
    SelfCheckReason.BACKGROUND_UNRESTRICTED ->
        "Android 标准后台限制未开启；厂商后台权限仍需人工确认。"
    SelfCheckReason.BACKGROUND_RESTRICTED -> "Android 已将本应用标记为后台受限。"
    SelfCheckReason.BACKGROUND_STATE_UNKNOWN -> "当前系统无法可靠读取标准后台限制。"
    SelfCheckReason.OEM_SETTING_ENABLED -> "厂商开关已确认开启。"
    SelfCheckReason.OEM_SETTING_DISABLED -> "厂商开关已确认关闭。"
    SelfCheckReason.OEM_SETTING_REQUIRES_MANUAL_CONFIRMATION ->
        "系统没有公开读取接口，请进入厂商设置人工确认。"
}
