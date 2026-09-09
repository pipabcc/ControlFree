package com.example.controlfree.todo.anniversary.widget

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.example.controlfree.supervision.persistence.ControlFreeDatabase
import com.example.controlfree.theme.BrandColors
import com.example.controlfree.theme.ControlFreeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AnniversaryWidgetConfigActivity : ComponentActivity() {
    private var appWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID

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
        val dao = ControlFreeDatabase.getInstance(applicationContext).anniversaryDao()
        setContent {
            ControlFreeTheme {
                val items by dao.observeAll().collectAsState(initial = emptyList())
                Surface(color = BrandColors.Canvas, modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text("选择时刻", fontWeight = FontWeight.Bold, color = BrandColors.TextPrimary)
                        Text(
                            "每个桌面组件显示一个倒数日或正数日",
                            color = BrandColors.TextSecondary,
                            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
                        )
                        if (items.isEmpty()) {
                            Text("请先在清单中创建时刻", color = BrandColors.TextTertiary)
                        } else {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(items, key = { it.id }) { item ->
                                    Surface(
                                        color = BrandColors.SurfaceCard,
                                        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { select(item.id) }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(14.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            if (item.isPinnedTop) {
                                                Icon(
                                                    Icons.Default.PushPin,
                                                    contentDescription = "已置顶",
                                                    tint = BrandColors.Primary,
                                                    modifier = Modifier.padding(end = 10.dp)
                                                )
                                            }
                                            Column {
                                                Text(item.title, color = BrandColors.TextPrimary)
                                                Text(
                                                    if (item.type == "COUNTDOWN") "倒数" else "正数",
                                                    color = BrandColors.TextSecondary
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun select(anniversaryId: String) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                AnniversaryWidgetPreferences(applicationContext).bind(appWidgetId, anniversaryId)
                ControlFreeDatabase.getInstance(applicationContext)
                    .anniversaryDao()
                    .setWidgetEnabled(anniversaryId, true, System.currentTimeMillis())
                AnniversaryWidgetProvider.updateWidget(
                    applicationContext,
                    AppWidgetManager.getInstance(applicationContext),
                    appWidgetId
                )
            }
            setResult(
                RESULT_OK,
                Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            )
            finish()
        }
    }
}
