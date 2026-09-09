package com.example.controlfree.ui.statistics

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.controlfree.theme.BrandColors
import com.example.controlfree.ui.history.HistoryStatisticsContent
import com.example.controlfree.ui.todo.calendar.UnifiedCalendarScreen
import com.example.controlfree.ui.todo.viewmodel.UnifiedCalendarViewModel
import com.example.controlfree.ui.usage.UsageStatisticsContent

internal enum class StatisticsPage(val displayName: String) {
    APP_USAGE("使用统计"),
    SUPERVISION_HISTORY("监督统计"),
    CALENDAR("日程统计")
}

@Composable
fun StatisticsContent(
    modifier: Modifier = Modifier,
    calendarViewModel: UnifiedCalendarViewModel = viewModel()
) {
    var selectedPage by rememberSaveable { mutableStateOf(StatisticsPage.entries.first()) }
    Column(modifier = modifier.fillMaxSize().background(BrandColors.PageBackground)) {
        TabRow(
            selectedTabIndex = selectedPage.ordinal,
            containerColor = BrandColors.BackgroundChrome,
            contentColor = BrandColors.BackgroundAccent,
            indicator = {},
            divider = {}
        ) {
            StatisticsPage.entries.forEach { page ->
                val isSelected = selectedPage == page
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember {
                                androidx.compose.foundation.interaction.MutableInteractionSource()
                            },
                            indication = null,
                            onClick = { selectedPage = page }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    val isImageBg = BrandColors.BackgroundType == "image"
                    val capsuleColor = if (isImageBg) {
                        if (BrandColors.UsesDarkForeground) {
                            if (isSelected) Color.White.copy(alpha = 0.82f) else Color.White.copy(alpha = 0.48f)
                        } else {
                            if (isSelected) Color.Black.copy(alpha = 0.72f) else Color.Black.copy(alpha = 0.38f)
                        }
                    } else {
                        if (isSelected) {
                            BrandColors.Primary.copy(alpha = 0.12f)
                        } else {
                            Color.Transparent
                        }
                    }
                    Box(
                        modifier = Modifier
                            .background(
                                color = capsuleColor,
                                shape = RoundedCornerShape(12.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = page.displayName,
                            fontSize = if (isSelected) 15.sp else 13.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                            softWrap = false,
                            color = if (isSelected) {
                                BrandColors.BackgroundAccent
                            } else {
                                BrandColors.BackgroundTextSecondary
                            }
                        )
                    }
                }
            }
        }
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            when (selectedPage) {
                StatisticsPage.CALENDAR -> UnifiedCalendarScreen(calendarViewModel)
                StatisticsPage.APP_USAGE -> UsageStatisticsContent()
                StatisticsPage.SUPERVISION_HISTORY -> HistoryStatisticsContent()
            }
        }
    }
}
