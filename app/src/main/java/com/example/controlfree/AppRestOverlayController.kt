package com.example.controlfree

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.controlfree.supervision.app.BlockedAppSupervision
import com.example.controlfree.supervision.app.AppSupervisionBlockReason
import com.example.controlfree.theme.BrandColorInts

/** 仅遮挡当前正在休息的目标 App；Home、电话、短信和其他 App 不属于锁定范围。 */
class AppRestOverlayController(
    context: Context,
    private val onReturnHome: () -> Unit
) {
    private val appContext = context.applicationContext
    private val windowManager = appContext.getSystemService(WindowManager::class.java)
    private var requestedWindowState: RequestedWindowState? = null

    val isShowing: Boolean
        get() = AppRestOverlayWindowRegistry.isShowing(this)

    fun show(
        blocked: BlockedAppSupervision,
        appLabel: String,
        appIcon: Drawable?
    ): OverlayShowResult {
        resolveOverlayShowPrecondition(
            hasOverlayPermission = Settings.canDrawOverlays(appContext),
            hasWindowManager = windowManager != null
        )?.let { failure ->
            hide()
            return failure
        }
        val remainingSeconds = millisToDisplaySeconds(blocked.remainingRestMillis)
        requestedWindowState = RequestedWindowState(
            appLabel = appLabel,
            appIcon = appIcon,
            remainingSeconds = remainingSeconds,
            reason = blocked.reason,
            packageName = blocked.rule.packageName
        )
        return AppRestOverlayWindowRegistry.show(
            owner = this,
            windowManager = requireNotNull(windowManager),
            createWindow = ::createWindowSpec,
            canAdoptWindow = ::canAdoptWindow,
            updateWindow = ::updateWindow
        )
    }

    fun hide() {
        AppRestOverlayWindowRegistry.hide(this)
        requestedWindowState = null
    }

    private fun createWindowSpec(): AppRestOverlayWindowSpec {
        val requested = requireNotNull(requestedWindowState)
        val root = FrameLayout(appContext).apply {
            setBackgroundColor(BrandColorInts.Canvas)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            systemUiVisibility = IMMERSIVE_FLAGS
            setOnSystemUiVisibilityChangeListener {
                if (systemUiVisibility != IMMERSIVE_FLAGS) {
                    systemUiVisibility = IMMERSIVE_FLAGS
                }
            }
        }
        val content = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(36), dp(28), dp(36))
        }
        content.addView(ImageView(appContext).apply {
            setImageDrawable(requested.appIcon ?: appContext.packageManager.defaultActivityIcon)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = requested.appLabel
        }, linearParams(dp(76), dp(76)).apply { bottomMargin = dp(20) })
        content.addView(
            textView(requested.appLabel, 17f, MUTED_WHITE).apply { maxLines = 2 },
            matchWidthWrapHeight().apply { bottomMargin = dp(8) }
        )
        val titleView = textView(requested.reason.title, 24f, BrandColorInts.TextPrimary).apply {
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
        content.addView(
            titleView,
            matchWidthWrapHeight().apply { bottomMargin = dp(16) }
        )
        val countdownView = textView(
            formatAppRestDuration(requested.remainingSeconds),
            42f,
            BrandColorInts.TextPrimary
        ).apply {
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1
            setAutoSizeTextTypeUniformWithConfiguration(
                20,
                48,
                1,
                TypedValue.COMPLEX_UNIT_SP
            )
        }
        content.addView(countdownView, matchWidthWrapHeight().apply { bottomMargin = dp(14) })
        val descriptionView = textView(
                requested.reason.description,
                14f,
                MUTED_WHITE
            )
        content.addView(
            descriptionView,
            matchWidthWrapHeight().apply { bottomMargin = dp(26) }
        )
        val returnHomeButton = Button(appContext).apply {
            text = "返回桌面"
            textSize = 16f
            minHeight = dp(48)
            setTextColor(BrandColorInts.OnPrimary)
            backgroundTintList = null
            background = roundedButtonBackground()
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setOnClickListener { onReturnHome() }
        }
        content.addView(returnHomeButton, matchWidthWrapHeight())
        val scroll = ScrollView(appContext).apply {
            isFillViewport = true
            addView(content, centeredContentParams())
        }
        root.addView(
            scroll,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        root.tag = WindowViews(
            packageName = requested.packageName,
            countdownView = countdownView,
            titleView = titleView,
            descriptionView = descriptionView,
            returnHomeButton = returnHomeButton
        )
        return AppRestOverlayWindowSpec(
            handle = AppRestOverlayWindowHandle(root),
            layoutParams = createLayoutParams()
        )
    }

    private fun updateWindow(handle: AppRestOverlayWindowHandle) {
        val requested = requestedWindowState ?: return
        val views = handle.rootView.tag as? WindowViews ?: return
        views.countdownView.text = formatAppRestDuration(requested.remainingSeconds)
        views.titleView.text = requested.reason.title
        views.descriptionView.text = requested.reason.description
        // 窗口可跨 Service 实例接管；按钮必须同时绑定到新实例的回调。
        views.returnHomeButton.setOnClickListener { onReturnHome() }
    }

    private fun canAdoptWindow(handle: AppRestOverlayWindowHandle): Boolean {
        val requested = requestedWindowState ?: return false
        val views = handle.rootView.tag as? WindowViews ?: return false
        return views.packageName == requested.packageName
    }

    private fun centeredContentParams(): FrameLayout.LayoutParams {
        val screenWidthDp = appContext.resources.configuration.screenWidthDp
        val width = if (screenWidthDp > 720) dp(720) else FrameLayout.LayoutParams.MATCH_PARENT
        return FrameLayout.LayoutParams(
            width,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        )
    }

    private fun createLayoutParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        appRestOverlayWindowFlags(),
        PixelFormat.OPAQUE
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        title = "${appContext.getString(R.string.app_name)} App 休息"
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun textView(text: String, sizeSp: Float, color: Int) = TextView(appContext).apply {
        this.text = text
        textSize = sizeSp
        setTextColor(color)
        gravity = Gravity.CENTER
    }

    private fun roundedButtonBackground() = RippleDrawable(
        ColorStateList.valueOf(0x26000000),
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(10).toFloat()
            setColor(ACCENT)
        },
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(10).toFloat()
            setColor(0xFFFFFFFF.toInt())
        }
    )

    private fun matchWidthWrapHeight() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )

    private fun linearParams(width: Int, height: Int) = LinearLayout.LayoutParams(width, height)

    private fun dp(value: Int): Int = (value * appContext.resources.displayMetrics.density).toInt()

    private companion object {
        val ACCENT = BrandColorInts.Primary
        val MUTED_WHITE = BrandColorInts.TextSecondary
        const val IMMERSIVE_FLAGS =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    private data class RequestedWindowState(
        val appLabel: String,
        val appIcon: Drawable?,
        val remainingSeconds: Int,
        val reason: AppSupervisionBlockReason,
        val packageName: String
    )

    private data class WindowViews(
        val packageName: String,
        val countdownView: TextView,
        val titleView: TextView,
        val descriptionView: TextView,
        val returnHomeButton: Button
    )
}

internal fun appRestOverlayWindowFlags(): Int =
    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
        WindowManager.LayoutParams.FLAG_FULLSCREEN

internal fun formatAppRestDuration(seconds: Int): String {
    val safeSeconds = seconds.coerceAtLeast(0)
    val hours = safeSeconds / 3_600
    val minutes = safeSeconds % 3_600 / 60
    val remainingSeconds = safeSeconds % 60
    return "${hours}小时${minutes}分钟${remainingSeconds}秒"
}

private val AppSupervisionBlockReason.title: String
    get() = when (this) {
        AppSupervisionBlockReason.REST -> "该 App 正在休息"
        AppSupervisionBlockReason.DAILY_LIMIT -> "今日使用时长已用完"
        AppSupervisionBlockReason.DISABLED_TIME -> "当前处于禁用时段"
    }

private val AppSupervisionBlockReason.description: String
    get() = when (this) {
        AppSupervisionBlockReason.REST -> "休息结束后可再次使用；其他 App、电话和短信不受影响"
        AppSupervisionBlockReason.DAILY_LIMIT -> "每日额度将在次日重置；其他 App、电话和短信不受影响"
        AppSupervisionBlockReason.DISABLED_TIME -> "禁用时段结束后可再次使用；其他 App、电话和短信不受影响"
    }

internal fun millisToDisplaySeconds(remainingMillis: Long): Int =
    ((remainingMillis.coerceAtLeast(0L) + 999L) / 1_000L)
        .coerceAtMost(Int.MAX_VALUE.toLong())
        .toInt()
