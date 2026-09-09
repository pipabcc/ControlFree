package com.example.controlfree.ui.todo.timeblock

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.example.controlfree.theme.BrandColors
import com.example.controlfree.theme.LocalBrandPalette
import com.example.controlfree.theme.DarkBrandPalette
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale

internal object TimeBlockColors {
    val Ink: Color @Composable @ReadOnlyComposable get() = BrandColors.TextPrimary
    val InkSecondary: Color @Composable @ReadOnlyComposable get() = BrandColors.TextSecondary
    val InkTertiary: Color @Composable @ReadOnlyComposable get() = BrandColors.TextTertiary
    val Line: Color @Composable @ReadOnlyComposable get() = BrandColors.OutlineSoft
    val AppBackground: Color @Composable @ReadOnlyComposable get() = if (LocalBrandPalette.current == DarkBrandPalette) Color(0xFF0E1310) else Color(0xFFF2F4F8)
    val AppBackgroundTop: Color @Composable @ReadOnlyComposable get() = if (LocalBrandPalette.current == DarkBrandPalette) Color(0xFF18221D) else Color(0xFFF7F9FC)
    val Surface = Color.White
    val Segment = Color(0xFFE5E9F0)
    val Work = Color(0xFF2F6BFF)
    val Life = Color(0xFF0FA06B)
    val Study = Color(0xFF8A4FD8)
    val Current = Color(0xFFFF3B30)
    val High = Color(0xFFE5484D)
    val Amber = Color(0xFFF5A524)
    val Focus = Color(0xFF22C55E)
    val Scrim = Color(0x990D1219)
}

internal fun projectColor(project: String): Color = when (project.trim()) {
    TIME_BLOCK_PROJECT_WORK -> TimeBlockColors.Work
    TIME_BLOCK_PROJECT_LIFE -> TimeBlockColors.Life
    TIME_BLOCK_PROJECT_STUDY -> TimeBlockColors.Study
    else -> Color(0xFF5B6675)
}

internal fun Color.softSurface(alpha: Float = 0.12f): Color = this.copy(alpha = alpha)

internal val TimeBlockMono = FontFamily.Monospace

@Composable
internal fun TimeBlockCompletionButton(
    completed: Boolean,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val background by animateColorAsState(
        if (completed) color else Color.White.copy(alpha = 0.88f),
        label = "完成按钮背景"
    )
    val checkAlpha by animateFloatAsState(if (completed) 1f else 0f, label = "完成按钮勾选")
    Box(
        modifier = modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(background)
            .border(1.5.dp, color, CircleShape)
            .clickable(role = Role.Checkbox, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Default.Check,
            contentDescription = if (completed) "恢复为未完成" else "标记完成",
            tint = Color.White,
            modifier = Modifier.size(16.dp).alpha(checkAlpha)
        )
    }
}

@Composable
internal fun TimeBlockProgressRing(
    progress: Float,
    label: String,
    modifier: Modifier = Modifier,
    color: Color = progressColor(progress)
) {
    val strokeColor = TimeBlockColors.Line
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val strokeWidth = 4.dp.toPx()
            drawArc(
                color = strokeColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(strokeWidth, cap = StrokeCap.Round)
            )
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = 360f * progress.coerceIn(0f, 1f),
                useCenter = false,
                style = Stroke(strokeWidth, cap = StrokeCap.Round)
            )
        }
        Text(
            label,
            color = TimeBlockColors.InkSecondary,
            fontFamily = TimeBlockMono,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

internal fun progressColor(progress: Float): Color = when {
    progress >= 1f -> TimeBlockColors.Focus
    progress >= 0.5f -> TimeBlockColors.Work
    else -> TimeBlockColors.Amber
}

@Composable
internal fun TimeBlockEntryChip(
    entry: TimeBlockEntry,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onToggle: (() -> Unit)? = null,
    compact: Boolean = false
) {
    val color = projectColor(entry.project)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(if (compact) 5.dp else 8.dp))
            .background(color.softSurface())
            .then(
                if (entry.isHighPriority) {
                    Modifier.border(1.dp, TimeBlockColors.High.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                } else {
                    Modifier
                }
            )
            .then(onClick?.let { Modifier.noRippleClickable(it) } ?: Modifier)
            .padding(horizontal = if (compact) 5.dp else 6.dp, vertical = if (compact) 2.dp else 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (onToggle != null && !compact) {
            TimeBlockCompletionButton(
                completed = entry.isCompleted,
                color = color,
                onClick = onToggle,
                modifier = Modifier.size(18.dp)
            )
        } else {
            Box(Modifier.size(6.dp).background(color, CircleShape))
        }
        Text(
            entry.title,
            color = if (entry.isCompleted) TimeBlockColors.InkTertiary else TimeBlockColors.Ink,
            fontSize = if (compact) 9.sp else 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textDecoration = if (entry.isCompleted) TextDecoration.LineThrough else null,
            modifier = Modifier.weight(1f, fill = false)
        )
        if (entry.isHighPriority && !compact) {
            Text(
                "急",
                color = Color.White,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .background(TimeBlockColors.High, RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            )
        }
    }
}

@Composable
internal fun TimeBlockProgressBar(progress: Float, modifier: Modifier = Modifier) {
    LinearProgressIndicator(
        progress = { progress.coerceIn(0f, 1f) },
        modifier = modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
        color = progressColor(progress),
        trackColor = Color(0xFFEFF1F4),
        strokeCap = StrokeCap.Round,
        gapSize = 0.dp,
        drawStopIndicator = {}
    )
}

@Composable
internal fun ProjectDot(project: String, modifier: Modifier = Modifier) {
    Box(modifier.size(7.dp).background(projectColor(project), CircleShape))
}

internal fun Modifier.noRippleClickable(onClick: () -> Unit): Modifier = clickable(
    interactionSource = MutableInteractionSource(),
    indication = null,
    onClick = onClick
)

internal fun formatMinute(minute: Int): String {
    val safe = minute.coerceIn(0, MINUTES_PER_DAY)
    if (safe == MINUTES_PER_DAY) return "24:00"
    return "%02d:%02d".format(safe / 60, safe % 60)
}

internal fun formatEpochTime(epochMillis: Long, zoneId: ZoneId): String =
    Instant.ofEpochMilli(epochMillis).atZone(zoneId).format(DateTimeFormatter.ofPattern("HH:mm"))

internal fun TimeBlockEntry.timeLabel(zoneId: ZoneId): String = when (kind(zoneId)) {
    TimeBlockEntryKind.EVENT,
    TimeBlockEntryKind.TIME_BLOCK_TASK -> {
        "${formatEpochTime(requireNotNull(scheduledStartEpochMillis), zoneId)} – " +
            formatEpochTime(requireNotNull(scheduledEndEpochMillis), zoneId)
    }
    TimeBlockEntryKind.DEADLINE_TASK -> "截止 ${formatEpochTime(requireNotNull(dueAtEpochMillis), zoneId)}"
    TimeBlockEntryKind.ALL_DAY_TASK -> "全天"
    TimeBlockEntryKind.INBOX_TASK -> "未排程"
}

internal fun LocalDate.weekdayLabel(): String =
    dayOfWeek.getDisplayName(JavaTextStyle.SHORT, Locale.SIMPLIFIED_CHINESE)

internal fun LocalDate.monthDayLabel(): String =
    format(DateTimeFormatter.ofPattern("M月d日"))

internal fun TimeBlockEntry.durationMinutes(): Int =
    if (scheduledStartEpochMillis != null && scheduledEndEpochMillis != null) {
        ((scheduledEndEpochMillis - scheduledStartEpochMillis) / 60_000L).toInt().coerceAtLeast(1)
    } else {
        estimatedFocusMinutes.coerceAtLeast(1)
    }

internal fun formatDurationMinutes(minutes: Int): String {
    val safeMinutes = minutes.coerceAtLeast(1)
    if (safeMinutes < 60) return "$safeMinutes′"
    val hours = safeMinutes / 60
    val remainder = safeMinutes % 60
    return if (remainder == 0) "${hours}小时" else "${hours}小时$remainder′"
}

internal fun Modifier.dashedBorder(
    width: Float = 1f,
    color: Color,
    radiusDp: Float = 8f
): Modifier = this.then(
    Modifier.drawDashedBorder(width, color, radiusDp)
)

private fun Modifier.drawDashedBorder(width: Float, color: Color, radiusDp: Float): Modifier =
    drawBehind {
        drawRoundRect(
            color = color,
            style = Stroke(
                width = width.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
            ),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(radiusDp.dp.toPx())
        )
    }
