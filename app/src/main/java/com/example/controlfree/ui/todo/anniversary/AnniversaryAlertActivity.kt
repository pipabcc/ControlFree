package com.example.controlfree.ui.todo.anniversary

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import com.example.controlfree.R
import com.example.controlfree.theme.BrandColors
import com.example.controlfree.GrowingPlantDrawable
import androidx.compose.ui.graphics.nativeCanvas
import com.example.controlfree.todo.TodoRepository
import com.example.controlfree.todo.anniversary.runtime.AnniversaryAlertReceiver
import kotlinx.coroutines.launch

class AnniversaryAlertActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 允许在锁屏上显示并点亮屏幕
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }

        val title = intent.getStringExtra("title") ?: "重要时刻"
        val message = intent.getStringExtra("message") ?: "你的时刻已到达！"
        val celebrationId = intent.getStringExtra("celebration_id")
        val anniversaryId = intent.getStringExtra("anniversary_id")

        setContent {
            val scope = rememberCoroutineScope()
            val repository = TodoRepository.getInstance(applicationContext)
            val preferences = remember { com.example.controlfree.data.PreferenceManager(applicationContext) }
            val bgColor = preferences.getBackgroundColor()
            val themeColor = if (bgColor != 0) androidx.compose.ui.graphics.Color(bgColor) else BrandColors.Primary

            // 半透明主题下呈现为居中卡片；点击卡片外区域不关闭，只能点“知道了”。
            val surfaceBgColor = if (bgColor != 0) Color(bgColor) else BrandColors.Canvas
            val surfaceContentColor = if (bgColor != 0) Color.White else BrandColors.TextPrimary
            val descriptionColor = if (bgColor != 0) Color.White.copy(alpha = 0.7f) else BrandColors.TextSecondary
            val borderStroke = BorderStroke(1.dp, themeColor.copy(alpha = if (bgColor != 0) 0.4f else 0.15f))

            Box(
                modifier = Modifier.fillMaxSize().padding(28.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    color = surfaceBgColor,
                    shape = RoundedCornerShape(24.dp),
                    border = borderStroke,
                    tonalElevation = 6.dp,
                    shadowElevation = 12.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "✨ 时刻到达 ✨",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = themeColor,
                            letterSpacing = 2.sp
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Box(
                            modifier = Modifier
                                .size(110.dp)
                                .background(
                                    brush = androidx.compose.ui.graphics.Brush.radialGradient(
                                        colors = listOf(themeColor.copy(alpha = 0.22f), Color.Transparent)
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            val plantDrawable = remember {
                                GrowingPlantDrawable(com.example.controlfree.growth.GrowthStage.SEEDLING).apply { start() }
                            }
                            androidx.compose.foundation.Canvas(
                                modifier = Modifier.size(82.dp)
                            ) {
                                plantDrawable.setBounds(0, 0, size.width.toInt(), size.height.toInt())
                                plantDrawable.draw(drawContext.canvas.nativeCanvas)
                            }
                        }
                        Spacer(modifier = Modifier.height(20.dp))
                        Text(
                            text = title,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = surfaceContentColor,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = message,
                            fontSize = 14.sp,
                            color = descriptionColor,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                            lineHeight = 20.sp
                        )
                        Spacer(modifier = Modifier.height(28.dp))
                        Button(
                            onClick = {
                                scope.launch {
                                    if (celebrationId != null) {
                                        repository.consumeCelebration(celebrationId, System.currentTimeMillis())
                                    }
                                    if (anniversaryId != null) {
                                        runCatching {
                                            NotificationManagerCompat.from(applicationContext).cancel(
                                                anniversaryId,
                                                AnniversaryAlertReceiver.NOTIFICATION_ALERT_ID
                                            )
                                        }
                                    }
                                    finish()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = themeColor, contentColor = Color.White),
                            shape = RoundedCornerShape(23.dp),
                            modifier = Modifier.width(180.dp).height(46.dp),
                            elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
                        ) {
                            Text("知道了", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
                        }
                    }
                }
            }
        }
    }
}
