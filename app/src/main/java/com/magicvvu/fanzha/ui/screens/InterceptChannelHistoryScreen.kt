package com.magicvvu.fanzha.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.magicvvu.fanzha.data.remote.ApiClient
import com.magicvvu.fanzha.data.remote.InterceptRecentEntryDto
import com.magicvvu.fanzha.ui.components.AppTopBar
import com.magicvvu.fanzha.ui.theme.LocalThemePreferenceController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

private fun InterceptSection.toHistoryChannelParam(): String = when (this) {
    InterceptSection.Call -> "CALL"
    InterceptSection.Sms -> "SMS"
    InterceptSection.App -> "SUSPICIOUS_APP"
    InterceptSection.Clipboard -> "CLIPBOARD"
}

private const val HISTORY_DETAIL_LIMIT = 20
private const val HISTORY_POLL_INTERVAL_MS = 20_000L
private const val HISTORY_LIST_SUBTITLE = "展示最新 $HISTORY_DETAIL_LIMIT 条，按拦截时间倒序；每 ${HISTORY_POLL_INTERVAL_MS / 1000} 秒自动刷新"

@Composable
fun InterceptChannelHistoryScreen(
    section: InterceptSection,
    currentUserId: Long?,
    onBack: () -> Unit,
    /**
     * 为 true 时不请求本机 `/intercept/history`、不轮询；版式与本人页完全一致，列表为空（家庭成员流水待后端对接）。
     */
    suppressHistoryFetch: Boolean = false,
) {
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

    val titleColor =
        if (themeController.isDarkTheme) Color(0xFFF8FAFC) else MaterialTheme.colorScheme.onSurface
    val bodyMuted =
        if (themeController.isDarkTheme) Color(0xFFCBD5E1) else MaterialTheme.colorScheme.onSurfaceVariant

    var loading by remember(section, currentUserId, suppressHistoryFetch) {
        mutableStateOf(if (suppressHistoryFetch) false else currentUserId != null)
    }
    var refreshing by remember(section, currentUserId, suppressHistoryFetch) { mutableStateOf(false) }
    var entries by remember(section, currentUserId, suppressHistoryFetch) {
        mutableStateOf<List<InterceptRecentEntryDto>>(emptyList())
    }

    LaunchedEffect(section, currentUserId, suppressHistoryFetch) {
        if (suppressHistoryFetch) {
            loading = false
            refreshing = false
            entries = emptyList()
            return@LaunchedEffect
        }
        if (currentUserId == null) {
            loading = false
            refreshing = false
            entries = emptyList()
            return@LaunchedEffect
        }
        suspend fun loadOnce(): List<InterceptRecentEntryDto> {
            val response = withContext(Dispatchers.IO) {
                ApiClient.interceptHistoryApi.history(
                    userId = currentUserId,
                    channel = section.toHistoryChannelParam(),
                    limit = HISTORY_DETAIL_LIMIT,
                )
            }
            val body = response.body()
            return if (response.isSuccessful && body != null && body.success) {
                body.data.orEmpty()
            } else {
                emptyList()
            }
        }
        loading = true
        try {
            entries = try {
                loadOnce()
            } catch (_: Exception) {
                emptyList()
            }
        } finally {
            loading = false
        }
        // 定时拉取最新 20 条，与列表轮替刷新（离开页面后协程取消）。
        while (isActive) {
            delay(HISTORY_POLL_INTERVAL_MS)
            refreshing = true
            try {
                entries = try {
                    loadOnce()
                } catch (_: Exception) {
                    entries
                }
            } finally {
                refreshing = false
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(brush = screenBrush)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            AppTopBar(title = section.interceptHistoryDetailTitle(), onBack = onBack)
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text(
                        text = HISTORY_LIST_SUBTITLE,
                        style = MaterialTheme.typography.bodyMedium,
                        color = bodyMuted
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                if (refreshing && !loading && entries.isNotEmpty()) {
                    item {
                        Text(
                            text = "正在刷新…",
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.primary,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                }

                if (loading) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = scheme.primary)
                        }
                    }
                } else if (entries.isEmpty()) {
                    item {
                        Text(
                            text = "暂无拦截记录",
                            style = MaterialTheme.typography.bodyMedium,
                            color = bodyMuted,
                            modifier = Modifier.padding(vertical = 24.dp)
                        )
                    }
                } else {
                    itemsIndexed(entries, key = { _, e ->
                        e.recordId?.toString() ?: "${e.interceptAt.orEmpty()}_${e.interceptContent.hashCode()}"
                    }) { index, entry ->
                        HistoryRecordCard(
                            index = index + 1,
                            section = section,
                            entry = entry,
                            titleColor = titleColor,
                            mutedColor = bodyMuted,
                        )
                    }
                }

                item { Spacer(modifier = Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
private fun HistoryRecordCard(
    index: Int,
    section: InterceptSection,
    entry: InterceptRecentEntryDto,
    titleColor: Color,
    mutedColor: Color,
) {
    val timeText = formatLastInterceptAt(entry.interceptAt)
    val contentText = entry.interceptContent?.trim().orEmpty()
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Text(
                text = "第 ${index} 条",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = titleColor
            )
            Spacer(modifier = Modifier.height(10.dp))
            if (timeText.isNotBlank()) {
                Text(
                    text = "拦截时间",
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedColor,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = timeText,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = titleColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (contentText.isNotBlank()) {
                if (timeText.isNotBlank()) Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = section.interceptHistoryContentLabel(),
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedColor,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = contentText,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = titleColor,
                    maxLines = if (section == InterceptSection.Clipboard || section == InterceptSection.Sms) 12 else 8,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (timeText.isBlank() && contentText.isBlank()) {
                Text(
                    text = "（无时间与内容字段）",
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedColor,
                )
            }
        }
    }
}
