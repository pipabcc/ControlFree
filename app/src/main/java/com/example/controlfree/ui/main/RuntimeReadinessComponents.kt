package com.example.controlfree.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.controlfree.theme.BrandColors

@Composable
fun RuntimeReadinessCard(
    readiness: RuntimeReadiness,
    onFix: (RuntimeRequirementKey) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = BrandColors.SurfaceCard),
        shape = RoundedCornerShape(18.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = androidx.compose.ui.res.painterResource(id = com.example.controlfree.R.drawable.ic_readiness),
                    contentDescription = null,
                    tint = if (readiness.canStart) BrandColors.Success else BrandColors.Warning
                )
                Spacer(Modifier.size(9.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("运行准备", color = BrandColors.TextPrimary, fontWeight = FontWeight.Bold)
                    Text(
                        if (readiness.canStart) "关键权限已就绪" else "还有 ${readiness.blockers.size} 项必须处理",
                        color = BrandColors.TextSecondary,
                        fontSize = 12.sp
                    )
                }
                IconButton(onClick = onRefresh) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "刷新权限状态",
                        tint = BrandColors.TextPrimary
                    )
                }
            }

            readiness.requirements.forEachIndexed { index, requirement ->
                if (index > 0) {
                    HorizontalDivider(
                        color = BrandColors.OutlineSoft,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
                RuntimeRequirementRow(
                    requirement = requirement,
                    onFix = onFix,
                    onRefresh = onRefresh
                )
            }
        }
    }
}

@Composable
fun FocusStartReadinessDialog(
    readiness: RuntimeReadiness,
    lockMinutes: Int,
    playMinutes: Int,
    onFix: (RuntimeRequirementKey) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
    onStart: () -> Unit
) = RuntimeReadinessDialog(
    readiness = readiness,
    title = "立即锁定",
    description =
        "立即锁定 $lockMinutes 分钟 → 玩机 $playMinutes 分钟 → 再次锁定并持续循环，直到手动终止。",
    confirmLabel = "确认",
    dismissLabel = "取消",
    onFix = onFix,
    onRefresh = onRefresh,
    onDismiss = onDismiss,
    onConfirm = onStart
)

@Composable
fun RuntimeReadinessDialog(
    readiness: RuntimeReadiness,
    title: String,
    description: String,
    confirmLabel: String,
    dismissLabel: String,
    onFix: (RuntimeRequirementKey) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BrandColors.OverlaySurface,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    description,
                    color = BrandColors.TextPrimary,
                    fontSize = 13.sp
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (readiness.canStart) "所有关键条件已就绪" else "请先处理 ${readiness.blockers.size} 项关键条件",
                        color = if (readiness.canStart) BrandColors.Success else BrandColors.Warning,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新权限状态")
                    }
                }
                readiness.requirements.forEachIndexed { index, requirement ->
                    if (index > 0) {
                        HorizontalDivider(
                            color = BrandColors.OutlineSoft,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                    RuntimeRequirementRow(
                        requirement = requirement,
                        onFix = onFix,
                        onRefresh = onRefresh
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = readiness.canStart) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(dismissLabel) }
        }
    )
}

@Composable
private fun RuntimeRequirementRow(
    requirement: RuntimeRequirement,
    onFix: (RuntimeRequirementKey) -> Unit,
    onRefresh: () -> Unit
) {
    val statusColor = when (requirement.state) {
        RuntimeRequirementState.READY -> BrandColors.Success
        RuntimeRequirementState.ACTION_REQUIRED -> BrandColors.Danger
        RuntimeRequirementState.RECOMMENDED -> BrandColors.Warning
        RuntimeRequirementState.NOT_APPLICABLE -> BrandColors.TextTertiary
    }
    val statusText = when (requirement.state) {
        RuntimeRequirementState.READY -> "已就绪"
        RuntimeRequirementState.ACTION_REQUIRED -> "需要处理"
        RuntimeRequirementState.RECOMMENDED -> "建议优化"
        RuntimeRequirementState.NOT_APPLICABLE -> "无需设置"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = if (BrandColors.isCustomBackground) BrandColors.SurfaceRaised else androidx.compose.ui.graphics.Color.Transparent,
                shape = RoundedCornerShape(12.dp)
            )
            .padding(if (BrandColors.isCustomBackground) 12.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    requirement.title,
                    color = BrandColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.size(7.dp))
                Text(statusText, color = statusColor, fontSize = 11.sp)
            }
            Spacer(Modifier.height(3.dp))
            Text(requirement.purpose, color = BrandColors.TextSecondary, fontSize = 11.sp)
            if (requirement.key == RuntimeRequirementKey.BATTERY_UNRESTRICTED) {
                val context = LocalContext.current
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = {
                        val intents = listOf(
                            // 1. 尝试直接打开“应用启动管理”
                            android.content.Intent().setComponent(
                                android.content.ComponentName(
                                    "com.huawei.systemmanager",
                                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                                )
                            ),
                            android.content.Intent().setComponent(
                                android.content.ComponentName(
                                    "com.huawei.systemmanager",
                                    "com.huawei.systemmanager.optimize.process.ProtectActivity"
                                )
                            ),
                            // 2. 尝试打开“应用和服务” (华为系统二级菜单)
                            android.content.Intent().setComponent(
                                android.content.ComponentName(
                                    "com.android.settings",
                                    "com.android.settings.Settings\$AppAndNotificationDashboardActivity"
                                )
                            ),
                            android.content.Intent().setComponent(
                                android.content.ComponentName(
                                    "com.huawei.settings",
                                    "com.huawei.settings.Settings\$AppAndNotificationDashboardActivity"
                                )
                            ),
                            // 3. 兜底使用通用忽略电池优化设置页
                            android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = android.net.Uri.parse("package:${context.packageName}")
                            },
                            // 4. 终极兜底使用通用系统设置主页
                            android.content.Intent(android.provider.Settings.ACTION_SETTINGS)
                        )
                        var opened = false
                        for (intent in intents) {
                            try {
                                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(intent)
                                opened = true
                                break
                            } catch (_: Exception) {}
                        }
                        if (!opened) {
                            android.widget.Toast.makeText(context, "无法打开系统设置，请手动前往设置", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp),
                        tint = BrandColors.Primary
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("点击进入启动管理", fontSize = 11.sp, color = BrandColors.Primary)
                }
            }
        }
        if (requirement.needsAction) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                OutlinedButton(
                    onClick = { onFix(requirement.key) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(5.dp))
                    Text(if (requirement.blocksStart) "处理" else "设置", fontSize = 12.sp)
                }
                if (requirement.key == RuntimeRequirementKey.BACKGROUND_POPUP) {
                    val context = LocalContext.current
                    Spacer(Modifier.height(4.dp))
                    TextButton(
                        onClick = {
                            com.example.controlfree.data.PreferenceManager(context.applicationContext)
                                .setBackgroundPopupManuallyConfirmed(true)
                            onRefresh()
                        },
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                        modifier = Modifier.height(24.dp)
                    ) {
                        Text("我已允许", fontSize = 10.sp, color = BrandColors.Danger)
                    }
                }
                if (requirement.key == RuntimeRequirementKey.BATTERY_UNRESTRICTED) {
                    val context = LocalContext.current
                    Spacer(Modifier.height(4.dp))
                    TextButton(
                        onClick = {
                            com.example.controlfree.data.PreferenceManager(context.applicationContext)
                                .setBatteryUnrestrictedManuallyConfirmed(true)
                            onRefresh()
                        },
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                        modifier = Modifier.height(24.dp)
                    ) {
                        Text("我已允许", fontSize = 10.sp, color = BrandColors.Danger)
                    }
                }
            }
        }
    }
}
