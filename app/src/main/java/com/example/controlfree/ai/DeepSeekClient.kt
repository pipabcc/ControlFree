package com.example.controlfree.ai

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

internal fun interface DeepSeekGateway {
    suspend fun complete(apiKey: String, requestBody: String): DeepSeekCallResult
}

/** 固定 DeepSeek HTTPS 端点的最小客户端；不接受动态 URL，也不跟随重定向。 */
internal class DeepSeekClient internal constructor() : DeepSeekGateway {
    override suspend fun complete(apiKey: String, requestBody: String): DeepSeekCallResult =
        withContext(Dispatchers.IO) {
            if (requestBody.toByteArray(Charsets.UTF_8).size > MAX_REQUEST_BYTES) {
                return@withContext DeepSeekCallResult.InvalidResponse
            }
            var connection: HttpsURLConnection? = null
            try {
                connection = URL(CHAT_COMPLETIONS_ENDPOINT).openConnection() as HttpsURLConnection
                connection.requestMethod = "POST"
                connection.instanceFollowRedirects = false
                connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
                connection.readTimeout = READ_TIMEOUT_MILLIS
                connection.useCaches = false
                connection.doInput = true
                connection.doOutput = true
                connection.setRequestProperty("Authorization", "Bearer $apiKey")
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setRequestProperty("Accept", "application/json")
                val requestBytes = requestBody.toByteArray(Charsets.UTF_8)
                try {
                    connection.setFixedLengthStreamingMode(requestBytes.size)
                    connection.outputStream.use { stream -> stream.write(requestBytes) }
                } finally {
                    requestBytes.fill(0)
                }

                when (connection.responseCode) {
                    HttpURLConnection.HTTP_OK -> parseSuccessfulResponse(
                        readBounded(connection.inputStream, MAX_RESPONSE_BYTES)
                    )
                    HttpURLConnection.HTTP_UNAUTHORIZED,
                    HttpURLConnection.HTTP_FORBIDDEN -> DeepSeekCallResult.InvalidApiKey
                    HTTP_TOO_MANY_REQUESTS -> DeepSeekCallResult.RateLimited
                    in 500..599 -> DeepSeekCallResult.ServiceUnavailable
                    else -> DeepSeekCallResult.InvalidResponse
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IOException) {
                DeepSeekCallResult.NetworkUnavailable
            } catch (_: JSONException) {
                DeepSeekCallResult.InvalidResponse
            } catch (_: RuntimeException) {
                DeepSeekCallResult.InvalidResponse
            } finally {
                try {
                    connection?.errorStream?.close()
                } catch (_: IOException) {
                    // 错误正文不参与业务判断，也不写日志。
                }
                connection?.disconnect()
            }
        }

    private fun parseSuccessfulResponse(responseBody: String): DeepSeekCallResult {
        val root = JSONObject(responseBody)
        val choices = root.optJSONArray("choices") ?: return DeepSeekCallResult.InvalidResponse
        if (choices.length() < 1) return DeepSeekCallResult.InvalidResponse
        val message = choices.optJSONObject(0)?.optJSONObject("message")
            ?: return DeepSeekCallResult.InvalidResponse
        if (!message.has("content") || message.isNull("content")) {
            return DeepSeekCallResult.InvalidResponse
        }
        val rawContent = message.optString("content", "")
        // 客户端还承载严格 JSON 的百科题增强；具体业务在各自协调器中再做窄化校验。
        val safeContent = DeepSeekResponseContentSanitizer.sanitize(rawContent)
        return if (safeContent.isBlank()) {
            DeepSeekCallResult.InvalidResponse
        } else {
            DeepSeekCallResult.Success(safeContent)
        }
    }

    private fun readBounded(input: InputStream, maxBytes: Int): String = input.use { stream ->
        val output = ByteArrayOutputStream(minOf(maxBytes, DEFAULT_BUFFER_BYTES))
        val buffer = ByteArray(DEFAULT_BUFFER_BYTES)
        var total = 0
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) throw IOException("DeepSeek response exceeded limit")
            output.write(buffer, 0, read)
        }
        output.toString(Charsets.UTF_8.name())
    }

    companion object {
        internal const val MODEL_NAME = "deepseek-v4-flash"
        private const val CHAT_COMPLETIONS_ENDPOINT =
            "https://api.deepseek.com/chat/completions"
        private const val CONNECT_TIMEOUT_MILLIS = 3_000
        private const val READ_TIMEOUT_MILLIS = 4_500
        private const val MAX_REQUEST_BYTES = 16 * 1_024
        private const val MAX_RESPONSE_BYTES = 64 * 1_024
        private const val DEFAULT_BUFFER_BYTES = 4 * 1_024
        private const val HTTP_TOO_MANY_REQUESTS = 429

        @Volatile
        private var instance: DeepSeekClient? = null

        fun getInstance(): DeepSeekClient =
            instance ?: synchronized(this) {
                instance ?: DeepSeekClient().also { instance = it }
            }
    }
}

internal object DeepSeekResponseContentSanitizer {
    private const val MAX_CONTENT_CODE_POINTS = 8_192

    fun sanitize(raw: String): String {
        val withoutControls = buildString(raw.length.coerceAtMost(MAX_CONTENT_CODE_POINTS)) {
            raw.forEach { character ->
                when {
                    character == '\n' || character == '\r' || character == '\t' -> append(' ')
                    character.code < 0x20 || character.code == 0x7f -> Unit
                    else -> append(character)
                }
            }
        }.trim()
        if (withoutControls.codePointCount(0, withoutControls.length) <= MAX_CONTENT_CODE_POINTS) {
            return withoutControls
        }
        val endIndex = withoutControls.offsetByCodePoints(0, MAX_CONTENT_CODE_POINTS)
        return withoutControls.substring(0, endIndex)
    }
}

internal object AiAdviceTextSanitizer {
    private const val MAX_ADVICE_CODE_POINTS = 220

    fun sanitize(raw: String): String {
        val withoutControls = buildString(raw.length.coerceAtMost(512)) {
            raw.forEach { character ->
                when {
                    character == '\n' -> append(' ')
                    character == '\r' || character == '\t' -> append(' ')
                    character.code < 0x20 || character.code == 0x7f -> Unit
                    else -> append(character)
                }
            }
        }
        val plainText = withoutControls
            .replace("```", "")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (plainText.codePointCount(0, plainText.length) <= MAX_ADVICE_CODE_POINTS) {
            return plainText
        }
        val endIndex = plainText.offsetByCodePoints(0, MAX_ADVICE_CODE_POINTS)
        return plainText.substring(0, endIndex).trimEnd() + "…"
    }
}
