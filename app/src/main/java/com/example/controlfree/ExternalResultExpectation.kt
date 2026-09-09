package com.example.controlfree

import android.os.SystemClock

/**
 * 应用内主动跳转系统相机、相册、文件选择器等外部界面时登记预期。
 * 返回本应用时若预期仍在有效窗口内，则不清除本次前台认证会话，
 * 避免拍照返回后被应用锁拦截。窗口过期或进程重建后仍正常要求验证。
 */
object ExternalResultExpectation {
    private const val VALID_WINDOW_MILLIS = 10L * 60_000L

    @Volatile
    private var expectedAtElapsedMillis: Long = Long.MIN_VALUE

    fun expect(nowElapsedMillis: Long = SystemClock.elapsedRealtime()) {
        expectedAtElapsedMillis = nowElapsedMillis
    }

    fun isActive(nowElapsedMillis: Long = SystemClock.elapsedRealtime()): Boolean {
        val elapsed = nowElapsedMillis - expectedAtElapsedMillis
        return elapsed in 0..VALID_WINDOW_MILLIS
    }

    fun clear() {
        expectedAtElapsedMillis = Long.MIN_VALUE
    }
}
