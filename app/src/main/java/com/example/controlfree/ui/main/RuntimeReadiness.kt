package com.example.controlfree.ui.main

import android.content.Context
import com.example.controlfree.MonitorNotificationChannels
import com.example.controlfree.data.UsageAccessManager
import com.example.controlfree.runtime.readMonitorRuntimeCapabilities

enum class RuntimeRequirementKey {
    USAGE_ACCESS,
    OVERLAY,
    BACKGROUND_POPUP,
    PHONE_STATE,
    NOTIFICATION_PERMISSION,
    NOTIFICATIONS_ENABLED,
    SERVICE_CHANNEL,
    LOCK_RECOVERY_CHANNEL,
    FULL_SCREEN_INTENT,
    EXACT_ALARM,
    BATTERY_UNRESTRICTED
}

internal fun RuntimeRequirementKey.isNotificationDeliveryRequirement(): Boolean = when (this) {
    RuntimeRequirementKey.NOTIFICATION_PERMISSION,
    RuntimeRequirementKey.NOTIFICATIONS_ENABLED,
    RuntimeRequirementKey.SERVICE_CHANNEL,
    RuntimeRequirementKey.LOCK_RECOVERY_CHANNEL -> true
    else -> false
}

internal fun notificationChannelIdForRequirement(key: RuntimeRequirementKey): String? = when (key) {
    RuntimeRequirementKey.SERVICE_CHANNEL -> MonitorNotificationChannels.SERVICE_CHANNEL_ID
    RuntimeRequirementKey.LOCK_RECOVERY_CHANNEL ->
        MonitorNotificationChannels.LOCK_RECOVERY_CHANNEL_ID
    else -> null
}

enum class RuntimeRequirementState {
    READY,
    ACTION_REQUIRED,
    RECOMMENDED,
    NOT_APPLICABLE
}

data class RuntimeRequirement(
    val key: RuntimeRequirementKey,
    val title: String,
    val purpose: String,
    val state: RuntimeRequirementState,
    val blocksStart: Boolean
) {
    val needsAction: Boolean
        get() = state == RuntimeRequirementState.ACTION_REQUIRED ||
            state == RuntimeRequirementState.RECOMMENDED
}

data class RuntimeReadiness(
    val requirements: List<RuntimeRequirement>
) {
    val blockers: List<RuntimeRequirement>
        get() = requirements.filter { it.blocksStart && it.state == RuntimeRequirementState.ACTION_REQUIRED }

    val canStart: Boolean
        get() = blockers.isEmpty()

    /** App 独立监督不进入全局锁定，也不依赖电话状态或锁定恢复入口。 */
    fun forAppSupervision(): RuntimeReadiness = RuntimeReadiness(
        requirements = requirements.filter { requirement ->
            requirement.key in APP_SUPERVISION_REQUIREMENTS
        }
    )

    private companion object {
        val APP_SUPERVISION_REQUIREMENTS = setOf(
            RuntimeRequirementKey.USAGE_ACCESS,
            RuntimeRequirementKey.OVERLAY,
            RuntimeRequirementKey.BACKGROUND_POPUP,
            RuntimeRequirementKey.NOTIFICATION_PERMISSION,
            RuntimeRequirementKey.NOTIFICATIONS_ENABLED,
            RuntimeRequirementKey.SERVICE_CHANNEL,
            RuntimeRequirementKey.EXACT_ALARM,
            RuntimeRequirementKey.BATTERY_UNRESTRICTED
        )
    }
}

data class RuntimeReadinessSignals(
    val usageAccessGranted: Boolean,
    val overlayGranted: Boolean,
    val backgroundPopupGranted: Boolean,
    val telephonySupported: Boolean,
    val phoneStateGranted: Boolean,
    val notificationRuntimeGranted: Boolean,
    val notificationsEnabled: Boolean,
    val notificationChannelsSupported: Boolean,
    val serviceChannelEnabled: Boolean,
    val lockRecoveryChannelEnabled: Boolean,
    val fullScreenIntentRequired: Boolean,
    val fullScreenIntentGranted: Boolean,
    val exactAlarmRequired: Boolean,
    val exactAlarmGranted: Boolean,
    val batteryUnrestricted: Boolean
)

fun evaluateRuntimeReadiness(signals: RuntimeReadinessSignals): RuntimeReadiness {
    fun requiredState(granted: Boolean) = if (granted) {
        RuntimeRequirementState.READY
    } else {
        RuntimeRequirementState.ACTION_REQUIRED
    }

    val phoneState = if (!signals.telephonySupported) {
        RuntimeRequirementState.NOT_APPLICABLE
    } else {
        requiredState(signals.phoneStateGranted)
    }
    val fullScreenIntent = if (!signals.fullScreenIntentRequired) {
        RuntimeRequirementState.NOT_APPLICABLE
    } else if (signals.fullScreenIntentGranted) {
        RuntimeRequirementState.READY
    } else {
        // Android 14+ 可能依据系统政策拒绝该特殊权限；它是备用增强，不能阻断主悬浮锁层。
        RuntimeRequirementState.RECOMMENDED
    }
    val serviceChannel = if (!signals.notificationChannelsSupported) {
        RuntimeRequirementState.NOT_APPLICABLE
    } else {
        requiredState(signals.serviceChannelEnabled)
    }
    val lockRecoveryChannel = if (!signals.notificationChannelsSupported) {
        RuntimeRequirementState.NOT_APPLICABLE
    } else if (signals.lockRecoveryChannelEnabled) {
        RuntimeRequirementState.READY
    } else {
        RuntimeRequirementState.RECOMMENDED
    }
    val exactAlarm = if (!signals.exactAlarmRequired) {
        RuntimeRequirementState.NOT_APPLICABLE
    } else if (signals.exactAlarmGranted) {
        RuntimeRequirementState.READY
    } else {
        RuntimeRequirementState.RECOMMENDED
    }

    return RuntimeReadiness(
        requirements = listOfNotNull(
            RuntimeRequirement(
                RuntimeRequirementKey.USAGE_ACCESS,
                "使用情况访问",
                "识别白名单应用离开并快速恢复锁定",
                requiredState(signals.usageAccessGranted),
                blocksStart = true
            ),
            RuntimeRequirement(
                RuntimeRequirementKey.OVERLAY,
                "悬浮显示",
                "在其他应用上层持续显示锁定界面",
                requiredState(signals.overlayGranted),
                blocksStart = true
            ),
            RuntimeRequirement(
                RuntimeRequirementKey.BATTERY_UNRESTRICTED,
                "后台运行保障",
                "允许关闭电池优化。华为/荣耀手机的鸿蒙系统还需要进入【应用启动管理】，关闭【自动管理】，并勾选【允许自启动】、【允许后台活动】、【允许关联启动】",
                if (signals.batteryUnrestricted) {
                    RuntimeRequirementState.READY
                } else {
                    RuntimeRequirementState.ACTION_REQUIRED
                },
                blocksStart = true
            ),
            RuntimeRequirement(
                RuntimeRequirementKey.BACKGROUND_POPUP,
                "后台弹窗",
                "允许在应用不在前台时弹出锁定界面",
                if (signals.backgroundPopupGranted) {
                    RuntimeRequirementState.READY
                } else {
                    RuntimeRequirementState.ACTION_REQUIRED
                },
                blocksStart = true
            ),
            RuntimeRequirement(
                RuntimeRequirementKey.PHONE_STATE,
                "电话状态",
                "只识别来电和通话状态，不读取号码或通话内容",
                phoneState,
                blocksStart = signals.telephonySupported
            ),
            RuntimeRequirement(
                RuntimeRequirementKey.NOTIFICATION_PERMISSION,
                "通知权限",
                "让前台监督服务持续运行",
                requiredState(signals.notificationRuntimeGranted),
                blocksStart = true
            ),
            if (signals.notificationRuntimeGranted) {
                RuntimeRequirement(
                    RuntimeRequirementKey.NOTIFICATIONS_ENABLED,
                    "应用通知总开关",
                    "确保监督状态和锁定恢复提示可见",
                    requiredState(signals.notificationsEnabled),
                    blocksStart = true
                )
            } else {
                null
            },
            RuntimeRequirement(
                RuntimeRequirementKey.SERVICE_CHANNEL,
                "监督服务通知频道",
                "显示当前阶段并维持前台服务",
                serviceChannel,
                blocksStart = signals.notificationChannelsSupported
            ),
            RuntimeRequirement(
                RuntimeRequirementKey.LOCK_RECOVERY_CHANNEL,
                "锁定恢复通知频道",
                "悬浮权限异常时提供备用锁定入口",
                lockRecoveryChannel,
                blocksStart = false
            ),
            RuntimeRequirement(
                RuntimeRequirementKey.FULL_SCREEN_INTENT,
                "全屏通知",
                "悬浮锁层失效时尝试展示备用锁页",
                fullScreenIntent,
                blocksStart = false
            ),
            RuntimeRequirement(
                RuntimeRequirementKey.EXACT_ALARM,
                "精确定时权限",
                "让定时监督尽量在设定分钟准时开始；未开启时使用系统延后执行模式",
                exactAlarm,
                blocksStart = false
            )
        )
    )
}

fun readRuntimeReadiness(
    context: Context,
    usageAccessManager: UsageAccessManager
): RuntimeReadiness {
    val capabilities = readMonitorRuntimeCapabilities(context, usageAccessManager)

    return evaluateRuntimeReadiness(
        RuntimeReadinessSignals(
            usageAccessGranted = capabilities.usageAccessGranted,
            overlayGranted = capabilities.overlayGranted,
            backgroundPopupGranted = capabilities.backgroundPopupGranted,
            telephonySupported = capabilities.telephonySupported,
            phoneStateGranted = capabilities.phoneStateGranted,
            notificationRuntimeGranted = capabilities.notificationRuntimeGranted,
            notificationsEnabled = capabilities.notificationsEnabled,
            notificationChannelsSupported = capabilities.notificationChannelsSupported,
            serviceChannelEnabled = capabilities.serviceChannelEnabled,
            lockRecoveryChannelEnabled = capabilities.lockRecoveryChannelEnabled,
            fullScreenIntentRequired = capabilities.fullScreenIntentRequired,
            fullScreenIntentGranted = capabilities.fullScreenIntentGranted,
            exactAlarmRequired = capabilities.exactAlarmRequired,
            exactAlarmGranted = capabilities.exactAlarmGranted,
            batteryUnrestricted = capabilities.batteryUnrestricted
        )
    )
}
