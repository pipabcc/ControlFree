package com.example.controlfree.ai

import android.content.Context
import com.example.controlfree.data.CacheStorage
import com.example.controlfree.security.AiApiKeyReadResult
import com.example.controlfree.security.AiApiKeyStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal class AiInterventionCoordinator internal constructor(
    private val companionEnabledReader: () -> Boolean,
    private val lockChatEnabledReader: () -> Boolean = { true },
    private val apiKeyReader: suspend () -> AiApiKeyReadResult,
    private val deepSeekGateway: DeepSeekGateway
) {
    private val requestMutex = Mutex()

    internal constructor(
        preferences: AiCompanionPreferences,
        apiKeyStore: AiApiKeyStore,
        deepSeekGateway: DeepSeekGateway
    ) : this(
        companionEnabledReader = preferences::isCompanionEnabled,
        lockChatEnabledReader = preferences::isLockChatEnabled,
        apiKeyReader = { withContext(Dispatchers.IO) { apiKeyStore.read() } },
        deepSeekGateway = deepSeekGateway
    )

    fun isEnabled(): Boolean = companionEnabledReader()

    suspend fun getChatResponse(chatRequest: AiChatRequest): AiCompanionAdvice =
        requestMutex.withLock {
            if (!companionEnabledReader()) {
                return@withLock AiCompanionAdvice("我在这里，陪你把这一分钟守住。", AiAdviceSource.LOCAL)
            }
            if (!lockChatEnabledReader()) {
                return@withLock AiCompanionAdvice("小芽现在只能通过动作回应你呢。开启角色陪聊后可以和我聊天哦~", AiAdviceSource.LOCAL)
            }

            val apiKey = when (val stored = apiKeyReader()) {
                is AiApiKeyReadResult.Available -> stored.apiKey
                AiApiKeyReadResult.Missing,
                AiApiKeyReadResult.StorageUnavailable -> {
                    return@withLock AiCompanionAdvice("没有配置 API Key，小芽现在只能眨眨眼啦。", AiAdviceSource.LOCAL)
                }
            }

            val requestBody = AiCompanionPrompt.buildChatRequest(chatRequest)
            val cloudResult = try {
                withTimeoutOrNull(CLOUD_REQUEST_DEADLINE_MILLIS) {
                    deepSeekGateway.complete(apiKey, requestBody)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }

            when (val result = cloudResult) {
                is DeepSeekCallResult.Success -> {
                    val message = AiAdviceTextSanitizer.sanitize(result.message)
                    if (message.isBlank()) {
                        AiCompanionAdvice("小芽在发呆呢，等会儿再理你哦~", AiAdviceSource.LOCAL)
                    } else {
                        AiCompanionAdvice(message, AiAdviceSource.DEEPSEEK)
                    }
                }
                null,
                DeepSeekCallResult.InvalidApiKey,
                DeepSeekCallResult.RateLimited,
                DeepSeekCallResult.NetworkUnavailable,
                DeepSeekCallResult.ServiceUnavailable,
                DeepSeekCallResult.InvalidResponse -> {
                    AiCompanionAdvice("（宠物打了个哈欠，看起来网络有点不通呢）", AiAdviceSource.LOCAL)
                }
            }
        }

    suspend fun getAdvice(request: AiInterventionRequest): AiCompanionAdvice =
        requestMutex.withLock {
            // 行为统计分析已取消；介入建议只使用当前动作参数并在本机生成。
            OfflineInterventionAdvisor.createAdvice(request)
        }

    suspend fun testConnection(candidateKey: String?): AiConnectionTestResult {
        val apiKey = if (candidateKey != null) {
            com.example.controlfree.security.AiApiKeyValidator.normalize(candidateKey)
                ?: return AiConnectionTestResult.INVALID_API_KEY
        } else {
            when (val stored = apiKeyReader()) {
                is AiApiKeyReadResult.Available -> stored.apiKey
                AiApiKeyReadResult.Missing -> return AiConnectionTestResult.MISSING_API_KEY
                AiApiKeyReadResult.StorageUnavailable ->
                    return AiConnectionTestResult.SECURE_STORAGE_UNAVAILABLE
            }
        }
        val result = try {
            withTimeoutOrNull(CLOUD_REQUEST_DEADLINE_MILLIS) {
                deepSeekGateway.complete(apiKey, AiCompanionPrompt.buildConnectionTestRequest())
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        return when (result) {
            is DeepSeekCallResult.Success -> AiConnectionTestResult.SUCCESS
            DeepSeekCallResult.InvalidApiKey -> AiConnectionTestResult.INVALID_API_KEY
            DeepSeekCallResult.RateLimited,
            DeepSeekCallResult.ServiceUnavailable -> AiConnectionTestResult.SERVICE_BUSY
            DeepSeekCallResult.NetworkUnavailable,
            null -> AiConnectionTestResult.NETWORK_UNAVAILABLE
            DeepSeekCallResult.InvalidResponse -> AiConnectionTestResult.INVALID_RESPONSE
        }
    }

    companion object {
        private const val CLOUD_REQUEST_DEADLINE_MILLIS = 8_000L

        @Volatile
        private var instance: AiInterventionCoordinator? = null

        fun getInstance(context: Context): AiInterventionCoordinator =
            instance ?: synchronized(this) {
                instance ?: createDefault(context.applicationContext).also { instance = it }
            }

        private fun createDefault(context: Context): AiInterventionCoordinator {
            CacheStorage.clearLegacyAiAdviceCache(context)
            return AiInterventionCoordinator(
                preferences = AiCompanionPreferences.getInstance(context),
                apiKeyStore = AiApiKeyStore.getInstance(context),
                deepSeekGateway = DeepSeekClient.getInstance()
            )
        }
    }
}
