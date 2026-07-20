package com.magicvvu.fanzha.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.magicvvu.fanzha.data.remote.ApiClient
import com.magicvvu.fanzha.data.remote.InterceptWeeklyDashboardDto
import com.magicvvu.fanzha.ui.components.AppTopBar
import com.magicvvu.fanzha.ui.theme.LocalThemePreferenceController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import com.magicvvu.fanzha.ui.theme.ThemePreferenceController
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val AccordionExpandTween = tween<IntSize>(
    durationMillis = 320,
    easing = FastOutSlowInEasing
)

private val AccordionShrinkTween = tween<IntSize>(
    durationMillis = 260,
    easing = FastOutSlowInEasing
)

private val ChevronSpring = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMedium
)

private val BarSpring = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessLow
)

enum class InterceptSection {
    Call,
    Sms,
    App,
    Clipboard
}

/** 通道拦截明细页顶栏标题（首页、家庭等跳转共用） */
fun InterceptSection.interceptHistoryDetailTitle(): String = when (this) {
    InterceptSection.Call -> "诈骗电话拦截"
    InterceptSection.Sms -> "诈骗短信拦截"
    InterceptSection.App -> "诈骗APP拦截"
    InterceptSection.Clipboard -> "剪切板隐私拦截"
}

/** 明细卡片正文列标题（与折叠区「最近拦截」单行摘要一致） */
fun InterceptSection.interceptHistoryContentLabel(): String = when (this) {
    InterceptSection.Call -> "对方号码"
    InterceptSection.Sms -> "短信详情"
    InterceptSection.App -> "应用详情"
    InterceptSection.Clipboard -> "剪切板内容"
}

/**
 * 单类拦截模块展示数据。
 * [weeklyCounts]：周一至周日每日条数，来自各 intercept_* 明细表；
 * [weekTotal]：本周该通道合计，与 [weeklyCounts] 同源（服务端各 intercept_* 明细表周聚合）。
 */
private data class ChannelLastIntercept(
    val at: String?,
    val content: String?,
)

private data class InterceptModule(
    val section: InterceptSection,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val iconTint: Color,
    val iconBgColor: Color,
    val weeklyCounts: List<Int>,
    val weekTotal: Int,
    /** 至多 1 条：来自 `/intercept/history?limit=1`，与明细表最新一行一致。 */
    val recentRecords: List<ChannelLastIntercept>,
)

internal fun formatLastInterceptAt(raw: String?): String {
    if (raw.isNullOrBlank()) return ""
    return try {
        val instant = Instant.parse(raw)
        instant.atZone(ZoneId.systemDefault()).format(
            DateTimeFormatter.ofPattern("M月d日 HH:mm", Locale.CHINA)
        )
    } catch (_: Exception) {
        raw.trim()
    }
}

internal fun lastInterceptDetailLabel(section: InterceptSection): String = when (section) {
    InterceptSection.Call, InterceptSection.Sms -> "对方号码"
    InterceptSection.App -> "应用名称"
    InterceptSection.Clipboard -> "剪切板内容"
}

private fun normalizeWeekDaily(raw: List<Int>?): List<Int> {
    val list = raw.orEmpty()
    return List(7) { idx -> list.getOrElse(idx) { 0 } }
}

/** channel：`CALL` / `SMS` / `SUSPICIOUS_APP` / `CLIPBOARD`，与 `/intercept/history` 一致。 */
private suspend fun loadLatestInterceptRecordsForChannel(
    userId: Long,
    channel: String,
): List<ChannelLastIntercept> = withContext(Dispatchers.IO) {
    try {
        val response = ApiClient.interceptHistoryApi.history(
            userId = userId,
            channel = channel,
            limit = 1,
        )
        val body = response.body()
        val first = if (response.isSuccessful && body != null && body.success) {
            body.data?.firstOrNull()
        } else {
            null
        }
        if (first == null) return@withContext emptyList()
        val at = first.interceptAt?.trim()?.takeIf { it.isNotEmpty() }
        val c = first.interceptContent?.trim()?.takeIf { it.isNotEmpty() }
        if (at == null && c == null) emptyList() else listOf(ChannelLastIntercept(at, c))
    } catch (_: Exception) {
        emptyList()
    }
}

/** 展开区单行预览：时间 + 内容，过长省略号。 */
private fun buildLatestInterceptPreviewLine(at: String?, content: String?): String {
    val timePart = formatLastInterceptAt(at).trim()
    val contentPart = content?.trim().orEmpty()
    return when {
        timePart.isNotEmpty() && contentPart.isNotEmpty() -> "$timePart · $contentPart"
        timePart.isNotEmpty() -> timePart
        contentPart.isNotEmpty() -> contentPart
        else -> ""
    }
}

@Composable
fun InterceptRecordsScreen(
    initialExpandedSection: InterceptSection = InterceptSection.Call,
    currentUserId: Long? = null,
    onBack: () -> Unit,
    onOpenChannelHistory: (InterceptSection) -> Unit = {},
) {
    var callWeeklyCounts by remember { mutableStateOf(List(7) { 0 }) }
    var smsWeeklyCounts by remember { mutableStateOf(List(7) { 0 }) }
    var appWeeklyCounts by remember { mutableStateOf(List(7) { 0 }) }
    var clipboardWeeklyCounts by remember { mutableStateOf(List(7) { 0 }) }

    var callWeekTotal by remember { mutableStateOf(0) }
    var smsWeekTotal by remember { mutableStateOf(0) }
    var appWeekTotal by remember { mutableStateOf(0) }
    var clipboardWeekTotal by remember { mutableStateOf(0) }

    var callRecentRecords by remember { mutableStateOf<List<ChannelLastIntercept>>(emptyList()) }
    var smsRecentRecords by remember { mutableStateOf<List<ChannelLastIntercept>>(emptyList()) }
    var appRecentRecords by remember { mutableStateOf<List<ChannelLastIntercept>>(emptyList()) }
    var clipboardRecentRecords by remember { mutableStateOf<List<ChannelLastIntercept>>(emptyList()) }

    LaunchedEffect(currentUserId) {
        if (currentUserId == null) {
            callWeeklyCounts = List(7) { 0 }
            smsWeeklyCounts = List(7) { 0 }
            appWeeklyCounts = List(7) { 0 }
            clipboardWeeklyCounts = List(7) { 0 }
            callWeekTotal = 0
            smsWeekTotal = 0
            appWeekTotal = 0
            clipboardWeekTotal = 0
            callRecentRecords = emptyList()
            smsRecentRecords = emptyList()
            appRecentRecords = emptyList()
            clipboardRecentRecords = emptyList()
            return@LaunchedEffect
        }
        try {
            supervisorScope {
                val dashboardDeferred = async(Dispatchers.IO) {
                    try {
                        val r = ApiClient.interceptWeeklyDashboardApi.weeklyDashboard(currentUserId)
                        val b = r.body()
                        if (r.isSuccessful && b != null && b.success) b.data else null
                    } catch (_: Exception) {
                        null
                    }
                }
                val callLatest = async(Dispatchers.IO) {
                    loadLatestInterceptRecordsForChannel(currentUserId, "CALL")
                }
                val smsLatest = async(Dispatchers.IO) {
                    loadLatestInterceptRecordsForChannel(currentUserId, "SMS")
                }
                val appLatest = async(Dispatchers.IO) {
                    loadLatestInterceptRecordsForChannel(currentUserId, "SUSPICIOUS_APP")
                }
                val clipLatest = async(Dispatchers.IO) {
                    loadLatestInterceptRecordsForChannel(currentUserId, "CLIPBOARD")
                }

                val dashboard: InterceptWeeklyDashboardDto? = dashboardDeferred.await()
                if (dashboard != null) {
                    callWeeklyCounts = normalizeWeekDaily(dashboard.call.dailyCounts)
                    smsWeeklyCounts = normalizeWeekDaily(dashboard.sms.dailyCounts)
                    appWeeklyCounts = normalizeWeekDaily(dashboard.suspiciousApp.dailyCounts)
                    clipboardWeeklyCounts = normalizeWeekDaily(dashboard.clipboard.dailyCounts)
                    callWeekTotal = dashboard.call.weekTotal
                    smsWeekTotal = dashboard.sms.weekTotal
                    appWeekTotal = dashboard.suspiciousApp.weekTotal
                    clipboardWeekTotal = dashboard.clipboard.weekTotal
                } else {
                    callWeeklyCounts = List(7) { 0 }
                    smsWeeklyCounts = List(7) { 0 }
                    appWeeklyCounts = List(7) { 0 }
                    clipboardWeeklyCounts = List(7) { 0 }
                    callWeekTotal = 0
                    smsWeekTotal = 0
                    appWeekTotal = 0
                    clipboardWeekTotal = 0
                }

                callRecentRecords = callLatest.await()
                smsRecentRecords = smsLatest.await()
                appRecentRecords = appLatest.await()
                clipboardRecentRecords = clipLatest.await()
            }
        } catch (_: Exception) {
            callWeeklyCounts = List(7) { 0 }
            smsWeeklyCounts = List(7) { 0 }
            appWeeklyCounts = List(7) { 0 }
            clipboardWeeklyCounts = List(7) { 0 }
            callWeekTotal = 0
            smsWeekTotal = 0
            appWeekTotal = 0
            clipboardWeekTotal = 0
            callRecentRecords = emptyList()
            smsRecentRecords = emptyList()
            appRecentRecords = emptyList()
            clipboardRecentRecords = emptyList()
        }
    }

    val modules = remember(
        callWeeklyCounts,
        smsWeeklyCounts,
        appWeeklyCounts,
        clipboardWeeklyCounts,
        callWeekTotal,
        smsWeekTotal,
        appWeekTotal,
        clipboardWeekTotal,
        callRecentRecords,
        smsRecentRecords,
        appRecentRecords,
        clipboardRecentRecords,
    ) {
        listOf(
            InterceptModule(
                section = InterceptSection.Call,
                title = "诈骗电话拦截",
                subtitle = "高频来电与冒充类风险拦截",
                icon = Icons.Filled.Phone,
                iconTint = Color(0xFFACCAB2),
                iconBgColor = Color(0xFFACCAB2).copy(alpha = 0.15f),
                weeklyCounts = callWeeklyCounts,
                weekTotal = callWeekTotal,
                recentRecords = callRecentRecords,
            ),
            InterceptModule(
                section = InterceptSection.Sms,
                title = "诈骗短信拦截",
                subtitle = "营销欺诈与钓鱼短信识别拦截",
                icon = Icons.Filled.Email,
                iconTint = Color(0xFFE9A752),
                iconBgColor = Color(0xFFE9A752).copy(alpha = 0.15f),
                weeklyCounts = smsWeeklyCounts,
                weekTotal = smsWeekTotal,
                recentRecords = smsRecentRecords,
            ),
            InterceptModule(
                section = InterceptSection.App,
                title = "诈骗APP拦截",
                subtitle = "可疑安装包与高危应用阻断",
                icon = Icons.Filled.Apps,
                iconTint = Color(0xFFD44720),
                iconBgColor = Color(0xFFD44720).copy(alpha = 0.15f),
                weeklyCounts = appWeeklyCounts,
                weekTotal = appWeekTotal,
                recentRecords = appRecentRecords,
            ),
            InterceptModule(
                section = InterceptSection.Clipboard,
                title = "截切版隐私泄露拦截",
                subtitle = "剪切板敏感信息外发防护",
                icon = Icons.Filled.ContentPaste,
                iconTint = Color(0xFF78614D),
                iconBgColor = Color(0xFF78614D).copy(alpha = 0.15f),
                weeklyCounts = clipboardWeeklyCounts,
                weekTotal = clipboardWeekTotal,
                recentRecords = clipboardRecentRecords,
            )
        )
    }

    var expandedSection by remember(initialExpandedSection) {
        mutableStateOf<InterceptSection?>(initialExpandedSection)
    }

    val themeController = LocalThemePreferenceController.current
    val scheme = MaterialTheme.colorScheme
    val screenBrush = if (!themeController.isDarkTheme) {
        Brush.verticalGradient(
            colors = listOf(
                Color(0xFFEAF3FF),
                Color(0xFFF4F8FF),
                scheme.background,
            ),
        )
    } else {
        Brush.verticalGradient(
            colors = listOf(
                scheme.surface,
                scheme.background,
                scheme.background,
            ),
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(brush = screenBrush)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            AppTopBar(title = "拦截记录", onBack = onBack)
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    val titleColor =
                        if (themeController.isDarkTheme) Color(0xFFF8FAFC) else MaterialTheme.colorScheme.onSurface
                    val subtitleColor =
                        if (themeController.isDarkTheme) Color(0xFFCBD5E1) else MaterialTheme.colorScheme.onSurfaceVariant
                    Text(
                        text = "本周风险拦截概览",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = titleColor
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "点击模块可展开查看本周拦截次数趋势",
                        style = MaterialTheme.typography.bodyMedium,
                        color = subtitleColor
                    )
                }

                items(modules) { module ->
                    InterceptModuleCard(
                        module = module,
                        expanded = expandedSection == module.section,
                        onToggle = {
                            expandedSection = if (expandedSection == module.section) {
                                null
                            } else {
                                module.section
                            }
                        },
                        onOpenChannelHistory = onOpenChannelHistory,
                    )
                }

                item {
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
private fun InterceptModuleCard(
    module: InterceptModule,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpenChannelHistory: (InterceptSection) -> Unit,
) {
    val themeController = LocalThemePreferenceController.current
    val weekTotal = module.weekTotal
    val titleColor =
        if (themeController.isDarkTheme) Color(0xFFF8FAFC) else MaterialTheme.colorScheme.onSurface
    val bodyMuted =
        if (themeController.isDarkTheme) Color(0xFFCBD5E1) else MaterialTheme.colorScheme.onSurfaceVariant

    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = ChevronSpring,
        label = "interceptChevron"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(module.iconBgColor),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = module.icon,
                        contentDescription = null,
                        tint = module.iconTint,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = module.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = titleColor
                    )
                    Text(
                        text = module.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = bodyMuted
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "$weekTotal 次",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = module.iconTint
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = bodyMuted,
                        modifier = Modifier
                            .size(20.dp)
                            .rotate(chevronRotation)
                    )
                }
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(
                    expandFrom = Alignment.Top,
                    animationSpec = AccordionExpandTween
                ) + fadeIn(
                    animationSpec = tween(
                        durationMillis = 220,
                        easing = FastOutSlowInEasing
                    )
                ),
                exit = shrinkVertically(
                    shrinkTowards = Alignment.Top,
                    animationSpec = AccordionShrinkTween
                ) + fadeOut(
                    animationSpec = tween(
                        durationMillis = 180,
                        easing = FastOutSlowInEasing
                    )
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                ) {
                    Spacer(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "本周拦截次数",
                        style = MaterialTheme.typography.labelLarge,
                        color = bodyMuted
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    WeeklyBarChart(
                        counts = module.weeklyCounts,
                        barColor = module.iconTint,
                        expanded = expanded,
                        labelColor = bodyMuted,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    RecentInterceptRecordsSection(
                        records = module.recentRecords,
                        accentColor = module.iconTint,
                        mutedColor = bodyMuted,
                        titleColor = titleColor,
                        onViewAllClick = { onOpenChannelHistory(module.section) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RecentInterceptRecordsSection(
    records: List<ChannelLastIntercept>,
    accentColor: Color,
    mutedColor: Color,
    titleColor: Color,
    onViewAllClick: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(14.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(accentColor.copy(alpha = 0.85f))
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "最近拦截记录",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = titleColor
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        val previewLine = records.firstOrNull()?.let { buildLatestInterceptPreviewLine(it.at, it.content) }.orEmpty()
        if (previewLine.isBlank()) {
            Text(
                text = "暂无拦截记录",
                style = MaterialTheme.typography.bodySmall,
                color = mutedColor,
            )
        } else {
            Text(
                text = previewLine,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(modifier = Modifier.height(14.dp))
        OutlinedButton(
            onClick = onViewAllClick,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, accentColor.copy(alpha = 0.45f)),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = accentColor,
            ),
        ) {
            Text(
                text = "查看详情",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "进入后展示该通道最新 20 条明细",
            style = MaterialTheme.typography.bodySmall,
            color = mutedColor,
        )
    }
}

@Composable
private fun WeeklyBarChart(
    counts: List<Int>,
    barColor: Color,
    expanded: Boolean,
    labelColor: Color,
) {
    val maxValue = (counts.maxOrNull() ?: 1).coerceAtLeast(1)
    val days = listOf("一", "二", "三", "四", "五", "六", "日")

    var barsReady by remember { mutableStateOf(false) }
    LaunchedEffect(expanded) {
        if (!expanded) {
            barsReady = false
            return@LaunchedEffect
        }
        barsReady = false
        delay(48)
        barsReady = true
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom
    ) {
        counts.forEachIndexed { index, value ->
            val ratio = value.toFloat() / maxValue.toFloat()
            val barHeight = (28f + ratio * 72f).dp
            val barScale by animateFloatAsState(
                targetValue = if (barsReady) 1f else 0f,
                animationSpec = BarSpring,
                label = "barScale$index"
            )
            val labelAlpha by animateFloatAsState(
                targetValue = if (barsReady) 1f else 0f,
                animationSpec = tween(durationMillis = 200, delayMillis = index * 28, easing = FastOutSlowInEasing),
                label = "labelAlpha$index"
            )

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = value.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = labelColor,
                    modifier = Modifier.graphicsLayer { alpha = labelAlpha }
                )
                Spacer(modifier = Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .width(18.dp)
                        .height(barHeight)
                        .graphicsLayer {
                            transformOrigin = TransformOrigin(0.5f, 1f)
                            scaleY = barScale
                            alpha = 0.92f * barScale.coerceIn(0f, 1f)
                        }
                        .clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp))
                        .background(barColor.copy(alpha = 0.9f))
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = days.getOrElse(index) { "" },
                    style = MaterialTheme.typography.labelSmall,
                    color = labelColor,
                    modifier = Modifier.graphicsLayer { alpha = labelAlpha }
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun InterceptRecordsScreenPreview() {
    val context = LocalContext.current
    val themeController = remember { ThemePreferenceController(context) }
    CompositionLocalProvider(LocalThemePreferenceController provides themeController) {
        MaterialTheme {
            InterceptRecordsScreen(onBack = {})
        }
    }
}
