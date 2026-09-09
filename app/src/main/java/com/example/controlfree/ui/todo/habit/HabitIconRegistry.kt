package com.example.controlfree.ui.todo.habit

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessAlarm
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.LocalDrink
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.ui.graphics.vector.ImageVector

data class HabitIconOption(
    val key: String,
    val label: String,
    val imageVector: ImageVector
)

object HabitIconRegistry {
    val options: List<HabitIconOption> = listOf(
        HabitIconOption("water", "喝水", Icons.Default.LocalDrink),
        HabitIconOption("book", "阅读", Icons.Default.MenuBook),
        HabitIconOption("run", "运动", Icons.Default.DirectionsRun),
        HabitIconOption("school", "学习", Icons.Default.School),
        HabitIconOption("alarm", "早起", Icons.Default.AccessAlarm),
        HabitIconOption("meditation", "冥想", Icons.Default.SelfImprovement),
        HabitIconOption("bedtime", "睡眠", Icons.Default.Bedtime),
        HabitIconOption("check", "其他", Icons.Default.TaskAlt)
    )

    fun iconFor(key: String): ImageVector =
        options.firstOrNull { it.key == key }?.imageVector ?: Icons.Default.TaskAlt
}
