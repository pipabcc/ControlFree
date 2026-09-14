package com.example.controlfree

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import com.example.controlfree.growth.GrowthStage

/**
 * 矢量的、带微风摇曳与阶段动效的“自律小草”可绘制对象。
 * 用于彻底替代原先老气、重浊的机器人宠物图像。
 */
internal class GrowingPlantDrawable(
    val stage: GrowthStage,
    val showCircleBackground: Boolean = true
) : Drawable(), Animatable {

    private var animator: ValueAnimator? = null
    private var swingAngle = 0f
    private var breatheScale = 1.0f
    private var starAlpha = 1.0f
    private var rotateAngle = 0f

    /**
     * Compose Canvas 直接 draw 时没有 Drawable callback，invalidateSelf() 不会触发重绘。
     * 外部可注册该回调，在每帧动画更新时驱动 Compose 状态刷新。
     */
    var onFrameChanged: (() -> Unit)? = null

    // —— 几何缓存：Path 仅在 bounds 变化时重建，避免动画期间每帧分配 ——
    private val geometry = PlantGeometry()
    private var geometryWidth = 0
    private var geometryHeight = 0

    private class PlantGeometry {
        val clipCircle = Path()
        val dirt = Path()
        val stem = Path()
        val branchL = Path()
        val branchR = Path()
        val sideBranch = Path()
        val leaves = HashMap<Long, Path>()
        val veins = HashMap<Long, Path>()
        val star = Path()
        var starOffsets = FloatArray(0)
    }

    private fun ensureGeometry() {
        val width = bounds.width()
        val height = bounds.height()
        if (width == geometryWidth && height == geometryHeight) return
        geometryWidth = width
        geometryHeight = height
        rebuildGeometry(width.toFloat(), height.toFloat())
    }

    private fun rebuildGeometry(width: Float, height: Float) {
        val radius = minOf(width, height) * 0.42f
        val density = minOf(width, height) / 100f
        val cx = width / 2f
        val cy = height / 2f

        geometry.clipCircle.reset()
        geometry.clipCircle.addCircle(cx, cy, radius, Path.Direction.CW)

        geometry.dirt.reset()
        geometry.dirt.moveTo(cx - radius, cy + radius * 0.72f)
        geometry.dirt.quadTo(cx, cy + radius * 0.68f, cx + radius, cy + radius * 0.72f)
        geometry.dirt.lineTo(cx + radius, cy + radius * 1.2f)
        geometry.dirt.lineTo(cx - radius, cy + radius * 1.2f)
        geometry.dirt.close()

        geometry.stem.reset()
        geometry.branchL.reset()
        geometry.branchR.reset()
        geometry.sideBranch.reset()
        when (stage) {
            GrowthStage.SEEDLING -> {
                geometry.stem.moveTo(0f, 0f)
                geometry.stem.quadTo(-density * 2f, -radius * 0.35f, 0f, -radius * 0.85f)
            }
            GrowthStage.NEW_LEAF -> {
                geometry.stem.moveTo(0f, 0f)
                geometry.stem.quadTo(-density * 3f, -radius * 0.4f, 0f, -radius * 0.88f)
            }
            GrowthStage.GREEN_BRANCH -> {
                geometry.stem.moveTo(0f, 0f)
                geometry.stem.quadTo(-density * 3f, -radius * 0.42f, -density * 1.5f, -radius * 0.90f)
                geometry.sideBranch.moveTo(-density * 2.2f, -radius * 0.35f)
                geometry.sideBranch.quadTo(-density * 8f, -radius * 0.5f, -density * 11f, -radius * 0.65f)
            }
            GrowthStage.GUARDIAN -> {
                geometry.stem.moveTo(0f, 0f)
                geometry.stem.quadTo(-density * 1f, -radius * 0.4f, 0f, -radius * 0.94f)
                geometry.branchL.moveTo(-density * 0.5f, -radius * 0.3f)
                geometry.branchL.quadTo(-density * 7f, -radius * 0.45f, -density * 10f, -radius * 0.58f)
                geometry.branchR.moveTo(density * 0.5f, -radius * 0.4f)
                geometry.branchR.quadTo(density * 7f, -radius * 0.55f, density * 10f, -radius * 0.65f)
            }
            GrowthStage.STAR_BLOOM -> {
                geometry.stem.moveTo(0f, 0f)
                geometry.stem.quadTo(-density * 1f, -radius * 0.4f, 0f, -radius * 0.94f)
                geometry.branchL.moveTo(-density * 0.5f, -radius * 0.3f)
                geometry.branchL.quadTo(-density * 8f, -radius * 0.45f, -density * 11f, -radius * 0.60f)
                geometry.branchR.moveTo(density * 0.5f, -radius * 0.4f)
                geometry.branchR.quadTo(density * 8f, -radius * 0.55f, density * 11f, -radius * 0.68f)
            }
        }

        geometry.starOffsets = floatArrayOf(
            -radius * 0.7f, -radius * 0.7f,
            radius * 0.8f, -radius * 0.5f,
            -radius * 0.6f, radius * 0.1f,
            radius * 0.7f, radius * 0.4f
        )
        geometry.leaves.clear()
        geometry.veins.clear()
    }

    // 绘制画笔
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFEBF5F0.toInt() // 极其温润淡雅的清浅微绿白背景盘
        style = Paint.Style.FILL
    }

    private val auraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val dirtPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF8B6D5C.toInt() // 温暖浅褐色小土丘
        style = Paint.Style.FILL
    }

    private val stemPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2B9B62.toInt() // 健康的莫兰迪草绿茎
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val leafPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF34AF72.toInt() // 饱满的绿色叶片
        style = Paint.Style.FILL
    }

    private val leafVeinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF238E5A.toInt() // 稍深色的叶脉
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    private val budPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF58CA3.toInt() // 粉绿色花骨朵
        style = Paint.Style.FILL
    }

    private val flowerCenterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF5C024.toInt() // 守护花黄色花芯
        style = Paint.Style.FILL
    }

    private val flowerPetalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt() // 守护花白色花瓣
        style = Paint.Style.FILL
    }

    private val berryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE53935.toInt() // 艳红色浆果
        style = Paint.Style.FILL
    }

    private val berryHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.FILL
    }

    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF5D658.toInt() // 金色星星
        style = Paint.Style.FILL
    }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF5D658.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }

    override fun draw(canvas: Canvas) {
        val width = bounds.width().toFloat()
        val height = bounds.height().toFloat()
        if (width <= 0f || height <= 0f) return
        ensureGeometry()

        val cx = width / 2f
        val cy = height / 2f
        val size = minOf(width, height)
        val radius = size * 0.42f
        val density = size / 100f // 适配性间距缩放因子

        // 1. 绘制光晕层（需在底色圆形下方绘制，以免遮挡小草）
        drawBackgroundAura(canvas, cx, cy, radius)

        // 2. 绘制基础微绿圆形底面
        if (showCircleBackground) {
            canvas.drawCircle(cx, cy, radius, bgPaint)
        }

        // 3. 绘制泥土小丘（使用 clipPath 限制在底盘内，防边缘溢出与分离）
        canvas.save()
        canvas.clipPath(geometry.clipCircle)
        canvas.drawPath(geometry.dirt, dirtPaint)
        canvas.restore()

        // 4. 平移旋转 Canvas 实现微风吹动摇摆（以茎部根部为支点）
        val rootX = cx
        val rootY = cy + radius * 0.68f
        canvas.save()
        canvas.translate(rootX, rootY)
        canvas.rotate(swingAngle)

        // 绘制不同阶段的茎和叶
        stemPaint.strokeWidth = density * 2.5f
        when (stage) {
            GrowthStage.SEEDLING -> {
                // 初芽：主茎较细，两片对称椭圆子叶
                canvas.drawPath(geometry.stem, stemPaint)

                // 顶端子叶
                canvas.save()
                canvas.translate(0f, -radius * 0.85f)
                drawLeaf(canvas, radius * 0.35f, -42f, true)
                drawLeaf(canvas, radius * 0.35f, 42f, false)
                canvas.restore()
            }
            GrowthStage.NEW_LEAF -> {
                // 新叶：主茎稍粗，三片叶子（一侧多长一片较亮新叶）
                canvas.drawPath(geometry.stem, stemPaint)

                // 左右大叶
                canvas.save()
                canvas.translate(0f, -radius * 0.88f)
                drawLeaf(canvas, radius * 0.36f, -45f, true)
                drawLeaf(canvas, radius * 0.36f, 45f, false)
                canvas.restore()

                // 新侧生小嫩叶
                canvas.save()
                canvas.translate(-density * 1.5f, -radius * 0.45f)
                drawLeaf(canvas, radius * 0.24f, 20f, false, isYoung = true)
                canvas.restore()
            }
            GrowthStage.GREEN_BRANCH -> {
                // 青枝：分叉茎干，四到五片叶子加顶端圆润花苞
                canvas.drawPath(geometry.stem, stemPaint)
                canvas.drawPath(geometry.sideBranch, stemPaint)

                // 侧枝叶片
                canvas.save()
                canvas.translate(-density * 11f, -radius * 0.65f)
                drawLeaf(canvas, radius * 0.28f, -70f, true)
                drawLeaf(canvas, radius * 0.24f, -10f, false)
                canvas.restore()

                // 顶端主叶与花苞
                canvas.save()
                canvas.translate(-density * 1.5f, -radius * 0.90f)
                drawLeaf(canvas, radius * 0.35f, -30f, true)
                drawLeaf(canvas, radius * 0.35f, 30f, false)
                // 顶端小粉色花苞
                canvas.drawCircle(0f, -radius * 0.05f, density * 4.5f, budPaint)
                canvas.restore()
            }
            GrowthStage.GUARDIAN -> {
                // 守望：茎粗壮多叶，顶端绽放出一朵美丽的5瓣守护白花
                canvas.drawPath(geometry.stem, stemPaint)
                canvas.drawPath(geometry.branchL, stemPaint)
                canvas.drawPath(geometry.branchR, stemPaint)

                // 分枝茂盛叶片
                canvas.save()
                canvas.translate(-density * 10f, -radius * 0.58f)
                drawLeaf(canvas, radius * 0.32f, -60f, true)
                canvas.restore()

                canvas.save()
                canvas.translate(density * 10f, -radius * 0.65f)
                drawLeaf(canvas, radius * 0.32f, 60f, false)
                canvas.restore()

                // 主顶大叶
                canvas.save()
                canvas.translate(0f, -radius * 0.94f)
                drawLeaf(canvas, radius * 0.36f, -48f, true)
                drawLeaf(canvas, radius * 0.36f, 48f, false)

                // 顶端白花（5瓣）
                canvas.translate(0f, -radius * 0.08f)
                for (i in 0 until 5) {
                    canvas.drawOval(
                        -density * 3f,
                        -density * 6.5f,
                        density * 3f,
                        -density * 2.2f,
                        flowerPetalPaint
                    )
                    canvas.rotate(72f)
                }
                canvas.drawCircle(0f, 0f, density * 3.2f, flowerCenterPaint)
                canvas.restore()
            }
            GrowthStage.STAR_BLOOM -> {
                // 星芽：繁茂灌木型小草，长有红熟小浆果
                canvas.drawPath(geometry.stem, stemPaint)
                canvas.drawPath(geometry.branchL, stemPaint)
                canvas.drawPath(geometry.branchR, stemPaint)

                // 多层叶片
                canvas.save()
                canvas.translate(-density * 11f, -radius * 0.60f)
                drawLeaf(canvas, radius * 0.32f, -55f, true)
                drawLeaf(canvas, radius * 0.25f, 15f, false)
                canvas.restore()

                canvas.save()
                canvas.translate(density * 11f, -radius * 0.68f)
                drawLeaf(canvas, radius * 0.32f, 55f, false)
                drawLeaf(canvas, radius * 0.25f, -15f, true)
                canvas.restore()

                canvas.save()
                canvas.translate(0f, -radius * 0.94f)
                drawLeaf(canvas, radius * 0.38f, -40f, true)
                drawLeaf(canvas, radius * 0.38f, 40f, false)
                canvas.restore()

                // 挂在旁边的2颗艳红色熟透浆果
                canvas.save()
                canvas.translate(-density * 6f, -radius * 0.52f)
                canvas.drawCircle(0f, 0f, density * 4.0f, berryPaint)
                canvas.drawCircle(-density * 1.1f, -density * 1.1f, density * 1.1f, berryHighlightPaint) // 亮色高光
                canvas.restore()

                canvas.save()
                canvas.translate(density * 7f, -radius * 0.46f)
                canvas.drawCircle(0f, 0f, density * 4.0f, berryPaint)
                canvas.drawCircle(-density * 1.1f, -density * 1.1f, density * 1.1f, berryHighlightPaint)
                canvas.restore()
            }
        }

        canvas.restore() // 还原微风摆动造成的旋转与位移

        // 5. 绘制星光（仅在 STAR_BLOOM 下绘制于最上层）
        if (stage == GrowthStage.STAR_BLOOM) {
            drawSparklingStars(canvas, cx, cy, radius, density)
        }
    }

    private fun drawLeaf(
        canvas: Canvas,
        length: Float,
        angle: Float,
        isLeft: Boolean,
        isYoung: Boolean = false
    ) {
        canvas.save()
        canvas.rotate(angle)

        // 若是新生幼叶，采用更加明亮温润的淡青绿，展现蓬勃生命力
        leafPaint.color = if (isYoung) 0xFF4ADE80.toInt() else 0xFF34AF72.toInt()
        leafVeinPaint.color = if (isYoung) 0xFF22C55E.toInt() else 0xFF238E5A.toInt()

        // 叶片形状只取决于 (length, isLeft)，缓存复用避免每帧分配
        val shapeKey = (length.toRawBits().toLong() shl 1) or if (isLeft) 0L else 1L
        val path = geometry.leaves.getOrPut(shapeKey) {
            Path().apply {
                moveTo(0f, 0f)
                if (isLeft) {
                    cubicTo(-length * 0.35f, -length * 0.15f, -length * 0.55f, -length * 0.55f, 0f, -length)
                    cubicTo(length * 0.18f, -length * 0.55f, length * 0.08f, -length * 0.18f, 0f, 0f)
                } else {
                    cubicTo(length * 0.35f, -length * 0.15f, length * 0.55f, -length * 0.55f, 0f, -length)
                    cubicTo(-length * 0.18f, -length * 0.55f, -length * 0.08f, -length * 0.18f, 0f, 0f)
                }
                close()
            }
        }
        canvas.drawPath(path, leafPaint)

        // 绘制一条极细的叶片主脉，极富工艺细节
        val veinPath = geometry.veins.getOrPut(shapeKey) {
            Path().apply {
                moveTo(0f, 0f)
                quadTo(if (isLeft) -length * 0.04f else length * 0.04f, -length * 0.5f, 0f, -length)
            }
        }
        canvas.drawPath(veinPath, leafVeinPaint)

        canvas.restore()
    }

    private fun drawBackgroundAura(canvas: Canvas, cx: CenterX, cy: CenterY, radius: Float) {
        val breatheOffset = (breatheScale - 1.0f) * radius * 0.5f
        when (stage) {
            GrowthStage.SEEDLING -> {
                // 原始外观无额外光晕
            }
            GrowthStage.NEW_LEAF -> {
                // 柔和绿光：单层半透明大绿圈
                auraPaint.color = 0xFF2A9E6C.toInt()
                auraPaint.alpha = (18 * 0.7f).toInt()
                canvas.drawCircle(cx, cy, radius * 1.15f + breatheOffset, auraPaint)
            }
            GrowthStage.GREEN_BRANCH -> {
                // 青绿光晕：稍微显著的双层绿圈
                auraPaint.color = 0xFF1B8253.toInt()
                auraPaint.alpha = 15
                canvas.drawCircle(cx, cy, radius * 1.2f + breatheOffset, auraPaint)
                auraPaint.alpha = 30
                canvas.drawCircle(cx, cy, radius * 1.08f + breatheOffset * 0.5f, auraPaint)
            }
            GrowthStage.GUARDIAN -> {
                // 强化守护光晕：带有点状星辉的强光环
                auraPaint.color = 0xFF1B8253.toInt()
                auraPaint.alpha = 20
                canvas.drawCircle(cx, cy, radius * 1.25f + breatheOffset, auraPaint)

                // 绘制虚线防护环
                ringPaint.color = 0xFF2B9B62.toInt()
                ringPaint.alpha = 80
                canvas.drawCircle(cx, cy, radius * 1.12f, ringPaint)
            }
            GrowthStage.STAR_BLOOM -> {
                // 星光双层光环：金黄色星光旋转细环
                ringPaint.color = 0xFFF5D658.toInt()
                ringPaint.alpha = (100 * starAlpha).toInt()

                canvas.save()
                canvas.translate(cx, cy)
                canvas.rotate(rotateAngle)
                // 绘制双环
                canvas.drawCircle(0f, 0f, radius * 1.15f, ringPaint)
                canvas.drawCircle(0f, 0f, radius * 1.26f, ringPaint)
                canvas.restore()
            }
        }
    }

    private fun drawSparklingStars(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        density: Float
    ) {
        starPaint.alpha = (255 * starAlpha).toInt()

        // 固定的几颗漂浮在空中的黄色闪光小四角星（偏移量已按 bounds 缓存）
        val offsets = geometry.starOffsets
        val size = density * 2.5f

        var index = 0
        while (index < offsets.size) {
            val sx = cx + offsets[index]
            val sy = cy + offsets[index + 1]
            geometry.star.reset()
            geometry.star.moveTo(sx, sy - size)
            geometry.star.quadTo(sx, sy, sx + size, sy)
            geometry.star.quadTo(sx, sy, sx, sy + size)
            geometry.star.quadTo(sx, sy, sx - size, sy)
            geometry.star.quadTo(sx, sy, sx, sy - size)
            geometry.star.close()
            canvas.drawPath(geometry.star, starPaint)
            index += 2
        }
    }

    override fun setAlpha(alpha: Int) {
        bgPaint.alpha = alpha
        dirtPaint.alpha = alpha
        stemPaint.alpha = alpha
        leafPaint.alpha = alpha
        leafVeinPaint.alpha = alpha
        budPaint.alpha = alpha
        flowerCenterPaint.alpha = alpha
        flowerPetalPaint.alpha = alpha
        berryPaint.alpha = alpha
        berryHighlightPaint.alpha = alpha
        starPaint.alpha = alpha
        ringPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        bgPaint.colorFilter = colorFilter
        dirtPaint.colorFilter = colorFilter
        stemPaint.colorFilter = colorFilter
        leafPaint.colorFilter = colorFilter
        leafVeinPaint.colorFilter = colorFilter
        budPaint.colorFilter = colorFilter
        flowerCenterPaint.colorFilter = colorFilter
        flowerPetalPaint.colorFilter = colorFilter
        berryPaint.colorFilter = colorFilter
        berryHighlightPaint.colorFilter = colorFilter
        starPaint.colorFilter = colorFilter
        ringPaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun start() {
        if (animator == null) {
            animator = ValueAnimator.ofFloat(0f, 2f * Math.PI.toFloat()).apply {
                duration = 2800
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { anim ->
                    val time = anim.animatedValue as Float
                    swingAngle = Math.sin(time.toDouble()).toFloat() * 3.8f // 莫兰迪微风慢摇晃 3.8度
                    breatheScale = 1.0f + Math.sin(time.toDouble() * 2).toFloat() * 0.04f
                    starAlpha = 0.35f + Math.abs(Math.sin(time.toDouble() * 1.5)).toFloat() * 0.65f
                    rotateAngle = (time / (2f * Math.PI.toFloat())) * 360f // 围绕中心徐徐自转
                    invalidateSelf()
                    onFrameChanged?.invoke()
                }
                start()
            }
        }
    }

    override fun stop() {
        animator?.cancel()
        animator = null
    }

    override fun isRunning(): Boolean = animator != null
}

/**
 * 让 ImageView 在挂载到窗口时自动启动摇曳动画、脱离窗口时停止，
 * 避免无限 ValueAnimator 在视图销毁后仍然空转泄漏。
 */
internal fun ImageView.animateGrowingPlantWhenAttached(drawable: GrowingPlantDrawable) {
    setImageDrawable(drawable)
    addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
            drawable.start()
        }

        override fun onViewDetachedFromWindow(v: View) {
            drawable.stop()
        }
    })
    if (isAttachedToWindow) drawable.start()
}

private typealias CenterX = Float
private typealias CenterY = Float
