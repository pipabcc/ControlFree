package com.example.controlfree.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.controlfree.data.CacheStorage
import com.example.controlfree.theme.BrandColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow

@Composable
internal fun CacheManagementCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val storage = remember(context) { CacheStorage(context) }
    val scope = rememberCoroutineScope()
    val operationMutex = remember { Mutex() }
    var sizeBytes by remember { mutableStateOf<Long?>(null) }
    var isBusy by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(storage, operationMutex) {
        try {
            sizeBytes = operationMutex.withLock {
                withContext(Dispatchers.IO) { storage.calculateSize() }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            statusMessage = "缓存大小读取失败"
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = BrandColors.SurfaceCard),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("缓存管理", color = BrandColors.TextPrimary, fontSize = 17.sp)
            Text(
                sizeBytes?.let { "当前缓存 ${formatCacheSize(it)}" } ?: "正在计算缓存大小…",
                color = BrandColors.TextSecondary,
                fontSize = 13.sp
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End
            ) {
                statusMessage?.let {
                    Text(
                        it,
                        color = BrandColors.TextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Button(
                    enabled = !isBusy,
                    onClick = {
                        if (isBusy) return@Button
                        isBusy = true
                        statusMessage = null
                        scope.launch {
                            try {
                                val (cleared, currentSize) = operationMutex.withLock {
                                    withContext(Dispatchers.IO) {
                                        storage.clear() to storage.calculateSize()
                                    }
                                }
                                sizeBytes = currentSize
                                statusMessage = if (cleared) {
                                    "缓存已清除"
                                } else {
                                    "部分缓存无法清除"
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                statusMessage = "缓存清除失败，请稍后重试"
                            } finally {
                                isBusy = false
                            }
                        }
                    },
                    modifier = Modifier.testTag("clear_app_cache")
                ) {
                    if (isBusy) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.height(18.dp))
                        Spacer(Modifier.width(6.dp))
                    } else {
                        Icon(Icons.Default.DeleteOutline, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text("清除缓存")
                }
            }
        }
    }
}

internal fun formatCacheSize(bytes: Long): String {
    val safeBytes = bytes.coerceAtLeast(0L)
    if (safeBytes < 1_024L) return "$safeBytes B"
    val exponent = (ln(safeBytes.toDouble()) / ln(1_024.0)).toInt().coerceIn(1, 4)
    val value = safeBytes / 1_024.0.pow(exponent.toDouble())
    val unit = arrayOf("B", "KB", "MB", "GB", "TB")[exponent]
    return String.format(Locale.US, "%.1f %s", value, unit)
}
