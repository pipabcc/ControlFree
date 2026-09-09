package com.example.controlfree.ai

internal data class AiChatMessage(
    val role: String, // "system" | "user" | "assistant"
    val content: String,
    val id: String = java.util.UUID.randomUUID().toString()
) {
    init {
        require(role.isNotBlank()) { "消息角色不能为空" }
        require(content.isNotBlank()) { "消息内容不能为空" }
    }
}

internal enum class AiPersonality(val key: String, val displayName: String) {
    GENTLE("gentle", "小芽 (温柔陪伴)"),
    STRICT("strict", "精算师 (严肃分析)"),
    PROVOCATIVE("provocative", "教官 (毒舌鞭策)");

    companion object {
        fun fromKey(key: String): AiPersonality =
            entries.firstOrNull { it.key == key } ?: GENTLE
    }
}

internal data class AiChatRequest(
    val history: List<AiChatMessage>,
    val personality: AiPersonality,
    val remainingSeconds: Int,
    val eyeDistanceStatus: com.example.controlfree.sensor.EyeDistanceStatus
)
