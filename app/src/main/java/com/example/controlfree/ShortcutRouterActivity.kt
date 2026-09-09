package com.example.controlfree

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * 将桌面快捷方式的白名单动作转换为主界面导航参数。
 *
 * 此 Activity 不显示界面，也不触发服务、权限申请或身份认证逻辑；后续流程仍完全由
 * [MainActivity] 及目标页面负责。
 */
class ShortcutRouterActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        route(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        route(intent)
    }

    private fun route(sourceIntent: Intent?) {
        val destination = AppShortcutRoute.destinationForAction(sourceIntent?.action)
        if (destination != null) {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    // 快捷方式启动器可能会清空其首个 Intent 所在的临时任务；显式回到
                    // 主应用 affinity，才能复用已有 MainActivity 且不误清空其任务栈。
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(AppShortcutRoute.EXTRA_DESTINATION, destination.name)
                }
            )
        }
        finish()
    }
}
