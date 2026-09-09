package com.example.controlfree.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.EventNote
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.controlfree.theme.BrandColors

internal const val MIN_FOCUS_LOCK_MINUTES = 1
internal const val MAX_FOCUS_LOCK_MINUTES = 180

internal data class FocusSessionPreset(
    val title: String,
    val lockMinutes: Int,
    val playMinutes: Int
) {
    init {
        require(title.isNotBlank() && title == title.trim()) { "专注模板名称无效" }
        require(lockMinutes in MIN_FOCUS_LOCK_MINUTES..MAX_FOCUS_LOCK_MINUTES) {
            "专注模板锁定时长无效"
        }
        require(playMinutes in 1..60) { "专注模板玩机时长无效" }
    }
}

internal val focusSessionPresets = listOf(
    FocusSessionPreset("轻量", lockMinutes = 5, playMinutes = 1),
    FocusSessionPreset("标准", lockMinutes = 15, playMinutes = 3),
    FocusSessionPreset("严格", lockMinutes = 30, playMinutes = 5)
)

internal fun focusSessionOverviewTitle(isFocusRunning: Boolean): String =
    if (isFocusRunning) "专注任务进行中" else "快速开始专注任务"

internal data class FocusStartConfiguration(
    val lockMinutes: Int,
    val playMinutes: Int
)

internal fun resolveFocusStartConfiguration(
    pendingPreset: FocusSessionPreset?,
    configuredLockMinutes: Int,
    configuredPlayMinutes: Int
): FocusStartConfiguration = FocusStartConfiguration(
    lockMinutes = pendingPreset?.lockMinutes
        ?: normalizeDuration(
            configuredLockMinutes,
            minValue = MIN_FOCUS_LOCK_MINUTES,
            maxValue = MAX_FOCUS_LOCK_MINUTES,
            step = 1
        ),
    playMinutes = pendingPreset?.playMinutes
        ?: normalizeDuration(configuredPlayMinutes, minValue = 1, maxValue = 60, step = 1)
)

internal fun areFocusPresetsEnabled(
    isAnyMonitorRunning: Boolean,
    isStarting: Boolean
): Boolean = !isAnyMonitorRunning && !isStarting

@Composable
internal fun FocusSessionOverview(
    isFocusRunning: Boolean,
    taskTitle: String,
    lockMinutes: Int,
    playMinutes: Int,
    arePresetsEnabled: Boolean,
    onTaskTitleChange: (String) -> Unit,
    onApplyPreset: (FocusSessionPreset) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 卡片 1 : 快速开始专注任务配置
        Card(
            colors = CardDefaults.cardColors(containerColor = BrandColors.SurfaceCard),
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val iconBg = BrandColors.Primary.copy(alpha = 0.12f)
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(iconBg),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Timer,
                            contentDescription = null,
                            tint = BrandColors.Primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(Modifier.width(13.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            focusSessionOverviewTitle(isFocusRunning),
                            color = BrandColors.TextPrimary,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "立即锁定，随后按玩机与锁定阶段循环，直到主动结束任务",
                            color = BrandColors.TextSecondary,
                            fontSize = 11.5.sp
                        )
                    }
                }

                OutlinedTextField(
                    value = taskTitle,
                    onValueChange = onTaskTitleChange,
                    enabled = !isFocusRunning,
                    label = { Text("专注任务") },
                    placeholder = { Text("例如：完成报告") },
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.EventNote,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    "锁定强度",
                    color = BrandColors.TextSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )

                FocusPresetSelector(
                    currentLockMinutes = lockMinutes,
                    currentPlayMinutes = playMinutes,
                    enabled = arePresetsEnabled,
                    onApplyPreset = onApplyPreset
                )
            }
        }

        // 卡片 2 : 锁定与可用时长展示（去掉点击切换字样，独立小卡片）
        Card(
            colors = CardDefaults.cardColors(containerColor = BrandColors.SurfaceCard),
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SessionMetric("锁定", lockMinutes.toString(), "分钟", Modifier.weight(1f))
                SessionMetric("可用", playMinutes.toString(), "分钟", Modifier.weight(1f))
            }
        }
    }
}

@Composable
internal fun FocusPresetSelector(
    currentLockMinutes: Int,
    currentPlayMinutes: Int,
    enabled: Boolean,
    onApplyPreset: (FocusSessionPreset) -> Unit
) {
    val selectedIndex = remember(currentLockMinutes, currentPlayMinutes) {
        focusSessionPresets.indexOfFirst {
            it.lockMinutes == currentLockMinutes && it.playMinutes == currentPlayMinutes
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFEEF2EE), RoundedCornerShape(14.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        focusSessionPresets.forEachIndexed { index, preset ->
            val isSelected = selectedIndex == index
            val containerColor = if (isSelected) Color.White else Color.Transparent
            val textColor = if (isSelected) BrandColors.Primary else BrandColors.TextSecondary
            val numberColor = if (isSelected) BrandColors.Primary.copy(alpha = 0.7f) else BrandColors.TextTertiary

            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(11.dp))
                    .background(containerColor)
                    .clickable(enabled = enabled) { onApplyPreset(preset) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = preset.title,
                        color = textColor,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false
                    )
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .size(4.dp)
                            .background(numberColor, CircleShape)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "${index + 1}",
                        color = numberColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionMetric(
    title: String,
    value: String,
    unit: String,
    modifier: Modifier
) {
    val isLock = title == "锁定"
    val startColor = if (isLock) Color(0xFFF0F8F4) else Color(0xFFFBF4E9)
    val endColor = if (isLock) Color(0xFFE2F1EA) else Color(0xFFF7EBD8)
    val borderColor = if (isLock) Color(0xFFCFE6DA) else Color(0xFFECD9BD)
    val iconColor = if (isLock) BrandColors.Primary else Color(0xFFCF7D1E)
    val textColor = BrandColors.TextPrimary
    val labelColor = BrandColors.TextSecondary

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.linearGradient(listOf(startColor, endColor)))
            .border(1.dp, borderColor, RoundedCornerShape(16.dp))
            .padding(horizontal = 15.dp, vertical = 14.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (isLock) Icons.Default.Lock else Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                    tint = iconColor
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    text = title,
                    color = labelColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = value,
                    color = textColor,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 24.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.SansSerif
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = unit,
                    color = labelColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }
        }
    }
}
