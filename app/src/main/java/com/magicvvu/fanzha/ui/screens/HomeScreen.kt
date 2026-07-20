package com.magicvvu.fanzha.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SettingsSuggest
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.magicvvu.fanzha.R
import com.magicvvu.fanzha.ui.components.AppButton
import com.magicvvu.fanzha.ui.components.AppCard
import com.magicvvu.fanzha.ui.components.AppTopBar
import com.magicvvu.fanzha.ui.components.SafetyIndexWavePercentage
import com.magicvvu.fanzha.util.LowSafetyVibration
import com.magicvvu.fanzha.util.SafetySoundManager
import com.magicvvu.fanzha.notifications.AlertNotificationCoordinator
import com.magicvvu.fanzha.ui.viewmodels.HomeViewModel
import com.magicvvu.fanzha.ui.viewmodels.PersonalizedSuggestion
import com.magicvvu.fanzha.ui.viewmodels.UserRole

import com.magicvvu.fanzha.ui.components.GlowButton
import kotlinx.coroutines.delay

private val HomeUnifiedShadowShape18 = RoundedCornerShape(18.dp)
private val HomeUnifiedShadowShape20 = RoundedCornerShape(20.dp)
private val HomeUnifiedShadowSpot = Color(0xFF94A3B8).copy(alpha = 0.20f)
private val HomeUnifiedShadowAmbient = Color(0xFF64748B).copy(alpha = 0.08f)
private val HomeUnifiedShadowElevation = 8.dp

private enum class SafetyIndexTier {
    Excellent,
    Good,
    Fair,
    Critical,
}

private data class SafetyIndexPresentation(
    val levelTitle: String,
    val explanation: String,
    val tier: SafetyIndexTier,
)

/** 80–100 优秀，60–79 良好，30–59 一般；低于 30 为高危（与首页警示背景一致） */
private fun safetyIndexPresentation(safetyIndex: Int): SafetyIndexPresentation {
    val s = safetyIndex.coerceIn(0, 100)
    return when {
        s >= 80 -> SafetyIndexPresentation(
            levelTitle = "优秀",
            explanation = "综合防护到位，风险整体可控，请继续保持良好的安全习惯。",
            tier = SafetyIndexTier.Excellent,
        )

        s >= 60 -> SafetyIndexPresentation(
            levelTitle = "良好",
            explanation = "整体较安全，仍有可优化项，建议关注系统提醒并及时处理。",
            tier = SafetyIndexTier.Good,
        )

        s >= 30 -> SafetyIndexPresentation(
            levelTitle = "一般",
            explanation = "存在一定风险敞口，建议尽快排查可疑项并适当提升防护设置。",
            tier = SafetyIndexTier.Fair,
        )

        else -> SafetyIndexPresentation(
            levelTitle = "高危",
            explanation = "您当前正在遭遇诈骗，请马上停止相关转账行为，并马上联系警方！",
            tier = SafetyIndexTier.Critical,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel = viewModel(),
    currentUserId: Long? = null,
    onNavigateToIdentify: (Int) -> Unit = {},
    onNavigateToReport: () -> Unit = {},
    onNavigateToInterceptRecord: (InterceptSection) -> Unit = {},
    onShowAiSheet: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val safetyWaveIntroDone by viewModel.safetyIndexWaveIntroDone.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // 安全指数进入 30–59 区间时弹出一次警示弹窗；离开该区间后重置
    var showFairWarningDialog by remember { mutableStateOf(false) }
    var fairWarningAcknowledged by rememberSaveable { mutableStateOf(false) }

    // 安全指数进入 0–29 区间时弹出高危警示弹窗；离开该区间后重置
    var showCriticalWarningDialog by remember { mutableStateOf(false) }
    var criticalWarningAcknowledged by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(uiState.safetyIndex) {
        if (uiState.safetyIndex in 30..59) {
            showFairWarningDialog = true
        }
        if (uiState.safetyIndex !in 30..59) {
            fairWarningAcknowledged = false
            showFairWarningDialog = false
        }
    }

    LaunchedEffect(uiState.safetyIndex) {
        if (uiState.safetyIndex in 0..29) {
            showCriticalWarningDialog = true
        }
        if (uiState.safetyIndex !in 0..29) {
            criticalWarningAcknowledged = false
            showCriticalWarningDialog = false
        }
    }

    // 安全指数 30–59：首次弹窗立即播放语音，随后每30s循环播报，点击"我已知晓"后停止
    LaunchedEffect(uiState.safetyIndex) {
        if (uiState.safetyIndex !in 30..59) return@LaunchedEffect
        SafetySoundManager.initialize(context)
        SafetySoundManager.playFairWarning(context)
        while (true) {
            delay(SafetySoundManager.repeatGapMs())
            if (viewModel.uiState.value.safetyIndex !in 30..59) break
            if (fairWarningAcknowledged) break
            SafetySoundManager.playFairWarning(context)
        }
    }

    // 安全指数 30–59 警示弹窗
    if (showFairWarningDialog) {
        AlertDialog(
            onDismissRequest = { },
            confirmButton = {
                TextButton(
                    onClick = {
                        showFairWarningDialog = false
                        fairWarningAcknowledged = true
                        SafetySoundManager.cancel(context)
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text("我已知晓", style = MaterialTheme.typography.labelLarge)
                }
            },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "安全提醒",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
            },
            text = {
                Text(
                    text = "您当前的安全指数较低，疑似遭遇诈骗，请咨询AI智能反诈助手，或向公安机关寻求帮助。",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
        )
    }

    // 安全指数 0–29 高危警示弹窗
    if (showCriticalWarningDialog) {
        AlertDialog(
            onDismissRequest = { },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCriticalWarningDialog = false
                        criticalWarningAcknowledged = true
                        LowSafetyVibration.cancel(context)
                        SafetySoundManager.cancel(context)
                        viewModel.setSafetyIndex(25)
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("我已知晓", style = MaterialTheme.typography.labelLarge)
                }
            },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "高危警示",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
            },
            text = {
                Text(
                    text = "您正在遭遇诈骗片，请立刻停止相关转账行为，并向公安机关寻求帮助。",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
        )
    }

    LaunchedEffect(currentUserId) {
        viewModel.refreshCallAndSmsInterceptWeek(currentUserId)
    }

    // 从学习页完成测评、或从后台回到前台时，确保首页能刷新安全指数（否则会停留在上次计算值）。
    DisposableEffect(lifecycleOwner, currentUserId) {
        val observer = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_RESUME) return@LifecycleEventObserver
            viewModel.refreshCallAndSmsInterceptWeek(currentUserId)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 安全系数 60–80：停留首页期间每约 20s 推送系统通知（需 POST_NOTIFICATIONS）
    LaunchedEffect(uiState.safetyIndex) {
        if (uiState.safetyIndex !in 60..80) return@LaunchedEffect
        while (true) {
            delay(20_000)
            val idx = viewModel.uiState.value.safetyIndex
            if (idx !in 60..80) break
            AlertNotificationCoordinator.postMildFraudRiskLearningReminder(context)
        }
    }

    // 安全系数持续 <30：在首页停留期间循环急促震动；≥30 或离开首页时停止
    LaunchedEffect(uiState.safetyIndex) {
        if (uiState.safetyIndex >= 30) return@LaunchedEffect
        while (true) {
            val idx = viewModel.uiState.value.safetyIndex
            if (idx >= 30) {
                LowSafetyVibration.cancel(context)
                SafetySoundManager.cancel(context)
                break
            }
            LowSafetyVibration.playUrgentBurst(context)
            SafetySoundManager.playCriticalAlert(context)
            delay(minOf(LowSafetyVibration.repeatGapMs(), SafetySoundManager.repeatGapMs()))
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            LowSafetyVibration.cancel(context)
            SafetySoundManager.cancel(context)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (uiState.safetyIndex < 30) {
            LowSafetyFlashingBackground(modifier = Modifier.fillMaxSize())
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
            )
        }
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Header
            AppTopBar(
                title = "首页",
                actions = {
                    Box(
                        modifier = Modifier
                            .padding(end = 16.dp)
                            .shadow(
                                elevation = HomeUnifiedShadowElevation,
                                shape = HomeUnifiedShadowShape20,
                                spotColor = HomeUnifiedShadowSpot,
                                ambientColor = HomeUnifiedShadowAmbient,
                            )
                    ) {
                        GlowButton(
                            text = "AI反诈助手",
                            onClick = onShowAiSheet
                        )
                    }
                }
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(top = 16.dp, bottom = 16.dp)
            ) {
                // Security Score Card
                item {
                    SecurityScoreCard(
                        onNavigateToReport = onNavigateToReport,
                        safetyIndex = uiState.safetyIndex,
                        safetyIndexPrecise = uiState.safetyIndexPrecise,
                        skipWaveFillIntro = safetyWaveIntroDone,
                        onWaveFillIntroFinished = { viewModel.markSafetyIndexWaveIntroDone() }
                    )
                }
                
                // Stats (诈骗拦截)
                item {
                    StatsCard(
                        onNavigateToInterceptRecord = onNavigateToInterceptRecord,
                        phoneWeekInterceptDisplay = uiState.phoneWeekInterceptDisplay,
                        smsWeekInterceptDisplay = uiState.smsWeekInterceptDisplay,
                        appWeekInterceptDisplay = uiState.appWeekInterceptDisplay,
                        clipboardWeekInterceptDisplay = uiState.clipboardWeekInterceptDisplay,
                    )
                }

                // Quick Detection
                item {
                    QuickDetectionCard(onNavigateToIdentify)
                }

                // Personalized Suggestions
                item {
                    PersonalizedSuggestionsCard(
                        suggestions = uiState.suggestions
                    )
                }
            }
        }
    }
}

/** 安全系数低于 30 时全屏浅红警示背景（明暗色阶之间缓变闪烁） */
@Composable
private fun LowSafetyFlashingBackground(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "home_low_safety_bg")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "low_safety_pulse",
    )
    val lightA = Color(0xFFFFEBEE)
    val lightB = Color(0xFFFFCDD2)
    val darkA = Color(0xFF3A1A1C)
    val darkB = Color(0xFF5C2629)
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.45f
    val c0 = if (isDark) darkA else lightA
    val c1 = if (isDark) darkB else lightB
    Box(modifier = modifier.background(lerp(c0, c1, pulse)))
}

/**
 * 安全指数由 [com.magicvvu.fanzha.ui.viewmodels.HomeViewModel] 按《安全指数数学模型》计算后传入 [safetyIndex]，
 * 勿在此写死分数。
 */
@Composable
fun SecurityScoreCard(
    onNavigateToReport: () -> Unit = {},
    safetyIndex: Int,
    safetyIndexPrecise: Double = safetyIndex.toDouble(),
    skipWaveFillIntro: Boolean = false,
    onWaveFillIntroFinished: () -> Unit = {}
) {
    val presentation = remember(safetyIndex) { safetyIndexPresentation(safetyIndex) }
    val headlineColor = when (presentation.tier) {
        SafetyIndexTier.Excellent,
        SafetyIndexTier.Good -> MaterialTheme.colorScheme.primary

        SafetyIndexTier.Fair -> MaterialTheme.colorScheme.tertiary
        SafetyIndexTier.Critical -> MaterialTheme.colorScheme.error
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = HomeUnifiedShadowElevation,
                shape = HomeUnifiedShadowShape20,
                spotColor = HomeUnifiedShadowSpot,
                ambientColor = HomeUnifiedShadowAmbient,
            ),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(end = 8.dp)
            ) {
                val displayIndex = remember(safetyIndexPrecise) {
                    safetyIndexPrecise.coerceIn(0.0, 100.0).toInt()
                }
                SafetyIndexWavePercentage(
                    currentPercentage = displayIndex.toFloat(),
                    maxPercentage = 100f,
                    circularSize = 100,
                    skipInitialFillAnimation = skipWaveFillIntro,
                    onInitialFillAnimationFinished = onWaveFillIntroFinished,
                    backgroundColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                    percentageAnimationDuration = 1500,
                    continuousWaveAnimationDuration = 2500,
                    centerTextStyle = MaterialTheme.typography.displaySmall.copy(
                        color = Color.White,
                        fontWeight = FontWeight.Light,
                        letterSpacing = 0.6.sp,
                        lineHeight = 40.sp,
                        shadow = Shadow(
                            color = Color(0xFF0C4A6E).copy(alpha = 0.45f),
                            offset = Offset(0f, 1f),
                            blurRadius = 5f
                        )
                    ),
                    centerText = displayIndex.toString(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "安全指数", 
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = presentation.levelTitle,
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = headlineColor,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = presentation.explanation,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "已保护天数：120天",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        AppButton(
            text = "查看安全分析报告",
            onClick = onNavigateToReport,
            modifier = Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = HomeUnifiedShadowElevation,
                    shape = HomeUnifiedShadowShape18,
                    spotColor = HomeUnifiedShadowSpot,
                    ambientColor = HomeUnifiedShadowAmbient,
                ),
            minHeight = 48.dp,
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 6.dp)
        )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1A4B8C)
@Composable
fun SecurityScoreCardPreview() {
    MaterialTheme {
        Box(modifier = Modifier.padding(16.dp)) {
            SecurityScoreCard(safetyIndex = 72)
        }
    }
}

@Composable
fun PersonalizedSuggestionsCard(
    suggestions: List<PersonalizedSuggestion>
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "个性化防护与建议",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        
        // We use a custom layout for the timeline style instead of AppCard
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            suggestions.forEachIndexed { index, suggestion ->
                SuggestionTimelineItem(suggestion = suggestion, isLast = index == suggestions.size - 1)
            }
        }
    }
}

@Composable
fun SuggestionTimelineItem(suggestion: PersonalizedSuggestion, isLast: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        // Left Timeline Indicator
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(end = 12.dp, top = 4.dp)
        ) {
            val indicatorColor = when (suggestion.riskLevel) {
                "高优先级" -> Color(0xFFE53935) // Red
                "建议操作" -> Color(0xFFFB8C00) // Orange
                "日常建议" -> Color(0xFF4CAF50) // Green
                else -> Color(0xFF1976D2) // Blue default
            }
            
            Box(
                modifier = Modifier
                    .width(28.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(indicatorColor)
                    .padding(vertical = 12.dp, horizontal = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = suggestion.riskLevel.take(2), // e.g. "高优", "建议"
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center
                )
            }
            
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(40.dp) // Adjust based on content height
                        .background(Color.LightGray.copy(alpha = 0.5f))
                )
            }
        }

        // Right Content Card
        Card(
            modifier = Modifier
                .weight(1f)
                .shadow(
                    elevation = HomeUnifiedShadowElevation,
                    shape = HomeUnifiedShadowShape18,
                    spotColor = HomeUnifiedShadowSpot,
                    ambientColor = HomeUnifiedShadowAmbient,
                ),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = suggestion.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = suggestion.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun StatsCard(
    onNavigateToInterceptRecord: (InterceptSection) -> Unit = {},
    phoneWeekInterceptDisplay: String = "…",
    smsWeekInterceptDisplay: String = "…",
    appWeekInterceptDisplay: String = "…",
    clipboardWeekInterceptDisplay: String = "…",
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "诈骗拦截",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatItemCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Filled.Phone,
                iconTint = Color(0xFFACCAB2),
                iconBgColor = Color(0xFFACCAB2).copy(alpha = 0.15f),
                value = phoneWeekInterceptDisplay,
                label = "拦截电话",
                onClick = { onNavigateToInterceptRecord(InterceptSection.Call) }
            )
            StatItemCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Filled.Email,
                iconTint = Color(0xFFE9A752),
                iconBgColor = Color(0xFFE9A752).copy(alpha = 0.15f),
                value = smsWeekInterceptDisplay,
                label = "拦截短信",
                onClick = { onNavigateToInterceptRecord(InterceptSection.Sms) }
            )
            StatItemCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Filled.Apps,
                iconTint = Color(0xFFD44720),
                iconBgColor = Color(0xFFD44720).copy(alpha = 0.15f),
                value = appWeekInterceptDisplay,
                label = "拦截可疑APP",
                onClick = { onNavigateToInterceptRecord(InterceptSection.App) }
            )
            StatItemCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Filled.ContentPaste,
                iconTint = Color(0xFF78614D),
                iconBgColor = Color(0xFF78614D).copy(alpha = 0.15f),
                value = clipboardWeekInterceptDisplay,
                label = "拦截剪切板",
                onClick = { onNavigateToInterceptRecord(InterceptSection.Clipboard) }
            )
        }
    }
}

@Composable
fun StatItemCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    iconTint: Color,
    iconBgColor: Color,
    value: String,
    label: String,
    onClick: () -> Unit = {}
) {
    // Lighter background derived from iconTint (aligns with InterceptRecords' tint + tinted-bg approach)
    val topFillColor = Color(
        red = (iconTint.red + (1f - iconTint.red) * 0.78f).coerceIn(0f, 1f),
        green = (iconTint.green + (1f - iconTint.green) * 0.78f).coerceIn(0f, 1f),
        blue = (iconTint.blue + (1f - iconTint.blue) * 0.78f).coerceIn(0f, 1f),
        alpha = 1f
    )

    Card(
        modifier = modifier
            .height(120.dp)
            .shadow(
                elevation = HomeUnifiedShadowElevation,
                shape = HomeUnifiedShadowShape18,
                spotColor = HomeUnifiedShadowSpot,
                ambientColor = HomeUnifiedShadowAmbient,
            ),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        onClick = onClick,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Colored top area with upward-convex arc at the bottom
            Canvas(modifier = Modifier.fillMaxSize()) {
                val splitY = size.height * 0.62f
                val arcDepth = size.height * 0.18f
                drawPath(
                    path = Path().apply {
                        moveTo(0f, 0f)
                        lineTo(size.width, 0f)
                        lineTo(size.width, splitY)
                        quadraticBezierTo(size.width / 2f, splitY - arcDepth, 0f, splitY)
                        close()
                    },
                    color = topFillColor
                )
            }
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Top icon zone
                Box(
                    modifier = Modifier
                        .weight(0.58f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(32.dp)
                    )
                }
                // Bottom text zone (white background via card containerColor)
                Box(
                    modifier = Modifier
                        .weight(0.42f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = value,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                        )
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            lineHeight = MaterialTheme.typography.labelSmall.lineHeight
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun QuickDetectionCard(onNavigateToIdentify: (Int) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "快速检测",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                DetectionItemCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.Image,
                    accentColor = Color(0xFF9FB3DF),
                    label = "图像检测",
                    onClick = { onNavigateToIdentify(0) }
                )
                DetectionItemCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.Videocam,
                    accentColor = Color(0xFF9EC6F3),
                    label = "视频检测",
                    onClick = { onNavigateToIdentify(1) }
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                DetectionItemCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.TextFields,
                    accentColor = Color(0xFFB39DDB),
                    label = "文本检测",
                    onClick = { onNavigateToIdentify(2) }
                )
                DetectionItemCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.Mic,
                    accentColor = Color(0xFFFF8A65),
                    label = "语音检测",
                    onClick = { onNavigateToIdentify(3) }
                )
            }
        }
    }
}

@Composable
fun DetectionItemCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    accentColor: Color,
    label: String,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier
            .height(80.dp)
            .shadow(
                elevation = HomeUnifiedShadowElevation,
                shape = HomeUnifiedShadowShape18,
                spotColor = HomeUnifiedShadowSpot,
                ambientColor = HomeUnifiedShadowAmbient,
            )
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = accentColor.copy(alpha = 0.10f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val splitX = size.width * 0.50f
                val slant  = size.height * 0.24f
                drawPath(
                    path = Path().apply {
                        moveTo(0f, 0f)
                        lineTo(splitX + slant, 0f)
                        lineTo(splitX - slant, size.height)
                        lineTo(0f, size.height)
                        close()
                    },
                    color = accentColor.copy(alpha = 0.85f)
                )
            }
            Row(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(0.46f),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(0.54f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = accentColor,
                        textAlign = TextAlign.Center,
                        maxLines = 2
                    )
                }
            }
        }
    }
}
