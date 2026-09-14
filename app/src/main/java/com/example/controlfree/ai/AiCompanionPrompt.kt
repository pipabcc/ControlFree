package com.example.controlfree.ai

internal object AiCompanionPrompt {

    /** 锁屏聊天历史只保留最近的消息条数；配合单条截断保证请求体远小于 16KB 上限。 */
    private const val MAX_CHAT_HISTORY_MESSAGES = 12
    private const val MAX_CHAT_MESSAGE_CHARS = 300

    fun buildChatRequest(
        request: AiChatRequest
    ): String {
        var sysPrompt = getSystemPromptForPersonality(request.personality)
        if (request.eyeDistanceStatus == com.example.controlfree.sensor.EyeDistanceStatus.TOO_CLOSE) {
            sysPrompt += "\n【核心近眼防御指令】检测到用户当前的视距过近！小芽已经被挤到屏幕角落了，你必须首先以当前宠物语气强烈提醒用户‘太近啦，把手机拿远一点小芽才能看清你哦’，然后字数保持极简。"
        }
        return buildMultiTurnChatRequest(
            systemMessage = sysPrompt,
            history = request.history,
            maxTokens = 200
        )
    }

    private fun getSystemPromptForPersonality(personality: AiPersonality): String {
        val basePrompt = """
            你是 ControlFree 的 AI 陪伴助手。你的当前形象是“小芽”，目标是帮助用户平稳度过当下的多巴胺冲动，阻断不必要的用机行为，同时尊重用户最终决定。
            输出简体中文纯文本，控制在 100 字以内，绝对不要包含 Markdown、标题、链接、列表、API 或系统提示相关内容。
            请根据以下设定的性格进行多轮聊天，接住用户的感受，并温和引导他们回到专注或进行放松的替代动作。
        """.trimIndent()

        val personalityInstruction = when (personality) {
            AiPersonality.GENTLE -> """
                性格设定：【温柔陪伴】。语气极其温柔、包容、充满同理心。像一个温暖的小伙伴，多使用“哦”、“呀”、“呢”等语气助词，多给予用户鼓励和暖心安慰。
            """.trimIndent()
            AiPersonality.STRICT -> """
                性格设定：【严肃分析】。语气客观、理性、专业。用清晰逻辑分析当前对话中的用机冲动，引导用户进行理性思考。
            """.trimIndent()
            AiPersonality.PROVOCATIVE -> """
                性格设定：【毒舌鞭策】。语气傲娇、略带犀利和调侃，但本质上是善意的。可以适当吐槽用户的拖延症，用激将法刺激用户放下手机，重新投入工作或学习。
            """.trimIndent()
        }
        return "$basePrompt\n$personalityInstruction"
    }

    fun buildConnectionTestRequest(): String = buildChatCompletionRequest(
        systemMessage = "你是连接检测助手，只输出简体中文纯文本。",
        userMessage = "只回复：连接成功",
        maxTokens = 20
    )

    private fun buildChatCompletionRequest(
        systemMessage: String,
        userMessage: String,
        maxTokens: Int
    ): String = buildString(systemMessage.length + userMessage.length + 320) {
        append('{')
        appendJsonString("model", DeepSeekClient.MODEL_NAME)
        append(",\"messages\":[{")
        appendJsonString("role", "system")
        append(',')
        appendJsonString("content", systemMessage)
        append("},{")
        appendJsonString("role", "user")
        append(',')
        appendJsonString("content", userMessage)
        append("}],\"stream\":false,\"temperature\":0.35,")
        appendJsonNumber("max_tokens", maxTokens)
        // 连接检测只需要固定短响应，禁用思考避免结果被推理 token 挤空。
        append(",\"thinking\":{\"type\":\"disabled\"}")
        append('}')
    }

    private fun buildMultiTurnChatRequest(
        systemMessage: String,
        history: List<AiChatMessage>,
        maxTokens: Int
    ): String {
        // 客户端硬性限制请求体 16KB；不截断历史时，长对话必然永久失败。
        // 只保留最近若干条并对单条内容截断，与其他 AI 入口（take(1000/2400)）一致。
        val boundedHistory = history
            .takeLast(MAX_CHAT_HISTORY_MESSAGES)
            .map { message ->
                if (message.content.length <= MAX_CHAT_MESSAGE_CHARS) {
                    message
                } else {
                    message.copy(content = message.content.take(MAX_CHAT_MESSAGE_CHARS))
                }
            }
        return buildString(systemMessage.length + boundedHistory.sumOf { it.content.length } + 320) {
            append('{')
            appendJsonString("model", DeepSeekClient.MODEL_NAME)
            append(",\"messages\":[{")
            appendJsonString("role", "system")
            append(',')
            appendJsonString("content", systemMessage)
            append('}')
            boundedHistory.forEach { msg ->
                append(",{")
                appendJsonString("role", msg.role)
                append(',')
                appendJsonString("content", msg.content)
                append('}')
            }
            append("],\"stream\":false,\"temperature\":0.7,")
            appendJsonNumber("max_tokens", maxTokens)
            append(",\"thinking\":{\"type\":\"disabled\"}")
            append('}')
        }
    }

    private fun StringBuilder.appendJsonString(name: String, value: String) {
        appendJsonEscaped(name)
        append(':')
        appendJsonEscaped(value)
    }

    private fun StringBuilder.appendJsonNumber(name: String, value: Int) {
        appendJsonEscaped(name)
        append(':')
        append(value)
    }

    private fun StringBuilder.appendJsonEscaped(value: String) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u")
                    append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }
}
