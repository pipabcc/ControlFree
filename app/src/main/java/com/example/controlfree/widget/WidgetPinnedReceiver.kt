package com.example.controlfree.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class WidgetPinnedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_WIDGET_PINNED) return
        WidgetRefreshCoordinator.refreshAll(context.applicationContext)
    }

    companion object {
        const val ACTION_WIDGET_PINNED = "com.example.controlfree.action.WIDGET_PINNED"
        const val EXTRA_WIDGET_TYPE = "widget_type"
    }
}
