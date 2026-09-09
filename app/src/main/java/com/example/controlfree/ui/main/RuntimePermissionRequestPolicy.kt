package com.example.controlfree.ui.main

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

/** 记录运行时权限是否已经发起过系统请求，用于识别“不再询问”。 */
class RuntimePermissionRequestHistory(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun wasRequested(permission: String): Boolean =
        preferences.getBoolean(requestKey(permission), false)

    fun markRequested(permission: String) {
        preferences.edit().putBoolean(requestKey(permission), true).apply()
    }

    fun clearRequested(permission: String) {
        preferences.edit().remove(requestKey(permission)).apply()
    }

    private fun requestKey(permission: String) = "requested_$permission"

    private companion object {
        const val PREFERENCES_NAME = "runtime_permission_request_history"
    }
}

/**
 * 首次请求或系统仍允许解释时继续使用权限弹窗；已经请求过且系统不再提供解释时，
 * 只能引导到应用详情页恢复权限。
 */
fun shouldOpenApplicationPermissionSettings(
    wasRequested: Boolean,
    shouldShowRationale: Boolean
): Boolean = wasRequested && !shouldShowRationale

fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        val next = current.baseContext
        if (next === current) return null
        current = next
    }
    return current as? Activity
}
