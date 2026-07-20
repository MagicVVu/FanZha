package com.magicvvu.fanzha.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.magicvvu.fanzha.ui.components.AppTopBar
import com.magicvvu.fanzha.ui.theme.LocalThemePreferenceController
import kotlinx.coroutines.delay

// --- Mock Data ---
data class ReportRecord(
    val id: String,
    val time: String,
    val type: String,
    val status: ReportStatus,
    val result: String? = null
)

enum class ReportStatus(val desc: String, val color: Color) {
    PENDING("待处理", Color(0xFFFFA000)),
    PROCESSING("处理中", Color(0xFF1976D2)),
    COMPLETED("已完成", Color(0xFF388E3C)),
    REJECTED("已驳回", Color(0xFFD32F2F))
}

data class ProgressNode(
    val title: String,
    val handler: String,
    val time: String,
    val comment: String?,
    val isCompleted: Boolean
)

val mockReportList = listOf(
    ReportRecord("R1001", "2023-10-24 14:30", "虚假兼职", ReportStatus.COMPLETED, "举报属实，已封禁违规账号。"),
    ReportRecord("R1002", "2023-10-25 09:15", "冒充客服", ReportStatus.PROCESSING),
    ReportRecord("R1003", "2023-10-26 16:45", "网络钓鱼", ReportStatus.PENDING)
)

val mockProgressNodes = listOf(
    ProgressNode("提交举报", "系统", "2023-10-24 14:30", "举报已成功提交，等待人工审核", true),
    ProgressNode("审核中", "审核专员A", "2023-10-24 15:00", "正在核实相关截图证据", true),
    ProgressNode("处理完成", "处理专员B", "2023-10-25 10:20", "经核实举报属实，已对违规账号执行封禁处理，感谢您的反馈！", true)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportProgressListScreen(
    onBack: () -> Unit,
    onNavigateToDetail: (String) -> Unit
) {
    var isLoading by remember { mutableStateOf(true) }
    var reportList by remember { mutableStateOf<List<ReportRecord>>(emptyList()) }

    LaunchedEffect(Unit) {
        delay(800) // Mock loading
        reportList = mockReportList
        isLoading = false
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

    Scaffold(
        topBar = { AppTopBar(title = "举报进度查询", onBack = onBack) },
        containerColor = scheme.background
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(brush = screenBrush)
                .padding(padding)
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.primary
                )
            } else if (reportList.isEmpty()) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "暂无举报记录",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(reportList) { record ->
                        ReportRecordCard(record = record, onClick = { onNavigateToDetail(record.id) })
                    }
                }
            }
        }
    }
}

@Composable
fun ReportRecordCard(record: ReportRecord, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "举报类型：${record.type}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Surface(
                    color = record.status.color.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = record.status.desc,
                        color = record.status.color,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "举报时间：${record.time}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (record.result != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "处理结果：${record.result}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportProgressDetailScreen(
    reportId: String,
    onBack: () -> Unit
) {
    var isLoading by remember { mutableStateOf(true) }
    var nodes by remember { mutableStateOf<List<ProgressNode>>(emptyList()) }

    LaunchedEffect(reportId) {
        delay(600) // Mock loading
        nodes = mockProgressNodes
        isLoading = false
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

    Scaffold(
        topBar = { AppTopBar(title = "进度详情", onBack = onBack) },
        containerColor = scheme.background
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(brush = screenBrush)
                .padding(padding)
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    Text(
                        text = "处理流程",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )

                    nodes.forEachIndexed { index, node ->
                        ProgressTimelineItem(
                            node = node,
                            isLast = index == nodes.size - 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ProgressTimelineItem(node: ProgressNode, isLast: Boolean) {
    Row(modifier = Modifier.fillMaxWidth()) {
        // Timeline Column
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(32.dp)
        ) {
            Icon(
                imageVector = if (node.isCompleted) Icons.Default.CheckCircle else Icons.Default.HourglassEmpty,
                contentDescription = null,
                tint = if (node.isCompleted) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(24.dp)
            )
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(60.dp) // Fixed height for timeline line
                        .background(
                            if (node.isCompleted) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                            } else {
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                            },
                        )
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Content Column
        Column(modifier = Modifier.weight(1f).padding(bottom = if (isLast) 0.dp else 24.dp)) {
            Text(
                text = node.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "处理人员：${node.handler}  |  ${node.time}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!node.comment.isNullOrEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = node.comment,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}
