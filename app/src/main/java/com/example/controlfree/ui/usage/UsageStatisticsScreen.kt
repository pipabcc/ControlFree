package com.example.controlfree.ui.usage

import android.widget.ImageView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.example.controlfree.ai.ProductivityAiCoordinator
import com.example.controlfree.ai.SelfDisciplineReport
import kotlinx.coroutines.launch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.controlfree.data.AppAllowlistManager
import com.example.controlfree.data.AppUsageDayDetails
import com.example.controlfree.data.AppUsageDetails
import com.example.controlfree.data.AppUsageRecord
import com.example.controlfree.data.AppUsageSession
import com.example.controlfree.data.AppUsageSessionDetailCompleteness
import com.example.controlfree.data.UsageAccessManager
import com.example.controlfree.data.UsageOverview
import com.example.controlfree.data.UsageStatsRepository
import com.example.controlfree.runtime.UsageAccessSettingsDestination
import com.example.controlfree.runtime.UsageAccessSettingsNavigator
import com.example.controlfree.theme.BrandColors
import com.example.controlfree.ui.layout.ResponsiveLayoutSpec
import com.example.controlfree.ui.layout.rememberResponsiveLayoutSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.format.DateTimeFormatter
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

private sealed interface UsageScreenState {
    data object PermissionRequired : UsageScreenState
    data object Loading : UsageScreenState
    data class Content(val overview: UsageOverview) : UsageScreenState
    data class Error(val message: String) : UsageScreenState
}

// 页面快速切换时，已进入系统 UsageStats 的阻塞查询无法立即响应协程取消；全局互斥可避免重入。
private val usageOverviewLoadMutex = Mutex()

internal fun resolveSelectedUsageDetails(
    overview: UsageOverview?,
    selectedPackageName: String?
): AppUsageDetails? = selectedPackageName?.let { overview?.appDetailsByPackage?.get(it) }

@Composable
fun UsageStatisticsContent(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val accessManager = remember { UsageAccessManager(context.applicationContext) }
    val repository = remember { UsageStatsRepository(context.applicationContext) }
    val allowlistManager = remember { AppAllowlistManager(context.applicationContext) }
    val refreshCoordinator = remember { UsageRefreshCoordinator(initialLoadActive = true) }
    val responsiveLayout = rememberResponsiveLayoutSpec()
    // token=0 的 LaunchedEffect 负责首次切换到统计页时立即加载。
    var refreshToken by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<UsageScreenState>(UsageScreenState.Loading) }
    var selectedPackageName by remember { mutableStateOf<String?>(null) }
    val requestRefresh = {
        if (refreshCoordinator.requestRefresh()) refreshToken++
    }

    DisposableEffect(lifecycleOwner) {
        var refreshAfterBackground = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_STOP -> refreshAfterBackground = true
                Lifecycle.Event.ON_RESUME -> {
                    if (refreshAfterBackground) {
                        refreshAfterBackground = false
                        requestRefresh()
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(refreshToken) {
        try {
            do {
                state = UsageScreenState.Loading
                state = if (!accessManager.hasUsageAccess()) {
                    UsageScreenState.PermissionRequired
                } else {
                    try {
                        UsageScreenState.Content(
                            withContext(Dispatchers.IO) {
                                usageOverviewLoadMutex.withLock { repository.loadOverview() }
                            }
                        )
                    } catch (error: CancellationException) {
                        // 页面退出属于正常控制流，不能显示为读取失败。
                        throw error
                    } catch (_: Exception) {
                        UsageScreenState.Error("暂时无法读取使用记录，请稍后重试")
                    }
                }
            } while (refreshCoordinator.finishLoadAndShouldRefresh())
        } finally {
            refreshCoordinator.cancelLoad()
        }
    }

    val openUsageAccessSettings = {
        val destination = UsageAccessSettingsNavigator.open(context.packageName) {
            context.startActivity(it)
        }
        if (destination == UsageAccessSettingsDestination.UNAVAILABLE) {
            Toast.makeText(
                context,
                "无法打开使用情况访问设置，请在系统设置中手动处理",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    val visibleSelectedDetails = resolveSelectedUsageDetails(
        overview = (state as? UsageScreenState.Content)?.overview,
        selectedPackageName = selectedPackageName
    )

    BackHandler(enabled = visibleSelectedDetails != null) {
        selectedPackageName = null
    }

    Box(
        modifier = modifier.fillMaxSize().background(BrandColors.PageBackground),
        contentAlignment = Alignment.Center
    ) {
        when (val current = state) {
            UsageScreenState.Loading -> CircularProgressIndicator(color = BrandColors.Primary)
            UsageScreenState.PermissionRequired -> PermissionRequiredContent(
                onOpenSettings = openUsageAccessSettings,
                responsiveLayout = responsiveLayout
            )
            is UsageScreenState.Error -> UsageErrorContent(
                message = current.message,
                onRetry = requestRefresh,
                onOpenSettings = openUsageAccessSettings,
                responsiveLayout = responsiveLayout
            )
            is UsageScreenState.Content -> {
                if (visibleSelectedDetails == null) {
                    UsageOverviewContent(
                        overview = current.overview,
                        allowlistManager = allowlistManager,
                        responsiveLayout = responsiveLayout,
                        onAppClick = { selectedPackageName = it }
                    )
                } else {
                    UsageAppDetailContent(
                        details = visibleSelectedDetails,
                        allowlistManager = allowlistManager,
                        responsiveLayout = responsiveLayout,
                        onBack = { selectedPackageName = null }
                    )
                }
            }
        }
    }
}

/** 合并加载期间收到的刷新请求，保证同一统计页始终只有一个查询在运行。 */
internal class UsageRefreshCoordinator(initialLoadActive: Boolean = false) {
    private var isLoadActive = initialLoadActive
    private var isRefreshPending = false

    @Synchronized
    fun requestRefresh(): Boolean {
        if (isLoadActive) {
            isRefreshPending = true
            return false
        }
        isLoadActive = true
        return true
    }

    @Synchronized
    fun finishLoadAndShouldRefresh(): Boolean {
        if (!isLoadActive) return false
        return if (isRefreshPending) {
            isRefreshPending = false
            true
        } else {
            isLoadActive = false
            false
        }
    }

    @Synchronized
    fun cancelLoad() {
        isLoadActive = false
        isRefreshPending = false
    }
}

@Composable
private fun UsageErrorContent(
    message: String,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    responsiveLayout: ResponsiveLayoutSpec
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .padding(responsiveLayout.pageHorizontalPaddingDp.dp)
    ) {
        Text(message, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(18.dp))
        if (responsiveLayout.isLargeFont) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("重试") }
                OutlinedButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("检查使用权限") }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onRetry) { Text("重试") }
                OutlinedButton(onClick = onOpenSettings) { Text("检查使用权限") }
            }
        }
    }
}

@Composable
private fun PermissionRequiredContent(
    onOpenSettings: () -> Unit,
    responsiveLayout: ResponsiveLayoutSpec
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .padding(responsiveLayout.pageHorizontalPaddingDp.dp)
    ) {
        Text(
            "需要使用情况访问权限",
            color = BrandColors.TextPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "统计明细仅保留在本机，用于白名单 App 离开后的自动回锁。",
            color = BrandColors.TextSecondary
        )
        Spacer(Modifier.height(22.dp))
        Button(onClick = onOpenSettings) { Text("前往授权") }
    }
}

@Composable
private fun UsageOverviewContent(
    overview: UsageOverview,
    allowlistManager: AppAllowlistManager,
    responsiveLayout: ResponsiveLayoutSpec,
    onAppClick: (String) -> Unit
) {
    val maxDay = overview.lastSevenDays.maxOfOrNull { it.foregroundMillis }?.coerceAtLeast(1L) ?: 1L
    var selectedDate by remember { mutableStateOf<java.time.LocalDate?>(null) }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val aiCoordinator = remember(context) { ProductivityAiCoordinator.getInstance(context) }
    var showReportDialog by remember { mutableStateOf(false) }
    var isGeneratingReport by remember { mutableStateOf(false) }
    var reportResult by remember { mutableStateOf<SelfDisciplineReport?>(null) }
    var reportError by remember { mutableStateOf<String?>(null) }

    val displayApps = remember(selectedDate, overview) {
        if (selectedDate == null) {
            overview.todayApps
        } else {
            val list = mutableListOf<AppUsageRecord>()
            overview.appDetailsByPackage.forEach { (pkg, details) ->
                val dayDetail = details.days.firstOrNull { it.date == selectedDate }
                if (dayDetail != null && dayDetail.foregroundMillis > 0) {
                    list.add(
                        AppUsageRecord(
                            packageName = pkg,
                            label = details.label,
                            foregroundMillis = dayDetail.foregroundMillis,
                            lastTimeUsedMillis = dayDetail.sessions.maxOfOrNull { it.endMillis } ?: 0L
                        )
                    )
                }
            }
            list.sortByDescending { it.foregroundMillis }
            list
        }
    }

    val displayTotalMillis = remember(selectedDate, displayApps, overview) {
        if (selectedDate == null) overview.todayTotalMillis else displayApps.sumOf { it.foregroundMillis }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxHeight()
            .widthIn(max = minOf(responsiveLayout.preferredContentMaxWidthDp, 960).dp)
            .fillMaxWidth(),
        contentPadding = PaddingValues(
            horizontal = responsiveLayout.pageHorizontalPaddingDp.dp,
            vertical = 18.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = BrandColors.SurfaceCard),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(Modifier.fillMaxWidth().padding(20.dp)) {
                    val headerTitle = if (selectedDate == null) "今日应用前台使用合计" else {
                        val formatter = java.time.format.DateTimeFormatter.ofPattern("MM/dd")
                        "${selectedDate!!.format(formatter)} 应用前台使用合计"
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(headerTitle, color = BrandColors.TextSecondary)
                        IconButton(
                            onClick = {
                                reportError = null
                                reportResult = null
                                showReportDialog = true
                                isGeneratingReport = true
                                coroutineScope.launch {
                                    try {
                                        val todayMin = displayTotalMillis / 60_000L
                                        val todayAppsPair = overview.todayApps.map { it.label to it.foregroundMillis / 60_000L }
                                        val lastSevenPair = overview.lastSevenDays.map {
                                            val dateStr = it.date.format(java.time.format.DateTimeFormatter.ofPattern("M/d"))
                                            dateStr to it.foregroundMillis / 60_000L
                                        }
                                        val result = aiCoordinator.generateSelfDisciplineReport(
                                            todayTotalMinutes = todayMin,
                                            todayApps = todayAppsPair,
                                            lastSevenDays = lastSevenPair
                                        )
                                        reportResult = result
                                    } catch (e: Exception) {
                                        reportError = e.message ?: "生成复盘报告失败，请重试。"
                                    } finally {
                                        isGeneratingReport = false
                                    }
                                }
                            },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.AutoAwesome,
                                contentDescription = "AI 智能复盘",
                                tint = BrandColors.Primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    Text(
                        formatDuration(displayTotalMillis),
                        color = BrandColors.TextPrimary,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "按本机自然日统计亮屏且已解锁期间的单一顶层前台 App；" +
                            "系统界面、熄屏和锁屏时段不计入，分屏及厂商事件可能略有误差。",
                        color = BrandColors.TextTertiary,
                        fontSize = 11.sp
                    )
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = BrandColors.SurfaceCard),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(Modifier.fillMaxWidth().padding(18.dp)) {
                    Text(
                        "最近 7 个自然日（含今日）",
                        color = BrandColors.TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(14.dp))
                    val dayEmphases = remember(overview.lastSevenDays) {
                        resolveUsageBarEmphases(
                            overview.lastSevenDays.map { it.foregroundMillis }
                        )
                    }
                    overview.lastSevenDays.forEachIndexed { index, day ->
                        val date = day.date.format(DateTimeFormatter.ofPattern("MM/dd"))
                        val duration = formatDurationShort(day.foregroundMillis)
                        val barColor = usageBarColor(dayEmphases[index])
                        val isSelected = selectedDate == day.date
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isSelected) BrandColors.Primary.copy(alpha = 0.12f) else Color.Transparent)
                                .clickable {
                                    selectedDate = if (isSelected) null else day.date
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            if (responsiveLayout.isLargeFont) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(date, color = BrandColors.TextSecondary, fontSize = 12.sp)
                                    Text(duration, color = BrandColors.TextSecondary, fontSize = 11.sp)
                                }
                                Spacer(Modifier.height(5.dp))
                                LinearProgressIndicator(
                                    progress = { day.foregroundMillis.toFloat() / maxDay.toFloat() },
                                    modifier = Modifier.fillMaxWidth().height(8.dp),
                                    color = barColor,
                                    trackColor = BrandColors.SurfaceMuted
                                )
                            } else {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        date,
                                        color = BrandColors.TextSecondary,
                                        fontSize = 12.sp,
                                        modifier = Modifier.width(52.dp)
                                    )
                                    LinearProgressIndicator(
                                        progress = { day.foregroundMillis.toFloat() / maxDay.toFloat() },
                                        modifier = Modifier.weight(1f).height(8.dp),
                                        color = barColor,
                                        trackColor = BrandColors.SurfaceMuted
                                    )
                                    Text(
                                        duration,
                                        color = BrandColors.TextSecondary,
                                        fontSize = 11.sp,
                                        modifier = Modifier.width(64.dp).padding(start = 8.dp)
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }
        }

        item {
            val appTitleText = if (selectedDate == null) {
                "今日应用"
            } else {
                val formatter = java.time.format.DateTimeFormatter.ofPattern("MM/dd")
                "${selectedDate!!.format(formatter)} 应用"
            }
            Text(
                appTitleText,
                color = BrandColors.TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }
        if (displayApps.isEmpty()) {
            item {
                Text(
                    "暂无可统计的 App 前台使用记录",
                    color = BrandColors.TextSecondary
                )
            }
        } else {
            itemsIndexed(displayApps, key = { _, r -> r.packageName }) { index, record ->
                UsageAppRow(
                    record = record,
                    rank = index,
                    totalMillis = displayTotalMillis,
                    allowlistManager = allowlistManager,
                    isLargeFont = responsiveLayout.isLargeFont,
                    onClick = { onAppClick(record.packageName) }
                )
            }
        }
    }

    if (showReportDialog) {
        AlertDialog(
                onDismissRequest = { showReportDialog = false },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.AutoAwesome,
                            contentDescription = null,
                            tint = BrandColors.Primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("AI 智能自律复盘报告", fontWeight = FontWeight.Bold)
                    }
                },
                text = {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp)
                    ) {
                        if (isGeneratingReport) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator(color = BrandColors.Primary)
                                Spacer(Modifier.height(16.dp))
                                Text(
                                    "AI 助理正在深度复盘今日行为并编写报告...",
                                    color = BrandColors.TextSecondary,
                                    fontSize = 13.sp,
                                    textAlign = TextAlign.Center
                                )
                            }
                        } else if (reportError != null) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    reportError ?: "未知错误",
                                    color = BrandColors.Danger,
                                    fontSize = 14.sp,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(Modifier.height(16.dp))
                                Button(
                                    onClick = {
                                        reportError = null
                                        isGeneratingReport = true
                                        coroutineScope.launch {
                                            try {
                                                val todayMin = displayTotalMillis / 60_000L
                                                val todayAppsPair = overview.todayApps.map { it.label to it.foregroundMillis / 60_000L }
                                                val lastSevenPair = overview.lastSevenDays.map {
                                                    val dateStr = it.date.format(java.time.format.DateTimeFormatter.ofPattern("M/d"))
                                                    dateStr to it.foregroundMillis / 60_000L
                                                }
                                                val result = aiCoordinator.generateSelfDisciplineReport(
                                                    todayTotalMinutes = todayMin,
                                                    todayApps = todayAppsPair,
                                                    lastSevenDays = lastSevenPair
                                                )
                                                reportResult = result
                                            } catch (e: Exception) {
                                                reportError = e.message ?: "生成复盘报告失败，请重试。"
                                            } finally {
                                                isGeneratingReport = false
                                            }
                                        }
                                    }
                                ) {
                                    Text("重试")
                                }
                            }
                        } else if (reportResult != null) {
                            val report = reportResult!!
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = BrandColors.PrimaryContainer),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = report.greeting,
                                        color = BrandColors.Primary,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.padding(12.dp),
                                        lineHeight = 20.sp
                                    )
                                }
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("🔍 行为使用诊断", fontWeight = FontWeight.Bold, color = BrandColors.TextPrimary, fontSize = 13.sp)
                                    Text(report.diagnosis, color = BrandColors.TextSecondary, fontSize = 13.sp, lineHeight = 19.sp)
                                }
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("📈 近期自律趋势", fontWeight = FontWeight.Bold, color = BrandColors.TextPrimary, fontSize = 13.sp)
                                    Text(report.trend, color = BrandColors.TextSecondary, fontSize = 13.sp, lineHeight = 19.sp)
                                }
                                if (report.suggestions.isNotEmpty()) {
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("💡 自律行动建议", fontWeight = FontWeight.Bold, color = BrandColors.TextPrimary, fontSize = 13.sp)
                                        report.suggestions.forEach { sugg ->
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.Top
                                            ) {
                                                Text("• ", color = BrandColors.Primary, fontWeight = FontWeight.Bold)
                                                Text(sugg, color = BrandColors.TextSecondary, fontSize = 13.sp, lineHeight = 18.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showReportDialog = false }) {
                        Text("我知晓了", fontWeight = FontWeight.Bold, color = BrandColors.Primary)
                    }
                }
            )
        }
}

@Composable
private fun UsageAppRow(
    record: AppUsageRecord,
    rank: Int,
    totalMillis: Long,
    allowlistManager: AppAllowlistManager,
    isLargeFont: Boolean,
    onClick: () -> Unit
) {
    val barColor = when (rank) {
        0 -> Color(0xFFE5A65D)
        1 -> Color(0xFFF57C00)
        2 -> Color(0xFF1E88E5)
        else -> BrandColors.Primary
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = BrandColors.SurfaceCard),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AndroidView(
                factory = { ImageView(it) },
                update = { it.setImageDrawable(allowlistManager.getApplicationIcon(record.packageName)) },
                modifier = Modifier.size(42.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                if (isLargeFont) {
                    Text(
                        record.label,
                        color = BrandColors.TextPrimary,
                        maxLines = 2
                    )
                    Text(
                        formatDuration(record.foregroundMillis),
                        color = barColor,
                        fontSize = 12.sp
                    )
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            record.label,
                            color = BrandColors.TextPrimary,
                            maxLines = 1,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            formatDuration(record.foregroundMillis),
                            color = barColor,
                            fontSize = 12.sp
                        )
                    }
                }
                Text(
                    "最近使用 ${formatLastUsed(record.lastTimeUsedMillis)}",
                    color = BrandColors.TextTertiary,
                    fontSize = 10.sp
                )
                Spacer(Modifier.height(7.dp))
                LinearProgressIndicator(
                    progress = {
                        if (totalMillis <= 0L) 0f
                        else record.foregroundMillis.toFloat() / totalMillis.toFloat()
                    },
                    modifier = Modifier.fillMaxWidth().height(5.dp),
                    color = barColor,
                    trackColor = BrandColors.SurfaceMuted
                )
            }
        }
    }
}

@Composable
private fun UsageAppDetailContent(
    details: AppUsageDetails,
    allowlistManager: AppAllowlistManager,
    responsiveLayout: ResponsiveLayoutSpec,
    onBack: () -> Unit
) {
    val today = details.days.lastOrNull()?.date
    LazyColumn(
        modifier = Modifier
            .fillMaxHeight()
            .widthIn(max = minOf(responsiveLayout.preferredContentMaxWidthDp, 960).dp)
            .fillMaxWidth(),
        contentPadding = PaddingValues(
            horizontal = responsiveLayout.pageHorizontalPaddingDp.dp,
            vertical = 18.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = BrandColors.SurfaceCard),
                shape = RoundedCornerShape(18.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回今日应用",
                            tint = BrandColors.TextPrimary
                        )
                    }
                    AndroidView(
                        factory = { ImageView(it) },
                        update = {
                            it.setImageDrawable(
                                allowlistManager.getApplicationIcon(details.packageName)
                            )
                        },
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = details.label,
                            color = BrandColors.TextPrimary,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2
                        )
                        Text(
                            text = "最近 7 个自然日使用详情",
                            color = BrandColors.TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }

        item {
            UsageSevenDayChartCard(
                days = details.days,
                isLargeFont = responsiveLayout.isLargeFont
            )
        }

        items(details.days.asReversed(), key = AppUsageDayDetails::date) { day ->
            UsageDayTimelineCard(day = day, isToday = day.date == today)
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = BrandColors.SurfaceMuted),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(
                    text = "说明：时间点依据 Android 提供的顶层前台事件重建。分屏、画中画、" +
                        "系统清理事件或部分厂商系统行为可能造成少量误差。",
                    color = BrandColors.TextTertiary,
                    fontSize = 11.sp,
                    modifier = Modifier.fillMaxWidth().padding(14.dp)
                )
            }
        }
    }
}

@Composable
private fun UsageDayTimelineCard(
    day: AppUsageDayDetails,
    isToday: Boolean
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = BrandColors.SurfaceCard),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = buildString {
                        append(day.date.format(usageDetailDateFormatter))
                        if (isToday) append(" · 今天")
                    },
                    color = BrandColors.TextPrimary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = formatDetailedDuration(day.foregroundMillis),
                    color = BrandColors.Primary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "进入前台 ${day.launchCount} 次",
                color = BrandColors.TextSecondary,
                fontSize = 12.sp
            )
            Spacer(Modifier.height(14.dp))
            if (
                day.sessionDetailCompleteness ==
                AppUsageSessionDetailCompleteness.PARTIAL
            ) {
                Text(
                    text = "系统仅保留部分逐次明细，总时长以系统汇总为准",
                    color = BrandColors.Warning,
                    fontSize = 12.sp
                )
                if (day.sessions.isNotEmpty()) Spacer(Modifier.height(12.dp))
            }
            if (day.sessions.isEmpty() && day.foregroundMillis <= 0L) {
                Text(
                    text = "当日无前台使用记录",
                    color = BrandColors.TextTertiary,
                    fontSize = 12.sp
                )
            } else if (day.sessions.isNotEmpty()) {
                day.sessions.forEachIndexed { index, session ->
                    UsageSessionTimelineItem(
                        session = session,
                        isLast = index == day.sessions.lastIndex
                    )
                }
            }
        }
    }
}

@Composable
private fun UsageSessionTimelineItem(
    session: AppUsageSession,
    isLast: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)
    ) {
        Column(
            modifier = Modifier.width(22.dp).fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier
                    .size(10.dp)
                    .background(BrandColors.Primary, CircleShape)
            )
            if (!isLast) {
                Box(
                    Modifier
                        .width(2.dp)
                        .weight(1f)
                        .background(BrandColors.Outline)
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(
            modifier = Modifier.weight(1f).padding(bottom = if (isLast) 0.dp else 16.dp)
        ) {
            Text(
                text = "${formatTimelineTime(session.startMillis)} – " +
                    formatTimelineTime(session.endMillis),
                color = BrandColors.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "持续 ${formatDetailedDuration(session.durationMillis)}",
                color = BrandColors.TextSecondary,
                fontSize = 11.sp
            )
        }
    }
}

private fun formatDuration(milliseconds: Long): String {
    val totalMinutes = milliseconds.coerceAtLeast(0L) / 60_000L
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return if (hours > 0L) "${hours}小时 ${minutes}分钟" else "${minutes}分钟"
}

private fun formatDurationShort(milliseconds: Long): String {
    val minutes = milliseconds.coerceAtLeast(0L) / 60_000L
    return if (minutes >= 60L) "${minutes / 60}时${minutes % 60}分" else "${minutes}分钟"
}

private fun formatDetailedDuration(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0L) / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = totalSeconds % 3_600L / 60L
    val seconds = totalSeconds % 60L
    return when {
        hours > 0L -> "${hours}小时${minutes}分钟${seconds}秒"
        minutes > 0L -> "${minutes}分钟${seconds}秒"
        else -> "${seconds}秒"
    }
}

private fun formatTimelineTime(milliseconds: Long): String =
    Instant.ofEpochMilli(milliseconds)
        .atZone(ZoneId.systemDefault())
        .format(usageDetailTimeFormatter)

private fun formatLastUsed(milliseconds: Long): String =
    if (milliseconds <= 0L) {
        "--:--"
    } else {
        Instant.ofEpochMilli(milliseconds)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("HH:mm"))
    }

private val usageDetailDateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.SIMPLIFIED_CHINESE)

private val usageDetailTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm:ss")
