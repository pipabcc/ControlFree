package com.example.controlfree.ui.todo.commitment

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.controlfree.data.AllowedApp
import com.example.controlfree.theme.BrandColors

data class CommitmentEditorSettings(
    val enabled: Boolean = false,
    val localDeadlineMinute: Int? = null,
    val graceMinutes: Int = 0,
    val maxLockMinutes: Int = 60,
    val blockedPackages: Set<String> = emptySet()
) {
    init {
        require(localDeadlineMinute == null || localDeadlineMinute in 0..1_439)
        require(graceMinutes in 0..1_440)
        require(maxLockMinutes in 1..1_440)
    }
}

@Composable
fun CommitmentPolicyEditor(
    settings: CommitmentEditorSettings,
    apps: List<AllowedApp>,
    isLoadingApps: Boolean,
    appsLoadFailed: Boolean,
    onRetryApps: () -> Unit,
    onSettingsChange: (CommitmentEditorSettings) -> Unit,
    modifier: Modifier = Modifier,
    deadlineLabel: String? = null,
    onDeadlineClick: (() -> Unit)? = null
) {
    var showApps by remember { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("防拖延监督", fontWeight = FontWeight.SemiBold, color = BrandColors.TextPrimary)
                Text("逾期未完成时限制选中的娱乐 App", style = MaterialTheme.typography.bodySmall, color = BrandColors.TextSecondary)
            }
            Switch(
                checked = settings.enabled,
                onCheckedChange = { onSettingsChange(settings.copy(enabled = it)) }
            )
        }

        if (settings.enabled) {
            if (onDeadlineClick != null) {
                OutlinedButton(onClick = onDeadlineClick, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(7.dp))
                    Text(deadlineLabel ?: "选择每日截止时间")
                }
            }
            PolicyNumberRow(
                label = "宽限期",
                value = settings.graceMinutes,
                range = 0..1_440,
                step = 5,
                suffix = "分钟",
                onValueChange = { onSettingsChange(settings.copy(graceMinutes = it)) }
            )
            OutlinedButton(
                onClick = {
                    if (apps.isEmpty() && !isLoadingApps) onRetryApps()
                    showApps = true
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isLoadingApps) {
                    CircularProgressIndicator(modifier = Modifier.size(17.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Apps, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(7.dp))
                Text(if (settings.blockedPackages.isEmpty()) "选择限制 App" else "已选择 ${settings.blockedPackages.size} 个 App")
            }
            when {
                appsLoadFailed -> Text("App 列表读取失败", color = BrandColors.Danger, fontSize = 12.sp)
                settings.blockedPackages.isEmpty() -> Text("至少选择一个 App", color = BrandColors.Danger, fontSize = 12.sp)
                else -> {
                    val labels = apps.filter { it.packageName in settings.blockedPackages }
                        .take(4)
                        .joinToString("、", transform = AllowedApp::label)
                    Text(
                        labels + if (settings.blockedPackages.size > 4) " 等" else "",
                        color = BrandColors.TextSecondary,
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }

    if (showApps) {
        CommitmentAppPickerDialog(
            apps = apps,
            selectedPackages = settings.blockedPackages,
            isLoading = isLoadingApps,
            loadFailed = appsLoadFailed,
            onRetry = onRetryApps,
            onDismiss = { showApps = false },
            onConfirm = { selected ->
                onSettingsChange(settings.copy(blockedPackages = selected))
                showApps = false
            }
        )
    }
}

@Composable
private fun PolicyNumberRow(
    label: String,
    value: Int,
    range: IntRange,
    step: Int,
    suffix: String,
    onValueChange: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = BrandColors.TextSecondary)
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { onValueChange((value - step).coerceAtLeast(range.first)) },
                enabled = value > range.first
            ) { Icon(Icons.Default.Remove, contentDescription = "减少$label") }
            Text(
                "$value $suffix",
                modifier = Modifier.width(88.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                color = BrandColors.TextPrimary
            )
            IconButton(
                onClick = { onValueChange((value + step).coerceAtMost(range.last)) },
                enabled = value < range.last
            ) { Icon(Icons.Default.Add, contentDescription = "增加$label") }
        }
    }
}

@Composable
private fun CommitmentAppPickerDialog(
    apps: List<AllowedApp>,
    selectedPackages: Set<String>,
    isLoading: Boolean,
    loadFailed: Boolean,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit
) {
    var selected by remember(selectedPackages) { mutableStateOf(selectedPackages) }
    var search by remember { mutableStateOf("") }
    val filtered = remember(apps, search) {
        apps.filter { app ->
            search.isBlank() || app.label.contains(search, ignoreCase = true) ||
                app.packageName.contains(search, ignoreCase = true)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("限制 App") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it.take(80) },
                    label = { Text("搜索 App") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                when {
                    isLoading -> Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    loadFailed -> Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                        OutlinedButton(onClick = onRetry) { Text("重新读取") }
                    }
                    else -> LazyColumn(modifier = Modifier.fillMaxWidth().height(320.dp)) {
                        items(filtered, key = AllowedApp::packageName) { app ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(app.packageName, fontSize = 10.sp, color = BrandColors.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Checkbox(
                                    checked = app.packageName in selected,
                                    onCheckedChange = { checked ->
                                        selected = if (checked) selected + app.packageName else selected - app.packageName
                                    }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(selected) }, enabled = selected.isNotEmpty() && !isLoading) {
                Text("确定")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
