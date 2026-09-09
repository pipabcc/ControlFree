package com.example.controlfree

import com.example.controlfree.security.VerificationResult
import com.example.controlfree.security.VerificationStatus

internal const val LOCK_ALLOWED_APP_COLUMNS = 4
private const val LOCK_ALLOWED_APP_MAX_ICON_DP = 42
private const val LOCK_ALLOWED_APP_ITEM_HORIZONTAL_PADDING_DP = 4
internal const val LOCK_ALLOWED_APPS_PANEL_MARGIN_DP = 24
internal const val LOCK_ALLOWED_APPS_PANEL_MAX_WIDTH_DP = 560
private const val LOCK_ALLOWED_APPS_CARD_VERTICAL_PADDING_DP = 20
private const val LOCK_ALLOWED_APPS_HEADER_HEIGHT_DP = 48
private const val LOCK_ALLOWED_APPS_HEADER_SPACING_DP = 8
private const val LOCK_ALLOWED_APPS_ROW_SPACING_DP = 8
private const val LOCK_ALLOWED_APPS_EMPTY_CONTENT_HEIGHT_DP = 72
internal const val LOCK_ACTION_BUTTON_SIZE_DP = 56
internal const val LOCK_ACTION_ICON_SIZE_DP = 24
internal const val LOCK_ACTION_BUTTON_SPACING_DP = 24
internal const val LOCK_ACTION_BOTTOM_GAP_DP = 28

/**
 * 返回固定四列网格需要渲染的槽位数。末行补齐空槽，避免 APP 数量变化时列位漂移。
 */
internal fun resolveAllowedAppGridSlotCount(appCount: Int): Int {
    if (appCount <= 0) return 0
    return ((appCount + LOCK_ALLOWED_APP_COLUMNS - 1) / LOCK_ALLOWED_APP_COLUMNS) *
        LOCK_ALLOWED_APP_COLUMNS
}

/** 在极窄窗口中按四列单元格收缩图标，避免固定 42dp 图标被裁切。 */
internal fun resolveAllowedAppIconSizeDp(
    contentWidthDp: Int,
    interColumnSpacingDp: Int = 0
): Int {
    val totalSpacing = interColumnSpacingDp.coerceAtLeast(0) * (LOCK_ALLOWED_APP_COLUMNS - 1)
    val gridWidth = (contentWidthDp.coerceAtLeast(0) - totalSpacing)
        .coerceAtLeast(LOCK_ALLOWED_APP_COLUMNS)
    val cellWidth = gridWidth / LOCK_ALLOWED_APP_COLUMNS
    return (cellWidth - LOCK_ALLOWED_APP_ITEM_HORIZONTAL_PADDING_DP)
        .coerceIn(1, LOCK_ALLOWED_APP_MAX_ICON_DP)
}

/** 让 Compose 锁页和备用悬浮锁层使用相同的 24dp 页边距及 560dp 最大宽度。 */
internal fun resolveAllowedAppsPanelWidthDp(screenWidthDp: Int): Int =
    (screenWidthDp - LOCK_ALLOWED_APPS_PANEL_MARGIN_DP * 2)
        .coerceAtLeast(1)
        .coerceAtMost(LOCK_ALLOWED_APPS_PANEL_MAX_WIDTH_DP)

/**
 * 白名单卡片优先按内容紧凑包裹；应用较多时以屏幕可用高度为上限，内容交给滚动区承载。
 */
internal fun resolveAllowedAppsPanelHeightDp(
    appCount: Int,
    screenHeightDp: Int,
    rowHeightDp: Int
): Int {
    val safeAppCount = appCount.coerceAtLeast(0).toLong()
    val rowCount = if (safeAppCount == 0L) {
        0L
    } else {
        (safeAppCount + LOCK_ALLOWED_APP_COLUMNS - 1) / LOCK_ALLOWED_APP_COLUMNS
    }
    val gridHeightDp = if (rowCount == 0L) {
        LOCK_ALLOWED_APPS_EMPTY_CONTENT_HEIGHT_DP.toLong()
    } else {
        rowCount * rowHeightDp.coerceAtLeast(1).toLong() +
            (rowCount - 1) * LOCK_ALLOWED_APPS_ROW_SPACING_DP
    }
    val contentHeightDp = (LOCK_ALLOWED_APPS_CARD_VERTICAL_PADDING_DP * 2 +
        LOCK_ALLOWED_APPS_HEADER_HEIGHT_DP +
        LOCK_ALLOWED_APPS_HEADER_SPACING_DP).toLong() +
        gridHeightDp
    val availableHeightDp = (screenHeightDp - LOCK_ALLOWED_APPS_PANEL_MARGIN_DP * 2)
        .coerceAtLeast(1)
    return contentHeightDp.coerceAtMost(availableHeightDp.toLong()).toInt()
}

internal fun lockVerificationMessage(result: VerificationResult): String = when (result.status) {
    VerificationStatus.SUCCESS -> "验证成功，正在解除锁定"
    VerificationStatus.FAILURE -> "验证失败，请重试"
    VerificationStatus.LOCKED -> "尝试次数过多，请 ${result.retryAfterSeconds} 秒后重试"
}

internal fun lockActionVerificationMessage(
    action: LockPendingAction,
    result: VerificationResult
): String = when (result.status) {
    VerificationStatus.SUCCESS -> when (action.kind) {
        LockPendingActionKind.PAUSE -> "验证通过，等待系统确认暂停"
        LockPendingActionKind.SKIP -> "验证通过，等待系统确认跳过"
    }
    VerificationStatus.FAILURE -> "验证失败，请重试"
    VerificationStatus.LOCKED -> "尝试次数过多，请 ${result.retryAfterSeconds} 秒后重试"
}

internal fun lockCountdownRuleText(
    sessionMode: MonitorSessionMode
): String = when (sessionMode) {
    MonitorSessionMode.SUPERVISION -> "亮屏暂停倒计时，熄屏后继续"
    MonitorSessionMode.FOCUS -> "专注模式亮屏与熄屏均持续倒计时"
}

/**
 * 锁定页优先展示当前定时任务的真实名称；手动启动或名称暂不可用时，按会话模式
 * 给出可区分的稳定名称，避免把内部计划编号暴露给用户。
 */
internal fun resolveLockTaskTitle(
    taskTitle: String?,
    sessionMode: MonitorSessionMode
): String = taskTitle
    ?.trim()
    ?.takeIf(String::isNotEmpty)
    ?: when (sessionMode) {
        MonitorSessionMode.SUPERVISION -> "普通监督任务"
        MonitorSessionMode.FOCUS -> "专注任务"
    }

/**
 * 定时启动命令的旧版本可能用内部计划 ID 代替缺失名称。内部 ID 只用于所有权校验，
 * 不能进入用户界面；识别到该占位值时统一回退为可读的会话名称。
 */
internal fun resolveRuntimeLockTaskTitle(
    taskTitle: String?,
    internalPlanId: String?,
    sessionMode: MonitorSessionMode
): String = resolveLockTaskTitle(
    taskTitle = taskTitle?.takeUnless { title ->
        internalPlanId?.let { planId -> title.trim() == planId.trim() } == true
    },
    sessionMode = sessionMode
)

/** 使用真正位于字面中线的 U+00B7，保证 Compose 与备用 TextView 标题一致。 */
internal fun lockStatusTitle(
    taskTitle: String?,
    sessionMode: MonitorSessionMode
): String = "${resolveLockTaskTitle(taskTitle, sessionMode)} · 锁定中"
