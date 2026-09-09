package com.example.controlfree.ui.main

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

internal enum class MainNavigationGlyph {
    SHIELD,
    TIMER,
    CHECKLIST,
    CHART,
    PERSON
}

internal val MainTab.navigationGlyph: MainNavigationGlyph
    get() = when (this) {
        MainTab.MONITOR -> MainNavigationGlyph.SHIELD
        MainTab.FOCUS -> MainNavigationGlyph.TIMER
        MainTab.TODO -> MainNavigationGlyph.CHECKLIST
        MainTab.STATISTICS -> MainNavigationGlyph.CHART
        MainTab.SETTINGS -> MainNavigationGlyph.PERSON
    }

/** 
 * 五个主导航入口统一使用高级、纤细的线条设计（线宽 1.3dp）。
 * 图标尺寸从 22dp 放大到 26dp，带来更高端通透的视觉体验。
 */
@Composable
internal fun MainNavigationIcon(
    tab: MainTab,
    modifier: Modifier = Modifier
) {
    val color = LocalContentColor.current
    Canvas(modifier = modifier.size(26.dp)) {
        val strokeWidth = 1.3.dp.toPx()
        val stroke = Stroke(
            width = strokeWidth,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )
        when (tab.navigationGlyph) {
            MainNavigationGlyph.SHIELD -> drawShieldGlyph(color, stroke, strokeWidth)
            MainNavigationGlyph.TIMER -> drawTimerGlyph(color, stroke, strokeWidth)
            MainNavigationGlyph.CHECKLIST -> drawChecklistGlyph(color, stroke, strokeWidth)
            MainNavigationGlyph.CHART -> drawChartGlyph(color, stroke, strokeWidth)
            MainNavigationGlyph.PERSON -> drawPersonGlyph(color, stroke, strokeWidth)
        }
    }
}

/** 监督 Tab：盾牌内加对号，保持高级纤细风格 */
private fun DrawScope.drawShieldGlyph(
    color: androidx.compose.ui.graphics.Color,
    stroke: Stroke,
    strokeWidth: Float
) {
    val shield = Path().apply {
        moveTo(x(12f), y(2.4f))
        lineTo(x(19f), y(5.1f))
        lineTo(x(19f), y(10.5f))
        cubicTo(x(19f), y(15.1f), x(16.4f), y(18.6f), x(12f), y(21f))
        cubicTo(x(7.6f), y(18.6f), x(5f), y(15.1f), x(5f), y(10.5f))
        lineTo(x(5f), y(5.1f))
        close()
    }
    drawPath(shield, color = color, style = stroke)
    
    // 对号
    drawLine(
        color = color,
        start = Offset(x(8.7f), y(11.6f)),
        end = Offset(x(11.1f), y(14f)),
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round
    )
    drawLine(
        color = color,
        start = Offset(x(11.1f), y(14f)),
        end = Offset(x(15.8f), y(9.3f)),
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round
    )
}

/** 专注 Tab：极简纤细秒表 */
private fun DrawScope.drawTimerGlyph(
    color: androidx.compose.ui.graphics.Color,
    stroke: Stroke,
    strokeWidth: Float
) {
    val center = Offset(x(12f), y(13.2f))
    val radius = x(7.6f)
    // 计时表盘
    drawCircle(
        color = color,
        radius = radius,
        center = center,
        style = stroke
    )
    // 表冠顶部横杠
    drawLine(
        color = color,
        start = Offset(x(9.5f), y(3f)),
        end = Offset(x(14.5f), y(3f)),
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round
    )
    // 连接表冠
    drawLine(
        color = color,
        start = Offset(x(12f), y(3f)),
        end = Offset(x(12f), y(5.6f)),
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round
    )
    // 垂直分针
    drawLine(
        color = color,
        start = Offset(x(12f), y(13.2f)),
        end = Offset(x(12f), y(9.2f)),
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round
    )
    // 倾斜时针
    drawLine(
        color = color,
        start = Offset(x(12f), y(13.2f)),
        end = Offset(x(15.2f), y(15.2f)),
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round
    )
}

/** 清单 Tab：参考图1“工作台”4个带圆角的矩形块排列成的田字格 */
private fun DrawScope.drawChecklistGlyph(
    color: androidx.compose.ui.graphics.Color,
    stroke: Stroke,
    strokeWidth: Float
) {
    drawRoundRect(
        color = color,
        topLeft = Offset(x(4f), y(2.8f)),
        size = Size(x(16f), y(18.4f)),
        cornerRadius = CornerRadius(x(2.4f), y(2.4f)),
        style = stroke
    )
    listOf(7.3f, 12f, 16.7f).forEach { rowY ->
        drawLine(
            color = color,
            start = Offset(x(7f), y(rowY)),
            end = Offset(x(8.2f), y(rowY + 1.2f)),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )
        drawLine(
            color = color,
            start = Offset(x(8.2f), y(rowY + 1.2f)),
            end = Offset(x(10.2f), y(rowY - 1.1f)),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )
        drawLine(
            color = color,
            start = Offset(x(12.4f), y(rowY)),
            end = Offset(x(17f), y(rowY)),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )
    }
}

/** 统计 Tab：去除外框，只保留三根长短错落、极富现代感的纤细数据对比柱 */
private fun DrawScope.drawChartGlyph(
    color: androidx.compose.ui.graphics.Color,
    stroke: Stroke,
    strokeWidth: Float
) {
    // 绘制无边框的三根对比圆角线段数据柱，使其在 Canvas 中水平居中且高低完美搭配
    listOf(
        Pair(6.5f, 12f),
        Pair(12f, 5f),
        Pair(17.5f, 9.5f)
    ).forEach { (barX, topY) ->
        drawLine(
            color = color,
            start = Offset(x(barX), y(19f)),
            end = Offset(x(barX), y(topY)),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )
    }
}

/** 我的 Tab：参考图1“我的”纤细人像（圆头加无底边开放式圆弧肩膀） */
private fun DrawScope.drawPersonGlyph(
    color: androidx.compose.ui.graphics.Color,
    stroke: Stroke,
    strokeWidth: Float
) {
    // 头部圆环
    drawCircle(
        color = color,
        radius = x(4.2f),
        center = Offset(x(12f), y(7.5f)),
        style = stroke
    )
    // 开放式圆弧肩膀
    val shoulders = Path().apply {
        moveTo(x(4.5f), y(20.5f))
        cubicTo(
            x(6.5f), y(14.5f),
            x(17.5f), y(14.5f),
            x(19.5f), y(20.5f)
        )
    }
    drawPath(shoulders, color = color, style = stroke)
}

private fun DrawScope.x(value: Float): Float = size.width * value / 24f

private fun DrawScope.y(value: Float): Float = size.height * value / 24f
