package com.example.controlfree.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.controlfree.theme.BrandColors

/**
 * 直接绘制在自定义背景上的监督分区标题。
 *
 * 标题、说明读取背景感知色。数量标签统一使用系统的 AssistChip 样式。
 */
@Composable
internal fun SupervisionSectionHeader(
    title: String,
    description: String,
    count: Int
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = BrandColors.BackgroundTextPrimary,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = description,
                color = BrandColors.BackgroundTextSecondary,
                fontSize = 13.sp
            )
        }
        AssistChip(
            onClick = {},
            enabled = false,
            label = { Text("$count 个") }
        )
    }
}
