package com.example.controlfree.widget.plan

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.example.controlfree.supervision.persistence.PlanLoadResult
import com.example.controlfree.supervision.persistence.PlanWriteResult
import com.example.controlfree.supervision.persistence.SupervisionPlanRepository
import com.example.controlfree.supervision.runtime.SupervisionScheduleReceiver
import com.example.controlfree.security.CredentialStore
import com.example.controlfree.widget.ControlFreeWidgetType
import com.example.controlfree.widget.PlanWidgetPreferences
import com.example.controlfree.widget.WidgetRefreshCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

class PlanWidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SET_PLAN_ENABLED) return
        val widgetId = intent.getIntExtra(EXTRA_WIDGET_ID, -1)
        val type = intent.getStringExtra(EXTRA_WIDGET_TYPE)
            ?.let { stored -> ControlFreeWidgetType.entries.firstOrNull { it.name == stored } }
            ?: return
        val targetEnabled = intent.takeIf { it.hasExtra(EXTRA_TARGET_ENABLED) }
            ?.getBooleanExtra(EXTRA_TARGET_ENABLED, false)
            ?: return
        val expectedPlanId = intent.getStringExtra(EXTRA_EXPECTED_PLAN_ID) ?: return
        if (
            widgetId < 0 ||
            type !in PLAN_WIDGET_TYPES ||
            requiresPlanWidgetAuthentication(
                type = type,
                targetEnabled = targetEnabled,
                hasCredentials = CredentialStore(context.applicationContext).hasAnyCredential()
            )
        ) {
            return
        }
        val pendingResult = goAsync()
        receiverScope.launch {
            val message = try {
                withTimeout(ACTION_TIMEOUT_MILLIS) {
                    PlanWidgetActionExecutor.setEnabled(
                        context = context.applicationContext,
                        type = type,
                        appWidgetId = widgetId,
                        expectedPlanId = expectedPlanId,
                        targetEnabled = targetEnabled
                    )
                }
            } catch (_: TimeoutCancellationException) {
                "操作超时，请稍后重试"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                "操作失败，请稍后重试"
            } finally {
                WidgetRefreshCoordinator.refreshPlans(context.applicationContext)
                pendingResult.finish()
            }
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        const val ACTION_SET_PLAN_ENABLED =
            "com.example.controlfree.action.SET_WIDGET_PLAN_ENABLED"
        internal const val EXTRA_WIDGET_ID = "app_widget_id"
        internal const val EXTRA_WIDGET_TYPE = "widget_type"
        internal const val EXTRA_TARGET_ENABLED = "target_enabled"
        internal const val EXTRA_EXPECTED_PLAN_ID = "expected_plan_id"
        internal const val ACTION_TIMEOUT_MILLIS = 8_000L
        private val PLAN_WIDGET_TYPES = setOf(
            ControlFreeWidgetType.SUPERVISION,
            ControlFreeWidgetType.FOCUS
        )
        private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun pendingIntent(
            context: Context,
            type: ControlFreeWidgetType,
            appWidgetId: Int,
            expectedPlanId: String,
            targetEnabled: Boolean
        ): PendingIntent = PendingIntent.getBroadcast(
            context,
            50_000 + type.ordinal * 10_000 + appWidgetId,
            Intent(context, PlanWidgetActionReceiver::class.java).apply {
                action = ACTION_SET_PLAN_ENABLED
                data = Uri.parse(
                    "controlfree://widget/plan-action/${type.name.lowercase()}/" +
                        "$appWidgetId/$targetEnabled"
                )
                putExtra(EXTRA_WIDGET_ID, appWidgetId)
                putExtra(EXTRA_WIDGET_TYPE, type.name)
                putExtra(EXTRA_TARGET_ENABLED, targetEnabled)
                putExtra(EXTRA_EXPECTED_PLAN_ID, expectedPlanId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

internal object PlanWidgetActionExecutor {
    private val actionMutex = Mutex()

    suspend fun setEnabled(
        context: Context,
        type: ControlFreeWidgetType,
        appWidgetId: Int,
        expectedPlanId: String,
        targetEnabled: Boolean
    ): String = actionMutex.withLock {
        val planId = PlanWidgetPreferences(context, type).planId(appWidgetId)
            ?: return "该组件尚未绑定任务"
        if (planId != expectedPlanId) return "组件绑定已变化，请重试"
        val repository = SupervisionPlanRepository.getInstance(context)
        val loaded = repository.loadPlans()
        if (loaded !is PlanLoadResult.Success) return "任务数据暂不可用"
        val plan = loaded.plans.firstOrNull { it.id == planId }
            ?: return "任务已不存在"
        if (plan.type !in compatiblePlanTypes(type)) return "组件与任务类型不匹配"
        if (plan.enabled == targetEnabled) {
            return if (targetEnabled) "计划已开启" else "计划已关闭"
        }
        if (plan.updatedAtEpochMillis == Long.MAX_VALUE) return "任务版本已达上限"
        val result = repository.setEnabled(
            planId = plan.id,
            enabled = targetEnabled,
            expectedUpdatedAtEpochMillis = plan.updatedAtEpochMillis,
            updatedAtEpochMillis = maxOf(
                System.currentTimeMillis().coerceAtLeast(0L),
                plan.updatedAtEpochMillis + 1L
            )
        )
        if (result is PlanWriteResult.Success) {
            SupervisionScheduleReceiver.requestReconciliation(context, "widget_plan_toggled")
        }
        return when (result) {
            is PlanWriteResult.Success -> if (targetEnabled) "计划已开启" else "计划已关闭"
            is PlanWriteResult.Conflicts -> "无法开启：与其他计划时间冲突"
            is PlanWriteResult.InvalidInput -> result.reason
            is PlanWriteResult.NotFound -> "任务已不存在"
            is PlanWriteResult.StaleData -> "任务刚刚发生变化，请重试"
            is PlanWriteResult.CorruptData -> "任务数据异常"
            PlanWriteResult.StorageFailure -> "保存失败，请检查存储空间"
            is PlanWriteResult.DuplicateIds,
            is PlanWriteResult.NonIncreasingVersion -> "任务版本异常"
        }
    }
}

internal fun requiresPlanWidgetAuthentication(
    type: ControlFreeWidgetType,
    targetEnabled: Boolean,
    hasCredentials: Boolean
): Boolean = type == ControlFreeWidgetType.SUPERVISION && !targetEnabled && hasCredentials
