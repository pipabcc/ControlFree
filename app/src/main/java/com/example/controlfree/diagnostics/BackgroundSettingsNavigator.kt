package com.example.controlfree.diagnostics

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.util.Locale

enum class BackgroundSettingsDestination {
    VENDOR_AUTO_START,
    APPLICATION_DETAILS,
    UNAVAILABLE
}

internal enum class VendorBackgroundSettingsFamily {
    XIAOMI,
    HUAWEI,
    OPPO,
    VIVO,
    SAMSUNG,
    MEIZU,
    UNKNOWN
}

object BackgroundSettingsNavigator {
    fun openAutoStartOrAppDetails(context: Context): BackgroundSettingsDestination {
        val candidates = vendorCandidates(Build.MANUFACTURER, context.packageName) +
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        candidates.forEachIndexed { index, intent ->
            val canOpen = try {
                intent.resolveActivity(context.packageManager) != null
            } catch (_: RuntimeException) {
                false
            }
            if (!canOpen) return@forEachIndexed
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return if (index < candidates.lastIndex) {
                    BackgroundSettingsDestination.VENDOR_AUTO_START
                } else {
                    BackgroundSettingsDestination.APPLICATION_DETAILS
                }
            } catch (_: RuntimeException) {
                // 继续尝试下一个候选入口。
            }
        }
        return BackgroundSettingsDestination.UNAVAILABLE
    }

    internal fun vendorCandidates(manufacturer: String?, packageName: String): List<Intent> {
        return when (detectVendorFamily(manufacturer)) {
            VendorBackgroundSettingsFamily.XIAOMI -> listOf(
                componentIntent(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )
            )
            VendorBackgroundSettingsFamily.HUAWEI -> listOf(
                componentIntent(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                ),
                componentIntent(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.optimize.process.ProtectActivity"
                )
            )
            VendorBackgroundSettingsFamily.OPPO -> listOf(
                componentIntent(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                ),
                componentIntent(
                    "com.oplus.safecenter",
                    "com.oplus.safecenter.startupapp.StartupAppListActivity"
                )
            )
            VendorBackgroundSettingsFamily.VIVO -> listOf(
                componentIntent(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
                ),
                componentIntent(
                    "com.iqoo.secure",
                    "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
                )
            )
            VendorBackgroundSettingsFamily.SAMSUNG -> listOf(
                Intent("android.settings.APP_SLEEPING_SETTINGS")
            )
            VendorBackgroundSettingsFamily.MEIZU -> listOf(
                Intent("com.meizu.safe.security.SHOW_APPSEC").apply {
                    addCategory(Intent.CATEGORY_DEFAULT)
                    putExtra("packageName", packageName)
                }
            )
            VendorBackgroundSettingsFamily.UNKNOWN -> emptyList()
        }
    }

    internal fun detectVendorFamily(manufacturer: String?): VendorBackgroundSettingsFamily {
        val normalized = manufacturer.orEmpty().lowercase(Locale.US)
        return when {
            normalized.contains("xiaomi") || normalized.contains("redmi") ->
                VendorBackgroundSettingsFamily.XIAOMI
            normalized.contains("huawei") || normalized.contains("honor") ->
                VendorBackgroundSettingsFamily.HUAWEI
            normalized.contains("oppo") || normalized.contains("realme") ||
                normalized.contains("oneplus") -> VendorBackgroundSettingsFamily.OPPO
            normalized.contains("vivo") || normalized.contains("iqoo") ->
                VendorBackgroundSettingsFamily.VIVO
            normalized.contains("samsung") -> VendorBackgroundSettingsFamily.SAMSUNG
            normalized.contains("meizu") -> VendorBackgroundSettingsFamily.MEIZU
            else -> VendorBackgroundSettingsFamily.UNKNOWN
        }
    }

    private fun componentIntent(packageName: String, className: String): Intent =
        Intent().setComponent(ComponentName(packageName, className))
}
