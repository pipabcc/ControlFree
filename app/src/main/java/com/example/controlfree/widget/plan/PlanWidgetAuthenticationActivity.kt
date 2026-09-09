package com.example.controlfree.widget.plan

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.example.controlfree.data.PreferenceManager
import com.example.controlfree.security.CredentialStore
import com.example.controlfree.theme.BrandColors
import com.example.controlfree.theme.ControlFreeTheme
import com.example.controlfree.ui.main.AuthenticationGate
import com.example.controlfree.widget.ControlFreeWidgetType
import com.example.controlfree.widget.WidgetRefreshCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class PlanWidgetAuthenticationActivity : ComponentActivity() {
    private lateinit var request: PlanWidgetDisableRequest
    private lateinit var credentials: CredentialStore
    private var actionStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        request = readRequest(intent) ?: run {
            finish()
            return
        }
        credentials = CredentialStore(applicationContext)
        val preferences = PreferenceManager(applicationContext)
        setContent {
            ControlFreeTheme(
                darkTheme = preferences.isDarkThemeEnabled(),
                bgType = preferences.getBackgroundType(),
                bgColor = preferences.getBackgroundColor(),
                bgGradient = preferences.getBackgroundGradient(),
                bgImageIndex = preferences.getBackgroundImageIndex()
            ) {
                if (credentials.hasAnyCredential()) {
                    AuthenticationGate(credentials = credentials, onUnlocked = ::disablePlan)
                } else {
                    PlanWidgetActionProgress()
                }
            }
        }
        if (!credentials.hasAnyCredential()) disablePlan()
    }

    private fun disablePlan() {
        if (actionStarted) return
        actionStarted = true
        lifecycleScope.launch {
            val message = try {
                withContext(Dispatchers.IO) {
                    withTimeout(PlanWidgetActionReceiver.ACTION_TIMEOUT_MILLIS) {
                        PlanWidgetActionExecutor.setEnabled(
                            context = applicationContext,
                            type = request.type,
                            appWidgetId = request.appWidgetId,
                            expectedPlanId = request.expectedPlanId,
                            targetEnabled = false
                        )
                    }
                }
            } catch (_: TimeoutCancellationException) {
                "操作超时，请稍后重试"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                "操作失败，请稍后重试"
            }
            WidgetRefreshCoordinator.refreshPlans(applicationContext)
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    companion object {
        fun pendingIntent(
            context: Context,
            type: ControlFreeWidgetType,
            appWidgetId: Int,
            expectedPlanId: String
        ): PendingIntent = PendingIntent.getActivity(
            context,
            150_000 + type.ordinal * 10_000 + appWidgetId,
            Intent(context, PlanWidgetAuthenticationActivity::class.java).apply {
                data = Uri.parse(
                    "controlfree://widget/plan-auth/${type.name.lowercase()}/$appWidgetId"
                )
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                putExtra(PlanWidgetActionReceiver.EXTRA_WIDGET_TYPE, type.name)
                putExtra(PlanWidgetActionReceiver.EXTRA_EXPECTED_PLAN_ID, expectedPlanId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        private fun readRequest(intent: Intent): PlanWidgetDisableRequest? {
            val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
            val type = intent.getStringExtra(PlanWidgetActionReceiver.EXTRA_WIDGET_TYPE)
                ?.let { stored ->
                    ControlFreeWidgetType.entries.firstOrNull { it.name == stored }
                }
            val expectedPlanId = intent
                .getStringExtra(PlanWidgetActionReceiver.EXTRA_EXPECTED_PLAN_ID)
                ?.trim()
                ?.takeIf { it.length in 1..MAX_PLAN_ID_LENGTH && it.none(Char::isISOControl) }
            return if (
                appWidgetId >= 0 &&
                type == ControlFreeWidgetType.SUPERVISION &&
                expectedPlanId != null
            ) {
                PlanWidgetDisableRequest(appWidgetId, type, expectedPlanId)
            } else {
                null
            }
        }

        private const val MAX_PLAN_ID_LENGTH = 256
    }
}

private data class PlanWidgetDisableRequest(
    val appWidgetId: Int,
    val type: ControlFreeWidgetType,
    val expectedPlanId: String
)

@Composable
private fun PlanWidgetActionProgress() {
    Box(
        modifier = Modifier.fillMaxSize().background(BrandColors.OverlaySurface),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator(color = BrandColors.Primary)
            Spacer(Modifier.height(12.dp))
            Text("正在关闭监督任务", color = BrandColors.TextSecondary)
        }
    }
}
