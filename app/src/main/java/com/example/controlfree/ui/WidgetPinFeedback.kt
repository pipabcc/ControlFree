package com.example.controlfree.ui

import android.content.Context
import android.widget.Toast
import com.example.controlfree.widget.WidgetPinRequestResult

internal fun Context.showWidgetPinResult(result: WidgetPinRequestResult) {
    val message = when (result) {
        WidgetPinRequestResult.REQUESTED -> "已请求添加桌面组件"
        WidgetPinRequestResult.UNSUPPORTED -> "当前桌面不支持快捷添加组件"
        WidgetPinRequestResult.FAILED -> "无法添加组件，请稍后重试"
    }
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
