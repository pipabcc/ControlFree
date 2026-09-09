package com.example.controlfree.ui.main

import com.example.controlfree.security.CredentialStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NumericPasswordPadTest {
    @Test
    fun `连续数字编辑按点击顺序完整保留`() {
        val result = reduceAll("123456".map(NumericPasswordEdit::Digit), inputLimit = 6)

        assertEquals("123456", result)
    }

    @Test
    fun `同一个数字连续点击不会互相覆盖`() {
        val result = reduceAll(List(6) { NumericPasswordEdit.Digit('8') }, inputLimit = 6)

        assertEquals("888888", result)
    }

    @Test
    fun `达到预期长度后忽略额外数字`() {
        val result = reduceAll("123456".map(NumericPasswordEdit::Digit), inputLimit = 4)

        assertEquals("1234", result)
    }

    @Test
    fun `未知长度最多接受密码上限位数`() {
        val edits = List(CredentialStore.MAX_PASSWORD_LENGTH + 3) {
            NumericPasswordEdit.Digit(('0'.code + it % 10).toChar())
        }
        val result = reduceAll(
            edits,
            inputLimit = numericPasswordInputLimit(expectedLength = null)
        )

        assertEquals(CredentialStore.MAX_PASSWORD_LENGTH, result.length)
        assertEquals("0123456789".repeat(7).take(CredentialStore.MAX_PASSWORD_LENGTH), result)
    }

    @Test
    fun `数字退格与清空按事件顺序执行`() {
        val result = reduceAll(
            listOf(
                NumericPasswordEdit.Digit('1'),
                NumericPasswordEdit.Digit('2'),
                NumericPasswordEdit.Backspace,
                NumericPasswordEdit.Digit('3'),
                NumericPasswordEdit.Clear,
                NumericPasswordEdit.Backspace,
                NumericPasswordEdit.Digit('4')
            ),
            inputLimit = 6
        )

        assertEquals("4", result)
    }

    @Test
    fun `只有达到已知密码长度时才自动验证`() {
        assertFalse(shouldAutoVerifyPassword("1234", null))
        assertFalse(shouldAutoVerifyPassword("123", 4))
        assertTrue(shouldAutoVerifyPassword("1234", 4))
        assertFalse(shouldAutoVerifyPassword("12345", 4))
    }

    @Test
    fun `同步缓冲在同一帧连续输入时不依赖画面重组`() {
        val buffer = NumericPasswordInputBuffer()
        val completions = buildList {
            "123456".forEach { digit ->
                buffer.apply(
                    edit = NumericPasswordEdit.Digit(digit),
                    inputLimit = 6,
                    expectedLength = 6
                ).completedSnapshot?.let(::add)
            }
        }

        assertEquals("123456", buffer.value)
        assertEquals(listOf("123456"), completions)
    }

    @Test
    fun `达到上限后的快速连点不会重复触发验证`() {
        val buffer = NumericPasswordInputBuffer()
        val completions = List(20) {
            buffer.apply(
                edit = NumericPasswordEdit.Digit('8'),
                inputLimit = 6,
                expectedLength = 6
            ).completedSnapshot
        }.filterNotNull()

        assertEquals("888888", buffer.value)
        assertEquals(listOf("888888"), completions)
    }

    @Test
    fun `重置后旧输入不会污染下一次验证快照`() {
        val buffer = NumericPasswordInputBuffer("1234")
        buffer.reset()
        var result: NumericPasswordInputResult? = null
        "5678".forEach { digit ->
            result = buffer.apply(
                edit = NumericPasswordEdit.Digit(digit),
                inputLimit = 4,
                expectedLength = 4
            )
        }

        assertEquals("5678", result?.completedSnapshot)
    }

    private fun reduceAll(
        edits: List<NumericPasswordEdit>,
        inputLimit: Int
    ): String = edits.fold("") { current, edit ->
        reduceNumericPassword(current, edit, inputLimit)
    }
}
