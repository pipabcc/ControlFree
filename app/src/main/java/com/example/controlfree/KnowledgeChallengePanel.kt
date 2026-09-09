package com.example.controlfree

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.controlfree.knowledge.KnowledgeChallengeSession
import com.example.controlfree.knowledge.KnowledgeChallengeStatus
import com.example.controlfree.knowledge.KnowledgeQuestionSource
import com.example.controlfree.knowledge.KnowledgeRoundReview
import com.example.controlfree.theme.BrandColors

@Composable
internal fun KnowledgeChallengePanel(
    session: KnowledgeChallengeSession?,
    isLoading: Boolean,
    isSubmitting: Boolean,
    statusMessage: String?,
    onSubmit: (Map<String, String>) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    roundReview: KnowledgeRoundReview? = null,
    onReviewContinue: () -> Unit = {}
) {
    Box(
        modifier = Modifier.fillMaxSize().background(BrandColors.Canvas).padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_brand_mark),
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = Modifier.size(48.dp)
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "AI 百科知识挑战",
                color = BrandColors.TextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                "共2轮，每轮3题；6题全部答对方可通关",
                color = BrandColors.TextSecondary,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                lineHeight = 18.sp,
                modifier = Modifier.padding(top = 6.dp, bottom = 14.dp)
            )

            when {
                isLoading -> KnowledgeLoadingCard(onBack)
                session != null -> key(
                    session.challengeId,
                    session.currentRoundNumber,
                    roundReview?.roundNumber
                ) {
                    if (roundReview == null) {
                        KnowledgeQuestionRound(
                            session = session,
                            isSubmitting = isSubmitting,
                            statusMessage = statusMessage,
                            onSubmit = onSubmit,
                            onBack = onBack
                        )
                    } else {
                        KnowledgeRoundReviewPanel(
                            session = session,
                            review = roundReview,
                            onContinue = onReviewContinue,
                            onBack = onBack
                        )
                    }
                }
                else -> KnowledgeUnavailableCard(statusMessage, onRetry, onBack)
            }
        }
    }
}

@Composable
private fun KnowledgeRoundReviewPanel(
    session: KnowledgeChallengeSession,
    review: KnowledgeRoundReview,
    onContinue: () -> Unit,
    onBack: () -> Unit
) {
    val isFinal = session.status != KnowledgeChallengeStatus.ACTIVE
    val title = when (session.status) {
        KnowledgeChallengeStatus.ACTIVE -> "第 ${review.roundNumber} 轮已完成"
        KnowledgeChallengeStatus.PASSED -> "两轮全部答对"
        KnowledgeChallengeStatus.FAILED -> "本次挑战未通过"
    }
    val summary = if (isFinal) {
        "累计答对 ${session.totalScore}/${KnowledgeChallengeSession.REQUIRED_TOTAL_SCORE} 题"
    } else {
        "本轮答对 ${review.score}/${KnowledgeChallengeSession.QUESTIONS_PER_ROUND} 题"
    }

    Text(
        title,
        color = if (session.status == KnowledgeChallengeStatus.FAILED) {
            BrandColors.Warning
        } else {
            BrandColors.Primary
        },
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold
    )
    Text(
        summary,
        color = BrandColors.TextSecondary,
        fontSize = 12.sp,
        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
    )

    review.answers.forEachIndexed { index, answer ->
        Card(
            colors = CardDefaults.cardColors(containerColor = BrandColors.SurfaceCard),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, BrandColors.Outline),
            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (answer.isCorrect) {
                            Icons.Default.CheckCircle
                        } else {
                            Icons.Default.Cancel
                        },
                        contentDescription = if (answer.isCorrect) "回答正确" else "回答错误",
                        tint = if (answer.isCorrect) BrandColors.Primary else BrandColors.Warning,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        "${index + 1}. ${answer.stem}",
                        color = BrandColors.TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 20.sp,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
                Text(
                    "你的答案：${answer.selectedOptionText}",
                    color = if (answer.isCorrect) BrandColors.TextSecondary else BrandColors.Warning,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 9.dp)
                )
                if (!answer.isCorrect) {
                    Text(
                        "正确答案：${answer.correctOptionText}",
                        color = BrandColors.Primary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }
                Text(
                    answer.explanation,
                    color = BrandColors.TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(top = 7.dp)
                )
            }
        }
    }

    Button(
        onClick = onContinue,
        colors = ButtonDefaults.buttonColors(containerColor = BrandColors.Primary),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().height(50.dp)
    ) {
        Text(
            when (session.status) {
                KnowledgeChallengeStatus.ACTIVE -> "继续第二轮"
                KnowledgeChallengeStatus.PASSED -> "继续解锁"
                KnowledgeChallengeStatus.FAILED -> "返回"
            },
            color = BrandColors.OnPrimary,
            fontWeight = FontWeight.Bold
        )
    }
    if (!isFinal) {
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("返回")
        }
    }
}

@Composable
private fun KnowledgeLoadingCard(onBack: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = BrandColors.SurfaceCard),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, BrandColors.Outline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator(color = BrandColors.Primary, modifier = Modifier.size(30.dp))
            Spacer(Modifier.height(12.dp))
            Text(
                "正在准备题目；网络不可用时会自动切换本地题库",
                color = BrandColors.TextSecondary,
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text("返回")
            }
        }
    }
}

@Composable
private fun KnowledgeQuestionRound(
    session: KnowledgeChallengeSession,
    isSubmitting: Boolean,
    statusMessage: String?,
    onSubmit: (Map<String, String>) -> Unit,
    onBack: () -> Unit
) {
    val selectedOptions = remember(session.challengeId, session.currentRoundNumber) {
        mutableStateMapOf<String, String>()
    }
    var selectionGeneration by remember { mutableStateOf(0) }
    Text(
        "第 ${session.currentRoundNumber}/2 轮",
        color = BrandColors.Primary,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 8.dp)
    )
    session.questions.forEachIndexed { index, question ->
        Card(
            colors = CardDefaults.cardColors(containerColor = BrandColors.SurfaceCard),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, BrandColors.Outline),
            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${index + 1}. ${question.category.displayName}",
                        color = BrandColors.TextSecondary,
                        fontSize = 11.sp
                    )
                    if (question.source == KnowledgeQuestionSource.DEEPSEEK) {
                        Text("AI润色", color = BrandColors.Primary, fontSize = 10.sp)
                    }
                }
                Text(
                    question.stem,
                    color = BrandColors.TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 21.sp,
                    modifier = Modifier.padding(top = 5.dp, bottom = 8.dp)
                )
                question.options.forEach { option ->
                    val selected = selectedOptions[question.id] == option.id
                    OutlinedButton(
                        enabled = !isSubmitting,
                        onClick = {
                            selectedOptions[question.id] = option.id
                            selectionGeneration++
                        },
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (selected) {
                                BrandColors.Primary.copy(alpha = 0.18f)
                            } else {
                                Color.Transparent
                            },
                            contentColor = BrandColors.TextPrimary
                        ),
                        border = BorderStroke(
                            1.dp,
                            if (selected) BrandColors.Primary else BrandColors.Outline
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                    ) {
                        Text(option.text, fontSize = 13.sp, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
    statusMessage?.let {
        Text(
            it,
            color = BrandColors.Warning,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 6.dp)
        )
    }
    val allAnswered = selectionGeneration >= 0 &&
        selectedOptions.keys.containsAll(session.questions.map { it.id })
    Button(
        enabled = allAnswered && !isSubmitting,
        onClick = { onSubmit(selectedOptions.toMap()) },
        colors = ButtonDefaults.buttonColors(containerColor = BrandColors.Primary),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().height(50.dp)
    ) {
        if (isSubmitting) {
            CircularProgressIndicator(
                color = BrandColors.OnPrimary,
                strokeWidth = 2.dp,
                modifier = Modifier.size(20.dp)
            )
        } else {
            Text("提交本轮答案", color = BrandColors.OnPrimary, fontWeight = FontWeight.Bold)
        }
    }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = onBack, enabled = !isSubmitting, modifier = Modifier.fillMaxWidth()) {
        Text("返回")
    }
}

@Composable
private fun KnowledgeUnavailableCard(
    message: String?,
    onRetry: () -> Unit,
    onBack: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = BrandColors.SurfaceCard),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, BrandColors.Outline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                message ?: "暂时无法准备百科题，请使用成长值或稍后重试",
                color = BrandColors.Warning,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("返回其他方式") }
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text("返回")
            }
        }
    }
}
