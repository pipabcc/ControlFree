package com.example.controlfree.ui.main

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.controlfree.PetStageUpgradeEvent
import com.example.controlfree.PetStageUpgradeTracker
import com.example.controlfree.R
import com.example.controlfree.petStageProfile
import com.example.controlfree.data.LockUnlockFeaturePreferences
import com.example.controlfree.data.canEnableUnlockAuthentication
import com.example.controlfree.growth.GrowthAccount
import com.example.controlfree.growth.GrowthLedgerEntry
import com.example.controlfree.growth.GrowthLedgerReason
import com.example.controlfree.growth.GrowthPolicy
import com.example.controlfree.growth.GrowthRepository
import com.example.controlfree.growth.GrowthStage
import com.example.controlfree.security.CredentialStore
import com.example.controlfree.theme.BrandColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun GrowthLevelCard(
    account: GrowthAccount?
) {
    val context = LocalContext.current
    val stageUpgradeTracker = remember(context.applicationContext) {
        PetStageUpgradeTracker(context.applicationContext)
    }
    var pendingStageUpgrade by remember { mutableStateOf<PetStageUpgradeEvent?>(null) }

    val currentStage = account?.levelProgress?.stage
    LaunchedEffect(currentStage, stageUpgradeTracker) {
        val stage = currentStage ?: return@LaunchedEffect
        try {
            pendingStageUpgrade = stageUpgradeTracker.consumePendingUpgrade(stage)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // 升级提示持久化失败不应阻断展示
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = BrandColors.SurfaceCard),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val currentStage = account?.levelProgress?.stage ?: com.example.controlfree.growth.GrowthStage.SEEDLING
                com.example.controlfree.ui.common.GrowingPlantCanvas(
                    stage = currentStage,
                    modifier = Modifier.size(62.dp)
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        "自律成长",
                        color = BrandColors.TextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    val progress = account?.levelProgress
                    Text(
                        if (progress == null) {
                            "正在读取成长记录…"
                        } else {
                            "${progress.stage.displayName} · Lv.${progress.level}"
                        },
                        color = BrandColors.TextSecondary,
                        fontSize = 13.sp
                    )
                }
            }

            account?.levelProgress?.let { progress ->
                Spacer(Modifier.height(12.dp))
                val levelSpan = progress.nextLevelThreshold
                    ?.minus(progress.currentLevelThreshold)
                    ?.coerceAtLeast(1L)
                val fraction = if (levelSpan == null) {
                    1f
                } else {
                    (progress.experienceIntoLevel.toFloat() / levelSpan.toFloat())
                        .coerceIn(0f, 1f)
                }
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth().height(7.dp),
                    color = BrandColors.Primary,
                    trackColor = BrandColors.Outline
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    progress.experienceNeededForNextLevel?.let { needed ->
                        "再获得 $needed 成长经验升级；消费余额不会让小芽退级"
                    } ?: "已达到当前最高成长等级",
                    color = BrandColors.TextSecondary,
                    fontSize = 12.sp
                )
            }
        }
    }

    pendingStageUpgrade?.let { upgrade ->
        PetStageUpgradeDialog(
            event = upgrade,
            onDismiss = { pendingStageUpgrade = null }
        )
    }
}

@Composable
internal fun GrowthPointsCard(account: GrowthAccount?) {
    Card(
        colors = CardDefaults.cardColors(containerColor = BrandColors.SurfaceCard),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            Text(
                "成长账户",
                color = BrandColors.TextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                GrowthMetric(
                    label = "可用成长值",
                    value = account?.availableBalancePoints?.toString() ?: "--",
                    detail = "余额上限 ${GrowthPolicy.MAX_BALANCE_POINTS} 点",
                    modifier = Modifier.weight(1f)
                )
                GrowthMetric(
                    label = "终身成长经验",
                    value = account?.lifetimeExperience?.toString() ?: "--",
                    detail = "只增不减，用于小芽升级",
                    modifier = Modifier.weight(1f)
                )
            }
            if (account != null && account.reservedPoints > 0) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 10.dp),
                    color = BrandColors.OutlineSoft
                )
                Text(
                    "当前另有 ${account.reservedPoints} 点处于操作预留中，总余额为 ${account.balancePoints} 点。",
                    color = BrandColors.TextSecondary,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
internal fun GrowthUnlockSettingsCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val preferences = remember(context.applicationContext) {
        LockUnlockFeaturePreferences(context.applicationContext)
    }
    val credentials = remember(context.applicationContext) {
        CredentialStore(context.applicationContext)
    }
    var growthEnabled by remember { mutableStateOf(preferences.growthUnlockEnabled) }
    var requireAuth by remember { mutableStateOf(preferences.requireAuthForGrowthUnlock) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    fun updateGrowth(enabled: Boolean) {
        runCatching { preferences.growthUnlockEnabled = enabled }
            .onSuccess {
                growthEnabled = enabled
                statusMessage = null
            }
            .onFailure { statusMessage = "成长值解锁开关保存失败" }
    }

    fun updateRequireAuth(enabled: Boolean) {
        if (!canEnableUnlockAuthentication(enabled, credentials.hasAnyCredential())) {
            statusMessage = "请先设置数字密码或手势密码"
            return
        }
        runCatching { preferences.requireAuthForGrowthUnlock = enabled }
            .onSuccess {
                requireAuth = enabled
                statusMessage = null
            }
            .onFailure { statusMessage = "密码验证开关保存失败" }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = BrandColors.SurfaceCard),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            LockUnlockFeatureToggle(
                title = "成长值解锁",
                description = "显示使用成长值暂停或跳过自律锁屏的入口",
                checked = growthEnabled,
                onCheckedChange = ::updateGrowth
            )
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 12.dp),
                color = BrandColors.OutlineSoft
            )
            LockUnlockFeatureToggle(
                title = "成长值解锁需要验证密码",
                description = "开启后使用成长值解锁需验证数字密码或手势，关闭则直接解锁",
                checked = requireAuth,
                onCheckedChange = ::updateRequireAuth
            )
            statusMessage?.let {
                Text(
                    it,
                    color = BrandColors.Warning,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

@Composable
internal fun GrowthLedgerCard(recentLedger: List<GrowthLedgerEntry>) {
    val dateFormat = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    Card(
        colors = CardDefaults.cardColors(containerColor = BrandColors.SurfaceCard),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            Text(
                "成长记录",
                color = BrandColors.TextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(12.dp))
            if (recentLedger.isEmpty()) {
                Text(
                    "暂无成长记录",
                    color = BrandColors.TextSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    textAlign = TextAlign.Center
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        recentLedger.forEach { entry ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        growthLedgerLabel(entry),
                                        color = BrandColors.TextPrimary,
                                        fontSize = 13.sp
                                    )
                                    Text(
                                        dateFormat.format(Date(entry.occurredAtEpochMillis)),
                                        color = BrandColors.TextTertiary,
                                        fontSize = 10.sp
                                    )
                                }
                                val delta = entry.balanceDeltaPoints
                                Text(
                                    when {
                                        delta > 0 -> "+$delta"
                                        delta < 0 -> delta.toString()
                                        entry.reservedDeltaPoints > 0 -> "预留 ${entry.reservedDeltaPoints}"
                                        else -> "已记录"
                                    },
                                    color = if (delta < 0) BrandColors.Warning else BrandColors.Primary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExpandableGrowthSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val rotationAngle by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        label = "arrowRotation"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(vertical = 12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = title,
                color = BrandColors.TextPrimary,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = if (expanded) "折叠" else "展开",
                tint = BrandColors.TextTertiary,
                modifier = Modifier
                    .size(20.dp)
                    .graphicsLayer(rotationZ = rotationAngle)
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                content()
            }
        }
    }
}

@Composable
internal fun GrowthRulesCard(
    account: GrowthAccount?,
    currentStage: GrowthStage?
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = BrandColors.SurfaceCard),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            Text(
                "成长账户规则与详情",
                color = BrandColors.TextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(6.dp))
            ExpandableGrowthSection("成长账户") {
                GrowthBalanceSection(account)
            }
            HorizontalDivider(color = BrandColors.OutlineSoft)
            ExpandableGrowthSection("获得成长值") {
                GrowthRewardRulesSection()
            }
            HorizontalDivider(color = BrandColors.OutlineSoft)
            ExpandableGrowthSection("暂停、跳过与费用") {
                GrowthSpendRulesSection()
            }
            HorizontalDivider(color = BrandColors.OutlineSoft)
            ExpandableGrowthSection("小芽成长阶段") {
                GrowthStageRulesSection(currentStage)
            }
        }
    }
}

@Composable
private fun PetStageUpgradeDialog(
    event: PetStageUpgradeEvent,
    onDismiss: () -> Unit
) {
    val profile = remember(event.currentStage) { petStageProfile(event.currentStage) }
    var revealed by remember(event.currentStage) { mutableStateOf(false) }
    LaunchedEffect(event.currentStage) { revealed = true }
    val scale by animateFloatAsState(
        targetValue = if (revealed) 1f else 0.72f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 320f),
        label = "pet-stage-upgrade-scale"
    )
    val rotation by animateFloatAsState(
        targetValue = if (revealed) 0f else -12f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 360f),
        label = "pet-stage-upgrade-rotation"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "成长为${event.currentStage.displayName}",
                color = BrandColors.TextPrimary,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(104.dp)
                        .background(
                            BrandColors.Primary.copy(
                                alpha = 0.08f + profile.appearanceEffect.glowAlpha
                            ),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    com.example.controlfree.ui.common.GrowingPlantCanvas(
                        stage = event.currentStage,
                        modifier = Modifier
                            .size(82.dp)
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                rotationZ = rotation
                            }
                    )
                }
                Text(
                    "${profile.expressionMark} 新阶段能力已解锁",
                    color = BrandColors.Primary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    event.unlockedStages.joinToString(separator = "\n") { stage ->
                        "${stage.displayName}：${stage.touchActionUnlocks}；" +
                            "${stage.bubbleExpressionUnlocks}；${stage.appearanceEffectUnlocks}"
                    },
                    color = BrandColors.TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("知道了") }
        }
    )
}

@Composable
private fun GrowthBalanceSection(account: GrowthAccount?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            GrowthMetric(
                label = "可用成长值",
                value = account?.availableBalancePoints?.toString() ?: "--",
                detail = "余额上限 ${GrowthPolicy.MAX_BALANCE_POINTS} 点",
                modifier = Modifier.weight(1f)
            )
            GrowthMetric(
                label = "终身成长经验",
                value = account?.lifetimeExperience?.toString() ?: "--",
                detail = "只增不减，用于小芽升级",
                modifier = Modifier.weight(1f)
            )
        }
        HorizontalDivider(color = BrandColors.OutlineSoft)
        GrowthRuleLine(
            "首次启用赠送 ${GrowthPolicy.INITIAL_BALANCE_POINTS} 点可用成长值；赠送值不计入终身经验。"
        )
        GrowthRuleLine(
            "消费只减少可用余额，不会降低等级；余额达到 ${GrowthPolicy.MAX_BALANCE_POINTS} 点后，奖励仍会增加终身经验。"
        )
        account?.takeIf { it.reservedPoints > 0 }?.let { currentAccount ->
            GrowthRuleLine(
                "当前另有 ${currentAccount.reservedPoints} 点处于操作预留中，总余额为 ${currentAccount.balancePoints} 点。"
            )
        }
    }
}

@Composable
private fun GrowthRewardRulesSection() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        GrowthRuleLine("自然完成任何正时长的锁定，至少获得 1 点。")
        GrowthRuleLine("每满 15 分钟获得 1 点；达到 25 分钟额外 +3 点，达到 60 分钟再额外 +2 点。")
        GrowthRuleLine("示例：5/10/15 分钟=1 点，25 分钟=4 点，30 分钟=5 点，45 分钟=6 点，60 分钟=9 点。")
        GrowthRuleLine("单次监督最多 ${GrowthPolicy.MAX_REWARD_PER_SUPERVISION} 点；自然完成与继续自律奖励每日合计最多 ${GrowthPolicy.MAX_COMMON_REWARD_PER_DAY} 点。")
        GrowthRuleLine("放弃暂停或跳过后继续自律满 5 分钟，每个监督周期额外获得 ${GrowthPolicy.CONTINUATION_REWARD_POINTS} 点。")
        GrowthRuleLine("每日奖励额度在设备本地时间 00:00 重置；升级前的历史记录不按新规则补算。")
    }
}

@Composable
private fun GrowthSpendRulesSection() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        GrowthRuleLine("选择暂停多长时间，就扣除多少成长值/积分。例如暂停 8 分钟扣除 8 积分。")
        GrowthRuleLine("解锁成功（含通过密码或手势验证成功）后暂停即生效并扣除积分；若取消或验证失败则不扣积分。")
        GrowthRuleLine("跳过本次监督需要 ${GrowthPolicy.SKIP_COST_POINTS} 点。")
        GrowthRuleLine("普通暂停或跳过后至少保留 ${GrowthPolicy.EMERGENCY_RESERVE_POINTS} 点应急余额；余额不足时不会执行。")
        GrowthRuleLine("百科挑战通关可免除本次暂停或跳过费用，并可直接解锁。")
        GrowthRuleLine("费用会先预留；操作执行失败、过期或取消落地时自动退回，不会产生负余额。")
    }
}

@Composable
private fun GrowthStageRulesSection(currentStage: GrowthStage?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            "高阶段会保留此前已解锁的动作、气泡表情和外观效果。",
            color = BrandColors.TextSecondary,
            fontSize = 12.sp,
            lineHeight = 18.sp
        )
        GrowthStage.entries.forEach { stage ->
            GrowthStageRuleRow(stage = stage, isCurrent = stage == currentStage)
        }
        val nextStage = currentStage?.let { GrowthStage.entries.getOrNull(it.ordinal + 1) }
        HorizontalDivider(color = BrandColors.OutlineSoft)
        Text(
            when {
                currentStage == null -> "正在读取当前阶段与下一阶段解锁内容…"
                nextStage == null -> "已达到星芽阶段，全部动作、气泡表情和外观效果均已解锁。"
                else -> "下一阶段「${nextStage.displayName}」需累计 ${nextStage.cumulativeExperienceThreshold} 经验；将解锁 ${nextStage.touchActionUnlocks}、${nextStage.bubbleExpressionUnlocks}、${nextStage.appearanceEffectUnlocks}。"
            },
            color = BrandColors.Primary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 18.sp
        )
    }
}

@Composable
private fun GrowthMetric(
    label: String,
    value: String,
    detail: String,
    modifier: Modifier = Modifier
) {
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()
    val numColor = if (BrandColors.isCustomBackground) {
        BrandColors.BackgroundAccent
    } else {
        if (isDark) Color(0xFF55E58F) else Color(0xFF1E3D2F)
    }
    Column(
        modifier = modifier
            .background(
                color = if (isDark) Color.White.copy(alpha = 0.03f) else Color(0xFFF1F5F3),
                shape = RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(label, color = BrandColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        Text(
            value,
            color = numColor,
            fontSize = 26.sp,
            fontWeight = FontWeight.ExtraBold
        )
        Spacer(Modifier.height(4.dp))
        Text(detail, color = BrandColors.TextTertiary, fontSize = 9.sp)
    }
}

@Composable
private fun GrowthRuleSectionTitle(title: String) {
    Spacer(Modifier.height(10.dp))
    Text(
        title,
        color = BrandColors.TextPrimary,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 7.dp)
    )
}

@Composable
private fun GrowthRuleLine(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .padding(top = 7.dp)
                .size(5.dp)
                .background(BrandColors.Primary, CircleShape)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            color = BrandColors.TextSecondary,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun GrowthStageRuleRow(stage: GrowthStage, isCurrent: Boolean) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .padding(top = 5.dp)
                .size(8.dp)
                .background(
                    if (isCurrent) BrandColors.Primary else BrandColors.Outline,
                    CircleShape
                )
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${stage.displayName} · ${stage.levelRangeText} · ${stage.cumulativeExperienceThreshold} 经验",
                    color = BrandColors.TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                if (isCurrent) {
                    Text(
                        "当前",
                        color = BrandColors.Primary,
                        fontSize = 10.sp,
                        modifier = Modifier
                            .background(BrandColors.PrimaryContainer, RoundedCornerShape(10.dp))
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    )
                }
            }
            Text(
                "形态：${stage.touchActionUnlocks}；气泡：${stage.bubbleExpressionUnlocks}；外观：${stage.appearanceEffectUnlocks}",
                color = BrandColors.TextTertiary,
                fontSize = 11.sp,
                lineHeight = 16.sp
            )
        }
    }
}

private fun growthLedgerLabel(entry: GrowthLedgerEntry): String {
    val reason = entry.reason
    val orderId = entry.relatedOrderId
    if (orderId != null) {
        if (orderId.startsWith("ord_pause_")) {
            val minutes = orderId.substringAfter("ord_pause_").substringBefore("_").toIntOrNull() ?: 5
            return when (reason) {
                GrowthLedgerReason.UNLOCK_RESERVED -> "暂停监督 ${minutes} 分钟待执行"
                GrowthLedgerReason.UNLOCK_APPLIED -> "暂停监督 ${minutes} 分钟已执行"
                GrowthLedgerReason.UNLOCK_REFUNDED -> "暂停 ${minutes} 分钟失败退回"
                GrowthLedgerReason.UNLOCK_RESERVATION_EXPIRED -> "暂停 ${minutes} 分钟预留释放"
                else -> "暂停监督 ${minutes} 分钟"
            }
        } else if (orderId.startsWith("ord_skip_")) {
            return when (reason) {
                GrowthLedgerReason.UNLOCK_RESERVED -> "跳过本次监督待执行"
                GrowthLedgerReason.UNLOCK_APPLIED -> "跳过本次监督已执行"
                GrowthLedgerReason.UNLOCK_REFUNDED -> "跳过监督失败退回"
                GrowthLedgerReason.UNLOCK_RESERVATION_EXPIRED -> "跳过监督预留释放"
                else -> "跳过本次监督"
            }
        }
    }
    return when (reason) {
        GrowthLedgerReason.INSTALLATION_GRANT -> "初始成长值"
        GrowthLedgerReason.NATURAL_SUPERVISION_COMPLETION -> "自然完成监督"
        GrowthLedgerReason.CONTINUED_SELF_DISCIPLINE -> "继续自律奖励"
        GrowthLedgerReason.TODO_COMPLETED -> "完成待办"
        GrowthLedgerReason.HABIT_CHECKED_IN -> "习惯达标"
        GrowthLedgerReason.QUICK_NOTE_CAPTURED -> "记录闪念"
        GrowthLedgerReason.STREAK_BADGE_UNLOCKED -> "解锁连击徽章"
        GrowthLedgerReason.ANNIVERSARY_REACHED -> "重要时刻已达成"
        GrowthLedgerReason.UNLOCK_RESERVED -> "暂停/跳过待执行"
        GrowthLedgerReason.UNLOCK_APPLIED -> "暂停/跳过已执行"
        GrowthLedgerReason.UNLOCK_REFUNDED -> "操作失败已退回"
        GrowthLedgerReason.UNLOCK_RESERVATION_EXPIRED -> "过期预留已释放"
    }
}
