package com.example.controlfree.ui.main

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import com.example.controlfree.R
import com.example.controlfree.theme.AppBackground
import com.example.controlfree.theme.BrandColors

internal data class InstalledAppInfo(
    val appName: String,
    val versionName: String?
)

internal fun formatVersionLabel(versionName: String?): String {
    val normalizedVersion = versionName?.trim().orEmpty().ifBlank { "未知" }
    return "版本 $normalizedVersion"
}

private fun loadInstalledAppInfo(context: Context): InstalledAppInfo {
    val packageManager = context.packageManager
    val appName = runCatching {
        context.applicationInfo.loadLabel(packageManager).toString().trim()
    }.getOrNull().orEmpty().ifBlank {
        context.getString(R.string.app_name)
    }
    @Suppress("DEPRECATION")
    val versionName = runCatching {
        packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull()
    return InstalledAppInfo(appName = appName, versionName = versionName)
}

@Composable
private fun rememberInstalledAppInfo(): InstalledAppInfo {
    val appContext = LocalContext.current.applicationContext
    return remember(appContext) { loadInstalledAppInfo(appContext) }
}

@Composable
private fun InstalledAppIcon(
    appName: String,
    size: Dp,
    modifier: Modifier = Modifier
) {
    val appContext = LocalContext.current.applicationContext
    val iconSizePx = with(LocalDensity.current) { size.roundToPx().coerceAtLeast(1) }
    val iconBitmap: ImageBitmap? = remember(appContext, iconSizePx) {
        runCatching {
            appContext.packageManager
                .getApplicationIcon(appContext.packageName)
                .toBitmap(width = iconSizePx, height = iconSizePx)
                .asImageBitmap()
        }.getOrNull()
    }
    val iconModifier = modifier
        .size(size)
        .clip(RoundedCornerShape(14.dp))
        .background(BrandColors.PrimaryContainer)

    if (iconBitmap != null) {
        Image(
            bitmap = iconBitmap,
            contentDescription = "$appName App 图标",
            modifier = iconModifier
        )
    } else {
        Icon(
            painter = painterResource(R.drawable.ic_brand_mark),
            contentDescription = "$appName App 图标",
            tint = Color.Unspecified,
            modifier = iconModifier.padding(8.dp)
        )
    }
}

@Composable
internal fun AppInformationCard(
    onOpenAbout: () -> Unit,
    modifier: Modifier = Modifier
) {
    val appInfo = rememberInstalledAppInfo()
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onOpenAbout),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = BrandColors.SurfaceCard),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_brand_mark),
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = Modifier.size(56.dp)
            )
            Spacer(Modifier.width(14.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = appInfo.appName,
                    color = BrandColors.TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = formatVersionLabel(appInfo.versionName),
                    color = BrandColors.TextSecondary,
                    fontSize = 13.sp
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = "关于",
                color = BrandColors.TextSecondary,
                fontSize = 14.sp
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = BrandColors.TextTertiary,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AboutAppDialog(
    bgType: String,
    bgColor: Int,
    bgGradient: String,
    bgImageIndex: Int,
    onBack: () -> Unit
) {
    val appInfo = rememberInstalledAppInfo()
    Dialog(
        onDismissRequest = onBack,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        AppBackground(
            bgType = bgType,
            bgColor = bgColor,
            bgGradient = bgGradient,
            bgImageIndex = bgImageIndex,
            modifier = Modifier.fillMaxSize()
        ) {
            Scaffold(
                containerColor = Color.Transparent,
                topBar = {
                    CenterAlignedTopAppBar(
                        title = {
                            Text(
                                text = "关于",
                                color = BrandColors.BackgroundTextPrimary,
                                fontWeight = FontWeight.Bold
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "返回",
                                    tint = BrandColors.BackgroundTextPrimary
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = BrandColors.BackgroundChrome,
                            navigationIconContentColor = BrandColors.BackgroundTextPrimary,
                            titleContentColor = BrandColors.BackgroundTextPrimary
                        )
                    )
                }
            ) { contentPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(contentPadding)
                        .navigationBarsPadding(),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 640.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 24.dp, vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_brand_mark),
                            contentDescription = null,
                            tint = Color.Unspecified,
                            modifier = Modifier.size(104.dp)
                        )
                        Spacer(Modifier.height(18.dp))
                        Text(
                            text = appInfo.appName,
                            color = BrandColors.BackgroundTextPrimary,
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = formatVersionLabel(appInfo.versionName),
                            color = BrandColors.BackgroundTextSecondary,
                            fontSize = 14.sp
                        )
                        Spacer(Modifier.height(28.dp))

                        // 卡片一：应用介绍
                        Card(
                            colors = CardDefaults.cardColors(containerColor = BrandColors.SurfaceCard),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Text(
                                    "用于管理监督、专注、待办与日常记录，让重要计划保持清晰、有序。",
                                    color = BrandColors.TextSecondary,
                                    fontSize = 14.sp,
                                    lineHeight = 22.sp
                                )
                            }
                        }

                        Spacer(Modifier.height(16.dp))

                        // 卡片二：新手指南、常见问题、AI功能
                        Card(
                            colors = CardDefaults.cardColors(containerColor = BrandColors.SurfaceCard),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                                ExpandableAboutRow("新手指南", "1. 设置专注目标：规划每天的学习或工作，设置锁定时间与玩机时间。\n2. 预备必要权限：使用运行准备卡片，一键补齐应用运行及防撤回所需的关键权限。\n3. 开始专注：启动专注番茄钟，锁定不需要的应用，保持高效自律。")
                                ExpandableAboutRow("常见问题", "Q: 为什么某些锁屏白名单应用会被系统强杀？\nA: 请确保为对应白名单 App 在系统中开启了“使用情况访问”以及“无障碍”权限，并允许其常驻后台运行，避免被系统电源管理自动清理。\n\nQ: 如何更改解锁密码？\nA: 您可以在“系统设置”的“密码设置”卡片中，重新配置您的数字密码或手势密码。")
                                ExpandableAboutRow("AI功能", "1. AI建议与AI助理：提供个性化的自律督促和伴学对话。\n2. 待办AI拆解：在新建待办任务时，支持通过 AI 智能拆解为多项细化步骤。\n3. AI记账分类：在日常记账中提供自动智能分类与分析服务。\n4. AI自律复盘报告：在使用统计中基于您的一周专注成果生成复盘报告。\n5. AI配置：请在主页的AI设置中配置您的 DeepSeek API Key 即可快速调用。")
                            }
                        }

                        Spacer(Modifier.height(16.dp))

                        // 卡片三：条款与协议
                        Card(
                            colors = CardDefaults.cardColors(containerColor = BrandColors.SurfaceCard),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                                ExpandableAboutRow("使用条款", "1. 本应用旨在提供自律管理与专注辅助服务。\n2. 请合理配置专注规则，应用对于由于配置失当或设备兼容问题引起的日常计划冲突不承担连带责任。")
                                ExpandableAboutRow("隐私声明", "ControlFree 极其重视您的隐私安全：\n1. 本机运行：应用的所有日常记录、配置、专注时长等业务数据均安全存储于您的本地设备上，不连接并上传到任何第三方云服务器。\n2. 数据隔离：除您在本地主动配置并连接 AI 模型（如 DeepSeek 服务端）进行智能交互之外，应用绝无任何后台外传行为，100% 保护您的隐私。")
                                ExpandableAboutRow("开源协议 (AGPL-3.0)", "ControlFree 基于 GNU Affero General Public License v3.0 (AGPL-3.0) 协议进行开源。\n您享有获取源代码、进行修改和二次分发的权利，但在修改和分发应用时，必须同样以 AGPL-3.0 协议公开所有源代码。")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExpandableAboutRow(
    title: String,
    content: String,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val rotationAngle by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        label = "arrowRotation"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(vertical = 12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = title,
                color = BrandColors.TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = if (expanded) "折叠" else "展开",
                tint = BrandColors.TextTertiary,
                modifier = Modifier
                    .size(20.dp)
                    .graphicsLayer(rotationZ = rotationAngle)
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Text(
                text = content,
                color = BrandColors.TextSecondary,
                fontSize = 13.5.sp,
                lineHeight = 21.sp,
                modifier = Modifier
                    .padding(top = 8.dp, bottom = 4.dp)
                    .fillMaxWidth()
            )
        }
    }
}
