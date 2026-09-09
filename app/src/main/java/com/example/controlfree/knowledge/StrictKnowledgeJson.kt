package com.example.controlfree.knowledge

/**
 * 面向模型结构化输出的无依赖 JSON 解析器。它拒绝重复字段、尾随内容、非法转义和
 * 过深结构，避免宽松解析把不符合协议的模型文本当成有效题目。
 */
internal object StrictKnowledgeJson {
    internal data class Limits(
        val maxJsonChars: Int,
        val maxStringChars: Int,
        val maxArrayItems: Int,
        val maxDepth: Int
    ) {
        init {
            require(maxJsonChars > 0)
            require(maxStringChars > 0)
            require(maxArrayItems > 0)
            require(maxDepth > 0)
        }
    }

    fun parseObject(
        raw: String,
        limits: Limits = MODEL_RESPONSE_LIMITS
    ): Map<String, Value>? = try {
        if (raw.isBlank() || raw.length > limits.maxJsonChars) return null
        Parser(raw, limits).parseRootObject()
    } catch (_: InvalidJsonException) {
        null
    } catch (_: RuntimeException) {
        null
    }

    internal sealed interface Value {
        data class ObjectValue(val values: Map<String, Value>) : Value
        data class ArrayValue(val values: List<Value>) : Value
        data class StringValue(val value: String) : Value
        data class NumberValue(val raw: String) : Value
        data class BooleanValue(val value: Boolean) : Value
        data object NullValue : Value
    }

    private class Parser(
        private val source: String,
        private val limits: Limits
    ) {
        private var index = 0

        fun parseRootObject(): Map<String, Value> {
            skipWhitespace()
            val value = parseValue(depth = 0) as? Value.ObjectValue ?: invalid()
            skipWhitespace()
            if (index != source.length) invalid()
            return value.values
        }

        private fun parseValue(depth: Int): Value {
            if (depth > limits.maxDepth) invalid()
            skipWhitespace()
            if (index >= source.length) invalid()
            return when (source[index]) {
                '{' -> parseObject(depth + 1)
                '[' -> parseArray(depth + 1)
                '"' -> Value.StringValue(parseString())
                't' -> parseLiteral("true", Value.BooleanValue(true))
                'f' -> parseLiteral("false", Value.BooleanValue(false))
                'n' -> parseLiteral("null", Value.NullValue)
                '-', in '0'..'9' -> Value.NumberValue(parseNumber())
                else -> invalid()
            }
        }

        private fun parseObject(depth: Int): Value.ObjectValue {
            expect('{')
            skipWhitespace()
            if (consumeIf('}')) return Value.ObjectValue(emptyMap())
            val result = linkedMapOf<String, Value>()
            while (true) {
                skipWhitespace()
                if (peek() != '"') invalid()
                val key = parseString()
                if (key in result) invalid()
                skipWhitespace()
                expect(':')
                result[key] = parseValue(depth)
                skipWhitespace()
                when {
                    consumeIf('}') -> return Value.ObjectValue(result)
                    consumeIf(',') -> Unit
                    else -> invalid()
                }
            }
        }

        private fun parseArray(depth: Int): Value.ArrayValue {
            expect('[')
            skipWhitespace()
            if (consumeIf(']')) return Value.ArrayValue(emptyList())
            val result = mutableListOf<Value>()
            while (true) {
                if (result.size >= limits.maxArrayItems) invalid()
                result += parseValue(depth)
                skipWhitespace()
                when {
                    consumeIf(']') -> return Value.ArrayValue(result)
                    consumeIf(',') -> Unit
                    else -> invalid()
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val result = StringBuilder()
            while (index < source.length) {
                val character = source[index++]
                when {
                    character == '"' -> {
                        val parsed = result.toString()
                        if (parsed.length > limits.maxStringChars || hasUnpairedSurrogate(parsed)) {
                            invalid()
                        }
                        return parsed
                    }
                    character == '\\' -> result.append(parseEscape())
                    character.code < 0x20 -> invalid()
                    else -> result.append(character)
                }
                if (result.length > limits.maxStringChars) invalid()
            }
            invalid()
        }

        private fun parseEscape(): Char {
            if (index >= source.length) invalid()
            return when (val escaped = source[index++]) {
                '"', '\\', '/' -> escaped
                'b' -> '\b'
                'f' -> '\u000c'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> {
                    if (index + 4 > source.length) invalid()
                    val hex = source.substring(index, index + 4)
                    if (hex.any { it !in HEX_DIGITS }) invalid()
                    index += 4
                    hex.toInt(16).toChar()
                }
                else -> invalid()
            }
        }

        private fun parseNumber(): String {
            val start = index
            consumeIf('-')
            if (consumeIf('0')) {
                if (peek()?.let { it in '0'..'9' } == true) invalid()
            } else {
                consumeDigits(requireAtLeastOne = true)
            }
            if (consumeIf('.')) consumeDigits(requireAtLeastOne = true)
            if (peek() == 'e' || peek() == 'E') {
                index++
                if (peek() == '+' || peek() == '-') index++
                consumeDigits(requireAtLeastOne = true)
            }
            return source.substring(start, index)
        }

        private fun consumeDigits(requireAtLeastOne: Boolean) {
            val start = index
            while (peek()?.let { it in '0'..'9' } == true) index++
            if (requireAtLeastOne && start == index) invalid()
        }

        private fun <T : Value> parseLiteral(literal: String, value: T): T {
            if (!source.regionMatches(index, literal, 0, literal.length)) invalid()
            index += literal.length
            return value
        }

        private fun skipWhitespace() {
            while (peek() == ' ' || peek() == '\n' || peek() == '\r' || peek() == '\t') index++
        }

        private fun expect(expected: Char) {
            if (!consumeIf(expected)) invalid()
        }

        private fun consumeIf(expected: Char): Boolean {
            if (peek() != expected) return false
            index++
            return true
        }

        private fun peek(): Char? = source.getOrNull(index)

        private fun hasUnpairedSurrogate(value: String): Boolean {
            var current = 0
            while (current < value.length) {
                val character = value[current]
                when {
                    Character.isHighSurrogate(character) -> {
                        if (current + 1 >= value.length ||
                            !Character.isLowSurrogate(value[current + 1])
                        ) {
                            return true
                        }
                        current += 2
                    }
                    Character.isLowSurrogate(character) -> return true
                    else -> current++
                }
            }
            return false
        }
    }

    private fun invalid(): Nothing = throw InvalidJsonException()

    private class InvalidJsonException : RuntimeException()

    private val MODEL_RESPONSE_LIMITS = Limits(
        maxJsonChars = 12_000,
        maxStringChars = 512,
        maxArrayItems = 64,
        maxDepth = 8
    )
    private val HEX_DIGITS = ('0'..'9') + ('a'..'f') + ('A'..'F')
}

internal fun Map<String, StrictKnowledgeJson.Value>.strictString(name: String): String? =
    (this[name] as? StrictKnowledgeJson.Value.StringValue)?.value

internal fun Map<String, StrictKnowledgeJson.Value>.strictInt(name: String): Int? =
    (this[name] as? StrictKnowledgeJson.Value.NumberValue)?.raw?.toIntOrNull()

internal fun Map<String, StrictKnowledgeJson.Value>.strictArray(name: String): List<StrictKnowledgeJson.Value>? =
    (this[name] as? StrictKnowledgeJson.Value.ArrayValue)?.values

internal fun StrictKnowledgeJson.Value.strictObject(): Map<String, StrictKnowledgeJson.Value>? =
    (this as? StrictKnowledgeJson.Value.ObjectValue)?.values
