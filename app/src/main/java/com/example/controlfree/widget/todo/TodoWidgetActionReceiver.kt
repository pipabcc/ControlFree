package com.example.controlfree.widget.todo

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.controlfree.AppShortcutDestination
import com.example.controlfree.AppShortcutRoute
import com.example.controlfree.MainActivity
import com.example.controlfree.supervision.commitment.CommitmentScheduleReceiver
import com.example.controlfree.supervision.runtime.SupervisionScheduleReceiver
import com.example.controlfree.todo.TodoRepository
import com.example.controlfree.ui.todo.TodoSubTab
import com.example.controlfree.widget.WidgetNavigationContract
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

internal enum class TodoWidgetRowOperation {
    OPEN_ITEM,
    SET_COMPLETION
}

internal data class TodoWidgetRowAction(
    val operation: TodoWidgetRowOperation,
    val itemId: String,
    val targetCompleted: Boolean? = null
)

internal fun resolveTodoWidgetRowAction(
    operationValue: String?,
    itemIdValue: String?,
    targetCompleted: Boolean?
): TodoWidgetRowAction? {
    val operation = TodoWidgetRowOperation.entries.firstOrNull { it.name == operationValue }
        ?: return null
    val itemId = itemIdValue
        ?.trim()
        ?.takeIf { value ->
            value.length in 1..MAX_WIDGET_ITEM_ID_LENGTH && value.none { it.isISOControl() }
        }
        ?: return null
    return when (operation) {
        TodoWidgetRowOperation.OPEN_ITEM -> if (targetCompleted == null) {
            TodoWidgetRowAction(operation, itemId)
        } else {
            null
        }
        TodoWidgetRowOperation.SET_COMPLETION -> targetCompleted?.let { target ->
            TodoWidgetRowAction(operation, itemId, target)
        }
    }
}

class TodoWidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_WIDGET_TODO_ROW) return
        val targetCompleted = if (intent.hasExtra(EXTRA_TARGET_COMPLETED)) {
            intent.getBooleanExtra(EXTRA_TARGET_COMPLETED, false)
        } else {
            null
        }
        val rowAction = resolveTodoWidgetRowAction(
            operationValue = intent.getStringExtra(EXTRA_OPERATION),
            itemIdValue = intent.getStringExtra(WidgetNavigationContract.EXTRA_ITEM_ID),
            targetCompleted = targetCompleted
        ) ?: return
        when (rowAction.operation) {
            TodoWidgetRowOperation.OPEN_ITEM -> openItem(context, rowAction.itemId)
            TodoWidgetRowOperation.SET_COMPLETION -> updateCompletion(context, rowAction)
        }
    }

    private fun openItem(context: Context, itemId: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(AppShortcutRoute.EXTRA_DESTINATION, AppShortcutDestination.TODO.name)
            putExtra(TodoSubTab.EXTRA_NAVIGATION_TARGET, TodoSubTab.TODO.name)
            putExtra(WidgetNavigationContract.EXTRA_ITEM_ID, itemId)
        }
        runCatching { context.startActivity(intent) }
    }

    private fun updateCompletion(context: Context, rowAction: TodoWidgetRowAction) {
        val targetCompleted = rowAction.targetCompleted ?: return
        val appContext = context.applicationContext
        val pendingResult = goAsync()
        receiverScope.launch {
            try {
                withTimeout(ACTION_TIMEOUT_MILLIS) {
                    actionMutex.withLock {
                        val updated = TodoRepository.getInstance(appContext).toggleTodoCompletion(
                            id = rowAction.itemId,
                            isCompleted = targetCompleted
                        )
                        if (updated != null) {
                            CommitmentScheduleReceiver.requestReconciliation(
                                appContext,
                                "widget_todo_completion_changed"
                            )
                            SupervisionScheduleReceiver.requestReconciliation(
                                appContext,
                                "widget_todo_completion_changed"
                            )
                        }
                    }
                }
            } catch (_: TimeoutCancellationException) {
                Unit
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                Unit
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val ACTION_WIDGET_TODO_ROW =
            "com.example.controlfree.action.WIDGET_TODO_ROW"
        private const val EXTRA_OPERATION = "widget_todo_operation"
        private const val EXTRA_TARGET_COMPLETED = "widget_todo_target_completed"
        private const val ACTION_TIMEOUT_MILLIS = 8_000L
        private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val actionMutex = Mutex()

        internal fun pendingIntentTemplate(context: Context, appWidgetId: Int): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                130_000 + appWidgetId,
                Intent(context, TodoWidgetActionReceiver::class.java).apply {
                    action = ACTION_WIDGET_TODO_ROW
                    data = Uri.parse("controlfree://widget/todo-action/$appWidgetId")
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )

        internal fun openItemFillInIntent(itemId: String): Intent = Intent()
            .putExtra(EXTRA_OPERATION, TodoWidgetRowOperation.OPEN_ITEM.name)
            .putExtra(WidgetNavigationContract.EXTRA_ITEM_ID, itemId)

        internal fun completionFillInIntent(
            itemId: String,
            targetCompleted: Boolean
        ): Intent = Intent()
            .putExtra(EXTRA_OPERATION, TodoWidgetRowOperation.SET_COMPLETION.name)
            .putExtra(WidgetNavigationContract.EXTRA_ITEM_ID, itemId)
            .putExtra(EXTRA_TARGET_COMPLETED, targetCompleted)
    }
}

private const val MAX_WIDGET_ITEM_ID_LENGTH = 256
