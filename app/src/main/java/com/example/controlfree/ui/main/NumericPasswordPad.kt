package com.example.controlfree.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.controlfree.theme.BrandColors
import com.example.controlfree.security.CredentialStore

sealed interface NumericPasswordEdit {
    data class Digit(val value: Char) : NumericPasswordEdit {
        init {
            require(value in '0'..'9') { "数字密码按键只能提交数字" }
        }
    }

    data object Backspace : NumericPasswordEdit
    data object Clear : NumericPasswordEdit
}

/**
 * 数字密码的同步输入缓冲区。
 *
 * Compose 状态负责显示，缓冲区负责按触摸事件的真实到达顺序保存数字。即使多个按键在下一帧
 * 重组前连续到达，也不会因为事件闭包读到旧画面而覆盖前一位输入。
 */
internal class NumericPasswordInputBuffer(initialValue: String = "") {
    private val digits = StringBuilder().apply { append(initialValue) }

    val value: String
        get() = digits.toString()

    fun apply(
        edit: NumericPasswordEdit,
        inputLimit: Int,
        expectedLength: Int?
    ): NumericPasswordInputResult {
        val safeLimit = inputLimit.coerceIn(0, CredentialStore.MAX_PASSWORD_LENGTH)
        val changed = when (edit) {
            is NumericPasswordEdit.Digit -> if (digits.length < safeLimit) {
                digits.append(edit.value)
                true
            } else false
            NumericPasswordEdit.Backspace -> if (digits.isNotEmpty()) {
                digits.deleteCharAt(digits.lastIndex)
                true
            } else false
            NumericPasswordEdit.Clear -> {
                val hadValue = digits.isNotEmpty()
                digits.setLength(0)
                hadValue
            }
        }
        val snapshot = digits.toString()
        return NumericPasswordInputResult(
            value = snapshot,
            completedSnapshot = snapshot.takeIf {
                changed && edit is NumericPasswordEdit.Digit &&
                    shouldAutoVerifyPassword(it, expectedLength)
            }
        )
    }

    fun reset(value: String = ""): String {
        digits.setLength(0)
        digits.append(value)
        return digits.toString()
    }
}

internal data class NumericPasswordInputResult(
    val value: String,
    /** 最后一位数字在同一个触摸事件中生成的不可变验证快照。 */
    val completedSnapshot: String?
)

internal fun numericPasswordInputLimit(expectedLength: Int?): Int =
    expectedLength?.coerceIn(
        CredentialStore.MIN_PASSWORD_LENGTH,
        CredentialStore.MAX_PASSWORD_LENGTH
    ) ?: CredentialStore.MAX_PASSWORD_LENGTH

internal fun reduceNumericPassword(
    current: String,
    edit: NumericPasswordEdit,
    inputLimit: Int
): String = when (edit) {
    is NumericPasswordEdit.Digit -> {
        if (current.length < inputLimit.coerceIn(0, CredentialStore.MAX_PASSWORD_LENGTH)) {
            current + edit.value
        } else {
            current
        }
    }
    NumericPasswordEdit.Backspace -> current.dropLast(1)
    NumericPasswordEdit.Clear -> ""
}

internal fun shouldAutoVerifyPassword(input: String, expectedLength: Int?): Boolean =
    expectedLength != null && input.length == expectedLength

/** 不唤起输入法的 3×4 数字拨号键盘。 */
@Composable
fun NumericPasswordPad(
    value: String,
    onEdit: (NumericPasswordEdit) -> Unit,
    expectedLength: Int?,
    onConfirm: (() -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    keySize: Dp = 64.dp
) {
    val latestOnEdit = rememberUpdatedState(onEdit)
    val stableOnEdit = remember { { edit: NumericPasswordEdit -> latestOnEdit.value(edit) } }
    Column(
        modifier = modifier.fillMaxWidth().padding(bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(11.dp)
    ) {
        Text(
            text = if (value.isEmpty()) {
                "○  ○  ○  ○"
            } else {
                List(value.length) { "●" }.joinToString("  ")
            },
            color = BrandColors.TextPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2
        )
        Text(
            text = expectedLength?.let { "已输入 ${value.length}/$it 位" }
                ?: "已输入 ${value.length} 位",
            color = BrandColors.TextTertiary,
            fontSize = 12.sp
        )

        listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9")
        ).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally)
            ) {
                row.forEach { digit ->
                    NumericKey(
                        contentDescription = "数字 $digit",
                        enabled = enabled,
                        keySize = keySize,
                        onPress = { stableOnEdit(NumericPasswordEdit.Digit(digit.single())) }
                    ) { Text(digit, fontSize = 24.sp, fontWeight = FontWeight.Medium) }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            NumericKey(
                contentDescription = "清空输入",
                enabled = enabled,
                keySize = keySize,
                onPress = { stableOnEdit(NumericPasswordEdit.Clear) }
            ) { Icon(Icons.Default.ClearAll, contentDescription = null) }
            NumericKey(
                contentDescription = "数字 0",
                enabled = enabled,
                keySize = keySize,
                onPress = { stableOnEdit(NumericPasswordEdit.Digit('0')) }
            ) { Text("0", fontSize = 24.sp, fontWeight = FontWeight.Medium) }
            NumericKey(
                contentDescription = "删除一位",
                enabled = enabled,
                keySize = keySize,
                onPress = { stableOnEdit(NumericPasswordEdit.Backspace) }
            ) { Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = null) }
        }

        if (onConfirm != null) {
            Button(
                onClick = onConfirm,
                enabled = enabled && value.length >= CredentialStore.MIN_PASSWORD_LENGTH,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = BrandColors.Primary,
                    contentColor = BrandColors.OnPrimary
                )
            ) {
                Icon(Icons.Default.Check, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("确认输入", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun NumericKey(
    contentDescription: String,
    enabled: Boolean,
    keySize: Dp,
    onPress: () -> Unit,
    content: @Composable () -> Unit
) {
    val latestOnPress = rememberUpdatedState(onPress)
    Box(
        modifier = Modifier
            .size(keySize)
            .background(
                color = BrandColors.SurfaceRaised,
                shape = CircleShape
            )
            // 按下即录入：快速连点、双指交替或滚动容器抢占手势时，都不会漏掉已按下的键。
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        event.changes.forEach { change ->
                            if (change.changedToDown()) {
                                change.consume()
                                latestOnPress.value()
                            }
                        }
                    }
                }
            }
            .semantics {
                this.contentDescription = contentDescription
                role = Role.Button
                if (!enabled) disabled()
                onClick {
                    if (enabled) {
                        latestOnPress.value()
                        true
                    } else {
                        false
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides if (enabled) {
                BrandColors.TextPrimary
            } else {
                BrandColors.TextTertiary
            }
        ) {
            content()
        }
    }
}
