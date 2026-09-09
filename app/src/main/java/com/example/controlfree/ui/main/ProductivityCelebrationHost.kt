package com.example.controlfree.ui.main

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.controlfree.R
import com.example.controlfree.theme.BrandColors
import com.example.controlfree.todo.TodoRepository
import kotlinx.coroutines.launch

@Composable
internal fun ProductivityCelebrationHost() {
    val context = LocalContext.current
    val repository = remember(context.applicationContext) {
        TodoRepository.getInstance(context.applicationContext)
    }
    val pending by repository.observePendingCelebrations()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val event = pending.firstOrNull() ?: return

    LaunchedEffect(event.id) {
        repository.consumeCelebration(event.id)
    }
}
