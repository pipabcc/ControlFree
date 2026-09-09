package com.example.controlfree

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager

internal enum class AppRestOverlayWindowPhase {
    ATTACHING,
    ACTIVE,
    REMOVING,
    SETTLING
}

internal sealed interface AppRestOverlayWindowClaim<out W : Any> {
    data object Available : AppRestOverlayWindowClaim<Nothing>

    data class Existing<W : Any>(val window: W) : AppRestOverlayWindowClaim<W>

    data class Conflicting<W : Any>(val window: W) : AppRestOverlayWindowClaim<W>

    data object WaitingForRelease : AppRestOverlayWindowClaim<Nothing>
}

/**
 * 维护进程内唯一监督悬浮窗的所有权真值。
 *
 * Service 可能在旧实例的窗口挂载或移除期间重建。同一目标的 ATTACHING/ACTIVE 窗口
 * 可以转交给新实例；正在移除或等待系统窗口事务收敛的窗口会阻止第二次注册。
 */
internal class AppRestOverlayOwnershipCoordinator<W : Any, O : Any> {
    private var active: Record<W, O>? = null

    @Synchronized
    fun claim(
        owner: O,
        canAdopt: (W) -> Boolean = { true }
    ): AppRestOverlayWindowClaim<W> {
        val record = active ?: return AppRestOverlayWindowClaim.Available
        return when (record.phase) {
            AppRestOverlayWindowPhase.ATTACHING,
            AppRestOverlayWindowPhase.ACTIVE -> if (canAdopt(record.window)) {
                record.owner = owner
                AppRestOverlayWindowClaim.Existing(record.window)
            } else {
                AppRestOverlayWindowClaim.Conflicting(record.window)
            }

            AppRestOverlayWindowPhase.REMOVING,
            AppRestOverlayWindowPhase.SETTLING ->
                AppRestOverlayWindowClaim.WaitingForRelease
        }
    }

    @Synchronized
    fun reserve(owner: O, window: W): Boolean {
        if (active != null) return false
        active = Record(
            window = window,
            owner = owner,
            phase = AppRestOverlayWindowPhase.ATTACHING
        )
        return true
    }

    @Synchronized
    fun markActive(window: W): Boolean {
        val record = active ?: return false
        if (record.window !== window) return false
        if (record.phase == AppRestOverlayWindowPhase.ATTACHING) {
            record.phase = AppRestOverlayWindowPhase.ACTIVE
        }
        return record.phase == AppRestOverlayWindowPhase.ACTIVE
    }

    @Synchronized
    fun ownedWindow(owner: O): W? = active
        ?.takeIf { record -> record.owner === owner }
        ?.window

    @Synchronized
    fun beginRemoval(owner: O, window: W): Boolean {
        val record = active ?: return false
        if (
            record.window !== window ||
            record.owner !== owner ||
            record.phase !in setOf(
                AppRestOverlayWindowPhase.ATTACHING,
                AppRestOverlayWindowPhase.ACTIVE
            )
        ) {
            return false
        }
        record.phase = AppRestOverlayWindowPhase.REMOVING
        return true
    }

    @Synchronized
    fun forceBeginRemoval(window: W): Boolean {
        val record = active ?: return false
        if (
            record.window !== window ||
            record.phase == AppRestOverlayWindowPhase.SETTLING
        ) {
            return false
        }
        record.phase = AppRestOverlayWindowPhase.REMOVING
        return true
    }

    /**
     * 已脱离的窗口在系统窗口事务收敛期间可能意外挂回。只有这个明确的重挂载路径
     * 可以把 SETTLING 恢复为 REMOVING；普通 hideAny 不能打断收敛期。
     */
    @Synchronized
    fun resumeRemovalAfterReattach(window: W): Boolean {
        val record = active ?: return false
        if (
            record.window !== window ||
            record.phase != AppRestOverlayWindowPhase.SETTLING
        ) {
            return false
        }
        record.phase = AppRestOverlayWindowPhase.REMOVING
        return true
    }

    @Synchronized
    fun markSettling(window: W): Boolean {
        val record = active ?: return false
        if (record.window !== window) return false
        record.phase = AppRestOverlayWindowPhase.SETTLING
        return true
    }

    @Synchronized
    fun release(window: W): Boolean {
        val record = active ?: return false
        if (
            record.window !== window ||
            record.phase != AppRestOverlayWindowPhase.SETTLING
        ) {
            return false
        }
        active = null
        return true
    }

    @Synchronized
    fun phaseOf(window: W): AppRestOverlayWindowPhase? = active
        ?.takeIf { record -> record.window === window }
        ?.phase

    @Synchronized
    fun anyWindow(): W? = active?.window

    @Synchronized
    fun hasRegisteredWindow(): Boolean = active != null

    private data class Record<W : Any, O : Any>(
        val window: W,
        var owner: O,
        var phase: AppRestOverlayWindowPhase
    )
}

internal class AppRestOverlayWindowHandle(val rootView: View)

internal data class AppRestOverlayWindowSpec(
    val handle: AppRestOverlayWindowHandle,
    val layoutParams: WindowManager.LayoutParams
)

/**
 * Android 窗口注册表必须跨 AppSupervisionService 实例存活，避免旧 Controller 丢失引用后
 * 新实例叠加第二个全屏悬浮窗。
 */
internal object AppRestOverlayWindowRegistry {
    private const val REMOVAL_MAX_FAST_ATTEMPTS = 8
    private const val REMOVAL_RETRY_DELAY_MILLIS = 250L
    private const val REMOVAL_RETRY_COOLDOWN_MILLIS = 4_000L
    private const val DETACH_SETTLEMENT_MILLIS = 500L

    private val handler = Handler(Looper.getMainLooper())
    private val ownership =
        AppRestOverlayOwnershipCoordinator<RegisteredWindow, Any>()

    fun show(
        owner: Any,
        windowManager: WindowManager,
        createWindow: () -> AppRestOverlayWindowSpec,
        canAdoptWindow: (AppRestOverlayWindowHandle) -> Boolean,
        updateWindow: (AppRestOverlayWindowHandle) -> Unit
    ): OverlayShowResult {
        repeat(2) {
            when (val claim = ownership.claim(owner) { registered ->
                canAdoptWindow(registered.handle)
            }) {
                is AppRestOverlayWindowClaim.Existing -> {
                    val registered = claim.window
                    if (
                        ownership.phaseOf(registered) == AppRestOverlayWindowPhase.ACTIVE &&
                        !registered.handle.rootView.isAttachedToWindow
                    ) {
                        beginSettlement(registered)
                        return OverlayShowResult.DEFERRED
                    }
                    updateWindow(registered.handle)
                    return OverlayShowResult.SHOWN
                }

                is AppRestOverlayWindowClaim.Conflicting -> {
                    if (ownership.forceBeginRemoval(claim.window)) {
                        attemptRemoval(claim.window)
                    }
                    return OverlayShowResult.DEFERRED
                }

                AppRestOverlayWindowClaim.WaitingForRelease ->
                    return OverlayShowResult.DEFERRED

                AppRestOverlayWindowClaim.Available -> {
                    val spec = try {
                        createWindow()
                    } catch (_: RuntimeException) {
                        return OverlayShowResult.WINDOW_ERROR
                    }
                    val registered = RegisteredWindow(spec, windowManager)
                    if (!ownership.reserve(owner, registered)) return@repeat
                    registerAttachmentListener(registered)
                    return attachReservedWindow(registered, updateWindow)
                }
            }
        }
        return OverlayShowResult.DEFERRED
    }

    fun isShowing(owner: Any): Boolean =
        ownership.ownedWindow(owner)?.let { registered ->
            ownership.phaseOf(registered) in setOf(
                AppRestOverlayWindowPhase.ATTACHING,
                AppRestOverlayWindowPhase.ACTIVE
            )
        } == true

    fun hasRegisteredWindow(): Boolean = ownership.hasRegisteredWindow()

    /** 仅测试/诊断使用：返回进程注册表当前持有的窗口数量（设计上只能为 0 或 1）。 */
    fun registeredWindowCount(): Int = if (ownership.hasRegisteredWindow()) 1 else 0

    fun hide(owner: Any) {
        val registered = ownership.ownedWindow(owner) ?: return
        if (ownership.beginRemoval(owner, registered)) attemptRemoval(registered)
    }

    fun hideAny() {
        val registered = ownership.anyWindow() ?: return
        if (ownership.forceBeginRemoval(registered)) {
            attemptRemoval(registered)
        }
    }

    private fun attachReservedWindow(
        registered: RegisteredWindow,
        updateWindow: (AppRestOverlayWindowHandle) -> Unit
    ): OverlayShowResult = try {
        registered.windowManager.addView(
            registered.handle.rootView,
            registered.spec.layoutParams
        )
        registered.wasAddedSuccessfully = true
        // WindowManager.addView 成功即表示客户端已完成注册。部分系统要到下一次遍历才更新
        // isAttachedToWindow，不能把这一正常时序误判成移除并让监督窗口永远无法显示。
        handler.postDelayed(registered.attachmentTimeoutRunnable, ATTACHMENT_TIMEOUT_MILLIS)
        updateWindow(registered.handle)
        OverlayShowResult.SHOWN
    } catch (_: RuntimeException) {
        if (registered.handle.rootView.isAttachedToWindow) {
            ownership.markActive(registered)
            ownership.forceBeginRemoval(registered)
            attemptRemoval(registered)
        } else {
            beginSettlement(registered)
        }
        OverlayShowResult.WINDOW_ERROR
    }

    private fun registerAttachmentListener(registered: RegisteredWindow) {
        val listener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                handler.removeCallbacks(registered.attachmentTimeoutRunnable)
                if (!ownership.markActive(registered)) {
                    val shouldRemove =
                        ownership.resumeRemovalAfterReattach(registered) ||
                            ownership.forceBeginRemoval(registered)
                    if (shouldRemove) handler.post { attemptRemoval(registered) }
                }
            }

            override fun onViewDetachedFromWindow(view: View) {
                handler.post { beginSettlement(registered) }
            }
        }
        registered.attachmentListener = listener
        registered.handle.rootView.addOnAttachStateChangeListener(listener)
    }

    private fun attemptRemoval(registered: RegisteredWindow) {
        if (ownership.phaseOf(registered) != AppRestOverlayWindowPhase.REMOVING) return
        val view = registered.handle.rootView
        handler.removeCallbacks(registered.attachmentTimeoutRunnable)
        if (!registered.wasAddedSuccessfully && !view.isAttachedToWindow) {
            beginSettlement(registered)
            return
        }

        registered.removalAttemptCount++
        var removalAccepted = false
        try {
            registered.windowManager.removeViewImmediate(view)
            removalAccepted = true
        } catch (_: RuntimeException) {
            // 系统窗口事务正在切换时继续持有进程级引用，稍后重试，绝不创建第二层。
        }
        if (!view.isAttachedToWindow && removalAccepted) {
            beginSettlement(registered)
            return
        }

        val retryDelayMillis = if (
            registered.removalAttemptCount >= REMOVAL_MAX_FAST_ATTEMPTS
        ) {
            registered.removalAttemptCount = 0
            REMOVAL_RETRY_COOLDOWN_MILLIS
        } else {
            REMOVAL_RETRY_DELAY_MILLIS
        }
        handler.removeCallbacks(registered.removalRunnable)
        handler.postDelayed(registered.removalRunnable, retryDelayMillis)
    }

    private fun beginSettlement(registered: RegisteredWindow) {
        if (registered.handle.rootView.isAttachedToWindow) {
            if (ownership.forceBeginRemoval(registered)) {
                handler.post { attemptRemoval(registered) }
            }
            return
        }
        if (!ownership.markSettling(registered)) return
        handler.removeCallbacks(registered.attachmentTimeoutRunnable)
        handler.removeCallbacks(registered.removalRunnable)
        handler.removeCallbacks(registered.settlementRunnable)
        handler.postDelayed(registered.settlementRunnable, DETACH_SETTLEMENT_MILLIS)
    }

    private fun finishSettlement(registered: RegisteredWindow) {
        if (registered.handle.rootView.isAttachedToWindow) {
            if (ownership.resumeRemovalAfterReattach(registered)) {
                handler.post { attemptRemoval(registered) }
            }
            return
        }
        if (!ownership.release(registered)) return
        handler.removeCallbacks(registered.removalRunnable)
        registered.attachmentListener?.let(
            registered.handle.rootView::removeOnAttachStateChangeListener
        )
        registered.attachmentListener = null
    }

    private class RegisteredWindow(
        val spec: AppRestOverlayWindowSpec,
        val windowManager: WindowManager
    ) {
        val handle: AppRestOverlayWindowHandle = spec.handle
        var attachmentListener: View.OnAttachStateChangeListener? = null
        var wasAddedSuccessfully: Boolean = false
        var removalAttemptCount: Int = 0
        val attachmentTimeoutRunnable = Runnable {
            if (
                ownership.phaseOf(this) == AppRestOverlayWindowPhase.ATTACHING &&
                ownership.forceBeginRemoval(this)
            ) {
                attemptRemoval(this)
            }
        }
        val removalRunnable = Runnable { attemptRemoval(this) }
        val settlementRunnable = Runnable { finishSettlement(this) }
    }

    private const val ATTACHMENT_TIMEOUT_MILLIS = 2_000L
}
