package com.example.controlfree.ui.todo.search

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.StickyNote2
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.controlfree.theme.BrandColors
import androidx.compose.foundation.clickable
import kotlinx.coroutines.delay

@Composable
fun SuperSearchScreen(
    viewModel: SuperSearchViewModel,
    onBack: () -> Unit,
    onResultClick: (SuperSearchResult) -> Unit,
    modifier: Modifier = Modifier
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val searchState by viewModel.searchState.collectAsStateWithLifecycle()
    val results = searchState.results
    val focusRequester = androidx.compose.runtime.remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val hasQuery = query.isNotBlank()
    val isSearching = hasQuery && (
        searchState.query != query || searchState.filter != filter
    )

    LaunchedEffect(Unit) {
        delay(120L)
        focusRequester.requestFocus()
        keyboard?.show()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Transparent)
            .clickable(
                interactionSource = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = onBack
            )
            .safeDrawingPadding()
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 720.dp)
                .heightIn(max = 720.dp)
                .animateContentSize()
                .clickable(
                    interactionSource = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
                .then(
                    if (hasQuery) Modifier.fillMaxSize() else Modifier.fillMaxWidth()
                ),
            color = BrandColors.OverlaySurface,
            shape = RoundedCornerShape(20.dp),
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = if (hasQuery) Modifier.fillMaxSize() else Modifier.fillMaxWidth()
            ) {
                SearchInputBar(
                    query = query,
                    onQueryChange = viewModel::updateQuery,
                    onBack = onBack,
                    onClear = { viewModel.updateQuery("") },
                    onSearch = { keyboard?.hide() },
                    focusRequester = focusRequester,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                )

                if (hasQuery) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SuperSearchFilter.entries.forEach { item ->
                            FilterChip(
                                selected = filter == item,
                                onClick = { viewModel.selectFilter(item) },
                                label = { Text(item.displayName) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = BrandColors.Primary.copy(alpha = 0.18f),
                                    selectedLabelColor = BrandColors.TextPrimary
                                )
                            )
                        }
                    }

                    when {
                        isSearching -> SearchLoadingState()
                        results.isEmpty() -> SearchEmptyState("暂无结果")
                        else -> {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(results, key = SuperSearchResult::stableKey) { result ->
                                    SearchResultCard(result, query, onResultClick)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchInputBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onBack: () -> Unit,
    onClear: () -> Unit,
    onSearch: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = Color.Transparent,
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f).focusRequester(focusRequester),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = BrandColors.TextPrimary),
                cursorBrush = SolidColor(BrandColors.Primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text(
                                text = "搜索全部内容",
                                style = MaterialTheme.typography.bodyLarge,
                                color = BrandColors.TextTertiary
                            )
                        }
                        innerTextField()
                    }
                }
            )
            if (query.isNotEmpty()) {
                IconButton(onClick = onClear) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "清空",
                        tint = BrandColors.TextSecondary
                    )
                }
            } else {
                Spacer(Modifier.width(8.dp))
            }
        }
    }
}

@Composable
private fun SearchLoadingState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun SearchEmptyState(message: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.Search,
            contentDescription = null,
            tint = BrandColors.TextTertiary,
            modifier = Modifier.size(38.dp)
        )
        Spacer(Modifier.height(12.dp))
        Text(message, color = BrandColors.TextSecondary, fontSize = 14.sp)
    }
}

@Composable
private fun SearchResultCard(
    result: SuperSearchResult,
    query: String,
    onClick: (SuperSearchResult) -> Unit
) {
    Surface(
        onClick = { onClick(result) },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = BrandColors.SurfaceCard,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                result.source.icon(),
                contentDescription = null,
                tint = BrandColors.Primary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        highlighted(result.title, query),
                        modifier = Modifier.weight(1f),
                        color = BrandColors.TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(result.source.displayName, color = BrandColors.Primary, fontSize = 11.sp)
                }
                result.snippet?.takeIf { it.isNotBlank() && it != result.title }?.let { snippet ->
                    Spacer(Modifier.height(5.dp))
                    Text(
                        highlighted(snippet, query),
                        color = BrandColors.TextSecondary,
                        fontSize = 13.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(result.metadata, color = BrandColors.TextTertiary, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun highlighted(text: String, rawQuery: String) = buildAnnotatedString {
    val query = rawQuery.trim()
    if (query.isEmpty()) {
        append(text)
        return@buildAnnotatedString
    }
    var start = 0
    while (start < text.length) {
        val index = text.indexOf(query, startIndex = start, ignoreCase = true)
        if (index < 0) {
            append(text.substring(start))
            break
        }
        append(text.substring(start, index))
        withStyle(SpanStyle(color = BrandColors.Primary, fontWeight = FontWeight.Bold)) {
            append(text.substring(index, index + query.length))
        }
        start = index + query.length
    }
}

private fun SuperSearchSource.icon(): ImageVector = when (this) {
    SuperSearchSource.TODO -> Icons.Default.CheckCircle
    SuperSearchSource.HABIT -> Icons.Default.TrackChanges
    SuperSearchSource.QUICK_NOTE -> Icons.AutoMirrored.Filled.StickyNote2
    SuperSearchSource.LEDGER -> Icons.Default.Payments
    SuperSearchSource.CALENDAR -> Icons.Default.CalendarMonth
    SuperSearchSource.ANNIVERSARY -> Icons.Default.Event
}
