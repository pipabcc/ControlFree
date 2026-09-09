package com.example.controlfree.ui.todo.ledger

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.automirrored.filled.PlaylistAddCheck
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.controlfree.ai.ParsedLedgerEntry
import com.example.controlfree.theme.BrandColors
import com.example.controlfree.todo.LedgerCategory
import com.example.controlfree.todo.LedgerAmountCodec
import com.example.controlfree.todo.LedgerDirection
import com.example.controlfree.todo.LedgerEntryEntity
import com.example.controlfree.todo.LedgerEntrySourceType
import com.example.controlfree.ui.todo.components.CardActionHintStore
import com.example.controlfree.ui.todo.components.CardActionHintTab
import com.example.controlfree.ui.todo.components.message
import com.example.controlfree.ui.todo.viewmodel.LedgerViewModel
import com.example.controlfree.ui.todo.search.SearchNavigationRequest
import com.example.controlfree.ui.todo.viewmodel.LedgerComposerState
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalAnimationApi::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun LedgerScreen(
    viewModel: LedgerViewModel,
    searchNavigationRequest: SearchNavigationRequest? = null,
    onSearchNavigationConsumed: (SearchNavigationRequest) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val cardHintStore = remember(context.applicationContext) {
        CardActionHintStore(context.applicationContext)
    }
    val composerState by viewModel.composerState.collectAsStateWithLifecycle()
    val selectedYearMonth by viewModel.selectedYearMonth.collectAsStateWithLifecycle()
    val monthEntries by viewModel.monthEntries.collectAsStateWithLifecycle()
    val areMonthEntriesLoaded by viewModel.areMonthEntriesLoaded.collectAsStateWithLifecycle()
    val daySummary by viewModel.daySummary.collectAsStateWithLifecycle()
    val weekSummary by viewModel.weekSummary.collectAsStateWithLifecycle()
    val monthSummary by viewModel.monthSummary.collectAsStateWithLifecycle()
    val yearSummary by viewModel.yearSummary.collectAsStateWithLifecycle()

    var editingEntryIndex by remember { mutableStateOf<Int?>(null) }
    var editingSavedEntry by remember { mutableStateOf<LedgerEntryEntity?>(null) }
    var savedEntryUpdateInFlight by remember { mutableStateOf(false) }

    LaunchedEffect(searchNavigationRequest?.revision, monthEntries) {
        val request = searchNavigationRequest ?: return@LaunchedEffect
        request.targetDate?.let(viewModel::selectMonth)
        val entry = monthEntries.firstOrNull { it.id == request.entityId } ?: return@LaunchedEffect
        editingSavedEntry = entry
        onSearchNavigationConsumed(request)
    }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect(snackbarHostState::showSnackbar)
    }

    LaunchedEffect(monthEntries.isNotEmpty(), cardHintStore) {
        if (cardHintStore.shouldShowWhenReady(CardActionHintTab.LEDGER, monthEntries.isNotEmpty())) {
            snackbarHostState.showSnackbar(
                message = CardActionHintTab.LEDGER.message(),
                withDismissAction = true,
                duration = SnackbarDuration.Long
            )
            cardHintStore.markShown(CardActionHintTab.LEDGER)
        }
    }

    val groupedEntries = remember(monthEntries) {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        monthEntries
            .groupBy { sdf.format(Date(it.occurredAtEpochMillis)) }
            .mapValues { (_, entries) -> ledgerDisplayGroups(entries) }
    }
    val deleteSavedEntryWithUndo: (LedgerEntryEntity) -> Unit = { entry ->
        if (!savedEntryUpdateInFlight) {
            savedEntryUpdateInFlight = true
            coroutineScope.launch {
                try {
                    if (viewModel.deleteEntry(entry.id)) {
                        editingSavedEntry = null
                        savedEntryUpdateInFlight = false
                        val result = snackbarHostState.showSnackbar(
                            message = "已删除「${entry.title}」",
                            actionLabel = "撤销",
                            withDismissAction = true,
                            duration = SnackbarDuration.Short
                        )
                        if (result == SnackbarResult.ActionPerformed) {
                            viewModel.restoreEntry(entry)
                        }
                    }
                } finally {
                    savedEntryUpdateInFlight = false
                }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(BrandColors.PageBackground)
            .imePadding()
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // A. 多维度汇总滑动面板
            item {
                LedgerSummaryPanel(
                    year = selectedYearMonth.first,
                    month = selectedYearMonth.second,
                    daySummary = daySummary,
                    weekSummary = weekSummary,
                    monthSummary = monthSummary,
                    yearSummary = yearSummary,
                    onPrevMonth = { viewModel.switchMonth(-1) },
                    onNextMonth = { viewModel.switchMonth(1) }
                )
            }

            // B. 闪念式记账编辑器
            item {
                LedgerComposer(
                    state = composerState,
                    onContentChanged = viewModel::updateInput,
                    onAiClassify = {
                        viewModel.classifyWithAi()
                        keyboardController?.hide()
                        focusManager.clearFocus()
                    },
                    onConfirmSave = {
                        val saveJob = viewModel.confirmAndSave()
                        if (saveJob != null) {
                            cardHintStore.armForCreation(
                                tab = CardActionHintTab.LEDGER,
                                isDataLoaded = areMonthEntriesLoaded,
                                wasEmpty = monthEntries.isEmpty(),
                                isNewItem = composerState.parsedResult
                                    ?.ledgerEntries
                                    ?.isNotEmpty() == true
                            )
                        }
                        keyboardController?.hide()
                        focusManager.clearFocus()
                    },
                    onEditEntry = { index -> editingEntryIndex = index },
                    onDeleteEntry = { index -> viewModel.removeLedgerEntry(index) }
                )
            }

            // C. 流水列表
            item {
                Text(
                    text = "当月账单流水",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = BrandColors.BackgroundTextPrimary,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }

            if (groupedEntries.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "本月暂无收支明细",
                            color = BrandColors.BackgroundTextTertiary
                        )
                    }
                }
            } else {
                groupedEntries.forEach { (dateStr, groups) ->
                    item {
                        Text(
                            text = dateStr,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = BrandColors.BackgroundTextSecondary,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }

                    itemsIndexed(
                        items = groups,
                        key = { _: Int, group: LedgerDisplayGroup ->
                            "$dateStr:${group.batchId ?: group.entries.first().id}"
                        }
                    ) { _: Int, group: LedgerDisplayGroup ->
                        if (group.entries.size == 1) {
                            val entry = group.entries.first()
                            LedgerTimelineItem(
                                entry = entry,
                                onClick = { editingSavedEntry = entry }
                            )
                        } else {
                            LedgerBatchCard(
                                entries = group.entries,
                                onEditEntry = { editingSavedEntry = it }
                            )
                        }
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }

    // 弹出微调编辑浮窗
    editingEntryIndex?.let { index ->
        val parsedResult = composerState.parsedResult
        if (parsedResult != null && index in parsedResult.ledgerEntries.indices) {
            val entry = parsedResult.ledgerEntries[index]
            LedgerAdjustmentDialog(
                entry = entry,
                onDismiss = { editingEntryIndex = null },
                onSave = { updated ->
                    viewModel.updateLedgerEntry(index, updated)
                    editingEntryIndex = null
                }
            )
        }
    }

    editingSavedEntry?.let { entry ->
        SavedLedgerAdjustmentDialog(
            entry = entry,
            isSaving = savedEntryUpdateInFlight,
            onDismiss = {
                if (!savedEntryUpdateInFlight) editingSavedEntry = null
            },
            onDeleteRequest = { deleteSavedEntryWithUndo(entry) },
            onSave = { updated ->
                if (!savedEntryUpdateInFlight) {
                    savedEntryUpdateInFlight = true
                    coroutineScope.launch {
                        try {
                            if (viewModel.updateSavedEntry(updated)) editingSavedEntry = null
                        } finally {
                            savedEntryUpdateInFlight = false
                        }
                    }
                }
            }
        )
    }
}

@Composable
fun LedgerSummaryPanel(
    year: Int,
    month: Int,
    daySummary: com.example.controlfree.todo.LedgerMonthSummary,
    weekSummary: com.example.controlfree.todo.LedgerMonthSummary,
    monthSummary: com.example.controlfree.todo.LedgerMonthSummary,
    yearSummary: com.example.controlfree.todo.LedgerMonthSummary,
    onPrevMonth: () -> Unit,
    onNextMonth: () -> Unit
) {
    val pagerState = rememberPagerState(
        initialPage = 2,
        pageCount = { 4 }
    )
    val coroutineScope = rememberCoroutineScope()
    
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = BrandColors.SurfaceCard),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 时间区间切换
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (pagerState.currentPage == 2) {
                    IconButton(onClick = onPrevMonth) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBackIos, contentDescription = "上月", tint = BrandColors.Primary, modifier = Modifier.size(16.dp))
                    }
                } else {
                    Spacer(modifier = Modifier.size(48.dp))
                }
                Text(
                    text = when (pagerState.currentPage) {
                        0 -> "本日财务汇总"
                        1 -> "本周财务汇总"
                        2 -> "${year}年${month}月汇总"
                        else -> "${year}年年度汇总"
                    },
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = BrandColors.TextPrimary,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                if (pagerState.currentPage == 2) {
                    IconButton(onClick = onNextMonth) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForwardIos, contentDescription = "下月", tint = BrandColors.Primary, modifier = Modifier.size(16.dp))
                    }
                } else {
                    Spacer(modifier = Modifier.size(48.dp))
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 模式指示条 (日、周、月、年)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf("本日", "本周", "本月", "本年").forEachIndexed { index, label ->
                    val isSelected = pagerState.currentPage == index
                    Text(
                        text = label,
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected) BrandColors.Primary else BrandColors.TextTertiary,
                        modifier = Modifier
                            .background(
                                if (isSelected) BrandColors.Primary.copy(alpha = 0.12f) else Color.Transparent,
                                RoundedCornerShape(6.dp)
                            )
                            .clickable {
                                coroutineScope.launch { pagerState.animateScrollToPage(index) }
                            }
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // HorizontalPager 滑动卡片
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxWidth()
            ) { page ->
                val currentSummary = when (page) {
                    0 -> daySummary
                    1 -> weekSummary
                    2 -> monthSummary
                    else -> yearSummary
                }
                val ringTrackColor = BrandColors.OutlineSoft.copy(alpha = 0.7f)
                
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 固定尺寸并以真实中心绘制，避免权重改变圆环的圆心。
                        Box(
                            modifier = Modifier.size(120.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Canvas(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .testTag("ledger-summary-ring")
                            ) {
                                val strokeWidth = 10.dp.toPx()
                                val diameter = (size.minDimension - strokeWidth).coerceAtLeast(0f)
                                val radius = diameter / 2f
                                val center = Offset(size.width / 2f, size.height / 2f)
                                val topLeft = Offset(center.x - radius, center.y - radius)
                                val arcSize = Size(diameter, diameter)

                                drawCircle(
                                    color = ringTrackColor,
                                    radius = radius,
                                    center = center,
                                    style = Stroke(width = strokeWidth, cap = StrokeCap.Butt)
                                )

                                if (currentSummary.totalExpense > 0L) {
                                    var startAngle = -90f
                                    currentSummary.categoryTotals
                                        .filter { it.total > 0L }
                                        .forEach { total ->
                                            val sweep = total.total.toDouble()
                                                .div(currentSummary.totalExpense.toDouble())
                                                .toFloat() * 360f
                                            drawArc(
                                                color = ledgerCategoryColor(LedgerCategory.fromString(total.category)),
                                                startAngle = startAngle,
                                                sweepAngle = sweep,
                                                useCenter = false,
                                                topLeft = topLeft,
                                                size = arcSize,
                                                style = Stroke(width = strokeWidth, cap = StrokeCap.Butt)
                                            )
                                            startAngle += sweep
                                        }
                                }
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                val expenseLabel = when (page) {
                                    0 -> "日支出"
                                    1 -> "周支出"
                                    2 -> "月支出"
                                    else -> "年支出"
                                }
                                val incomeLabel = when (page) {
                                    0 -> "日收入"
                                    1 -> "周收入"
                                    2 -> "月收入"
                                    else -> "年收入"
                                }
                                Text(
                                    text = expenseLabel,
                                    fontSize = 10.sp,
                                    color = BrandColors.TextSecondary
                                )
                                Text(
                                    text = formatLedgerCurrencyFen(currentSummary.totalExpense),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = BrandColors.Danger
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = incomeLabel,
                                    fontSize = 10.sp,
                                    color = BrandColors.TextSecondary
                                )
                                Text(
                                    text = formatLedgerCurrencyFen(currentSummary.totalIncome),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = BrandColors.Success
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        // 右侧数据汇总
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            SummaryRow(
                                label = when (page) {
                                    0 -> "日支出"
                                    1 -> "周支出"
                                    2 -> "月支出"
                                    else -> "年支出"
                                },
                                amountFen = currentSummary.totalExpense,
                                color = BrandColors.Danger
                            )
                            SummaryRow(
                                label = when (page) {
                                    0 -> "日收入"
                                    1 -> "周收入"
                                    2 -> "月收入"
                                    else -> "年收入"
                                },
                                amountFen = currentSummary.totalIncome,
                                color = BrandColors.Success
                            )
                            SummaryRow(
                                label = when (page) {
                                    0 -> "日结余"
                                    1 -> "周结余"
                                    2 -> "月结余"
                                    else -> "年结余"
                                },
                                amountFen = currentSummary.balance,
                                color = if (currentSummary.balance >= 0L) BrandColors.TextPrimary else BrandColors.Danger
                            )
                        }
                    }

                    val legendItems = currentSummary.categoryTotals
                        .filter { it.total > 0L }
                        .map { total ->
                            LedgerLegendItem(
                                category = LedgerCategory.fromString(total.category),
                                amountFen = total.total,
                                totalExpenseFen = currentSummary.totalExpense
                            )
                        }
                    if (legendItems.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            legendItems.forEach { item ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .background(ledgerCategoryColor(item.category), CircleShape)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "${item.category.emoji}${item.category.displayName} ${item.percentText}",
                                        fontSize = 11.sp,
                                        color = BrandColors.TextSecondary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class LedgerLegendItem(
    val category: LedgerCategory,
    val amountFen: Long,
    val totalExpenseFen: Long
) {
    val percentText: String
        get() {
            if (amountFen <= 0L || totalExpenseFen <= 0L) return "0%"
            val percent = BigDecimal.valueOf(amountFen)
                .multiply(BigDecimal.valueOf(100L))
                .divide(BigDecimal.valueOf(totalExpenseFen), 1, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString()
            return "$percent%"
        }
}

private fun ledgerCategoryColor(category: LedgerCategory): Color = when (category) {
    LedgerCategory.FOOD -> Color(0xFFEF6C5B)
    LedgerCategory.TRANSPORT -> Color(0xFF3D8ED0)
    LedgerCategory.SHOPPING -> Color(0xFFC85F93)
    LedgerCategory.ENTERTAINMENT -> Color(0xFF7A63C5)
    LedgerCategory.HOUSING -> Color(0xFF2F9A73)
    LedgerCategory.MEDICAL -> Color(0xFFD85F65)
    LedgerCategory.EDUCATION -> Color(0xFF4776B7)
    LedgerCategory.SOCIAL -> Color(0xFFE29245)
    LedgerCategory.EXPENSE_OTHER -> Color(0xFF77867F)
    LedgerCategory.SALARY -> Color(0xFF2F9A73)
    LedgerCategory.PART_TIME -> Color(0xFF3D8ED0)
    LedgerCategory.INVESTMENT -> Color(0xFF7A63C5)
    LedgerCategory.RED_PACKET -> Color(0xFFD85F65)
    LedgerCategory.INCOME_OTHER -> Color(0xFF77867F)
}

private fun ledgerCategoryFor(directionValue: String, categoryValue: String): LedgerCategory {
    val direction = LedgerDirection.fromStoredValue(directionValue) ?: LedgerDirection.EXPENSE
    val parsed = LedgerCategory.fromStoredValue(categoryValue)
    return parsed?.takeIf { it.direction == direction }
        ?: if (direction == LedgerDirection.INCOME) {
            LedgerCategory.INCOME_OTHER
        } else {
            LedgerCategory.EXPENSE_OTHER
        }
}

private fun formatLedgerCurrencyFen(amountFen: Long): String {
    val amount = BigDecimal.valueOf(amountFen, 2).setScale(2, RoundingMode.UNNECESSARY)
    val absoluteText = amount.abs().toPlainString()
    return if (amount.signum() < 0) "-¥$absoluteText" else "¥$absoluteText"
}

private fun signedLedgerCurrency(amountFen: Long, isExpense: Boolean): String {
    val sign = if (isExpense) "-" else "+"
    return "$sign ${formatLedgerCurrencyFen(amountFen)}"
}


@Composable
fun SummaryRow(label: String, amountFen: Long, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 13.sp, color = BrandColors.TextSecondary)
        Text(
            text = formatLedgerCurrencyFen(amountFen),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = color
        )
    }
}

@Composable
fun LedgerComposer(
    state: LedgerComposerState,
    onContentChanged: (String) -> Unit,
    onAiClassify: () -> Unit,
    onConfirmSave: () -> Unit,
    onEditEntry: (Int) -> Unit,
    onDeleteEntry: (Int) -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    val result = state.parsedResult
    val hasParsedContent = result != null &&
        (result.ledgerEntries.isNotEmpty() || result.todoItems.isNotEmpty())

    LaunchedEffect(isFocused, imeBottom) {
        if (isFocused) bringIntoViewRequester.bringIntoView()
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = BrandColors.SurfaceCard),
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bringIntoViewRequester)
    ) {
        Column(modifier = Modifier.padding(
            horizontal = 14.dp,
            vertical = if (isFocused) 12.dp else 10.dp
        )) {

            BasicTextField(
                value = state.content,
                onValueChange = onContentChanged,
                enabled = !state.isParsing && !state.isSaving,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (isFocused) Modifier.height(72.dp)
                        else Modifier
                    )
                    .onFocusChanged { isFocused = it.isFocused },
                singleLine = !isFocused,
                keyboardOptions = KeyboardOptions.Default,
                textStyle = TextStyle(fontSize = 14.sp, color = BrandColors.TextPrimary),
                decorationBox = { innerTextField ->
                    Box {
                        if (state.content.isEmpty()) {
                            Text(
                                text = "例如：中午吃拉面花了18元",
                                fontSize = 13.sp,
                                color = BrandColors.TextTertiary
                            )
                        }
                        innerTextField()
                    }
                }
            )

            Spacer(Modifier.height(if (isFocused) 8.dp else 4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onAiClassify,
                    enabled = state.content.isNotBlank() && !state.isParsing && !state.isSaving,
                    colors = ButtonDefaults.buttonColors(containerColor = BrandColors.Primary),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(30.dp)
                ) {
                    if (state.isParsing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            color = Color.White,
                            strokeWidth = 1.5.dp
                        )
                    } else {
                        Text("AI 记账", fontSize = 12.sp)
                    }
                }
            }

            if (state.error != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = state.error,
                    color = BrandColors.Danger,
                    fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // AI 整理结果展示（账目聚合为一张预览卡，避免多条结果造成视觉跳动）。
            if (hasParsedContent) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = BrandColors.OutlineSoft)

                    result.warnings.take(3).forEach { warning ->
                        Text(
                            text = "提示：$warning",
                            fontSize = 11.sp,
                            color = BrandColors.Warning,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }

                    // 理财教练小贴士
                    result.tip?.let { coachTip ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(BrandColors.PrimaryContainer.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                                .padding(10.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.SupportAgent, contentDescription = null, tint = BrandColors.Primary, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = coachTip,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = BrandColors.TextPrimary
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                    }

                    // 1. 待确认财务卡片
                    if (result.ledgerEntries.isNotEmpty()) {
                        ParsedLedgerPreview(
                            entries = result.ledgerEntries,
                            enabled = !state.isSaving,
                            onEditEntry = onEditEntry,
                            onDeleteEntry = onDeleteEntry
                        )
                    }

                    // 2. 三位一体路由解析出的待办事项卡片
                    if (result.todoItems.isNotEmpty()) {
                        Text("分流生成的待办事项:", fontSize = 12.sp, color = BrandColors.TextSecondary)
                        Spacer(modifier = Modifier.height(6.dp))
                        result.todoItems.forEach { todoItem ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(BrandColors.SurfaceCard, RoundedCornerShape(10.dp))
                                    .padding(10.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.PlaylistAddCheck,
                                        contentDescription = null,
                                        tint = BrandColors.Primary
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(todoItem.content, fontSize = 13.sp, color = BrandColors.TextPrimary)
                                        if (todoItem.dueDateEpochMillis != null) {
                                            val sdf = SimpleDateFormat("M月d日 H:mm", Locale.getDefault())
                                            Text(
                                                "截止时间：${sdf.format(Date(todoItem.dueDateEpochMillis))}",
                                                fontSize = 11.sp,
                                                color = BrandColors.TextSecondary
                                            )
                                        }
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // 批量保存确认入账
                    Button(
                        onClick = onConfirmSave,
                        enabled = !state.isSaving && hasParsedContent,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("ledger-confirm-button"),
                        colors = ButtonDefaults.buttonColors(containerColor = BrandColors.Success),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        if (state.isSaving) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White)
                        } else {
                            Text("确认", fontWeight = FontWeight.Bold)
                        }
                    }
            }
        }
    }
}

@Composable
internal fun ParsedLedgerPreview(
    entries: List<ParsedLedgerEntry>,
    enabled: Boolean = true,
    onEditEntry: (Int) -> Unit,
    onDeleteEntry: (Int) -> Unit
) {
    val totalAmountFen = entries.sumOf {
        if (it.direction == com.example.controlfree.todo.LedgerDirection.EXPENSE) -it.amountFen else it.amountFen
    }
    val totalText = signedLedgerCurrency(
        amountFen = kotlin.math.abs(totalAmountFen),
        isExpense = totalAmountFen < 0
    )
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = BrandColors.SurfaceCard),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("ledger-preview-card")
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "待确认账目 · ${entries.size}笔",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = BrandColors.TextSecondary
                )
                Text(
                    text = "合计：$totalText",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (totalAmountFen < 0) BrandColors.Danger else BrandColors.Success
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            entries.forEachIndexed { index, entry ->
                ParsedLedgerRow(
                    entry = entry,
                    enabled = enabled,
                    onClick = { onEditEntry(index) },
                    onDelete = { onDeleteEntry(index) }
                )
                if (index < entries.lastIndex) {
                    HorizontalDivider(color = BrandColors.OutlineSoft)
                }
            }
        }
    }
}

@Composable
private fun ParsedLedgerRow(
    entry: ParsedLedgerEntry,
    enabled: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .background(BrandColors.PrimaryContainer.copy(alpha = 0.3f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(entry.category.emoji, fontSize = 17.sp)
        }

        Spacer(modifier = Modifier.width(9.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = BrandColors.TextPrimary,
                    modifier = Modifier.weight(1f, fill = false),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (entry.isEstimated) {
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = "估算",
                        fontSize = 10.sp,
                        color = BrandColors.Warning,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Text(
                text = buildString {
                    append(entry.category.displayName)
                    append(" · ").append(entry.emotion)
                },
                fontSize = 11.sp,
                color = BrandColors.TextSecondary
            )
            entry.note?.takeIf(String::isNotBlank)?.let { note ->
                Text(
                    text = note,
                    fontSize = 11.sp,
                    color = BrandColors.TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Text(
            text = signedLedgerCurrency(entry.amountFen, entry.direction == LedgerDirection.EXPENSE),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = if (entry.direction == LedgerDirection.EXPENSE) BrandColors.Danger else BrandColors.Success
        )

        IconButton(onClick = onDelete, enabled = enabled, modifier = Modifier.size(30.dp)) {
            Icon(
                Icons.Default.Close,
                contentDescription = "删除账目",
                tint = BrandColors.TextTertiary,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/** 保留单条卡片 API，供旧调用方兼容；新编辑器使用 [ParsedLedgerPreview] 聚合展示。 */
@Composable
fun ParsedLedgerCard(
    entry: ParsedLedgerEntry,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    ParsedLedgerPreview(
        entries = listOf(entry),
        enabled = true,
        onEditEntry = { onClick() },
        onDeleteEntry = { onDelete() }
    )
}

/** 同一日内按 AI 提交批次聚合的展示分组；手动或无批次条目各自成组。 */
internal data class LedgerDisplayGroup(
    val batchId: String?,
    val entries: List<LedgerEntryEntity>
)

/** 保持原有排序，把相同 source_batch_id 的条目合并到首次出现的位置。 */
internal fun ledgerDisplayGroups(entries: List<LedgerEntryEntity>): List<LedgerDisplayGroup> {
    val groups = mutableListOf<LedgerDisplayGroup>()
    val batchIndexById = HashMap<String, Int>()
    entries.forEach { entry ->
        val batchId = entry.sourceBatchId
        if (batchId == null) {
            groups += LedgerDisplayGroup(batchId = null, entries = listOf(entry))
        } else {
            val existingIndex = batchIndexById[batchId]
            if (existingIndex == null) {
                batchIndexById[batchId] = groups.size
                groups += LedgerDisplayGroup(batchId = batchId, entries = listOf(entry))
            } else {
                val existing = groups[existingIndex]
                groups[existingIndex] = existing.copy(entries = existing.entries + entry)
            }
        }
    }
    return groups
}

/** 同批次多笔账目的聚合卡片：右上角展示批次合计，点击具体行编辑单笔。 */
@Composable
fun LedgerBatchCard(
    entries: List<LedgerEntryEntity>,
    onEditEntry: (LedgerEntryEntity) -> Unit
) {
    val netAmountFen = entries.sumOf { entry ->
        if (entry.direction == LedgerDirection.EXPENSE.storedValue) -entry.amount else entry.amount
    }
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = BrandColors.SurfaceCard),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Assistant,
                        contentDescription = "AI 整理批次",
                        tint = BrandColors.Primary,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = "同批记账 · ${entries.size}笔",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = BrandColors.TextSecondary
                    )
                }
                Text(
                    text = "合计：${signedLedgerCurrency(
                        amountFen = kotlin.math.abs(netAmountFen),
                        isExpense = netAmountFen < 0
                    )}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (netAmountFen < 0) BrandColors.Danger else BrandColors.Success
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            entries.forEachIndexed { index, entry ->
                LedgerBatchEntryRow(
                    entry = entry,
                    onClick = { onEditEntry(entry) }
                )
                if (index < entries.lastIndex) {
                    HorizontalDivider(color = BrandColors.OutlineSoft)
                }
            }
        }
    }
}

@Composable
private fun LedgerBatchEntryRow(
    entry: LedgerEntryEntity,
    onClick: () -> Unit
) {
    val cat = ledgerCategoryFor(entry.direction, entry.category)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BrandColors.SurfaceCard)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(BrandColors.Canvas, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(cat.emoji, fontSize = 16.sp)
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.title,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = BrandColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val sdf = SimpleDateFormat("H:mm", Locale.getDefault())
            Text(
                text = "${cat.displayName} · ${sdf.format(Date(entry.occurredAtEpochMillis))}",
                fontSize = 11.sp,
                color = BrandColors.TextSecondary
            )
        }
        Text(
            text = signedLedgerCurrency(
                amountFen = entry.amount,
                isExpense = entry.direction == LedgerDirection.EXPENSE.storedValue
            ),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = if (entry.direction == LedgerDirection.EXPENSE.storedValue) {
                BrandColors.Danger
            } else {
                BrandColors.Success
            }
        )
    }
}

@Composable
fun LedgerTimelineItem(
    entry: LedgerEntryEntity,
    onClick: () -> Unit = {}
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = BrandColors.SurfaceCard),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val cat = ledgerCategoryFor(entry.direction, entry.category)
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(BrandColors.Canvas, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(cat.emoji, fontSize = 20.sp)
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = entry.title,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = BrandColors.TextPrimary,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                val sdf = SimpleDateFormat("H:mm", Locale.getDefault())
                Text(
                    text = "${cat.displayName} · ${sdf.format(Date(entry.occurredAtEpochMillis))}",
                    fontSize = 12.sp,
                    color = BrandColors.TextSecondary
                )
                entry.note?.takeIf(String::isNotBlank)?.let { note ->
                    Text(
                        text = note,
                        fontSize = 11.sp,
                        color = BrandColors.TextTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Text(
                text = signedLedgerCurrency(
                    amountFen = entry.amount,
                    isExpense = entry.direction == LedgerDirection.EXPENSE.storedValue
                ),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = if (entry.direction == LedgerDirection.EXPENSE.storedValue) BrandColors.Danger else BrandColors.Success
            )
        }
    }
}

@Composable
fun LedgerAdjustmentDialog(
    entry: ParsedLedgerEntry,
    onDismiss: () -> Unit,
    onSave: (ParsedLedgerEntry) -> Unit
) {
    LedgerAdjustmentForm(
        title = "微调交易数据",
        initialTitle = entry.title,
        initialAmountFen = entry.amountFen,
        direction = entry.direction,
        initialCategory = entry.category,
        onDismiss = onDismiss,
        onSave = { title, amountFen, category ->
            onSave(
                entry.copy(
                    title = title,
                    amountFen = amountFen,
                    isEstimated = false,
                    category = category
                )
            )
        }
    )
}

@Composable
fun SavedLedgerAdjustmentDialog(
    entry: LedgerEntryEntity,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onDeleteRequest: () -> Unit,
    onSave: (LedgerEntryEntity) -> Unit
) {
    val direction = LedgerDirection.fromStoredValue(entry.direction) ?: LedgerDirection.EXPENSE
    LedgerAdjustmentForm(
        title = "编辑账目",
        initialTitle = entry.title,
        initialAmountFen = entry.amount,
        direction = direction,
        initialCategory = ledgerCategoryFor(entry.direction, entry.category),
        isSaving = isSaving,
        onDismiss = onDismiss,
        onDeleteRequest = onDeleteRequest,
        onSave = { title, amountFen, category ->
            onSave(
                entry.copy(
                    title = title,
                    amount = amountFen,
                    direction = direction.storedValue,
                    category = category.storedValue,
                    isEstimated = false,
                    emotion = entry.emotion?.takeIf(validLedgerEmotions::contains),
                    necessity = entry.necessity?.takeIf(validLedgerNecessities::contains),
                    updatedAtEpochMillis = System.currentTimeMillis()
                )
            )
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LedgerAdjustmentForm(
    title: String,
    initialTitle: String,
    initialAmountFen: Long,
    direction: LedgerDirection,
    initialCategory: LedgerCategory,
    isSaving: Boolean = false,
    onDismiss: () -> Unit,
    onDeleteRequest: (() -> Unit)? = null,
    onSave: (title: String, amountFen: Long, category: LedgerCategory) -> Unit
) {
    var amountText by remember(initialAmountFen) {
        mutableStateOf(LedgerAmountCodec.formatFen(initialAmountFen))
    }
    var titleText by remember(initialTitle) { mutableStateOf(initialTitle.take(20)) }
    var selectedCategory by remember(initialCategory) { mutableStateOf(initialCategory) }
    val parsedAmountFen = remember(amountText) { LedgerAmountCodec.parseFen(amountText) }
    val amountError = amountText.isNotBlank() && parsedAmountFen == null
    val canSave = titleText.isNotBlank() && parsedAmountFen != null
    var showDeleteConfirmation by remember(initialTitle) { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = { if (!isSaving) onDismiss() },
        modifier = Modifier.fillMaxWidth(),
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
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    title,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = BrandColors.TextPrimary
                )
                if (onDeleteRequest != null) {
                    IconButton(
                        onClick = { showDeleteConfirmation = true },
                        enabled = !isSaving
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "删除账目",
                            tint = BrandColors.Danger
                        )
                    }
                }
            }

            OutlinedTextField(
                value = titleText,
                onValueChange = { titleText = it.take(20) },
                label = { Text("摘要", fontSize = 12.sp) },
                singleLine = true,
                enabled = !isSaving,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = amountText,
                onValueChange = { amountText = it },
                label = { Text("金额（元）", fontSize = 12.sp) },
                singleLine = true,
                enabled = !isSaving,
                isError = amountError,
                supportingText = if (amountError) {
                    { Text("请输入大于 0 且最多两位小数的金额") }
                } else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )

            Text("选择分类", fontSize = 12.sp, color = BrandColors.TextSecondary)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LedgerCategory.entries
                    .filter { it.direction == direction }
                    .forEach { category ->
                        val isSelected = category == selectedCategory
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (isSelected) BrandColors.PrimaryContainer
                                    else BrandColors.Canvas
                                )
                                .clickable(enabled = !isSaving) { selectedCategory = category }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "${category.emoji} ${category.displayName}",
                                fontSize = 12.sp,
                                color = BrandColors.TextPrimary
                            )
                        }
                    }
            }

            Spacer(Modifier.height(4.dp))
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
                    onClick = {
                        parsedAmountFen?.let { amount ->
                            onSave(titleText.trim(), amount, selectedCategory)
                        }
                    },
                    enabled = canSave && !isSaving,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(11.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BrandColors.Primary)
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = BrandColors.OnPrimary
                        )
                    } else {
                        Text("保存")
                    }
                }
            }
        }
    }

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { if (!isSaving) showDeleteConfirmation = false },
            containerColor = BrandColors.OverlaySurface,
            title = { Text("删除账目？") },
            text = { Text("“$initialTitle”将从账本中删除。") },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirmation = false
                        onDeleteRequest?.invoke()
                    },
                    enabled = !isSaving,
                    colors = ButtonDefaults.buttonColors(containerColor = BrandColors.Danger)
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteConfirmation = false },
                    enabled = !isSaving
                ) {
                    Text("取消")
                }
            }
        )
    }
}

private val validLedgerEmotions = setOf("冲动", "解压", "刚需", "社交", "自我投资")
private val validLedgerNecessities = setOf("need", "want")
