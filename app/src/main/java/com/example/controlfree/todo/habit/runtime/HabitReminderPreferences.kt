package com.example.controlfree.todo.habit.runtime

import android.content.Context

class HabitReminderPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun read(): HabitReminderSettings = HabitReminderSettings(
        enabled = preferences.getBoolean(KEY_ENABLED, true),
        reminderMinute = preferences.getInt(
            KEY_REMINDER_MINUTE,
            HabitReminderSettings.DEFAULT_REMINDER_MINUTE
        )
    ).normalized()

    fun setEnabled(enabled: Boolean): HabitReminderSettings {
        preferences.edit().putBoolean(KEY_ENABLED, enabled).apply()
        return read()
    }

    fun setReminderMinute(reminderMinute: Int): HabitReminderSettings {
        preferences.edit()
            .putInt(
                KEY_REMINDER_MINUTE,
                reminderMinute.coerceIn(0, HabitReminderSettings.MINUTES_PER_DAY - 1)
            )
            .apply()
        return read()
    }

    companion object {
        internal const val PREFERENCES_NAME = "habit_reminder_preferences"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_REMINDER_MINUTE = "reminder_minute"
    }
}
