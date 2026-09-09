package com.example.controlfree.widget.todo

import android.content.Context
import android.content.Intent
import android.widget.RemoteViewsService
import com.example.controlfree.widget.list.ListWidgetFactory
import com.example.controlfree.widget.list.ListWidgetProvider
import com.example.controlfree.widget.list.ListWidgetType

class TodoWidgetProvider : ListWidgetProvider() {
    override val widgetType = ListWidgetType.TODO

    companion object {
        fun updateAll(context: Context) = ListWidgetProvider.updateAll(
            context,
            ListWidgetType.TODO,
            TodoWidgetProvider::class.java
        )

        fun refreshCollection(context: Context) = ListWidgetProvider.refreshCollection(
            context,
            TodoWidgetProvider::class.java
        )
    }
}

class TodoWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        object : ListWidgetFactory(applicationContext, ListWidgetType.TODO) {}
}
