package com.example.controlfree.ui.main

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.controlfree.data.LockUnlockFeaturePreferences
import com.example.controlfree.theme.BrandColors

@Composable
internal fun LockUnlockFeatureSettingsCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val preferences = remember(context.applicationContext) {
        LockUnlockFeaturePreferences(context.applicationContext)
    }
    var growthEnabled by remember { mutableStateOf(preferences.growthUnlockEnabled) }
    var challengeEnabled by remember { mutableStateOf(preferences.knowledgeChallengeEnabled) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    fun updateGrowth(enabled: Boolean) {
        runCatching { preferences.growthUnlockEnabled = enabled }
            .onSuccess {
                growthEnabled = enabled
                statusMessage = null
            }
            .onFailure { statusMessage = "成长值解锁开关保存失败" }
    }

    fun updateChallenge(enabled: Boolean) {
        runCatching { preferences.knowledgeChallengeEnabled = enabled }
            .onSuccess {
                challengeEnabled = enabled
                statusMessage = null
            }
            .onFailure { statusMessage = "百科挑战开关保存失败" }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = BrandColors.SurfaceCard),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.LockOpen, contentDescription = null, tint = BrandColors.Primary)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("锁屏解锁方式", color = BrandColors.TextPrimary, fontSize = 18.sp)
                    Text(
                        "分别控制锁屏小芽建议卡中的解锁入口",
                        color = BrandColors.TextSecondary,
                        fontSize = 12.sp
                    )
                }
            }
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 10.dp),
                color = BrandColors.OutlineSoft
            )
            LockUnlockFeatureToggle(
                title = "成长值解锁",
                description = "显示使用成长值暂停或跳过的入口",
                checked = growthEnabled,
                onCheckedChange = ::updateGrowth
            )
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 6.dp),
                color = BrandColors.OutlineSoft
            )
            LockUnlockFeatureToggle(
                title = "百科挑战解锁",
                description = "显示答题通关免成长值的入口；仍需密码或手势",
                checked = challengeEnabled,
                onCheckedChange = ::updateChallenge
            )
            statusMessage?.let {
                Text(
                    it,
                    color = BrandColors.Warning,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

@Composable
internal fun LockUnlockFeatureToggle(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Switch) { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = BrandColors.TextPrimary, fontSize = 14.sp)
            Text(description, color = BrandColors.TextSecondary, fontSize = 11.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = BrandColors.Primary,
                uncheckedThumbColor = if (androidx.compose.foundation.isSystemInDarkTheme()) Color(0xFF94A3B8) else Color(0xFFFFFFFF),
                uncheckedTrackColor = if (androidx.compose.foundation.isSystemInDarkTheme()) Color(0xFF334155) else Color(0xFFE2E8F0),
                uncheckedBorderColor = Color.Transparent
            )
        )
    }
}
