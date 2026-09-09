package com.example.controlfree.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager
import android.util.LruCache
import java.security.MessageDigest
import java.text.Collator
import java.util.Locale

data class AllowedApp(
    val packageName: String,
    val label: String,
    val isSystemRequired: Boolean = false,
    val icon: Drawable? = null
)

internal data class SystemRolePackages(
    val defaultDialerPackage: String?,
    val systemDialerPackage: String?,
    val defaultSmsPackage: String?
) {
    val callUiPackages: Set<String>
        get() = setOfNotNull(defaultDialerPackage, systemDialerPackage)
            .filterTo(linkedSetOf(), String::isNotBlank)
}

class AppAllowlistManager(private val context: Context) {
    private val packageManager = context.packageManager
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val iconCache = LruCache<String, Drawable.ConstantState>(MAX_ICON_CACHE_ENTRIES)

    internal fun getAllowedApps(
        systemRoles: SystemRolePackages = getSystemRolePackages()
    ): List<AllowedApp> {
        val systemApps = getRequiredSystemApps(systemRoles)
        val systemPackages = systemApps.mapTo(mutableSetOf()) { it.packageName }
        val customApps = getCustomPackages()
            .asSequence()
            .filterNot(systemPackages::contains)
            .filterNot(::isBlockedPackage)
            .mapNotNull { packageName -> resolveAllowedApp(packageName, false) }
            .sortedWith(appComparator)
            .toList()
        // 该方法只在后台线程执行。锁层随后会在主线程立即渲染这些少量 App，
        // 因此在这里预取图标，避免 PackageManager I/O 阻塞锁屏界面。
        return (systemApps + customApps).map { app ->
            app.copy(icon = getApplicationIcon(app.packageName))
        }
    }

    fun getSelectableApps(): List<AllowedApp> {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(
                launcherIntent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)
        }

        return resolved.asSequence()
            .map { it.activityInfo.applicationInfo }
            .distinctBy(ApplicationInfo::packageName)
            .filterNot { isBlockedPackage(it.packageName) }
            .map {
                AllowedApp(
                    packageName = it.packageName,
                    label = packageManager.getApplicationLabel(it).toString()
                )
            }
            .sortedWith(appComparator)
            .toList()
    }

    /**
     * App 独立监督候选项。电话、短信、桌面、系统设置和本应用不能成为限制目标，
     * 从源头保持紧急通信与退出路径可用。该方法包含 PackageManager/Binder 查询，需在后台线程调用。
     */
    fun getSupervisableApps(): List<AllowedApp> {
        val protectedPackages = getRequiredSystemApps(getSystemRolePackages())
            .mapTo(linkedSetOf(), AllowedApp::packageName)
        return getSelectableApps()
            .asSequence()
            .filterNot { app -> app.packageName in protectedPackages }
            .map { app -> app.copy(icon = getApplicationIcon(app.packageName)) }
            .toList()
    }

    /** 读取已保存计划目标的名称与图标；调用方必须在后台线程执行。 */
    internal fun getAppMetadata(packageName: String): AllowedApp? = try {
        resolveAllowedApp(packageName, isSystemRequired = false)
            ?.let { app -> app.copy(icon = getApplicationIcon(packageName)) }
    } catch (_: RuntimeException) {
        null
    }

    /**
     * 运行时再次确认目标是否属于不可监督范围。默认电话或短信 App 可能在计划保存后发生变化，
     * 因此不能只依赖创建计划时的候选列表过滤。
     */
    fun isProtectedFromSupervision(packageName: String): Boolean {
        if (packageName.isBlank() || isBlockedPackage(packageName)) return true
        return getRequiredSystemApps(getSystemRolePackages())
            .any { app -> app.packageName == packageName }
    }

    fun getCustomPackages(): Set<String> {
        val storedEntries = prefs.getStringSet(CUSTOM_ALLOWLIST, emptySet()).orEmpty().toSet()
        // 读取不回写清洗结果，避免覆盖同时发生的白名单保存。
        return storedEntries
            .mapNotNull(::verifyStoredEntry)
            .mapTo(mutableSetOf()) { it.substringBefore(ENTRY_SEPARATOR) }
    }

    fun setCustomPackages(
        packages: Set<String>,
        expectedCustomPackages: Set<String>
    ): AllowlistSaveResult {
        if (getCustomPackages() != expectedCustomPackages) return AllowlistSaveResult.CONFLICT
        val validEntries = linkedSetOf<String>()
        packages.forEach { packageName ->
            if (isBlockedPackage(packageName)) return AllowlistSaveResult.FAILED
            if (packageManager.getLaunchIntentForPackage(packageName) == null) {
                return AllowlistSaveResult.FAILED
            }
            validEntries += createStoredEntry(packageName) ?: return AllowlistSaveResult.FAILED
        }
        return if (prefs.edit().putStringSet(CUSTOM_ALLOWLIST, validEntries).commit()) {
            AllowlistSaveResult.SAVED
        } else {
            AllowlistSaveResult.FAILED
        }
    }

    fun isAllowed(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        return isAllowed(packageName, getSystemRolePackages())
    }

    private fun isAllowed(packageName: String, systemRoles: SystemRolePackages): Boolean =
        getRequiredSystemApps(systemRoles).any { it.packageName == packageName } ||
            (!isBlockedPackage(packageName) && packageName in getCustomPackages())

    /**
     * 返回能够承载系统通话界面的拨号器包。调用包含 Telecom binder 查询，
     * 必须与白名单刷新一样放在后台线程执行。
     *
     * 除默认/系统拨号器角色外，很多 OEM 设备的来电界面运行在独立包里
     * （如 com.android.incallui、com.samsung.android.incallui），响铃阶段前台
     * 还可能是 telecom/telephony 进程。这些包不纳入豁免集合时，来电界面会被
     * 锁屏判定为"非拨号器前台"而立刻盖回去，导致只响铃不见接听界面。
     */
    internal fun getCallUiPackages(
        systemRoles: SystemRolePackages = getSystemRolePackages()
    ): Set<String> = systemRoles.callUiPackages + getInstalledInCallUiPackages()

    private fun getInstalledInCallUiPackages(): Set<String> {
        val result = mutableSetOf<String>()
        try {
            packageManager.queryIntentServices(
                Intent(IN_CALL_SERVICE_INTERFACE),
                PackageManager.MATCH_ALL
            ).forEach { resolved ->
                resolved.serviceInfo?.packageName?.let(result::add)
            }
        } catch (_: RuntimeException) {
            // Binder 暂时不可用时退回静态候选列表。
        }
        KNOWN_IN_CALL_UI_PACKAGES.filterTo(result, ::isPackageInstalled)
        return result
    }

    private fun isPackageInstalled(packageName: String): Boolean = try {
        packageManager.getPackageInfo(packageName, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    } catch (_: RuntimeException) {
        false
    }

    internal fun getSystemRolePackages(): SystemRolePackages {
        val telecomManager = context.getSystemService(TelecomManager::class.java)
        return SystemRolePackages(
            defaultDialerPackage = getDefaultDialerPackage(telecomManager),
            systemDialerPackage = getSystemDialerPackage(telecomManager),
            defaultSmsPackage = getDefaultSmsPackage()
        )
    }

    fun launch(packageName: String): Boolean {
        return launchResolvedIntents(resolveLaunchIntents(packageName))
    }

    internal fun resolveLaunchIntents(packageName: String): List<Intent> {
        val systemRoles = getSystemRolePackages()
        if (!isAllowed(packageName, systemRoles)) return emptyList()
        val preferredIntent = when (packageName) {
            systemRoles.defaultDialerPackage, systemRoles.systemDialerPackage ->
                Intent(Intent.ACTION_DIAL).setPackage(packageName)
            systemRoles.defaultSmsPackage -> Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_APP_MESSAGING)
                .setPackage(packageName)
            else -> null
        }
        val fallbackIntent = packageManager.getLaunchIntentForPackage(packageName)
        return listOfNotNull(preferredIntent, fallbackIntent)
            .map { intent -> Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    }

    internal fun launchResolvedIntents(intents: List<Intent>): Boolean {
        for (intent in intents) {
            try {
                context.startActivity(intent)
                return true
            } catch (_: RuntimeException) {
                // 继续尝试该包的普通 launcher 入口。
            }
        }
        return false
    }

    fun getApplicationIcon(packageName: String): Drawable = synchronized(iconCache) {
        iconCache.get(packageName)?.let { state ->
            return@synchronized state.newDrawable(context.resources).mutate()
        }
        val icon = try {
            packageManager.getApplicationIcon(packageName)
        } catch (_: PackageManager.NameNotFoundException) {
            packageManager.defaultActivityIcon
        } catch (_: RuntimeException) {
            packageManager.defaultActivityIcon
        }
        icon.constantState?.let { state -> iconCache.put(packageName, state) }
        icon
    }

    fun invalidateApplicationIcon(packageName: String?) = synchronized(iconCache) {
        if (packageName.isNullOrBlank()) {
            iconCache.evictAll()
        } else {
            iconCache.remove(packageName)
        }
    }

    private fun getRequiredSystemApps(systemRoles: SystemRolePackages): List<AllowedApp> {
        val result = mutableListOf<AllowedApp>()
        sequenceOf(
            systemRoles.defaultDialerPackage,
            systemRoles.systemDialerPackage
        )
            .filterNotNull()
            .distinct()
            .mapNotNull { resolveAllowedApp(it, true, "电话") }
            .forEach(result::add)

        systemRoles.defaultSmsPackage
            ?.let { resolveAllowedApp(it, true, "短信") }
            ?.takeIf { sms -> result.none { it.packageName == sms.packageName } }
            ?.let(result::add)
        return result
    }

    private fun resolveAllowedApp(
        packageName: String,
        isSystemRequired: Boolean,
        fallbackLabel: String = packageName
    ): AllowedApp? = try {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getApplicationInfo(
                packageName,
                PackageManager.ApplicationInfoFlags.of(0L)
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getApplicationInfo(packageName, 0)
        }
        AllowedApp(
            packageName = packageName,
            label = packageManager.getApplicationLabel(info).toString().ifBlank { fallbackLabel },
            isSystemRequired = isSystemRequired
        )
    } catch (_: PackageManager.NameNotFoundException) {
        if (isSystemRequired) AllowedApp(packageName, fallbackLabel, true) else null
    }

    private fun isBlockedPackage(packageName: String): Boolean {
        if (packageName == context.packageName || packageName in ALWAYS_BLOCKED_PACKAGES) return true

        val settingsPackage = Intent(Settings.ACTION_SETTINGS)
            .resolveActivity(packageManager)
            ?.packageName
        if (packageName == settingsPackage) return true

        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val homePackages = packageManager.queryIntentActivities(homeIntent, 0)
            .map { it.activityInfo.packageName }
        return packageName in homePackages
    }

    private fun getDefaultDialerPackage(telecomManager: TelecomManager?): String? =
        telecomManager?.defaultDialerPackage

    private fun getSystemDialerPackage(telecomManager: TelecomManager?): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            telecomManager?.systemDialerPackage
        } else {
            null
        }

    private fun getDefaultSmsPackage(): String? = Telephony.Sms.getDefaultSmsPackage(context)

    private fun createStoredEntry(packageName: String): String? {
        val signingDigest = getSigningDigest(packageName) ?: return null
        return "$packageName$ENTRY_SEPARATOR$signingDigest"
    }

    private fun verifyStoredEntry(entry: String): String? {
        val separatorIndex = entry.lastIndexOf(ENTRY_SEPARATOR)
        if (separatorIndex <= 0 || separatorIndex == entry.lastIndex) return null
        val packageName = entry.substring(0, separatorIndex)
        val storedDigest = entry.substring(separatorIndex + 1)
        if (isBlockedPackage(packageName)) return null
        val currentDigest = getSigningDigest(packageName) ?: return null
        return entry.takeIf { storedDigest.equals(currentDigest, ignoreCase = true) }
    }

    private fun getSigningDigest(packageName: String): String? = try {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val packageInfo = packageManager.getPackageInfo(
                packageName,
                PackageManager.GET_SIGNING_CERTIFICATES
            )
            packageInfo.signingInfo?.apkContentsSigners.orEmpty()
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
                .signatures
                .orEmpty()
        }
        signatures
            .map { signature ->
                MessageDigest.getInstance("SHA-256")
                    .digest(signature.toByteArray())
                    .joinToString("") { byte -> "%02x".format(byte) }
            }
            .sorted()
            .takeIf { it.isNotEmpty() }
            ?.joinToString(".")
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    companion object {
        private const val PREFS_NAME = "control_free_prefs"
        private const val IN_CALL_SERVICE_INTERFACE = "android.telecom.InCallService"

        // OEM 来电界面/电话进程的常见宿主包；仅在设备上确实安装时才纳入豁免。
        private val KNOWN_IN_CALL_UI_PACKAGES = setOf(
            "com.android.incallui",
            "com.samsung.android.incallui",
            "com.android.server.telecom",
            "com.android.phone",
            "com.android.dialer",
            "com.google.android.dialer"
        )
        private const val CUSTOM_ALLOWLIST = "custom_app_allowlist"
        private const val ENTRY_SEPARATOR = '|'
        private const val MAX_ICON_CACHE_ENTRIES = 64

        private val ALWAYS_BLOCKED_PACKAGES = setOf(
            "com.android.settings",
            "com.android.systemui",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller"
        )

        private val appComparator = Comparator<AllowedApp> { first, second ->
            Collator.getInstance(Locale.getDefault()).compare(first.label, second.label)
                .takeIf { it != 0 }
                ?: first.packageName.compareTo(second.packageName)
        }
    }
}
