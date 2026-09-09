package com.example.controlfree.ui.main

import com.example.controlfree.data.AllowedApp
import com.example.controlfree.supervision.AppRulePolicy
import com.example.controlfree.supervision.SupervisionPlan
import com.example.controlfree.supervision.SupervisionPlanType

internal data class AppPlanTargetMetadata(
    val appsByPackage: Map<String, AllowedApp> = emptyMap(),
    val resolvedPackages: Set<String> = emptySet()
) {
    init {
        require(appsByPackage.keys.all(resolvedPackages::contains)) {
            "已加载的 App 元数据必须同时标记为已解析"
        }
    }

    fun retainTargets(targetPackages: Set<String>): AppPlanTargetMetadata =
        AppPlanTargetMetadata(
            appsByPackage = appsByPackage.filterKeys(targetPackages::contains),
            resolvedPackages = resolvedPackages.filterTo(linkedSetOf(), targetPackages::contains)
        )

    fun resolve(
        requestedPackages: Set<String>,
        resolvedApps: Collection<AllowedApp>
    ): AppPlanTargetMetadata {
        if (requestedPackages.isEmpty()) return this
        val resolvedByPackage = resolvedApps
            .asSequence()
            .filter { app -> app.packageName in requestedPackages }
            .associateBy(AllowedApp::packageName)
        return AppPlanTargetMetadata(
            appsByPackage = appsByPackage + resolvedByPackage,
            resolvedPackages = resolvedPackages + requestedPackages
        )
    }

    fun unresolvedTargets(targetPackages: Set<String>): Set<String> =
        targetPackages.filterTo(linkedSetOf()) { packageName ->
            packageName !in resolvedPackages
        }
}

internal fun appPlanTargetPackages(plans: Collection<SupervisionPlan>): Set<String> =
    plans.asSequence()
        .filter { plan -> plan.type == SupervisionPlanType.APP }
        .mapNotNull { plan -> (plan.policy as? AppRulePolicy)?.packageName }
        .toCollection(linkedSetOf())
