package com.example.controlfree.ui.todo.components

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** 时间块内部条目的快速移除手势；不提供编辑操作区。 */
@Composable
fun SwipeToDeleteContainer(
    itemName: String,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(12.dp),
    enabled: Boolean = true,
    onDelete: () -> Unit,
    content: @Composable () -> Unit
) {
    var offsetX by remember(itemName) { mutableStateOf(0f) }
    var isDismissed by remember(itemName) { mutableStateOf(false) }
    val density = LocalDensity.current
    val deleteThreshold = with(density) { 90.dp.toPx() }
    val maxSlide = with(density) { 360.dp.toPx() }

    LaunchedEffect(enabled) {
        if (!enabled) {
            offsetX = 0f
            isDismissed = false
        }
    }

    if (isDismissed) {
        Spacer(Modifier.height(1.dp))
    } else {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .clip(shape)
                .then(
                    if (enabled) {
                        Modifier.pointerInput(itemName) {
                            detectHorizontalDragGestures(
                                onDragEnd = {
                                    if (offsetX <= -deleteThreshold) {
                                        isDismissed = true
                                        onDelete()
                                    } else {
                                        offsetX = 0f
                                    }
                                },
                                onDragCancel = { offsetX = 0f },
                                onHorizontalDrag = { _, dragAmount ->
                                    offsetX = (offsetX + dragAmount).coerceIn(-maxSlide, 0f)
                                }
                            )
                        }
                    } else {
                        Modifier
                    }
                )
        ) {
            Box(
                modifier = Modifier
                    .offset { IntOffset(offsetX.roundToInt(), 0) }
                    .fillMaxWidth()
                    .clip(shape)
            ) {
                content()
            }
        }
    }
}
