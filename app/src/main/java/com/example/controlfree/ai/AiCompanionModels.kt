package com.example.controlfree.ai

import com.example.controlfree.LockPendingActionKind
import com.example.controlfree.MonitorSessionMode

internal data class AiInterventionRequest(
    val actionKind: LockPendingActionKind,
    val requestedPauseMinutes: Int?,
    val sessionMode: MonitorSessionMode,
    val remainingSeconds: Int,
    val lockSessionId: Long
)

internal enum class AiAdviceSource {
    DEEPSEEK,
    CACHE,
    LOCAL
}

internal data class AiCompanionAdvice(
    val message: String,
    val source: AiAdviceSource
) {
    init {
        require(message.isNotBlank()) { "AI 陪伴建议不能为空" }
    }
}

internal enum class AiConnectionTestResult {
    SUCCESS,
    MISSING_API_KEY,
    INVALID_API_KEY,
    SECURE_STORAGE_UNAVAILABLE,
    NETWORK_UNAVAILABLE,
    SERVICE_BUSY,
    INVALID_RESPONSE;

    val isSuccess: Boolean
        get() = this == SUCCESS
}

internal sealed interface DeepSeekCallResult {
    data class Success(val message: String) : DeepSeekCallResult
    data object InvalidApiKey : DeepSeekCallResult
    data object RateLimited : DeepSeekCallResult
    data object NetworkUnavailable : DeepSeekCallResult
    data object ServiceUnavailable : DeepSeekCallResult
    data object InvalidResponse : DeepSeekCallResult
}
