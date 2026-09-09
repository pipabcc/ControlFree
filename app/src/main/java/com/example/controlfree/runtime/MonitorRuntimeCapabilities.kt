package com.example.controlfree.runtime

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.controlfree.MonitorNotificationChannels
import com.example.controlfree.data.UsageAccessManager

data class MonitorRuntimeCapabilities(
    val usageAccessGranted: Boolean,
    val overlayGranted: Boolean,
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
    val batteryUnrestricted: Boolean,
    val backgroundPopupGranted: Boolean
) {
    val canStartMonitor: Boolean
        get() = usageAccessGranted &&
            overlayGranted &&
            backgroundPopupGranted &&
            (!telephonySupported || phoneStateGranted) &&
            notificationRuntimeGranted &&
            notificationsEnabled &&
            (!notificationChannelsSupported || serviceChannelEnabled)

    val canStartAppSupervision: Boolean
        get() = usageAccessGranted &&
            overlayGranted &&
            backgroundPopupGranted &&
            notificationRuntimeGranted &&
            notificationsEnabled &&
            (!notificationChannelsSupported || serviceChannelEnabled)
}

fun readMonitorRuntimeCapabilities(
    context: Context,
    usageAccessManager: UsageAccessManager = UsageAccessManager(context.applicationContext)
): MonitorRuntimeCapabilities {
    val appContext = context.applicationContext
    try {
        MonitorNotificationChannels.ensureCreated(appContext)
    } catch (_: RuntimeException) {
        // 后续按实际读取结果报告能力缺失，避免厂商通知服务异常导致准备页崩溃。
    }
    val notificationManager = appContext.getSystemService(NotificationManager::class.java)
    val channelsSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
    val serviceChannelEnabled = if (channelsSupported) {
        notificationManager?.getNotificationChannel(MonitorNotificationChannels.SERVICE_CHANNEL_ID)
            ?.importance?.let { it != NotificationManager.IMPORTANCE_NONE } == true
    } else {
        true
    }
    val lockRecoveryChannelEnabled = if (channelsSupported) {
        notificationManager
            ?.getNotificationChannel(MonitorNotificationChannels.LOCK_RECOVERY_CHANNEL_ID)
            ?.importance?.let { it != NotificationManager.IMPORTANCE_NONE } == true
    } else {
        true
    }
    val fullScreenIntentRequired = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
    val fullScreenIntentGranted = if (fullScreenIntentRequired) {
        try {
            notificationManager?.canUseFullScreenIntent() == true
        } catch (_: RuntimeException) {
            false
        }
    } else {
        true
    }
    val exactAlarmRequired = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val exactAlarmGranted = if (exactAlarmRequired) {
        try {
            appContext.getSystemService(AlarmManager::class.java)
                ?.canScheduleExactAlarms() == true
        } catch (_: RuntimeException) {
            false
        }
    } else {
        true
    }
    val powerManager = appContext.getSystemService(PowerManager::class.java)
    val telephonySupported = try {
        appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
    } catch (_: RuntimeException) {
        true
    }
    val preferenceManager = com.example.controlfree.data.PreferenceManager(appContext)
    val backgroundPopupGranted = preferenceManager.isBackgroundPopupManuallyConfirmed() || try {
        val appOps = appContext.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        // 使用双重反射（元反射）绕过 Android 9+ 对隐藏 API 检查的拦截限制
        val getDeclaredMethod = Class::class.java.getDeclaredMethod(
            "getDeclaredMethod",
            String::class.java,
            arrayOf<Class<*>>().javaClass
        )
        val method = getDeclaredMethod.invoke(
            appOps.javaClass,
            "checkOpNoThrow",
            arrayOf<Class<*>>(
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                String::class.java
            )
        ) as java.lang.reflect.Method
        val result = method.invoke(appOps, 10021, android.os.Process.myUid(), appContext.packageName) as Int
        result == android.app.AppOpsManager.MODE_ALLOWED
    } catch (e: Exception) {
        android.util.Log.e("CF_Capabilities", "checkOpNoThrow met error, fallback to vendor check", e)
        // 兜底逻辑：若反射因极度特殊的 ROM 变动仍失败，且当前是明确要求并拦截后台启动的国产机型，
        // 默认返回 false 以确保安全性，否则在不需要该权限的原生/模拟器等设备上默认返回 true。
        val manufacturer = Build.MANUFACTURER.orEmpty().lowercase(java.util.Locale.US)
        val isTargetVendor = manufacturer.contains("xiaomi") || manufacturer.contains("redmi") ||
                manufacturer.contains("vivo") || manufacturer.contains("iqoo") ||
                manufacturer.contains("oppo") || manufacturer.contains("realme") ||
                manufacturer.contains("oneplus") || manufacturer.contains("meizu") ||
                manufacturer.contains("huawei") || manufacturer.contains("honor")
        !isTargetVendor
    }
    return MonitorRuntimeCapabilities(
        usageAccessGranted = usageAccessManager.hasUsageAccess(),
        overlayGranted = Settings.canDrawOverlays(appContext),
        telephonySupported = telephonySupported,
        phoneStateGranted = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED,
        notificationRuntimeGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED,
        notificationsEnabled = NotificationManagerCompat.from(appContext).areNotificationsEnabled(),
        notificationChannelsSupported = channelsSupported,
        serviceChannelEnabled = serviceChannelEnabled,
        lockRecoveryChannelEnabled = lockRecoveryChannelEnabled,
        fullScreenIntentRequired = fullScreenIntentRequired,
        fullScreenIntentGranted = fullScreenIntentGranted,
        exactAlarmRequired = exactAlarmRequired,
        exactAlarmGranted = exactAlarmGranted,
        batteryUnrestricted = preferenceManager.isBatteryUnrestrictedManuallyConfirmed() ||
            powerManager?.isIgnoringBatteryOptimizations(appContext.packageName) == true,
        backgroundPopupGranted = backgroundPopupGranted
    )
}
