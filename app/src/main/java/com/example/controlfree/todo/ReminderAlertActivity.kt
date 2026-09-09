package com.example.controlfree.todo

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
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

class ReminderAlertActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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

        val title = intent.getStringExtra("title") ?: "提醒"
        val content = intent.getStringExtra("content") ?: "您有一条提醒"
        val itemId = intent.getStringExtra("item_id") ?: ""
        val itemType = intent.getStringExtra("item_type") ?: ""

        setContent {
            val preferences = remember { com.example.controlfree.data.PreferenceManager(applicationContext) }
            val bgColor = preferences.getBackgroundColor()
            val themeColor = if (bgColor != 0) Color(bgColor) else BrandColors.Primary

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
                            text = "✨ 提醒到达 ✨",
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
                                    brush = Brush.radialGradient(
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
                            text = content,
                            fontSize = 14.sp,
                            color = descriptionColor,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                            lineHeight = 20.sp
                        )
                        Spacer(modifier = Modifier.height(28.dp))
                        Button(
                            onClick = {
                                // 点击“知道了”，清除通知栏中此提醒的通知
                                runCatching {
                                    val notificationId = itemId.hashCode()
                                    NotificationManagerCompat.from(applicationContext).cancel(notificationId)
                                }
                                finish()
                            },
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text("知道了", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
