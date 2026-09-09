package com.example.controlfree.supervision.app

import com.example.controlfree.data.ForegroundEventKind
import com.example.controlfree.data.ForegroundObservation

/**
 * 记录用户通过 Home/最近任务离开阻止页后的临时界面状态。
 *
 * 这里只抑制旧观察结果重复显示悬浮层，不修改额度、禁用时段或可信阻止状态。目标 App
 * 再次产生新的前台事件后立即解除抑制，后续监督仍按原规则执行。
 */
data class AppSupervisionOverlayDismissal(
    val rule: AppSupervisionRule,
    val dismissedAtEpochMillis: Long
) {
    init {
        require(dismissedAtEpochMillis >= 0L) { "悬浮层退出时间无效" }
    }
}

object AppSupervisionOverlayDismissalPolicy {
    fun dismiss(
        blocked: BlockedAppSupervision,
        nowEpochMillis: Long
    ): AppSupervisionOverlayDismissal {
        require(nowEpochMillis >= 0L) { "当前墙钟无效" }
        return AppSupervisionOverlayDismissal(
            rule = blocked.rule,
            dismissedAtEpochMillis = nowEpochMillis
        )
    }

    /**
     * 其他前台包只证明用户已经离开目标 App，不能立即销毁退出令牌：服务仍可能随后收到
     * 一轮旧目标状态。目标 App 只有在退出时间之后产生新的前台切换才算重新进入，查询
     * 重叠窗口回放的旧事件不能提前解除抑制。
     *
     * UsageEvents 查询与服务回调是异步的：新的前台事件可能已经在上一轮被 reducer
     * 归并，当前轮 newEvents 因去重而为空。因此除了检查本轮新事件，还必须接受
     * currentPackageName 与 transitionTimestampMillis 给出的同一份已归并真值。
     */
    fun reconcile(
        dismissal: AppSupervisionOverlayDismissal?,
        observation: ForegroundObservation
    ): AppSupervisionOverlayDismissal? {
        dismissal ?: return null
        if (!observation.isAvailable) return dismissal

        val targetPackage = dismissal.rule.packageName
        val latestTargetForegroundEvent = observation.newEvents.asSequence()
            .filter { event ->
                event.kind == ForegroundEventKind.FOREGROUND &&
                    event.packageName == targetPackage
            }
            .maxOfOrNull { event -> event.timestampMillis }
        val hasConfirmedCurrentTargetReentry =
            observation.packageName == targetPackage &&
                observation.transitionTimestampMillis >= dismissal.dismissedAtEpochMillis

        return if (
            hasConfirmedCurrentTargetReentry ||
            (
                latestTargetForegroundEvent != null &&
                    latestTargetForegroundEvent >= dismissal.dismissedAtEpochMillis
                )
        ) {
            null
        } else {
            dismissal
        }
    }

    /**
     * 全屏悬浮层可能收不到系统的 Home 广播，因此以前台转场作为可靠事实来源。
     * 只有悬浮层显示之后完成的新查询明确确认其他应用在前台时才允许关闭。前台转场
     * 可能早于悬浮层显示（例如桌面已在悬浮层下方恢复），因此不能要求转场时间也晚于
     * 显示时间；但旧查询、空前台和查询失败都继续保持阻止层。
     */
    fun shouldDismissVisibleOverlay(
        blocked: BlockedAppSupervision,
        shownAtEpochMillis: Long,
        observation: ForegroundObservation
    ): Boolean {
        require(shownAtEpochMillis >= 0L) { "悬浮层显示时间无效" }
        if (!observation.isAvailable || shownAtEpochMillis == 0L) return false
        val foregroundPackage = observation.packageName ?: return false
        return foregroundPackage != blocked.rule.packageName &&
            observation.observedAtEpochMillis >= shownAtEpochMillis
    }

    fun shouldSuppress(
        dismissal: AppSupervisionOverlayDismissal?,
        blocked: BlockedAppSupervision
    ): Boolean = dismissal?.rule == blocked.rule
}
