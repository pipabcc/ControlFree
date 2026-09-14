package com.example.controlfree.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import com.example.controlfree.GrowingPlantDrawable
import com.example.controlfree.growth.GrowthStage

/**
 * 在 Compose Canvas 中绘制带摇曳动画的“自律小草”。
 *
 * Drawable 的 invalidateSelf() 依赖 View 回调，直接在 Canvas 里绘制时不会触发重绘；
 * 这里通过 onFrameChanged 驱动每帧重绘，并在离开组合时停止无限动画避免泄漏。
 */
@Composable
fun GrowingPlantCanvas(
    stage: GrowthStage,
    modifier: Modifier = Modifier,
    showCircleBackground: Boolean = true
) {
    val frameTick = remember { mutableIntStateOf(0) }
    val plantDrawable = remember(stage, showCircleBackground) {
        GrowingPlantDrawable(stage, showCircleBackground).apply {
            onFrameChanged = { frameTick.intValue++ }
            start()
        }
    }
    DisposableEffect(stage, showCircleBackground) {
        onDispose { plantDrawable.stop() }
    }
    Canvas(modifier = modifier) {
        // 读取帧计数以纳入快照观察，动画每帧都会触发本次绘制块重执行
        @Suppress("UNUSED_EXPRESSION") frameTick.intValue
        plantDrawable.setBounds(0, 0, size.width.toInt(), size.height.toInt())
        plantDrawable.draw(drawContext.canvas.nativeCanvas)
    }
}
