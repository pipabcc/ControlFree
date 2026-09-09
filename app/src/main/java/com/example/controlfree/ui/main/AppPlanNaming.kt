package com.example.controlfree.ui.main

internal const val LEGACY_DEFAULT_APP_PLAN_NAME = "App 独立监督"

internal fun appPlanNameAfterAppSelection(
    currentName: String,
    currentPackageLabel: String,
    selectedAppLabel: String
): String {
    val shouldFollowSelectedApp = currentName.isBlank() ||
        currentName == LEGACY_DEFAULT_APP_PLAN_NAME ||
        currentName == currentPackageLabel
    return if (shouldFollowSelectedApp) selectedAppLabel else currentName
}

internal fun appPlanListItemName(planName: String, appLabel: String): String =
    planName.takeUnless { it.isBlank() || it == LEGACY_DEFAULT_APP_PLAN_NAME } ?: appLabel
