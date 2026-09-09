package com.example.controlfree.security

internal enum class CredentialMethod {
    GESTURE,
    PASSWORD
}

internal fun preferredCredentialMethods(
    hasPassword: Boolean,
    hasGesture: Boolean
): List<CredentialMethod> = when {
    hasGesture && hasPassword -> listOf(CredentialMethod.GESTURE, CredentialMethod.PASSWORD)
    hasGesture -> listOf(CredentialMethod.GESTURE)
    hasPassword -> listOf(CredentialMethod.PASSWORD)
    else -> emptyList()
}
