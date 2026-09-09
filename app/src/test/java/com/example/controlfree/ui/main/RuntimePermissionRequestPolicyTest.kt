package com.example.controlfree.ui.main

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimePermissionRequestPolicyTest {
    @Test
    fun `首次请求仍使用系统权限弹窗`() {
        assertFalse(
            shouldOpenApplicationPermissionSettings(
                wasRequested = false,
                shouldShowRationale = false
            )
        )
    }

    @Test
    fun `普通拒绝后系统仍允许再次请求`() {
        assertFalse(
            shouldOpenApplicationPermissionSettings(
                wasRequested = true,
                shouldShowRationale = true
            )
        )
    }

    @Test
    fun `永久拒绝后进入应用权限详情页`() {
        assertTrue(
            shouldOpenApplicationPermissionSettings(
                wasRequested = true,
                shouldShowRationale = false
            )
        )
    }

    @Test
    fun `授权成功清除历史后再次被系统撤权可重新请求`() {
        assertFalse(
            shouldOpenApplicationPermissionSettings(
                wasRequested = false,
                shouldShowRationale = false
            )
        )
    }
}
