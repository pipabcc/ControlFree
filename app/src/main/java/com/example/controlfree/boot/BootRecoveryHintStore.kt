package com.example.controlfree.boot

import android.content.Context
import android.content.SharedPreferences

/**
 * 仅保存 Direct Boot 阶段需要的最小恢复提示，不包含监督时长、任务名称或应用列表。
 *
 * 提示位不是监督状态的权威来源。用户解锁后仍必须读取凭据加密存储中的完整快照；
 * 提示损坏或过期时最多只会多运行一次轻量恢复协调，不得据此直接建立监督状态。
 */
data class BootRecoveryHint(
    val monitorRecoveryRequired: Boolean,
    val appSupervisionRecoveryRequired: Boolean,
    val readFailed: Boolean = false
) {
    val runtimeRecoveryRequired: Boolean
        get() = monitorRecoveryRequired || appSupervisionRecoveryRequired || readFailed
}

class BootRecoveryHintStore(context: Context) {
    private val preferences: SharedPreferences = context.applicationContext
        .createDeviceProtectedStorageContext()
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun read(): BootRecoveryHint = try {
        BootRecoveryHint(
            monitorRecoveryRequired = preferences.getBoolean(KEY_MONITOR_REQUIRED, false),
            appSupervisionRecoveryRequired =
                preferences.getBoolean(KEY_APP_SUPERVISION_REQUIRED, false)
        )
    } catch (_: RuntimeException) {
        // 无法确认提示时按需要恢复处理，解锁后的权威状态读取仍会阻止凭空启动监督。
        BootRecoveryHint(
            monitorRecoveryRequired = true,
            appSupervisionRecoveryRequired = true,
            readFailed = true
        )
    }

    @Synchronized
    fun setMonitorRecoveryRequired(required: Boolean): Boolean =
        updateBoolean(KEY_MONITOR_REQUIRED, required)

    @Synchronized
    fun setAppSupervisionRecoveryRequired(required: Boolean): Boolean =
        updateBoolean(KEY_APP_SUPERVISION_REQUIRED, required)

    @Synchronized
    fun replaceRuntimeHints(
        monitorRecoveryRequired: Boolean,
        appSupervisionRecoveryRequired: Boolean
    ): Boolean = try {
        preferences.edit()
            .putBoolean(KEY_MONITOR_REQUIRED, monitorRecoveryRequired)
            .putBoolean(KEY_APP_SUPERVISION_REQUIRED, appSupervisionRecoveryRequired)
            .commit()
    } catch (_: RuntimeException) {
        false
    }

    private fun updateBoolean(key: String, value: Boolean): Boolean = try {
        preferences.edit().putBoolean(key, value).commit()
    } catch (_: RuntimeException) {
        false
    }

    private companion object {
        const val PREFERENCES_NAME = "boot_recovery_hints_v1"
        const val KEY_MONITOR_REQUIRED = "monitor_required"
        const val KEY_APP_SUPERVISION_REQUIRED = "app_supervision_required"
    }
}
