package com.example.controlfree

/**
 * 判断关闭系统对话框的原因是否表示用户已离开白名单应用。
 *
 * Android 广播解析保留在接收边界，本策略保持纯 Kotlin，便于 JVM 单元测试。
 */
object SystemDialogRelockPolicy {
    const val REASON_HOME_KEY = "homekey"
    const val REASON_RECENT_APPS = "recentapps"

    fun shouldRelock(reason: String?): Boolean = when (reason) {
        REASON_HOME_KEY,
        REASON_RECENT_APPS -> true
        else -> false
    }
}
