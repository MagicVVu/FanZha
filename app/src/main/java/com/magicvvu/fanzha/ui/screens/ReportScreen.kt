package com.magicvvu.fanzha.ui.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.magicvvu.fanzha.ui.viewmodels.HomeUiState
import com.magicvvu.fanzha.ui.viewmodels.HomeViewModel
import com.magicvvu.fanzha.ui.viewmodels.ProfileViewModel
import com.magicvvu.fanzha.ui.components.AppButton
import com.magicvvu.fanzha.ui.components.AppCard
import com.magicvvu.fanzha.ui.components.AppTextField
import com.magicvvu.fanzha.ui.components.AppTopBar
import com.magicvvu.fanzha.ui.viewmodels.DefenseSuggestion
import com.magicvvu.fanzha.ui.viewmodels.ReportType
import com.magicvvu.fanzha.ui.viewmodels.ReportViewModel
import com.magicvvu.fanzha.ui.viewmodels.RiskBehavior
import com.magicvvu.fanzha.ui.viewmodels.RiskStat
import com.magicvvu.fanzha.ui.viewmodels.TrendPoint

/** 与首页 StatsCard 同源：周展示字符串 → 次数（「…」「—」视为 0） */
private fun parseInterceptWeekCount(display: String): Int {
    val t = display.trim()
    if (t == "…" || t == "—" || t.isEmpty()) return 0
    return t.toIntOrNull() ?: 0
}

/** 与首页「诈骗拦截」四项名称、配色、数值一致（HomeViewModel 周聚合） */
private fun homeInterceptRiskStats(home: HomeUiState): List<RiskStat> {
    return listOf(
        RiskStat("拦截电话", parseInterceptWeekCount(home.phoneWeekInterceptDisplay), Color(0xFFACCAB2)),
        RiskStat("拦截短信", parseInterceptWeekCount(home.smsWeekInterceptDisplay), Color(0xFFE9A752)),
        RiskStat("拦截可疑APP", parseInterceptWeekCount(home.appWeekInterceptDisplay), Color(0xFFD44720)),
        RiskStat("拦截剪切板", parseInterceptWeekCount(home.clipboardWeekInterceptDisplay), Color(0xFF78614D)),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportScreen(
    onBack: () -> Unit,
    currentUserId: Long? = null,
    viewModel: ReportViewModel = viewModel(),
    homeViewModel: HomeViewModel = viewModel(),
    profileViewModel: ProfileViewModel = viewModel(),
) {
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }

    val homeUi by homeViewModel.uiState.collectAsState()
    val currentUser = profileViewModel.currentUser
    val interceptStats = remember(
        homeUi.phoneWeekInterceptDisplay,
        homeUi.smsWeekInterceptDisplay,
        homeUi.appWeekInterceptDisplay,
        homeUi.clipboardWeekInterceptDisplay,
    ) {
        homeInterceptRiskStats(homeUi)
    }

    LaunchedEffect(currentUserId) {
        homeViewModel.refreshCallAndSmsInterceptWeek(currentUserId)
    }

    LaunchedEffect(
        viewModel.reportContentKey,
        homeUi.phoneWeekInterceptDisplay,
        homeUi.smsWeekInterceptDisplay,
        homeUi.appWeekInterceptDisplay,
        homeUi.clipboardWeekInterceptDisplay,
        currentUser.id,
        currentUser.age,
        currentUser.gender,
        currentUser.occupation,
    ) {
        viewModel.refreshPersonalizedSuggestions(
            homeUiState = homeUi,
            userProfile = currentUser,
        )
    }

    LaunchedEffect(viewModel.pushSuccessMessage) {
        viewModel.pushSuccessMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = "安全分析报告",
                onBack = onBack,
                actions = {
                    IconButton(onClick = { viewModel.exportReport() }) {
                        Icon(Icons.Default.Share, contentDescription = "Export", tint = MaterialTheme.colorScheme.onSurface)
                    }
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More", tint = MaterialTheme.colorScheme.onSurface)
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        DropdownMenuItem(
                            text = { Text("发送邮件", color = MaterialTheme.colorScheme.onSurface) },
                            onClick = {
                                showMenu = false
                                viewModel.showPushDialog = true
                            },
                            leadingIcon = { Icon(Icons.Default.Email, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface) }
                        )
                    }
                }
            )
        }
    ) { padding ->
        if (viewModel.isLoading && viewModel.trendData.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Time Range Selector
                item {
                    AnimatedReportTypeSelector(
                        currentType = viewModel.currentReportType,
                        onSelectWeekly = { viewModel.generateReport(ReportType.WEEKLY) },
                        onSelectMonthly = { viewModel.generateReport(ReportType.MONTHLY) },
                    )
                }

                // Summary + Risk with switch animation
                item {
                    AnimatedContent(
                        targetState = viewModel.reportContentKey,
                        transitionSpec = {
                            (
                                    fadeIn(
                                        animationSpec = tween(320, easing = FastOutSlowInEasing),
                                    ) + slideInHorizontally { fullWidth -> fullWidth / 5 }
                                    ).togetherWith(
                                    fadeOut(animationSpec = tween(220)) +
                                            slideOutHorizontally { fullWidth -> -fullWidth / 5 },
                                ).using(
                                    SizeTransform(clip = false) { _, _ ->
                                        tween(durationMillis = 280, easing = FastOutSlowInEasing)
                                    },
                                )
                        },
                        label = "reportBody",
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            StatisticsSection(interceptStats, viewModel.trendData)
                            RiskAnalysisSection(viewModel.riskBehaviors)
                            DefenseSuggestionSection(
                                suggestions = viewModel.defenseSuggestions,
                                summary = viewModel.adviceSummary,
                                riskLevel = viewModel.adviceRiskLevel,
                                reasons = viewModel.adviceReasons,
                                isGenerating = viewModel.isGeneratingAdvice,
                            )
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
        }
    }

    if (viewModel.showPushDialog) {
        PushConfigDialog(
            onDismiss = { viewModel.showPushDialog = false },
            onSend = { email, isSystem -> viewModel.sendReport(email, isSystem) }
        )
    }
}

@Composable
private fun AnimatedReportTypeSelector(
    currentType: ReportType,
    onSelectWeekly: () -> Unit,
    onSelectMonthly: () -> Unit,
) {
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = trackColor,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
        ) {
            val segmentWidth = maxWidth / 2
            val pillOffset by animateDpAsState(
                targetValue = if (currentType == ReportType.WEEKLY) 0.dp else segmentWidth,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
                label = "reportTabPill",
            )
            Box(
                modifier = Modifier
                    .offset(x = pillOffset)
                    .width(segmentWidth)
                    .height(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                ReportTypeTabSegment(
                    title = "本周报告",
                    selected = currentType == ReportType.WEEKLY,
                    onClick = onSelectWeekly,
                    modifier = Modifier.weight(1f),
                )
                ReportTypeTabSegment(
                    title = "本月报告",
                    selected = currentType == ReportType.MONTHLY,
                    onClick = onSelectMonthly,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ReportTypeTabSegment(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val textColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "tabTextColor",
    )
    Box(
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title,
            color = textColor,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}

// --- Charts Section ---

private const val DonutStrokePx = 46f
private const val DonutGapDeg = 4f
private const val TrendMaxBarDp = 100f

@Composable
fun StatisticsSection(stats: List<RiskStat>, trends: List<TrendPoint>) {
    AppCard {
        Text(
            "拦截数据统计",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(20.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(168.dp),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedDonutChart(stats)
            }
            Column(
                modifier = Modifier
                    .padding(start = 12.dp)
                    .widthIn(min = 120.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val total = stats.sumOf { it.count }
                stats.forEach { stat ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(stat.color, RoundedCornerShape(3.dp)),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = stat.label,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            val pct = if (total > 0) (stat.count * 100f / total).toInt() else 0
                            Text(
                                text = "${stat.count} 次 · $pct%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(modifier = Modifier.height(18.dp))

        Text(
            "趋势变化",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(16.dp))

        AnimatedTrendBarChart(trends)
    }
}

@Composable
fun AnimatedDonutChart(stats: List<RiskStat>) {
    val total = stats.sumOf { it.count }
    val sweepDenominator = total.coerceAtLeast(1)

    var triggered by remember { mutableStateOf(false) }
    val animProgress by animateFloatAsState(
        targetValue = if (triggered) 1f else 0f,
        animationSpec = tween(durationMillis = 900, easing = FastOutSlowInEasing),
        label = "donutProgress",
    )
    LaunchedEffect(stats) {
        triggered = false
        kotlinx.coroutines.delay(40)
        triggered = true
    }

    val surfaceColor = MaterialTheme.colorScheme.surface
    val subtleRingColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)

    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeW = DonutStrokePx
            val radius = (size.minDimension - strokeW) / 2f
            val cx = size.width / 2f
            val cy = size.height / 2f
            val inset = strokeW / 2f

            // Background track ring
            drawCircle(
                color = subtleRingColor,
                radius = radius,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeW)
            )

            var startAngle = -90f
            stats.forEach { stat ->
                val fullSweep = (stat.count.toFloat() / sweepDenominator) * 360f
                val sweep = (fullSweep * animProgress - DonutGapDeg).coerceAtLeast(0f)
                drawArc(
                    color = stat.color,
                    startAngle = startAngle,
                    sweepAngle = sweep,
                    useCenter = false,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = strokeW,
                        cap = androidx.compose.ui.graphics.StrokeCap.Round
                    ),
                    topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                    size = androidx.compose.ui.geometry.Size(
                        size.width - strokeW,
                        size.height - strokeW
                    )
                )
                startAngle += fullSweep * animProgress
            }

            // Centre fill
            drawCircle(color = surfaceColor, radius = radius - strokeW / 2f + 2f)
        }

        // Centre label
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val displayTotal = (total * animProgress).toInt()
            Text(
                text = displayTotal.toString(),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "次拦截",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun AnimatedTrendBarChart(data: List<TrendPoint>) {
    if (data.isEmpty()) return
    val maxVal = data.maxOf { it.value }.coerceAtLeast(1)
    val primary = MaterialTheme.colorScheme.primary
    val primaryLight = primary.copy(alpha = 0.35f)

    var triggered by remember { mutableStateOf(false) }
    LaunchedEffect(data) {
        triggered = false
        kotlinx.coroutines.delay(60)
        triggered = true
    }

    Column {
        // Bar + value area
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(130.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Bottom
        ) {
            data.forEachIndexed { idx, point ->
                val targetFraction = point.value.toFloat() / maxVal
                val animFraction by animateFloatAsState(
                    targetValue = if (triggered) targetFraction else 0f,
                    animationSpec = tween(
                        durationMillis = 600,
                        delayMillis = idx * 70,
                        easing = androidx.compose.animation.core.FastOutSlowInEasing
                    ),
                    label = "trendBar$idx"
                )
                val barHeightDp = (TrendMaxBarDp * animFraction).dp

                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom
                ) {
                    if (animFraction > 0.05f) {
                        Text(
                            text = point.value.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = primary,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.graphicsLayer { alpha = (animFraction * 3f).coerceIn(0f, 1f) }
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                    }
                    Box(
                        modifier = Modifier
                            .width(18.dp)
                            .height(barHeightDp.coerceAtLeast(2.dp))
                            .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                            .background(
                                Brush.verticalGradient(listOf(primaryLight, primary))
                            )
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Date labels row aligned with bars
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            data.forEach { point ->
                Text(
                    text = point.date,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
}

// --- Risk Analysis Section ---

@Composable
fun RiskAnalysisSection(behaviors: List<RiskBehavior>) {
    AppCard {
        Text(
            "风险行为分析",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(16.dp))

        if (behaviors.isEmpty()) {
            Text(
                "暂无高风险行为，请继续保持。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            behaviors.forEach { behavior ->
                RiskBehaviorItem(behavior)
                if (behavior != behaviors.last()) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
fun RiskBehaviorItem(item: RiskBehavior) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                item.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            val color = when(item.level) {
                "High" -> MaterialTheme.colorScheme.error
                "Medium" -> MaterialTheme.colorScheme.tertiary // Keep orange for warning
                else -> Color(0xFF388E3C) // Keep green for safe
            }
            Text(
                item.level,
                color = color,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                "${item.frequency}次",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// --- Suggestion Section ---

@Composable
private fun DefenseSuggestionSection(
    suggestions: List<DefenseSuggestion>,
    summary: String,
    riskLevel: String,
    reasons: List<String>,
    isGenerating: Boolean,
) {
    AppCard {
        Text(
            "个性化安全建议",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )

        if (summary.isNotBlank()) {
            Spacer(modifier = Modifier.height(12.dp))
            val summaryBg = when (riskLevel.lowercase()) {
                "high" -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)
                "medium" -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f)
                else -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(summaryBg)
                    .padding(14.dp),
            ) {
                Text(
                    text = "风险概览",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    lineHeight = 21.sp,
                )
                if (reasons.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    reasons.take(2).forEach { reason ->
                        Text(
                            text = "• $reason",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 19.sp,
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (isGenerating && suggestions.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "正在生成个性化建议…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            suggestions.forEachIndexed { index, item ->
                val isLast = index == suggestions.lastIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "${index + 1}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Text(
                        text = item.content,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        lineHeight = 22.sp,
                    )
                }

                if (!isLast) {
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

// --- Push Dialog ---

@Composable
fun PushConfigDialog(
    onDismiss: () -> Unit,
    onSend: (String, Boolean) -> Unit
) {
    var email by remember { mutableStateOf("") }
    var isSystemPush by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("推送报告", style = MaterialTheme.typography.headlineSmall) },
        containerColor = MaterialTheme.colorScheme.surface,
        text = {
            Column {
                AppTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = "接收邮箱",
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = isSystemPush,
                        onCheckedChange = { isSystemPush = it },
                        colors = CheckboxDefaults.colors(
                            checkedColor = MaterialTheme.colorScheme.primary,
                            uncheckedColor = MaterialTheme.colorScheme.outline
                        )
                    )
                    Text(
                        "同时发送系统消息通知",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        },
        confirmButton = {
            AppButton(
                text = "发送",
                onClick = { onSend(email, isSystemPush) },
                enabled = email.isNotEmpty(),
                modifier = Modifier.height(48.dp)
            )
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary)
            ) {
                Text("取消", style = MaterialTheme.typography.labelLarge)
            }
        }
    )
}
