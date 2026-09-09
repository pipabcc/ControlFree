package com.example.controlfree.todo.anniversary.runtime

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationManagerCompat
import com.example.controlfree.R
import com.example.controlfree.theme.BrandColorInts
import com.example.controlfree.todo.TodoRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 时刻到达的桌面弹出卡片。厂商系统普遍限制后台 startActivity（“后台弹出界面”权限），
 * 但 TYPE_APPLICATION_OVERLAY 悬浮窗只依赖悬浮窗权限，不打开应用也能弹到桌面；
 * 锁屏/熄屏场景由全屏意图通知拉起卡片 Activity 兜底。点“知道了”才消失。
 */
object AnniversaryAlertOverlay {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val consumeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var attachedView: View? = null
    private var attachedWindowManager: WindowManager? = null

    fun show(
        context: Context,
        title: String,
        message: String,
        celebrationId: String?,
        anniversaryId: String?
    ): Boolean {
        val appContext = context.applicationContext
        if (!Settings.canDrawOverlays(appContext)) return false
        mainHandler.post {
            showOnMainThread(appContext, title, message, celebrationId, anniversaryId)
        }
        return true
    }

    private fun showOnMainThread(
        appContext: Context,
        title: String,
        message: String,
        celebrationId: String?,
        anniversaryId: String?
    ) {
        dismiss()
        val windowManager = appContext.getSystemService(WindowManager::class.java) ?: return
        val root = buildCard(appContext, title, message) {
            dismiss()
            if (anniversaryId != null) {
                runCatching {
                    NotificationManagerCompat.from(appContext).cancel(
                        anniversaryId,
                        AnniversaryAlertReceiver.NOTIFICATION_ALERT_ID
                    )
                }
            }
            if (celebrationId != null) {
                consumeScope.launch {
                    runCatching {
                        TodoRepository.getInstance(appContext)
                            .consumeCelebration(celebrationId, System.currentTimeMillis())
                    }
                }
            }
        }
        try {
            windowManager.addView(root, createLayoutParams())
            attachedView = root
            attachedWindowManager = windowManager
        } catch (_: RuntimeException) {
            // 窗口服务拒绝时保留通知路径，不阻塞刷新流程。
        }
    }

    private fun dismiss() {
        val view = attachedView ?: return
        runCatching { attachedWindowManager?.removeViewImmediate(view) }
        attachedView = null
        attachedWindowManager = null
    }

    private fun buildCard(
        context: Context,
        title: String,
        message: String,
        onAcknowledge: () -> Unit
    ): View {
        fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

        val preferences = com.example.controlfree.data.PreferenceManager(context)
        val isDark = preferences.isDarkThemeEnabled()
        val bgColor = preferences.getBackgroundColor()
        val cardBgColor = when {
            bgColor != 0 -> bgColor
            isDark -> BrandColorInts.Canvas
            else -> com.example.controlfree.theme.LightBrandColorInts.Canvas
        }
        val themeColor = when {
            bgColor != 0 -> bgColor
            isDark -> BrandColorInts.Primary
            else -> com.example.controlfree.theme.LightBrandColorInts.Primary
        }
        val textPrimaryColor = when {
            bgColor != 0 || isDark -> BrandColorInts.TextPrimary
            else -> com.example.controlfree.theme.LightBrandColorInts.TextPrimary
        }
        val textSecondaryColor = when {
            bgColor != 0 || isDark -> BrandColorInts.TextSecondary
            else -> com.example.controlfree.theme.LightBrandColorInts.TextSecondary
        }

        val root = FrameLayout(context).apply {
            setBackgroundColor(0xB3000000.toInt())
            isClickable = true
            isFocusable = true
        }
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(26), dp(24), dp(22))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(24).toFloat()
                setColor(cardBgColor)
                setStroke(dp(1), themeColor and 0x33FFFFFF)
            }
        }

        card.addView(
            TextView(context).apply {
                text = "✨ 时刻到达 ✨"
                textSize = 20f
                setTextColor(themeColor)
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
            },
            linearParams()
        )
        card.addView(
            ImageView(context).apply {
                setImageDrawable(com.example.controlfree.GrowingPlantDrawable(com.example.controlfree.growth.GrowthStage.SEEDLING).apply { start() })
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(themeColor and 0x22FFFFFF)
                }
                val padding = dp(12)
                setPadding(padding, padding, padding, padding)
            },
            LinearLayout.LayoutParams(dp(110), dp(110)).apply {
                topMargin = dp(16)
                gravity = Gravity.CENTER_HORIZONTAL
            }
        )
        card.addView(
            TextView(context).apply {
                text = title
                textSize = 22f
                setTextColor(textPrimaryColor)
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
            },
            linearParams().apply { topMargin = dp(16) }
        )
        card.addView(
            TextView(context).apply {
                text = message
                textSize = 14f
                setTextColor(textSecondaryColor)
                gravity = Gravity.CENTER
            },
            linearParams().apply { topMargin = dp(8) }
        )
        card.addView(
            Button(context).apply {
                text = "知道了"
                isAllCaps = false
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(0xFFFFFFFF.toInt())
                backgroundTintList = null
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(23).toFloat()
                    setColor(themeColor)
                }
                minHeight = dp(46)
                setOnClickListener { onAcknowledge() }
            },
            LinearLayout.LayoutParams(
                dp(180),
                dp(46)
            ).apply {
                topMargin = dp(24)
                gravity = Gravity.CENTER_HORIZONTAL
            }
        )

        val cardWidth = minOf(
            dp(340),
            context.resources.displayMetrics.widthPixels - dp(48)
        ).coerceAtLeast(dp(240))
        root.addView(
            card,
            FrameLayout.LayoutParams(
                cardWidth,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )
        return root
    }

    private fun linearParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )

    private fun createLayoutParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
    }
}
