package com.example.controlfree.widget.plan

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.example.controlfree.supervision.SupervisionPlan
import com.example.controlfree.supervision.persistence.PlanLoadResult
import com.example.controlfree.supervision.persistence.SupervisionPlanRepository
import com.example.controlfree.theme.BrandColors
import com.example.controlfree.theme.ControlFreeTheme
import com.example.controlfree.widget.ControlFreeWidgetType
import com.example.controlfree.widget.PendingPlanWidgetRequestStore
import com.example.controlfree.widget.PlanWidgetPreferences
import com.example.controlfree.widget.WidgetBindingOptions
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

abstract class PlanWidgetConfigActivity : ComponentActivity() {
    protected abstract val widgetType: ControlFreeWidgetType
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var screenState by mutableStateOf<PlanConfigScreenState>(PlanConfigScreenState.Loading)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        appWidgetId = intent?.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        setContent {
            ControlFreeTheme {
                Surface(
                    color = BrandColors.Canvas,
                    modifier = Modifier.fillMaxSize()
                ) {
                    PlanConfigScreen(
                        type = widgetType,
                        state = screenState,
                        onSelect = ::select
                    )
                }
            }
        }
        loadPlans()
    }

    private fun loadPlans() {
        lifecycleScope.launch {
            val appContext = applicationContext
            val loaded = withContext(Dispatchers.IO) {
                SupervisionPlanRepository.getInstance(appContext).loadPlans()
            }
            val plans = when (loaded) {
                is PlanLoadResult.Success -> loaded.plans.filter {
                    it.type in compatiblePlanTypes(widgetType)
                }
                is PlanLoadResult.CorruptData,
                PlanLoadResult.StorageFailure -> {
                    screenState = PlanConfigScreenState.Error("任务数据暂不可用，请返回应用检查")
                    return@launch
                }
            }
            val manager = AppWidgetManager.getInstance(appContext)
            val optionsPlanId = runCatching {
                manager.getAppWidgetOptions(appWidgetId)
                    .getString(WidgetBindingOptions.OPTION_PLAN_ID)
            }.getOrNull()
            val pendingPlanId = PendingPlanWidgetRequestStore(appContext).consume(
                widgetType,
                System.currentTimeMillis().coerceAtLeast(0L)
            )
            val requestedPlan = plans.firstOrNull { it.id == optionsPlanId }
                ?: plans.firstOrNull { it.id == pendingPlanId }
            if (requestedPlan != null) {
                completeSelection(requestedPlan.id)
            } else {
                screenState = PlanConfigScreenState.Ready(plans)
            }
        }
    }

    private fun select(planId: String) {
        if (screenState is PlanConfigScreenState.Saving) return
        screenState = PlanConfigScreenState.Saving
        lifecycleScope.launch { completeSelection(planId) }
    }

    private suspend fun completeSelection(planId: String) {
        val appContext = applicationContext
        val saved = withContext(Dispatchers.IO) {
            PlanWidgetPreferences(appContext, widgetType).bind(appWidgetId, planId)
        }
        if (!saved) {
            screenState = PlanConfigScreenState.Error("组件设置保存失败，请重试")
            return
        }
        withContext(Dispatchers.IO) {
            BoundPlanWidgetRenderer.update(
                context = appContext,
                type = widgetType,
                manager = AppWidgetManager.getInstance(appContext),
                appWidgetIds = intArrayOf(appWidgetId)
            )
        }
        setResult(
            RESULT_OK,
            Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        )
        finish()
    }
}

class SupervisionWidgetConfigActivity : PlanWidgetConfigActivity() {
    override val widgetType = ControlFreeWidgetType.SUPERVISION
}

class FocusWidgetConfigActivity : PlanWidgetConfigActivity() {
    override val widgetType = ControlFreeWidgetType.FOCUS
}

private sealed interface PlanConfigScreenState {
    data object Loading : PlanConfigScreenState
    data object Saving : PlanConfigScreenState
    data class Ready(val plans: List<SupervisionPlan>) : PlanConfigScreenState
    data class Error(val message: String) : PlanConfigScreenState
}

@Composable
private fun PlanConfigScreen(
    type: ControlFreeWidgetType,
    state: PlanConfigScreenState,
    onSelect: (String) -> Unit
) {
    Column(modifier = Modifier.padding(20.dp)) {
        Text(
            if (type == ControlFreeWidgetType.FOCUS) "选择专注任务" else "选择监督任务",
            fontWeight = FontWeight.Bold,
            color = BrandColors.TextPrimary
        )
        Text(
            "每个桌面组件绑定一个任务，可直接查看和启停",
            color = BrandColors.TextSecondary,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
        )
        when (state) {
            PlanConfigScreenState.Loading,
            PlanConfigScreenState.Saving -> Row(
                modifier = Modifier.fillMaxWidth().padding(top = 28.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(color = BrandColors.Primary)
            }
            is PlanConfigScreenState.Error -> Text(
                state.message,
                color = BrandColors.Danger
            )
            is PlanConfigScreenState.Ready -> if (state.plans.isEmpty()) {
                Text(
                    if (type == ControlFreeWidgetType.FOCUS) {
                        "请先在专注页创建专注任务"
                    } else {
                        "请先在监督页创建监督任务"
                    },
                    color = BrandColors.TextTertiary
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.plans, key = SupervisionPlan::id) { plan ->
                        val presentation = planWidgetPresentation(
                            plan = plan,
                            isActive = false,
                            now = Instant.now(),
                            deviceZoneId = ZoneId.systemDefault()
                        )
                        Surface(
                            color = BrandColors.SurfaceCard,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth().clickable { onSelect(plan.id) }
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        plan.name,
                                        color = BrandColors.TextPrimary,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        "${presentation.category} · ${presentation.status}",
                                        color = BrandColors.TextSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Text("选择", color = BrandColors.Primary)
                            }
                        }
                    }
                }
            }
        }
    }
}
