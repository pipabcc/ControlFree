package com.example.controlfree.diagnostics

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

object BackgroundPopupSettingsNavigator {
    fun openBackgroundPopupSettings(context: Context): Boolean {
        val packageName = context.packageName
        val candidates = mutableListOf<Intent>()

        val vendor = BackgroundSettingsNavigator.detectVendorFamily(Build.MANUFACTURER)
        when (vendor) {
            VendorBackgroundSettingsFamily.XIAOMI -> {
                // 小米/红米/MIUI/HyperOS：APP_PERM_EDITOR 包含后台弹出界面权限
                candidates.add(
                    Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                        setComponent(
                            ComponentName(
                                "com.miui.securitycenter",
                                "com.miui.permcenter.permissions.AppPermissionsEditorActivity"
                            )
                        )
                        putExtra("extra_pkgname", packageName)
                    }
                )
            }
            VendorBackgroundSettingsFamily.VIVO -> {
                // Vivo：后台启动管理
                candidates.add(
                    Intent().apply {
                        setComponent(
                            ComponentName(
                                "com.vivo.permissionmanager",
                                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
                            )
                        )
                    }
                )
                candidates.add(
                    Intent().apply {
                        setComponent(
                            ComponentName(
                                "com.iqoo.secure",
                                "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"
                            )
                        )
                    }
                )
            }
            VendorBackgroundSettingsFamily.OPPO -> {
                // OPPO：权限管理界面
                candidates.add(
                    Intent().apply {
                        setComponent(
                            ComponentName(
                                "com.coloros.safecenter",
                                "com.coloros.safecenter.permission.PermissionManagerActivity"
                            )
                        )
                    }
                )
            }
            else -> {}
        }

        // 通用应用详情页兜底，用户可以点击进入“权限” -> “后台弹出界面”
        candidates.add(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
        )

        for (intent in candidates) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return true
            } catch (_: Exception) {
                // 继续尝试下一个候选
            }
        }
        return false
    }
}
