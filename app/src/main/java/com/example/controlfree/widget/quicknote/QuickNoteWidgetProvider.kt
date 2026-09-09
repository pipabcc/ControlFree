package com.example.controlfree.widget.quicknote

import android.content.Context
import android.content.Intent
import android.widget.RemoteViewsService
import com.example.controlfree.widget.list.ListWidgetFactory
import com.example.controlfree.widget.list.ListWidgetProvider
import com.example.controlfree.widget.list.ListWidgetType

class QuickNoteWidgetProvider : ListWidgetProvider() {
    override val widgetType = ListWidgetType.QUICK_NOTE

    companion object {
        fun updateAll(context: Context) = ListWidgetProvider.updateAll(
            context,
            ListWidgetType.QUICK_NOTE,
            QuickNoteWidgetProvider::class.java
        )

        fun refreshCollection(context: Context) = ListWidgetProvider.refreshCollection(
            context,
            QuickNoteWidgetProvider::class.java
        )
    }
}

class QuickNoteWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        object : ListWidgetFactory(applicationContext, ListWidgetType.QUICK_NOTE) {}
}
