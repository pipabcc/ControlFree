package com.example.controlfree.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.controlfree.supervision.history.SupervisionHistoryAnalytics
import com.example.controlfree.supervision.history.SupervisionHistoryEventRecord
import com.example.controlfree.supervision.history.SupervisionHistoryOverview
import com.example.controlfree.supervision.history.SupervisionRecoveryStatus
import com.example.controlfree.supervision.history.SupervisionSessionEndReason
import com.example.controlfree.supervision.history.SupervisionSessionKind
import com.example.controlfree.supervision.history.SupervisionSessionRecord
import com.example.controlfree.supervision.history.isLegacyAmbiguousManual
import com.example.controlfree.supervision.persistence.SupervisionHistoryRepository
import com.example.controlfree.theme.BrandColors
import com.example.controlfree.ui.layout.ResponsiveLayoutSpec
import com.example.controlfree.ui.layout.rememberResponsiveLayoutSpec
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private const val RECENT_HISTORY_LIMIT = 50

internal class HistoryResumeRefreshPolicy {
    private var refreshAfterBackground = false

    fun onLifecycleEvent(event: Lifecycle.Event): Boolean = when (event) {
        Lifecycle.Event.ON_PAUSE,
        Lifecycle.Event.ON_STOP -> {
            refreshAfterBackground = true
            false
        }
        Lifecycle.Event.ON_RESUME -> {
            val shouldRefresh = refreshAfterBackground
            refreshAfterBackground = false
            shouldRefresh
        }
        else -> false
    }
}

private data class HistoryStatisticsSnapshot(
    val overview: SupervisionHistoryOverview,
    val recent: List<SupervisionSessionRecord>,
    val recentEvents: List<SupervisionHistoryEventRecord>,
    val generatedAtEpochMillis: Long,
    val zoneId: ZoneId
)

@Composable
fun HistoryStatisticsContent(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val repository = remember {
        SupervisionHistoryRepository.getInstance(context.applicationContext)
    }
    val responsiveLayout = rememberResponsiveLayoutSpec()
    var periodDays by rememberSaveable { mutableIntStateOf(7) }
    var refreshToken by remember { mutableIntStateOf(0) }
    var snapshot by remember { mutableStateOf<HistoryStatisticsSnapshot?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    DisposableEffect(lifecycleOwner) {
        val refreshPolicy = HistoryResumeRefreshPolicy()
        val observer = LifecycleEventObserver { _, event ->
            if (refreshPolicy.onLifecycleEvent(event)) refreshToken++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(lifecycleOwner) {
        while (true) {
            delay(60_000L)
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                refreshToken++
            }
        }
    }

    LaunchedEffect(repository, periodDays, refreshToken) {
        isLoading = true
        try {
            snapshot = withContext(Dispatchers.IO) {
                loadHistoryStatisticsSnapshot(repository, periodDays)
            }
            loadError = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            loadError = "暂时无法读取监督历史，请稍后重试"
        }
        // 被新刷新取消的旧协程不会执行到这里，避免它关闭新请求的加载状态。
        isLoading = false
    }

    Box(
        modifier = modifier.fillMaxSize().background(BrandColors.PageBackground),
        contentAlignment = Alignment.TopCenter
    ) {
        when {
            snapshot == null && isLoading -> CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = BrandColors.Primary
            )
            snapshot == null -> HistoryLoadErrorContent(
                message = loadError ?: "暂无可显示的监督历史",
                onRetry = { refreshToken++ },
                modifier = Modifier.align(Alignment.Center)
            )
            else -> HistoryStatisticsList(
                snapshot = requireNotNull(snapshot),
                periodDays = periodDays,
                isRefreshing = isLoading,
                loadError = loadError,
                responsiveLayout = responsiveLayout,
                onPeriodChange = { selectedDays -> periodDays = selectedDays },
                onRefresh = { refreshToken++ }
            )
        }
    }
}

private suspend fun loadHistoryStatisticsSnapshot(
    repository: SupervisionHistoryRepository,
    periodDays: Int
): HistoryStatisticsSnapshot {
    val nowEpochMillis = System.currentTimeMillis().coerceAtLeast(0L)
    val now = Instant.ofEpochMilli(nowEpochMillis)
    val zoneId = ZoneId.systemDefault()
    val firstDate = now.atZone(zoneId).toLocalDate().minusDays(periodDays.toLong() - 1L)
    val startEpochMillis = firstDate.atStartOfDay(zoneId).toInstant().toEpochMilli().coerceAtLeast(0L)
    val endExclusiveEpochMillis = (nowEpochMillis + 1L).coerceAtLeast(nowEpochMillis)
    val records = repository.getOverlapping(
        startEpochMillis = startEpochMillis,
        endExclusiveEpochMillis = endExclusiveEpochMillis,
        activeEndEpochMillis = nowEpochMillis
    )
    return HistoryStatisticsSnapshot(
        overview = SupervisionHistoryAnalytics.aggregate(
            records = records,
            periodDays = periodDays,
            now = now,
            zoneId = zoneId
        ),
        recent = repository.observeRecent(RECENT_HISTORY_LIMIT).first(),
        recentEvents = repository.observeRecentEvents(RECENT_HISTORY_LIMIT).first(),
        generatedAtEpochMillis = nowEpochMillis,
        zoneId = zoneId
    )
}

@Composable
private fun HistoryStatisticsList(
    snapshot: HistoryStatisticsSnapshot,
    periodDays: Int,
    isRefreshing: Boolean,
    loadError: String?,
    responsiveLayout: ResponsiveLayoutSpec,
    onPeriodChange: (Int) -> Unit,
    onRefresh: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxHeight()
            .widthIn(max = minOf(responsiveLayout.preferredContentMaxWidthDp, 960).dp)
            .fillMaxWidth(),
        contentPadding = PaddingValues(
            start = responsiveLayout.pageHorizontalPaddingDp.dp,
            end = responsiveLayout.pageHorizontalPaddingDp.dp,
            top = 6.dp,
            bottom = 24.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            HistoryToolbar(
                periodDays = periodDays,
                isRefreshing = isRefreshing,
                responsiveLayout = responsiveLayout,
                onPeriodChange = onPeriodChange,
                onRefresh = onRefresh
            )
        }
        if (loadError != null) {
            item { HistoryInlineError(loadError, onRefresh, responsiveLayout.isLargeFont) }
        }
        item { HistorySummaryCard(snapshot.overview, responsiveLayout.isLargeFont) }
        item { HistoryTrendCard(snapshot.overview, responsiveLayout.isLargeFont) }
        if (snapshot.recentEvents.isNotEmpty()) {
            item {
                Text(
                    text = "设备事件",
                    color = BrandColors.TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            items(
                snapshot.recentEvents,
                key = SupervisionHistoryEventRecord::eventId
            ) { event ->
                HistoryDeviceEventCard(
                    event = event,
                    zoneId = snapshot.zoneId,
                    isLargeFont = responsiveLayout.isLargeFont
                )
            }
        }
        item {
            Text(
                text = "最近会话",
                color = BrandColors.TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        if (snapshot.recent.isEmpty()) {
            item {
                Text(
                    "完成或开始一次监督后，记录会显示在这里。",
                    color = BrandColors.TextSecondary
                )
            }
        } else {
            items(snapshot.recent, key = SupervisionSessionRecord::sessionId) { record ->
                HistorySessionCard(
                    record = record,
                    nowEpochMillis = snapshot.generatedAtEpochMillis,
                    zoneId = snapshot.zoneId,
                    isLargeFont = responsiveLayout.isLargeFont
                )
            }
        }
    }
}

@Composable
private fun HistoryDeviceEventCard(
    event: SupervisionHistoryEventRecord,
    zoneId: ZoneId,
    isLargeFont: Boolean
) {
    val statusText = when (event.recoveryStatus) {
        SupervisionRecoveryStatus.RECOVERY_REQUESTED -> "已请求恢复监督"
        SupervisionRecoveryStatus.RESTORED -> "监督已恢复"
        SupervisionRecoveryStatus.FAILED -> "监督恢复失败"
        SupervisionRecoveryStatus.NO_ACTIVE_SUPERVISION -> "当时无活动监督"
    }
    val statusColor = when (event.recoveryStatus) {
        SupervisionRecoveryStatus.RESTORED -> BrandColors.Primary
        SupervisionRecoveryStatus.FAILED -> BrandColors.Danger
        else -> BrandColors.TextSecondary
    }
    val timeText = Instant.ofEpochMilli(event.occurredAtEpochMillis)
        .atZone(zoneId)
        .format(DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.getDefault()))
    Card(
        colors = CardDefaults.cardColors(containerColor = BrandColors.SurfaceCard),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        if (isLargeFont) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(15.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("设备重启 · $timeText", color = BrandColors.TextPrimary, fontWeight = FontWeight.Bold)
                Text(statusText, color = statusColor, fontSize = 12.sp)
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(15.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "设备重启 · $timeText",
                    color = BrandColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Text(statusText, color = statusColor, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun HistoryToolbar(
    periodDays: Int,
    isRefreshing: Boolean,
    responsiveLayout: ResponsiveLayoutSpec,
    onPeriodChange: (Int) -> Unit,
    onRefresh: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (responsiveLayout.isLargeFont) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "监督趋势",
                    color = BrandColors.TextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "按本机自然日统计；历史明细保存在本机",
                    color = BrandColors.TextSecondary,
                    fontSize = 12.sp
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "监督趋势",
                        color = BrandColors.TextPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "按本机自然日统计；历史明细保存在本机",
                        color = BrandColors.TextSecondary,
                        fontSize = 12.sp
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = periodDays == 7,
                onClick = { onPeriodChange(7) },
                label = { Text("近 7 天") }
            )
            FilterChip(
                selected = periodDays == 30,
                onClick = { onPeriodChange(30) },
                label = { Text("近 30 天") }
            )
            if (!responsiveLayout.isLargeFont) {
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = onRefresh, enabled = !isRefreshing) {
                    Icon(Icons.Default.Refresh, contentDescription = "刷新监督历史")
                }
            }
        }
        if (responsiveLayout.isLargeFont) {
            OutlinedButton(
                onClick = onRefresh,
                enabled = !isRefreshing,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(if (isRefreshing) "刷新中…" else "刷新")
            }
        }
    }
}

@Composable
private fun HistorySummaryCard(
    overview: SupervisionHistoryOverview,
    isLargeFont: Boolean
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = BrandColors.SurfaceCard),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "${overview.periodDays} 天监督覆盖",
                color = BrandColors.TextSecondary
            )
            Text(
                formatHistoryDuration(overview.totalCoveredMillis),
                color = BrandColors.TextPrimary,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                "共 ${overview.sessionCount} 次会话；同时运行的全局与 App 监督只计算一次覆盖时长。",
                color = BrandColors.TextTertiary,
                fontSize = 11.sp
            )
            HorizontalDivider(color = BrandColors.OutlineSoft)
            HistoryMetricPair(
                firstLabel = "进行中",
                firstValue = overview.activeCount,
                secondLabel = "已完成",
                secondValue = overview.completedCount,
                isLargeFont = isLargeFont
            )
            HistoryMetricPair(
                firstLabel = "已取消",
                firstValue = overview.cancelledCount,
                secondLabel = "已替换",
                secondValue = overview.replacedCount,
                isLargeFont = isLargeFont
            )
            val legacyManualCount = overview.legacyAmbiguousManualCount
            val manualSupervisionCount =
                (overview.countsByKind[SupervisionSessionKind.MANUAL_GLOBAL] ?: 0) -
                    legacyManualCount
            val legacySummary = if (legacyManualCount > 0) {
                "  ·  旧版即时任务 $legacyManualCount"
            } else {
                ""
            }
            Text(
                "即时监督 $manualSupervisionCount  ·  " +
                    "定时监督 ${overview.countsByKind[SupervisionSessionKind.SCHEDULED_GLOBAL] ?: 0}  ·  " +
                    "即时专注 ${overview.countsByKind[SupervisionSessionKind.MANUAL_FOCUS] ?: 0}  ·  " +
                    "定时专注 ${overview.countsByKind[SupervisionSessionKind.SCHEDULED_FOCUS] ?: 0}  ·  " +
                    "App 监督 ${overview.countsByKind[SupervisionSessionKind.APP] ?: 0}" +
                    legacySummary,
                color = BrandColors.Primary,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun HistoryMetricPair(
    firstLabel: String,
    firstValue: Int,
    secondLabel: String,
    secondValue: Int,
    isLargeFont: Boolean
) {
    if (isLargeFont) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HistoryMetric(firstLabel, firstValue, Modifier.fillMaxWidth())
            HistoryMetric(secondLabel, secondValue, Modifier.fillMaxWidth())
        }
    } else {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HistoryMetric(firstLabel, firstValue, Modifier.weight(1f))
            HistoryMetric(secondLabel, secondValue, Modifier.weight(1f))
        }
    }
}

@Composable
private fun HistoryMetric(label: String, value: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(BrandColors.SurfaceRaised, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = BrandColors.TextSecondary, fontSize = 12.sp)
        Text(value.toString(), color = BrandColors.TextPrimary, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun HistoryTrendCard(
    overview: SupervisionHistoryOverview,
    isLargeFont: Boolean
) {
    val maxCovered = overview.days.maxOfOrNull { it.coveredMillis }?.coerceAtLeast(1L) ?: 1L
    Card(
        colors = CardDefaults.cardColors(containerColor = BrandColors.SurfaceCard),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("每日覆盖趋势", color = BrandColors.TextPrimary, fontWeight = FontWeight.Bold)
            overview.days.forEach { day ->
                val date = day.date.format(DateTimeFormatter.ofPattern("MM/dd"))
                val duration = formatHistoryDurationShort(day.coveredMillis)
                if (isLargeFont) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(date, color = BrandColors.TextSecondary, fontSize = 12.sp)
                        Text(duration, color = BrandColors.TextSecondary, fontSize = 11.sp)
                    }
                    LinearProgressIndicator(
                        progress = { day.coveredMillis.toFloat() / maxCovered.toFloat() },
                        modifier = Modifier.fillMaxWidth().height(8.dp),
                        color = BrandColors.Primary,
                        trackColor = BrandColors.SurfaceMuted
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            date,
                            color = BrandColors.TextSecondary,
                            fontSize = 12.sp,
                            modifier = Modifier.width(48.dp)
                        )
                        LinearProgressIndicator(
                            progress = { day.coveredMillis.toFloat() / maxCovered.toFloat() },
                            modifier = Modifier.weight(1f).height(8.dp),
                            color = BrandColors.Primary,
                            trackColor = BrandColors.SurfaceMuted
                        )
                        Text(
                            duration,
                            color = BrandColors.TextSecondary,
                            fontSize = 11.sp,
                            modifier = Modifier.width(68.dp).padding(start = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HistorySessionCard(
    record: SupervisionSessionRecord,
    nowEpochMillis: Long,
    zoneId: ZoneId,
    isLargeFont: Boolean
) {
    val statusColor = if (record.isActive) BrandColors.Primary else BrandColors.TextSecondary
    Card(
        colors = CardDefaults.cardColors(containerColor = BrandColors.SurfaceCard),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(15.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (isLargeFont) {
                Text(
                    record.displayName,
                    color = BrandColors.TextPrimary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    record.historyStatusName,
                    color = statusColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        record.displayName,
                        color = BrandColors.TextPrimary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        record.historyStatusName,
                        color = statusColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Text(
                "${record.historyKindName}  ·  ${formatHistoryPeriod(record, zoneId)}",
                color = BrandColors.TextTertiary,
                fontSize = 12.sp
            )
            Text(
                "${record.historyPolicySummary}  ·  " +
                    "持续 ${formatHistoryDuration(record.effectiveDurationMillis(nowEpochMillis))}",
                color = BrandColors.TextSecondary,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun HistoryInlineError(
    message: String,
    onRetry: () -> Unit,
    isLargeFont: Boolean
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = BrandColors.DangerContainer),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        if (isLargeFont) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(message, color = BrandColors.Danger, fontSize = 12.sp)
                Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("重试") }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
            Text(
                message,
                color = BrandColors.Danger,
                modifier = Modifier.weight(1f),
                fontSize = 12.sp
            )
            Button(onClick = onRetry) { Text("重试") }
            }
        }
    }
}

@Composable
private fun HistoryLoadErrorContent(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(message, color = MaterialTheme.colorScheme.error)
        Button(onClick = onRetry) { Text("重试") }
    }
}

private val SupervisionSessionRecord.historyStatusName: String
    get() = when (endReason) {
        null -> "进行中"
        SupervisionSessionEndReason.COMPLETED -> "已完成"
        SupervisionSessionEndReason.CANCELLED -> "已取消"
        SupervisionSessionEndReason.REPLACED -> "已替换"
    }

private val SupervisionSessionRecord.historyKindName: String
    get() = when {
        isLegacyAmbiguousManual -> "旧版即时任务"
        else -> when (kind) {
            SupervisionSessionKind.MANUAL_GLOBAL -> "即时监督"
            SupervisionSessionKind.SCHEDULED_GLOBAL -> "定时监督"
            SupervisionSessionKind.MANUAL_FOCUS -> "即时专注"
            SupervisionSessionKind.SCHEDULED_FOCUS -> "定时专注"
            SupervisionSessionKind.APP -> "App 独立监督"
        }
    }

private val SupervisionSessionRecord.historyPolicySummary: String
    get() = when {
        isLegacyAmbiguousManual ->
            "旧版参数 ${usageMinutes ?: 0} / ${lockMinutes ?: 0} 分钟（监督/专注无法区分）"
        else -> when (kind) {
            SupervisionSessionKind.APP ->
                "额度 ${usageMinutes ?: 0} 分钟 · 休息 ${lockMinutes ?: 0} 分钟"
            SupervisionSessionKind.MANUAL_GLOBAL,
            SupervisionSessionKind.SCHEDULED_GLOBAL ->
                "可用 ${usageMinutes ?: 0} 分钟 · 锁定 ${lockMinutes ?: 0} 分钟"
            SupervisionSessionKind.MANUAL_FOCUS,
            SupervisionSessionKind.SCHEDULED_FOCUS ->
                "锁定 ${lockMinutes ?: 0} 分钟 · 玩机 ${usageMinutes ?: 0} 分钟"
        }
    }

private fun formatHistoryPeriod(record: SupervisionSessionRecord, zoneId: ZoneId): String {
    val formatter = DateTimeFormatter.ofPattern("MM/dd HH:mm", Locale.ROOT)
    val start = Instant.ofEpochMilli(record.startedAtEpochMillis).atZone(zoneId).format(formatter)
    val end = record.endedAtEpochMillis?.let { epochMillis ->
        Instant.ofEpochMilli(epochMillis).atZone(zoneId).format(formatter)
    } ?: "至今"
    return "$start – $end"
}

private fun formatHistoryDuration(milliseconds: Long): String {
    val safeMillis = milliseconds.coerceAtLeast(0L)
    val totalMinutes = safeMillis / 60_000L
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return when {
        hours > 0L -> "${hours}小时 ${minutes}分钟"
        totalMinutes > 0L -> "${totalMinutes}分钟"
        safeMillis > 0L -> "不足 1 分钟"
        else -> "0 分钟"
    }
}

private fun formatHistoryDurationShort(milliseconds: Long): String {
    val totalMinutes = milliseconds.coerceAtLeast(0L) / 60_000L
    return if (totalMinutes >= 60L) {
        "${totalMinutes / 60}时${totalMinutes % 60}分"
    } else {
        "${totalMinutes}分钟"
    }
}
