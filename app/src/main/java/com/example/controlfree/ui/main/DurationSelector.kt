package com.example.controlfree.ui.main

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.controlfree.theme.BrandColors
import kotlin.math.roundToInt

fun normalizeDuration(rawValue: Int, minValue: Int, maxValue: Int, step: Int): Int {
    require(minValue <= maxValue)
    require(step > 0)
    val clamped = rawValue.coerceIn(minValue, maxValue)
    val slot = ((clamped - minValue).toFloat() / step).roundToInt()
    return (minValue + slot * step).coerceIn(minValue, maxValue)
}

private const val DURATION_PRESET_COLUMN_COUNT = 3
private const val DURATION_PRESET_MAX_COUNT = 6

internal fun durationPresetRows(
    presets: List<Int>,
    minValue: Int,
    maxValue: Int
): List<List<Int>> {
    require(minValue <= maxValue)
    return presets
        .filter { it in minValue..maxValue }
        .distinct()
        .take(DURATION_PRESET_MAX_COUNT)
        .chunked(DURATION_PRESET_COLUMN_COUNT)
}

internal fun durationPresetLabel(minutes: Int): String = "$minutes 分钟"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DurationSelector(
    title: String,
    value: Int,
    minValue: Int,
    maxValue: Int,
    step: Int,
    presets: List<Int>,
    accentColor: Color,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val normalizedValue = normalizeDuration(value, minValue, maxValue, step)
    val inactiveTrackColor = BrandColors.OutlineSoft
    val slotCount = (maxValue - minValue) / step

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = BrandColors.SurfaceCard)
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(title, color = BrandColors.TextPrimary, fontSize = 15.sp)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        normalizedValue.toString().padStart(2, '0'),
                        color = BrandColors.TextPrimary,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        " 分钟",
                        color = accentColor,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(bottom = 5.dp),
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                DurationStepButton(
                    icon = { Icon(Icons.Default.Remove, "减少$title") },
                    enabled = normalizedValue > minValue,
                    accentColor = accentColor,
                    onClick = { onValueChange((normalizedValue - step).coerceAtLeast(minValue)) }
                )
                Slider(
                    value = normalizedValue.toFloat(),
                    onValueChange = { rawMinutes ->
                        onValueChange(
                            normalizeDuration(rawMinutes.roundToInt(), minValue, maxValue, step)
                        )
                    },
                    valueRange = minValue.toFloat()..maxValue.toFloat(),
                    steps = (slotCount - 1).coerceAtLeast(0),
                    colors = SliderDefaults.colors(
                        thumbColor = accentColor,
                        activeTrackColor = accentColor,
                        inactiveTrackColor = BrandColors.SurfaceMuted
                    ),
                    thumb = {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .background(accentColor, CircleShape)
                                .border(3.dp, BrandColors.SurfaceCard, CircleShape)
                        )
                    },
                    track = { sliderState ->
                        Canvas(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(24.dp)
                        ) {
                            val centerY = size.height / 2f
                            val trackStrokeWidth = 8.dp.toPx()
                            val range = sliderState.valueRange
                            val fraction = if (range.endInclusive > range.start) {
                                ((sliderState.value - range.start) /
                                    (range.endInclusive - range.start)).coerceIn(0f, 1f)
                            } else {
                                0f
                            }

                            drawLine(
                                color = inactiveTrackColor,
                                start = androidx.compose.ui.geometry.Offset(0f, centerY),
                                end = androidx.compose.ui.geometry.Offset(size.width, centerY),
                                strokeWidth = trackStrokeWidth,
                                cap = StrokeCap.Round
                            )
                            drawLine(
                                color = accentColor,
                                start = androidx.compose.ui.geometry.Offset(0f, centerY),
                                end = androidx.compose.ui.geometry.Offset(
                                    size.width * fraction,
                                    centerY
                                ),
                                strokeWidth = trackStrokeWidth,
                                cap = StrokeCap.Round
                            )
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 10.dp)
                        .semantics {
                            contentDescription = title
                            stateDescription = "当前 $normalizedValue 分钟"
                        }
                )
                DurationStepButton(
                    icon = { Icon(Icons.Default.Add, "增加$title") },
                    enabled = normalizedValue < maxValue,
                    accentColor = accentColor,
                    onClick = { onValueChange((normalizedValue + step).coerceAtMost(maxValue)) }
                )
            }

            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                durationPresetRows(presets, minValue, maxValue).forEach { rowPresets ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        repeat(DURATION_PRESET_COLUMN_COUNT) { columnIndex ->
                            val preset = rowPresets.getOrNull(columnIndex)
                            if (preset == null) {
                                Spacer(Modifier.weight(1f))
                            } else {
                                FilterChip(
                                    selected = normalizedValue == preset,
                                    onClick = {
                                        onValueChange(
                                            normalizeDuration(preset, minValue, maxValue, step)
                                        )
                                    },
                                    label = {
                                        Box(
                                            modifier = Modifier.fillMaxWidth(),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                durationPresetLabel(preset),
                                                maxLines = 1,
                                                softWrap = false
                                            )
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = accentColor.copy(alpha = 0.22f),
                                        selectedLabelColor = BrandColors.TextPrimary,
                                        labelColor = BrandColors.TextSecondary
                                    )
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
private fun DurationStepButton(
    icon: @Composable () -> Unit,
    enabled: Boolean,
    accentColor: Color,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(48.dp),
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = Color.Transparent,
            contentColor = accentColor,
            disabledContentColor = BrandColors.TextTertiary
        )
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .border(
                    width = 1.5.dp,
                    color = if (enabled) accentColor.copy(alpha = 0.3f) else BrandColors.TextTertiary.copy(alpha = 0.3f),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            icon()
        }
    }
}
