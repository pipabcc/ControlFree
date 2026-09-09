package com.example.controlfree

import com.example.controlfree.security.VerificationResult
import com.example.controlfree.security.VerificationStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class LockUiPolicyTest {
    @Test
    fun `锁屏顶部日期时间按月日星期和分钟单行显示`() {
        val zoneId = ZoneId.of("Asia/Shanghai")
        val epochMillis = LocalDateTime.of(2026, 8, 4, 14, 30, 59)
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()

        assertEquals("8月4日 星期二 14:30", formatLockDateTime(epochMillis, zoneId))
    }

    @Test
    fun `锁屏底部操作使用稳定紧凑尺寸并远离屏幕底部`() {
        assertEquals(56, LOCK_ACTION_BUTTON_SIZE_DP)
        assertEquals(24, LOCK_ACTION_ICON_SIZE_DP)
        assertEquals(24, LOCK_ACTION_BUTTON_SPACING_DP)
        assertEquals(28, LOCK_ACTION_BOTTOM_GAP_DP)
    }

    @Test
    fun `可用APP固定四列且末行补齐空槽`() {
        assertEquals(4, LOCK_ALLOWED_APP_COLUMNS)
        assertEquals(0, resolveAllowedAppGridSlotCount(0))
        assertEquals(4, resolveAllowedAppGridSlotCount(1))
        assertEquals(4, resolveAllowedAppGridSlotCount(4))
        assertEquals(8, resolveAllowedAppGridSlotCount(5))
        assertEquals(8, resolveAllowedAppGridSlotCount(8))
        assertEquals(12, resolveAllowedAppGridSlotCount(9))
    }

    @Test
    fun `可用APP图标在极窄四列网格内自动收缩`() {
        assertEquals(42, resolveAllowedAppIconSizeDp(contentWidthDp = 312))
        assertEquals(
            39,
            resolveAllowedAppIconSizeDp(contentWidthDp = 184, interColumnSpacingDp = 4)
        )
        assertEquals(
            33,
            resolveAllowedAppIconSizeDp(contentWidthDp = 160, interColumnSpacingDp = 4)
        )
    }

    @Test
    fun `白名单卡片宽度复用图一页边距并限制最大宽度`() {
        assertEquals(312, resolveAllowedAppsPanelWidthDp(screenWidthDp = 360))
        assertEquals(560, resolveAllowedAppsPanelWidthDp(screenWidthDp = 1_000))
        assertEquals(1, resolveAllowedAppsPanelWidthDp(screenWidthDp = 40))
    }

    @Test
    fun `白名单卡片随应用行数紧凑增长`() {
        assertEquals(
            168,
            resolveAllowedAppsPanelHeightDp(appCount = 0, screenHeightDp = 720, rowHeightDp = 84)
        )
        assertEquals(
            180,
            resolveAllowedAppsPanelHeightDp(appCount = 1, screenHeightDp = 720, rowHeightDp = 84)
        )
        assertEquals(
            180,
            resolveAllowedAppsPanelHeightDp(appCount = 4, screenHeightDp = 720, rowHeightDp = 84)
        )
        assertEquals(
            272,
            resolveAllowedAppsPanelHeightDp(appCount = 5, screenHeightDp = 720, rowHeightDp = 84)
        )
        assertEquals(
            272,
            resolveAllowedAppsPanelHeightDp(appCount = 8, screenHeightDp = 720, rowHeightDp = 84)
        )
    }

    @Test
    fun `白名单卡片内容过多时受屏幕可用高度限制`() {
        assertEquals(
            672,
            resolveAllowedAppsPanelHeightDp(appCount = 40, screenHeightDp = 720, rowHeightDp = 84)
        )
        assertEquals(
            672,
            resolveAllowedAppsPanelHeightDp(
                appCount = Int.MAX_VALUE,
                screenHeightDp = 720,
                rowHeightDp = Int.MAX_VALUE
            )
        )
        assertEquals(
            52,
            resolveAllowedAppsPanelHeightDp(appCount = 8, screenHeightDp = 100, rowHeightDp = 108)
        )
        assertEquals(
            168,
            resolveAllowedAppsPanelHeightDp(appCount = -1, screenHeightDp = 720, rowHeightDp = 84)
        )
    }

    @Test
    fun `普通监督提示亮屏暂停熄屏继续计时`() {
        assertEquals(
            "亮屏暂停倒计时，熄屏后继续",
            lockCountdownRuleText(MonitorSessionMode.SUPERVISION)
        )
    }

    @Test
    fun `专注锁定提示亮屏熄屏持续计时`() {
        assertEquals(
            "专注模式亮屏与熄屏均持续倒计时",
            lockCountdownRuleText(MonitorSessionMode.FOCUS)
        )
    }

    @Test
    fun `锁定标题优先使用具体任务名称并规范首尾空白`() {
        assertEquals(
            "晚间学习 · 锁定中",
            lockStatusTitle("  晚间学习  ", MonitorSessionMode.FOCUS)
        )
    }

    @Test
    fun `锁定标题在任务名称缺失时按会话类型区分`() {
        assertEquals(
            "普通监督任务 · 锁定中",
            lockStatusTitle(null, MonitorSessionMode.SUPERVISION)
        )
        assertEquals(
            "专注任务 · 锁定中",
            lockStatusTitle("   ", MonitorSessionMode.FOCUS)
        )
    }

    @Test
    fun `旧启动命令的内部计划编号不会显示在锁定标题中`() {
        assertEquals(
            "普通监督任务",
            resolveRuntimeLockTaskTitle(
                taskTitle = "plan-internal-42",
                internalPlanId = "plan-internal-42",
                sessionMode = MonitorSessionMode.SUPERVISION
            )
        )
        assertEquals(
            "晚间学习",
            resolveRuntimeLockTaskTitle(
                taskTitle = "晚间学习",
                internalPlanId = "plan-internal-42",
                sessionMode = MonitorSessionMode.FOCUS
            )
        )
    }

    @Test
    fun `应急解锁反馈覆盖成功失败和限流`() {
        assertEquals(
            "验证成功，正在解除锁定",
            lockVerificationMessage(VerificationResult(VerificationStatus.SUCCESS))
        )
        assertEquals(
            "验证失败，请重试",
            lockVerificationMessage(VerificationResult(VerificationStatus.FAILURE))
        )
        assertEquals(
            "尝试次数过多，请 30 秒后重试",
            lockVerificationMessage(
                VerificationResult(VerificationStatus.LOCKED, retryAfterSeconds = 30)
            )
        )
    }

    @Test
    fun `认证成功反馈与不可变待执行动作一致`() {
        assertEquals(
            "验证通过，等待系统确认暂停",
            lockActionVerificationMessage(
                LockPendingAction.pause(12),
                VerificationResult(VerificationStatus.SUCCESS)
            )
        )
        assertEquals(
            "验证通过，等待系统确认跳过",
            lockActionVerificationMessage(
                LockPendingAction.Skip,
                VerificationResult(VerificationStatus.SUCCESS)
            )
        )
    }
}
