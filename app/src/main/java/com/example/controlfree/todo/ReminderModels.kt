package com.example.controlfree.todo

import org.json.JSONArray
import org.json.JSONObject

enum class ReminderChannel {
    NOTIFICATION, // 下拉通知
    CARD,         // 卡片弹窗
    BOTH          // 下拉通知和卡片弹窗
}

data class ItemReminder(
    val minutesBefore: Int, // 提前的分钟数。为0表示准时提醒。
    val channel: ReminderChannel = ReminderChannel.BOTH
)

data class HabitTimeReminder(
    val hour: Int,
    val minute: Int,
    val channel: ReminderChannel = ReminderChannel.BOTH
)

data class HabitIntervalReminder(
    val intervalMinutes: Int, // 循环提醒的时间间隔，单位：分钟
    val startHour: Int = 8,   // 开始触发的系统时间，默认 08:00
    val endHour: Int = 22,    // 截止触发的系统时间，默认 22:00
    val channel: ReminderChannel = ReminderChannel.BOTH
)

data class HabitReminderConfig(
    val timedReminders: List<HabitTimeReminder> = emptyList(),
    val intervalReminder: HabitIntervalReminder? = null
)

fun List<ItemReminder>.toJsonString(): String {
    if (isEmpty()) return "[]"
    val array = JSONArray()
    this.forEach { reminder ->
        val obj = JSONObject().apply {
            put("minutesBefore", reminder.minutesBefore)
            put("channel", reminder.channel.name)
        }
        array.put(obj)
    }
    return array.toString()
}

fun String?.toItemReminders(): List<ItemReminder> {
    if (this.isNullOrBlank()) return emptyList()
    if (trim() == "[]") return emptyList()
    return try {
        val list = mutableListOf<ItemReminder>()
        val array = JSONArray(this)
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val minutesBefore = obj.getInt("minutesBefore")
            val channel = ReminderChannel.valueOf(obj.optString("channel", ReminderChannel.BOTH.name))
            list.add(ItemReminder(minutesBefore, channel))
        }
        list.sortedBy { it.minutesBefore }
    } catch (_: Exception) {
        emptyList()
    }
}

fun HabitReminderConfig.toJsonString(): String {
    val obj = JSONObject()
    val timedArray = JSONArray()
    this.timedReminders.forEach { r ->
        timedArray.put(JSONObject().apply {
            put("hour", r.hour)
            put("minute", r.minute)
            put("channel", r.channel.name)
        })
    }
    obj.put("timedReminders", timedArray)

    this.intervalReminder?.let { ir ->
        obj.put("intervalReminder", JSONObject().apply {
            put("intervalMinutes", ir.intervalMinutes)
            put("startHour", ir.startHour)
            put("endHour", ir.endHour)
            put("channel", ir.channel.name)
        })
    }
    return obj.toString()
}

fun String?.toHabitReminderConfig(): HabitReminderConfig {
    if (this.isNullOrBlank()) return HabitReminderConfig()
    return try {
        val obj = JSONObject(this)
        val timedList = mutableListOf<HabitTimeReminder>()
        val timedArray = obj.optJSONArray("timedReminders")
        if (timedArray != null) {
            for (i in 0 until timedArray.length()) {
                val rObj = timedArray.getJSONObject(i)
                timedList.add(
                    HabitTimeReminder(
                        hour = rObj.getInt("hour"),
                        minute = rObj.getInt("minute"),
                        channel = ReminderChannel.valueOf(rObj.optString("channel", ReminderChannel.BOTH.name))
                    )
                )
            }
        }
        val irObj = obj.optJSONObject("intervalReminder")
        val intervalReminder = if (irObj != null) {
            HabitIntervalReminder(
                intervalMinutes = irObj.getInt("intervalMinutes"),
                startHour = irObj.optInt("startHour", 8),
                endHour = irObj.optInt("endHour", 22),
                channel = ReminderChannel.valueOf(irObj.optString("channel", ReminderChannel.BOTH.name))
            )
        } else {
            null
        }
        HabitReminderConfig(timedList, intervalReminder)
    } catch (_: Exception) {
        HabitReminderConfig()
    }
}
