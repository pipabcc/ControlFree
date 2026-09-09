package com.example.controlfree.todo.anniversary.runtime

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.controlfree.AppShortcutDestination
import com.example.controlfree.AppShortcutRoute
import com.example.controlfree.MainActivity
import com.example.controlfree.R
import com.example.controlfree.todo.AnniversaryItemEntity
import com.example.controlfree.ui.todo.anniversary.AnniversaryOccurrenceResolver
import com.example.controlfree.ui.todo.anniversary.AnniversaryType
import com.example.controlfree.ui.todo.TodoSubTab
import com.example.controlfree.ui.todo.anniversary.toSpec
import com.example.controlfree.ui.todo.anniversary.AnniversaryAlertActivity
import java.time.Instant
import java.time.ZoneId

class AnniversaryNotificationPublisher(
    context: Context,
    private val resolver: AnniversaryOccurrenceResolver
) {
    private val appContext = context.applicationContext
    private val notificationManager = NotificationManagerCompat.from(appContext)
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @SuppressLint("MissingPermission")
    fun sync(items: List<AnniversaryItemEntity>, now: Instant) {
        ensureChannel()
        val enabled = items.filter(AnniversaryItemEntity::showOnLockScreen)
        val desiredIds = enabled.map(AnniversaryItemEntity::id).toSet()
        val previouslyPublished = preferences.getStringSet(KEY_PUBLISHED_IDS, emptySet()).orEmpty()
        (previouslyPublished - desiredIds).forEach(::cancel)
        if (!canPostNotifications()) {
            preferences.edit().putStringSet(KEY_PUBLISHED_IDS, emptySet()).apply()
            return
        }
        val published = buildSet {
            enabled.forEach { item ->
                val notification = runCatching { buildNotification(item, now) }.getOrNull()
                    ?: return@forEach
                runCatching {
                    notificationManager.notify(item.id, NOTIFICATION_ID, notification)
                    add(item.id)
                }
            }
        }
        preferences.edit().putStringSet(KEY_PUBLISHED_IDS, published).apply()
    }

    fun cancel(anniversaryId: String) {
        runCatching { notificationManager.cancel(anniversaryId, NOTIFICATION_ID) }
    }

    private fun buildNotification(item: AnniversaryItemEntity, now: Instant): Notification {
        val spec = item.toSpec(ZoneId.systemDefault())
        val occurrence = resolver.resolve(spec, now)
        val isCountdown = spec.type == AnniversaryType.COUNTDOWN
        val reached = isCountdown && !occurrence.instant.isAfter(now) && !occurrence.isRecurring
        val contentIntent = PendingIntent.getActivity(
            appContext,
            item.id.hashCode(),
            Intent(appContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(AppShortcutRoute.EXTRA_DESTINATION, AppShortcutDestination.TODO.name)
                putExtra(TodoSubTab.EXTRA_NAVIGATION_TARGET, TodoSubTab.ANNIVERSARY.name)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val deleteIntent = Intent(appContext, AnniversaryAlertReceiver::class.java).apply {
            action = AnniversaryAlertReceiver.ACTION_DISMISS_PERSISTENT
            putExtra("anniversary_id", item.id)
        }
        val deletePendingIntent = PendingIntent.getBroadcast(
            appContext,
            item.id.hashCode() + 3,
            deleteIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // 部分厂商系统不渲染通知内的计时器，文本里必须直接给出目标时间与剩余时长；
        // 支持计时器的系统仍会在其上实时走秒。
        val zone = ZoneId.systemDefault()
        val targetText = formatAnniversaryInstant(occurrence.instant, now, zone)
        val contentText = when {
            reached -> "时刻已到达"
            isCountdown -> {
                val remaining = occurrence.instant.toEpochMilli() - now.toEpochMilli()
                "距离 $targetText 还有 ${formatCoarseDuration(remaining)}"
            }
            else -> {
                val elapsed = now.toEpochMilli() - occurrence.instant.toEpochMilli()
                "自 $targetText 已经坚持 ${formatCoarseDuration(elapsed)}"
            }
        }
        val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_anniversary)
            .setContentTitle(item.title)
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setContentIntent(contentIntent)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // 允许左滑清除；清除即视为用户关闭该项的常驻显示，由 deleteIntent 同步开关，
            // 避免下次同步时通知又被重新发出。
            .setOngoing(false)
            .setDeleteIntent(deletePendingIntent)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(true)

        if (!reached) {
            builder
                .setWhen(occurrence.instant.toEpochMilli())
                .setUsesChronometer(true)
                .setChronometerCountDown(isCountdown)
        }
        return builder.build()
    }

    private fun canPostNotifications(): Boolean =
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED) && notificationManager.areNotificationsEnabled()

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = appContext.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "时刻常驻",
            // 低重要性通知在部分厂商锁屏上默认不展示；提升到默认重要性但保持静音。
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "在通知栏和锁屏显示选中的时刻倒数日或正数日"
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
        // 渠道重要性无法就地修改，迁移到 v2 渠道后删除旧渠道，避免设置页出现重复项。
        runCatching { manager.deleteNotificationChannel(LEGACY_CHANNEL_ID) }

        val alertChannel = NotificationChannel(
            CHANNEL_ID_ALERT,
            "时刻到达提醒",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "当重要时刻到达时发出高优先级通知及震动声音"
            enableLights(true)
            enableVibration(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(alertChannel)
    }

    @SuppressLint("MissingPermission")
    fun showAlertNotification(item: AnniversaryItemEntity, celebrationId: String) {
        ensureChannel()
        if (!canPostNotifications()) return

        val dismissIntent = Intent(appContext, AnniversaryAlertReceiver::class.java).apply {
            action = AnniversaryAlertReceiver.ACTION_DISMISS_ALERT
            putExtra("anniversary_id", item.id)
            putExtra("celebration_id", celebrationId)
        }
        val dismissPendingIntent = PendingIntent.getBroadcast(
            appContext,
            item.id.hashCode() + 1,
            dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val alertActivityIntent = Intent(appContext, AnniversaryAlertActivity::class.java).apply {
            putExtra("title", item.title)
            putExtra("message", "${item.title} 已经到达！")
            putExtra("celebration_id", celebrationId)
            putExtra("anniversary_id", item.id)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            appContext,
            item.id.hashCode() + 2,
            alertActivityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(appContext, CHANNEL_ID_ALERT)
            .setSmallIcon(R.drawable.ic_notification_anniversary)
            .setContentTitle(item.title)
            .setContentText("你的时刻已到达！")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setDefaults(Notification.DEFAULT_ALL)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setContentIntent(fullScreenPendingIntent)
            .addAction(R.drawable.ic_notification_anniversary, "知道了", dismissPendingIntent)

        runCatching {
            notificationManager.notify(item.id, AnniversaryAlertReceiver.NOTIFICATION_ALERT_ID, builder.build())
        }

        // 后台 startActivity 会被厂商“后台弹出界面”限制拦截，只有应用在前台时才生效；
        // 悬浮窗卡片不受该限制，保证不打开应用也能弹到桌面。锁屏场景由上面的
        // 全屏意图通知拉起卡片 Activity 兜底。
        AnniversaryAlertOverlay.show(
            context = appContext,
            title = item.title,
            message = "${item.title} 已经到达！",
            celebrationId = celebrationId,
            anniversaryId = item.id
        )
    }

    companion object {
        const val CHANNEL_ID = "controlfree_anniversary_v2"
        const val CHANNEL_ID_ALERT = "controlfree_anniversary_alert_v1"
        private const val LEGACY_CHANNEL_ID = "controlfree_anniversary_v1"
        private const val NOTIFICATION_ID = 7462
        private const val PREFERENCES_NAME = "anniversary_notifications"
        private const val KEY_PUBLISHED_IDS = "published_ids"
    }
}

/** 常驻通知的文本时长：粗粒度到天/小时/分钟，精确走秒交给系统计时器（若厂商支持）。 */
internal fun formatCoarseDuration(durationMillis: Long): String {
    val totalMinutes = durationMillis.coerceAtLeast(0L) / 60_000L
    val days = totalMinutes / (24L * 60L)
    val hours = totalMinutes % (24L * 60L) / 60L
    val minutes = totalMinutes % 60L
    return when {
        days > 0L -> "${days}天${hours}小时"
        hours > 0L -> "${hours}小时${minutes}分钟"
        else -> "${minutes}分钟"
    }
}

internal fun formatAnniversaryInstant(
    target: Instant,
    now: Instant,
    zoneId: ZoneId
): String {
    val targetDateTime = target.atZone(zoneId)
    val pattern = if (targetDateTime.year == now.atZone(zoneId).year) {
        "M月d日 HH:mm"
    } else {
        "yyyy年M月d日 HH:mm"
    }
    return targetDateTime.format(java.time.format.DateTimeFormatter.ofPattern(pattern))
}
