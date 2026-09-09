package com.example.controlfree.ui.todo.timeblock

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class FocusTimerState(
    val stableKey: String,
    val title: String,
    val totalSeconds: Int,
    val remainingSeconds: Int,
    val paused: Boolean = false,
    val startTimestampMillis: Long = 0L,
    val pausedRemainingSeconds: Int = 0
)

object FocusTimerManager {
    private var prefs: SharedPreferences? = null

    private val _timerState = MutableStateFlow<FocusTimerState?>(null)
    val timerState: StateFlow<FocusTimerState?> = _timerState.asStateFlow()

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences("focus_timer_prefs", Context.MODE_PRIVATE)
            loadFromPrefs()
        }
    }

    private fun loadFromPrefs() {
        val sp = prefs ?: return
        val stableKey = sp.getString("stable_key", null)
        if (stableKey != null) {
            val title = sp.getString("title", "") ?: ""
            val totalSeconds = sp.getInt("total_seconds", 0)
            val startTimestampMillis = sp.getLong("start_timestamp_millis", 0L)
            val paused = sp.getBoolean("paused", false)
            val pausedRemainingSeconds = sp.getInt("paused_remaining_seconds", 0)

            val remaining = if (paused) {
                pausedRemainingSeconds
            } else {
                val elapsed = (System.currentTimeMillis() - startTimestampMillis) / 1000
                (totalSeconds - elapsed).toInt().coerceAtLeast(0)
            }

            if (remaining > 0) {
                _timerState.value = FocusTimerState(
                    stableKey = stableKey,
                    title = title,
                    totalSeconds = totalSeconds,
                    remainingSeconds = remaining,
                    paused = paused,
                    startTimestampMillis = startTimestampMillis,
                    pausedRemainingSeconds = pausedRemainingSeconds
                )
            } else {
                clearTimer()
            }
        }
    }

    fun startTimer(stableKey: String, title: String, totalSeconds: Int) {
        val startTimestamp = System.currentTimeMillis()
        val state = FocusTimerState(
            stableKey = stableKey,
            title = title,
            totalSeconds = totalSeconds,
            remainingSeconds = totalSeconds,
            paused = false,
            startTimestampMillis = startTimestamp,
            pausedRemainingSeconds = totalSeconds
        )
        _timerState.value = state
        saveToPrefs(state)
    }

    fun togglePause() {
        val current = _timerState.value ?: return
        val now = System.currentTimeMillis()
        val newState = if (current.paused) {
            // Resume
            val newStart = now - (current.totalSeconds - current.remainingSeconds) * 1000L
            current.copy(
                paused = false,
                startTimestampMillis = newStart,
                pausedRemainingSeconds = current.remainingSeconds
            )
        } else {
            // Pause
            current.copy(
                paused = true,
                pausedRemainingSeconds = current.remainingSeconds
            )
        }
        _timerState.value = newState
        saveToPrefs(newState)
    }

    fun skipOrClose() {
        clearTimer()
    }

    fun completeFocus(context: Context, coroutineScope: kotlinx.coroutines.CoroutineScope) {
        val current = _timerState.value ?: return
        val stableKey = current.stableKey
        val parts = stableKey.split(":")
        if (parts.size == 2) {
            val sourceName = parts[0]
            val id = parts[1]
            coroutineScope.launch {
                try {
                    if (sourceName == "todo") {
                        val repo = com.example.controlfree.todo.TodoRepository.getInstance(context)
                        val todo = repo.getTodoById(id)
                        if (todo != null && !todo.isCompleted) {
                            repo.toggleTodoCompletion(id, true)
                        }
                    } else if (sourceName == "event") {
                        val repo = com.example.controlfree.todo.TimeBlockEventRepository.getInstance(context)
                        val event = repo.getById(id)
                        if (event != null && !event.isCompleted) {
                            repo.toggleCompletion(id, true)
                        } else if (event == null) {
                            // v15 会把独立日程迁移为同 ID 待办，兼容升级前已启动的专注计时。
                            val todoRepo = com.example.controlfree.todo.TodoRepository.getInstance(context)
                            val todo = todoRepo.getTodoById(id)
                            if (todo != null && !todo.isCompleted) {
                                todoRepo.toggleTodoCompletion(id, true)
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        clearTimer()
    }

    fun tick() {
        val current = _timerState.value ?: return
        if (current.paused) return

        val elapsed = (System.currentTimeMillis() - current.startTimestampMillis) / 1000
        val remaining = (current.totalSeconds - elapsed).toInt().coerceAtLeast(0)

        if (remaining <= 0) {
            clearTimer()
        } else {
            val newState = current.copy(remainingSeconds = remaining)
            _timerState.value = newState
        }
    }

    private fun clearTimer() {
        _timerState.value = null
        prefs?.edit()?.clear()?.apply()
    }

    private fun saveToPrefs(state: FocusTimerState) {
        prefs?.edit()?.apply {
            putString("stable_key", state.stableKey)
            putString("title", state.title)
            putInt("total_seconds", state.totalSeconds)
            putLong("start_timestamp_millis", state.startTimestampMillis)
            putBoolean("paused", state.paused)
            putInt("paused_remaining_seconds", state.pausedRemainingSeconds)
        }?.apply()
    }
}
