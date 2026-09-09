package com.example.controlfree.runtime

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings

enum class UsageAccessSettingsDestination {
    APP_SPECIFIC,
    APP_LIST,
    UNAVAILABLE
}

internal enum class UsageAccessSettingsAttempt {
    APP_SPECIFIC,
    APP_LIST
}

/**
 * 打开系统“使用情况访问”设置。
 *
 * 优先通过 package URI 请求当前应用的专属页面；部分旧系统或定制系统不支持时，
 * 自动回退到通用应用列表。这里只使用 Android 公共 Settings Action，不绑定厂商组件。
 */
object UsageAccessSettingsNavigator {
    fun open(
        packageName: String,
        launchIntent: (Intent) -> Unit
    ): UsageAccessSettingsDestination = openWithFallback { attempt ->
        try {
            launchIntent(createIntent(packageName, attempt))
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    internal fun openWithFallback(
        openAttempt: (UsageAccessSettingsAttempt) -> Boolean
    ): UsageAccessSettingsDestination {
        UsageAccessSettingsAttempt.entries.forEach { attempt ->
            if (openAttempt(attempt)) {
                return when (attempt) {
                    UsageAccessSettingsAttempt.APP_SPECIFIC ->
                        UsageAccessSettingsDestination.APP_SPECIFIC
                    UsageAccessSettingsAttempt.APP_LIST ->
                        UsageAccessSettingsDestination.APP_LIST
                }
            }
        }
        return UsageAccessSettingsDestination.UNAVAILABLE
    }

    private fun createIntent(
        packageName: String,
        attempt: UsageAccessSettingsAttempt
    ): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
        if (attempt == UsageAccessSettingsAttempt.APP_SPECIFIC) {
            data = Uri.fromParts("package", packageName, null)
        }
    }
}
