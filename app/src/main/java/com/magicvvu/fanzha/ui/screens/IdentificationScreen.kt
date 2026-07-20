package com.magicvvu.fanzha.ui.screens

import android.content.Context
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import com.magicvvu.fanzha.ui.components.AppTopBar
import com.magicvvu.fanzha.ui.components.SectionHeader
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.magicvvu.fanzha.data.local.DetectionHistoryEntry
import com.magicvvu.fanzha.data.local.DetectionHistoryPreferences
import com.magicvvu.fanzha.ui.viewmodels.IdentificationUiState
import com.magicvvu.fanzha.ui.viewmodels.IdentificationViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


private enum class DetectionState { IDLE, DETECTING, RESULT }
private data class DetectionResult(
    val riskLevel: String,   // "High" | "Medium" | "Safe"
    val title: String,
    val summary: String,
    val confidence: Float,
    val probability: Float
)
private const val MAX_DETECTION_IMAGES_OR_AUDIO = 9

private fun mapBackendRiskLevel(level: String): String {
    return when (level.lowercase()) {
        "high" -> "High"
        "medium" -> "Medium"
        else -> "Safe"
    }
}

private fun buildDetectionResult(
    riskLevel: String,
    reply: String,
    reason: List<String>,
    confidence: Float,
    probability: Float
): DetectionResult {
    val title = when (riskLevel.lowercase()) {
        "high" -> "检测到高风险内容"
        "medium" -> "检测到中风险内容"
        else -> "内容相对安全"
    }

    val summary = when {
        reply.isNotBlank() -> reply
        reason.isNotEmpty() -> reason.joinToString("；")
        else -> "未返回更多说明"
    }

    return DetectionResult(
        riskLevel = mapBackendRiskLevel(riskLevel),
        title = title,
        summary = summary,
        confidence = confidence,
        probability = probability
    )
}


@Composable
fun IdentificationScreen(
    initialTab: Int = 0,
    onBack: () -> Unit = {},
    onReport: () -> Unit = {}
) {
    IdentificationContent(initialTab = initialTab, onBack = onBack, onReport = onReport)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdentificationContent(
    initialTab: Int = 0,
    onBack: () -> Unit = {},
    onReport: () -> Unit = {}
) {
    val tabs = listOf("图像检测", "视频检测", "文本检测", "语音检测")
    val pagerState = rememberPagerState(
        initialPage = initialTab,
        pageCount = { tabs.size }
    )
    var selectedType by remember { mutableStateOf(tabs[initialTab]) }

    LaunchedEffect(pagerState.currentPage) {
        selectedType = tabs[pagerState.currentPage]
    }
    LaunchedEffect(initialTab) {
        if (pagerState.currentPage != initialTab) {
            pagerState.scrollToPage(initialTab)
        }
    }

    val coroutineScope = rememberCoroutineScope()
    val identificationViewModel: IdentificationViewModel = viewModel()
    val uiState by identificationViewModel.uiState.collectAsState()

    val context = LocalContext.current

    var inputText by remember { mutableStateOf("") }

    var detectionState by remember { mutableStateOf(DetectionState.IDLE) }
    var detectionResult by remember { mutableStateOf<DetectionResult?>(null) }


    val selectedImageUris = remember { mutableStateListOf<Uri>() }
    var selectedVideoUri by remember { mutableStateOf<Uri?>(null) }
    val selectedAudioUris = remember { mutableStateListOf<Uri>() }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri ->
            if (uri != null && selectedImageUris.size < MAX_DETECTION_IMAGES_OR_AUDIO) {
                selectedImageUris.add(uri)
            }
        }
    )
    val videoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri -> selectedVideoUri = uri }
    )
    val audioPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri ->
            if (uri != null && selectedAudioUris.size < MAX_DETECTION_IMAGES_OR_AUDIO) {
                selectedAudioUris.add(uri)
            }
        }
    )

    // 切换检测类型时清空服务端结果，避免上一类型结果残留；上传/输入文件保留便于切回继续用
    LaunchedEffect(pagerState.currentPage) {
        identificationViewModel.resetUiState()
        detectionState = DetectionState.IDLE
        detectionResult = null
    }

    LaunchedEffect(
        uiState.loading,
        uiState.error,
        uiState.fraudProbability,
        uiState.resultConfidence,
        uiState.riskLevel,
        uiState.reply,
        uiState.reason
    ) {
        when {
            uiState.loading -> {
                detectionState = DetectionState.DETECTING
                detectionResult = null
            }

            uiState.error != null -> {
                detectionState = DetectionState.RESULT
                detectionResult = DetectionResult(
                    riskLevel = "Medium",
                    title = "检测失败",
                    summary = uiState.error ?: "请求失败",
                    confidence = 0f,
                    probability = 0f
                )
            }

            uiState.reply.isNotBlank() || uiState.resultConfidence > 0f -> {
                detectionState = DetectionState.RESULT
                detectionResult = buildDetectionResult(
                    riskLevel = uiState.riskLevel,
                    reply = uiState.reply,
                    reason = uiState.reason,
                    confidence = uiState.resultConfidence.toFloat(),
                    probability = uiState.fraudProbability.toFloat()
                )
            }
        }
    }


    fun startDetection() {
        when (pagerState.currentPage) {
            0 -> {
                if (selectedImageUris.isNotEmpty()) {
                    identificationViewModel.analyzeImageListPreferLocalOcr(context, selectedImageUris.toList())
                }
            }
            1 -> {
                selectedVideoUri?.let {
                    identificationViewModel.analyzeFile(context, "video", it)
                }
            }
            2 -> {
                if (inputText.isNotBlank()) {
                    identificationViewModel.analyzeTextContent(inputText.trim())
                }
            }
            3 -> {
                if (selectedAudioUris.isNotEmpty()) {
                    identificationViewModel.analyzeAudioList(context, selectedAudioUris.toList())
                }
            }
        }
    }

    val canStartDetection = when (pagerState.currentPage) {
        0 -> selectedImageUris.isNotEmpty()
        1 -> selectedVideoUri != null
        2 -> inputText.isNotBlank()
        3 -> selectedAudioUris.isNotEmpty()
        else -> false
    }


    var riskRecords by remember { mutableStateOf<List<RiskRecord>>(emptyList()) }

    LaunchedEffect(Unit) {
        riskRecords = DetectionHistoryPreferences.load(context).map { it.toRiskRecord() }
    }

    LaunchedEffect(Unit) {
        var prevLoading = false
        identificationViewModel.uiState.collect { state ->
            val loading = state.loading
            if (prevLoading && !loading) {
                appendFinishedDetectionToHistory(
                    context = context,
                    state = state,
                    modalityLabel = tabs[pagerState.currentPage],
                )
                riskRecords = DetectionHistoryPreferences.load(context).map { it.toRiskRecord() }
            }
            prevLoading = loading
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        AppTopBar(
            title = "检测",
            onBack = onBack,
            actions = {
                TextButton(onClick = onReport) {
                    Text(
                        text = "一键举报",
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                // Flat card: no shadow/elevation in this module
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        PrimaryScrollableTabRow(
                            selectedTabIndex = pagerState.currentPage,
                            containerColor = Color(0xFFF5F5F5),
                            contentColor = MaterialTheme.colorScheme.primary,
                            edgePadding = 4.dp,
                            indicator = {},
                            divider = {},
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(24.dp))
                        ) {
                            tabs.forEachIndexed { index, type ->
                                val selected = pagerState.currentPage == index
                                Tab(
                                    selected = selected,
                                    onClick = {
                                        coroutineScope.launch { pagerState.animateScrollToPage(index) }
                                    },
                                    modifier = Modifier
                                        .padding(4.dp)
                                        .clip(RoundedCornerShape(20.dp))
                                        .background(if (selected) Color.White else Color.Transparent),
                                    text = {
                                        Text(
                                            text = type,
                                            color = if (selected) MaterialTheme.colorScheme.primary else Color.Gray,
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top
                        ) { page ->
                            AnimatedContent(
                                targetState = detectionState,
                                transitionSpec = {
                                    fadeIn(tween(400)) togetherWith fadeOut(tween(300))
                                },
                                label = "detection_state"
                            ) { state ->
                                @Suppress("UNUSED_EXPRESSION")
                                when (state) {
                                    DetectionState.IDLE -> {
                                        val (tabIcon, uploadLabel, desc) = when (page) {
                                            0 -> Triple(Icons.Filled.Image,   "上传图片", "上传可疑图片，AI将为您识别是否伪造或包含欺诈信息。")
                                            1 -> Triple(Icons.Filled.Videocam,"上传视频", "上传视频文件，深度检测是否存在AI换脸、声音合成等风险。")
                                            2 -> Triple(Icons.Filled.TextFields, null, "粘贴或输入可疑短信、话术等文本，识别诈骗诱导与钓鱼话术。")
                                            else -> Triple(Icons.Filled.Mic,  "上传录音", "上传录音文件，智能识别声音合成与情绪诱导等诈骗特征。")
                                        }
                                        // Colors aligned with Home -> QuickDetectionCard (one-to-one by page)
                                        val accentColor = when (page) {
                                            0 -> Color(0xFF9FB3DF) // 图像检测
                                            1 -> Color(0xFF9EC6F3) // 视频检测
                                            2 -> Color(0xFFB39DDB) // 文本检测
                                            else -> Color(0xFFFF8A65) // 语音检测
                                        }
                                        fun shade(c: Color, m: Float) = Color(
                                            red = (c.red * m).coerceIn(0f, 1f),
                                            green = (c.green * m).coerceIn(0f, 1f),
                                            blue = (c.blue * m).coerceIn(0f, 1f),
                                            alpha = 1f
                                        )
                                        val iconColor = accentColor
                                        val primaryButtonColor = shade(accentColor, 0.86f)
                                        val primaryButtonTextColor =
                                            if (primaryButtonColor.luminance() > 0.72f) Color(0xFF1F2937) else Color.White
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Icon(
                                                imageVector = tabIcon,
                                                contentDescription = null,
                                                tint = iconColor,
                                                modifier = Modifier.size(72.dp)
                                            )
                                            Spacer(modifier = Modifier.height(16.dp))
                                            Text(
                                                desc,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                textAlign = TextAlign.Center
                                            )
                                            Spacer(modifier = Modifier.height(20.dp))
                                            if (page == 2) {
                                                OutlinedTextField(
                                                    value = inputText,
                                                    onValueChange = { inputText = it },
                                                    modifier = Modifier.fillMaxWidth(),
                                                    placeholder = {
                                                        Text(
                                                            "粘贴或输入待检测文本…",
                                                            color = accentColor.copy(alpha = 0.55f)
                                                        )
                                                    },
                                                    shape = RoundedCornerShape(16.dp),
                                                    colors = OutlinedTextFieldDefaults.colors(
                                                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                                                        focusedBorderColor = accentColor,
                                                        unfocusedBorderColor = accentColor.copy(alpha = 0.5f),
                                                        cursorColor = accentColor,
                                                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                                                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                                                        focusedPlaceholderColor = accentColor.copy(alpha = 0.55f),
                                                        unfocusedPlaceholderColor = accentColor.copy(alpha = 0.5f),
                                                    ),
                                                )
                                                Spacer(modifier = Modifier.height(12.dp))
                                            }
                                            Column(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalArrangement = Arrangement.spacedBy(12.dp)
                                            ) {
                                                if (uploadLabel != null) {
                                                    when (page) {
                                                        0 -> {
                                                            val count = selectedImageUris.size
                                                            val atMax = count >= MAX_DETECTION_IMAGES_OR_AUDIO
                                                            val addButtonText = when {
                                                                count == 0 -> uploadLabel
                                                                !atMax -> "还可上传${MAX_DETECTION_IMAGES_OR_AUDIO - count}张图片"
                                                                else -> "最多可上传9张图片"
                                                            }
                                                            OutlinedButton(
                                                                onClick = { imagePicker.launch("image/*") },
                                                                enabled = !atMax,
                                                                modifier = Modifier.fillMaxWidth().height(50.dp),
                                                                shape = RoundedCornerShape(16.dp),
                                                                border = ButtonDefaults.outlinedButtonBorder.copy(
                                                                    brush = SolidColor(
                                                                        if (atMax) Color(0xFFBDBDBD)
                                                                        else accentColor.copy(alpha = 0.65f)
                                                                    )
                                                                ),
                                                                colors = ButtonDefaults.outlinedButtonColors(
                                                                    contentColor = shade(accentColor, 0.65f),
                                                                    disabledContentColor = Color(0xFF9E9E9E),
                                                                    disabledContainerColor = Color(0xFFEEEEEE),
                                                                )
                                                            ) {
                                                                Text(
                                                                    text = addButtonText,
                                                                    fontWeight = FontWeight.SemiBold
                                                                )
                                                            }
                                                            if (selectedImageUris.isNotEmpty()) {
                                                                Spacer(modifier = Modifier.height(8.dp))
                                                                DetectionMediaPreviewRow(
                                                                    uris = selectedImageUris.toList(),
                                                                    asImage = true,
                                                                    accentColor = accentColor,
                                                                    onRemove = { selectedImageUris.remove(it) },
                                                                )
                                                            }
                                                        }

                                                        1 -> {
                                                            val pickedUri = selectedVideoUri
                                                            val alreadyUploaded = pickedUri != null
                                                            OutlinedButton(
                                                                onClick = { videoPicker.launch("video/*") },
                                                                enabled = !alreadyUploaded,
                                                                modifier = Modifier.fillMaxWidth().height(50.dp),
                                                                shape = RoundedCornerShape(16.dp),
                                                                border = ButtonDefaults.outlinedButtonBorder.copy(
                                                                    brush = SolidColor(
                                                                        if (alreadyUploaded) Color(0xFFBDBDBD)
                                                                        else accentColor.copy(alpha = 0.65f)
                                                                    )
                                                                ),
                                                                colors = ButtonDefaults.outlinedButtonColors(
                                                                    contentColor = shade(accentColor, 0.65f),
                                                                    disabledContentColor = Color(0xFF9E9E9E),
                                                                    disabledContainerColor = Color(0xFFEEEEEE),
                                                                )
                                                            ) {
                                                                Text(
                                                                    text = if (alreadyUploaded) "已上传" else uploadLabel,
                                                                    fontWeight = FontWeight.SemiBold
                                                                )
                                                            }
                                                            if (pickedUri != null) {
                                                                Text(
                                                                    text = "已选择：${pickedUri.lastPathSegment ?: pickedUri.toString()}",
                                                                    style = MaterialTheme.typography.bodySmall,
                                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                                    modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                                                                )
                                                            }
                                                        }

                                                        3 -> {
                                                            val count = selectedAudioUris.size
                                                            val atMax = count >= MAX_DETECTION_IMAGES_OR_AUDIO
                                                            val addButtonText = when {
                                                                count == 0 -> uploadLabel
                                                                !atMax -> "还可上传${MAX_DETECTION_IMAGES_OR_AUDIO - count}段音频"
                                                                else -> "最多可上传9段音频"
                                                            }
                                                            OutlinedButton(
                                                                onClick = { audioPicker.launch("audio/*") },
                                                                enabled = !atMax,
                                                                modifier = Modifier.fillMaxWidth().height(50.dp),
                                                                shape = RoundedCornerShape(16.dp),
                                                                border = ButtonDefaults.outlinedButtonBorder.copy(
                                                                    brush = SolidColor(
                                                                        if (atMax) Color(0xFFBDBDBD)
                                                                        else accentColor.copy(alpha = 0.65f)
                                                                    )
                                                                ),
                                                                colors = ButtonDefaults.outlinedButtonColors(
                                                                    contentColor = shade(accentColor, 0.65f),
                                                                    disabledContentColor = Color(0xFF9E9E9E),
                                                                    disabledContainerColor = Color(0xFFEEEEEE),
                                                                )
                                                            ) {
                                                                Text(
                                                                    text = addButtonText,
                                                                    fontWeight = FontWeight.SemiBold
                                                                )
                                                            }
                                                            if (selectedAudioUris.isNotEmpty()) {
                                                                Spacer(modifier = Modifier.height(8.dp))
                                                                DetectionMediaPreviewRow(
                                                                    uris = selectedAudioUris.toList(),
                                                                    asImage = false,
                                                                    accentColor = accentColor,
                                                                    onRemove = { selectedAudioUris.remove(it) },
                                                                )
                                                            }
                                                        }

                                                        else -> {}
                                                    }
                                                }
                                                Button(
                                                    onClick = { startDetection() },
                                                    enabled = canStartDetection && !uiState.loading,
                                                    modifier = Modifier.fillMaxWidth().height(50.dp),
                                                    shape = RoundedCornerShape(16.dp),
                                                    colors = ButtonDefaults.buttonColors(
                                                        containerColor = primaryButtonColor,
                                                        contentColor = primaryButtonTextColor,
                                                    ),
                                                    elevation = ButtonDefaults.buttonElevation(
                                                        defaultElevation = 0.dp,
                                                        pressedElevation = 0.dp
                                                    )
                                                ) {
                                                    Text(
                                                        if (uiState.loading) "检测中..." else "开始检测",
                                                        fontSize = 16.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }

                                            }
                                        }
                                    }

                                    DetectionState.DETECTING -> {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 12.dp)
                                        ) {
                                            ScanningAnimation()
                                            Spacer(modifier = Modifier.height(20.dp))
                                        Text(
                                            "雷达扫描中，正在识别风险目标…",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        }
                                    }

                                    DetectionState.RESULT -> {
                                        detectionResult?.let { result ->
                                            val accentColor = when (page) {
                                                0 -> Color(0xFF9FB3DF)
                                                1 -> Color(0xFF9EC6F3)
                                                2 -> Color(0xFFB39DDB)
                                                else -> Color(0xFFFF8A65)
                                            }
                                            DetectionResultCard(
                                                result = result,
                                                accentColor = accentColor,
                                                onRedetect = {
                                                    detectionState = DetectionState.IDLE
                                                    detectionResult = null
                                                    identificationViewModel.resetUiState()
                                                    when (page) {
                                                        0 -> selectedImageUris.clear()
                                                        1 -> selectedVideoUri = null
                                                        3 -> selectedAudioUris.clear()
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item { SectionHeader("风险记录") }

            if (riskRecords.isEmpty()) {
                item {
                    Text(
                        text = "暂无检测记录。在上方完成图像/视频/文本/语音检测后，结果会自动保存在此列表。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            } else {
                items(riskRecords) { record -> RecordItem(record) }
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "没有更多记录了",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DetectionMediaPreviewRow(
    uris: List<Uri>,
    asImage: Boolean,
    accentColor: Color,
    onRemove: (Uri) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        itemsIndexed(
            items = uris,
            key = { index, uri -> "$index-$uri" },
        ) { _, uri ->
            Box(modifier = Modifier.size(width = 76.dp, height = 72.dp)) {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 4.dp, end = 4.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = scheme.surface,
                    tonalElevation = 1.dp,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (asImage) {
                            AsyncImage(
                                model = uri,
                                contentDescription = uri.lastPathSegment,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(6.dp)),
                                contentScale = ContentScale.Crop,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Filled.Mic,
                                contentDescription = uri.lastPathSegment,
                                tint = accentColor,
                                modifier = Modifier.size(36.dp),
                            )
                        }
                    }
                }
                IconButton(
                    onClick = { onRemove(uri) },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(26.dp)
                        .offset(x = 4.dp, y = (-2).dp),
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = scheme.errorContainer,
                        contentColor = scheme.onErrorContainer,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "移除",
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}

// Radar blip: fixed position on the radar, fades in when swept and then decays
private data class RadarBlip(
    val angleDeg: Float,   // angle from 12-o'clock, clockwise (degrees)
    val distFrac: Float    // distance from center, fraction of max radius
)

@Composable
private fun ScanningAnimation() {
    val sweepPeriodMs = 2400

    // Sweep line: -90° = 12 o'clock, sweeps clockwise to 270° = one full turn
    val tr = rememberInfiniteTransition(label = "radar")
    val sweepAngle by tr.animateFloat(
        initialValue = -90f,
        targetValue  = 270f,
        animationSpec = infiniteRepeatable(tween(sweepPeriodMs, easing = LinearEasing)),
        label = "sweep"
    )

    // Two blips at fixed radar positions
    val blips = remember {
        listOf(
            RadarBlip(angleDeg = 78f,  distFrac = 0.52f),
            RadarBlip(angleDeg = 215f, distFrac = 0.64f)
        )
    }
    val blipAlphas = remember { blips.map { Animatable(0f) } }

    // Drive blip appearances: when sweep reaches each blip's angle, flash it then fade
    LaunchedEffect(Unit) {
        while (true) {
            blips.forEachIndexed { i, blip ->
                val delayMs = (blip.angleDeg / 360f * sweepPeriodMs).toLong()
                if (i == 0) delay(delayMs)
                else {
                    val prevDelay = (blips[i - 1].angleDeg / 360f * sweepPeriodMs).toLong()
                    delay(delayMs - prevDelay)
                }
                launch {
                    blipAlphas[i].snapTo(1f)
                    blipAlphas[i].animateTo(0f, tween(2400, easing = FastOutSlowInEasing))
                }
            }
            // Wait remainder of cycle
            val lastDelay = (blips.last().angleDeg / 360f * sweepPeriodMs).toLong()
            delay(sweepPeriodMs - lastDelay)
        }
    }

    val primary = MaterialTheme.colorScheme.primary

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(192.dp)) {
        Canvas(modifier = Modifier.size(192.dp)) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val maxR = size.minDimension / 2f

            // ── Background filled circle (scope / screen) ──
            drawCircle(color = primary.copy(alpha = 0.05f), radius = maxR, center = Offset(cx, cy))

            // ── Concentric grid rings ──
            listOf(0.34f, 0.67f, 1.0f).forEach { f ->
                drawCircle(
                    color = primary.copy(alpha = 0.12f),
                    radius = maxR * f,
                    center = Offset(cx, cy),
                    style = Stroke(width = 1.dp.toPx())
                )
            }

            // ── Crosshair ──
            val hairAlpha = primary.copy(alpha = 0.10f)
            drawLine(hairAlpha, Offset(cx - maxR, cy), Offset(cx + maxR, cy), 1.dp.toPx())
            drawLine(hairAlpha, Offset(cx, cy - maxR), Offset(cx, cy + maxR), 1.dp.toPx())

            // ── Sweep wake: smooth gradient via 48 thin arc slices ──
            val wakeSpan = 82f
            val steps    = 48
            val wakeSize   = Size(maxR * 2, maxR * 2)
            val wakeOrigin = Offset(cx - maxR, cy - maxR)
            repeat(steps) { i ->
                // t goes 0 (back) → 1 (leading edge), quadratic so it brightens sharply near head
                val t = (i + 1).toFloat() / steps
                drawArc(
                    color = primary.copy(alpha = t * t * 0.32f),
                    startAngle = sweepAngle - wakeSpan + i.toFloat() / steps * wakeSpan,
                    sweepAngle = wakeSpan / steps,
                    useCenter = true,
                    topLeft = wakeOrigin,
                    size = wakeSize
                )
            }

            // ── Sweep leading line (center → edge) ──
            val rad = Math.toRadians(sweepAngle.toDouble())
            drawLine(
                color = primary.copy(alpha = 0.90f),
                start = Offset(cx, cy),
                end   = Offset(cx + maxR * cos(rad).toFloat(), cy + maxR * sin(rad).toFloat()),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round
            )

            // ── Radar blips ──
            blips.forEachIndexed { i, blip ->
                val a = blipAlphas[i].value
                if (a <= 0f) return@forEachIndexed
                val bRad = Math.toRadians((-90.0 + blip.angleDeg))
                val bx = cx + maxR * blip.distFrac * cos(bRad).toFloat()
                val by = cy + maxR * blip.distFrac * sin(bRad).toFloat()
                // Outer glow ring
                drawCircle(primary.copy(alpha = a * 0.20f), 13.dp.toPx(), Offset(bx, by))
                // Mid ring
                drawCircle(primary.copy(alpha = a * 0.45f),  7.dp.toPx(), Offset(bx, by))
                // Core dot
                drawCircle(primary.copy(alpha = a * 0.95f),  3.5f.dp.toPx(), Offset(bx, by))
            }

            // ── Center origin dot ──
            drawCircle(primary.copy(alpha = 0.80f), 3.dp.toPx(), Offset(cx, cy))
        }
    }
}

/** 依据 [probability]（0~1）绘制的风险概率环形图，与检测结果卡片风格一致 */
@Composable
private fun RiskProbabilityRingChart(
    probability: Float,
    accentColor: Color,
    modifier: Modifier = Modifier,
) {
    val target = probability.coerceIn(0f, 1f)
    val animated by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(1000, easing = FastOutSlowInEasing),
        label = "risk_probability_ring",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(132.dp)) {
            val stroke = 14.dp.toPx()
            val diameter = size.minDimension - stroke
            val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
            val arcSize = Size(diameter, diameter)
            drawArc(
                color = accentColor.copy(alpha = 0.14f),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            drawArc(
                color = accentColor,
                startAngle = -90f,
                sweepAngle = 360f * animated,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "${(animated * 100).toInt()}%",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = accentColor,
            )
            Text(
                text = "风险概率",
                style = MaterialTheme.typography.labelSmall,
                color = accentColor.copy(alpha = 0.68f),
            )
        }
    }
}

@Composable
private fun DetectionResultCard(
    result: DetectionResult,
    accentColor: Color,
    onRedetect: () -> Unit
) {
    val (bgColor, textColor, icon) = when (result.riskLevel) {
        "High" -> Triple(Color(0xFFFFEBEE), Color(0xFFD32F2F), Icons.Default.Warning)
        "Medium" -> Triple(Color(0xFFFFF8E1), Color(0xFFF57F17), Icons.Default.Warning)
        else -> Triple(Color(0xFFE8F5E9), Color(0xFF2E7D32), Icons.Default.CheckCircle)
    }

    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(tween(400, easing = FastOutSlowInEasing)) { it / 2 } + fadeIn(tween(400)),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(bgColor)
                    .padding(16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(icon, contentDescription = null, tint = textColor, modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(result.title, style = MaterialTheme.typography.titleMedium, color = textColor, fontWeight = FontWeight.Bold)
                    }
                    Text(result.summary, style = MaterialTheme.typography.bodySmall, color = textColor.copy(alpha = 0.85f))
                    RiskProbabilityRingChart(probability = result.probability, accentColor = textColor)
                }
            }
            OutlinedButton(
                onClick = onRedetect,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(16.dp),
                border = ButtonDefaults.outlinedButtonBorder.copy(
                    brush = SolidColor(accentColor.copy(alpha = 0.65f))
                ),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = Color(
                        red = (accentColor.red * 0.65f).coerceIn(0f, 1f),
                        green = (accentColor.green * 0.65f).coerceIn(0f, 1f),
                        blue = (accentColor.blue * 0.65f).coerceIn(0f, 1f),
                        alpha = 1f
                    )
                )
            ) {
                Text("重新检测", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
fun RecordItem(record: RiskRecord) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(record.iconColor.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    record.icon,
                    contentDescription = null,
                    tint = record.iconColor,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    record.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "${record.date} | ${record.description}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            RiskBadge(record.type)
        }
    }
}

@Composable
fun RiskBadge(type: String) {
    val (text, color) = when (type) {
        "High" -> "高风险" to MaterialTheme.colorScheme.error
        "Medium" -> "中风险" to MaterialTheme.colorScheme.tertiary
        "Safe" -> "安全" to Color(0xFF4CAF50) // Custom Green for Safe
        "Error" -> "失败" to Color(0xFFE65100)
        else -> "未知" to MaterialTheme.colorScheme.outline
    }

    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.1f), shape = RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Bold
        )
    }
}

// Data Models & Mock Data

data class RiskRecord(
    val title: String,
    val date: String,
    val type: String, // "High", "Medium", "Safe", "Error"
    val description: String,
    val icon: ImageVector,
    val iconColor: Color
)

private fun appendFinishedDetectionToHistory(
    context: Context,
    state: IdentificationUiState,
    modalityLabel: String,
) {
    when {
        state.error != null -> {
            DetectionHistoryPreferences.prepend(
                context,
                DetectionHistoryEntry(
                    timestampMillis = System.currentTimeMillis(),
                    modalityLabel = modalityLabel,
                    riskLevel = "Error",
                    title = "检测失败",
                    summary = state.error,
                    probability = 0f,
                    confidence = 0f,
                ),
            )
        }

        state.reply.isNotBlank() || state.resultConfidence > 0.0 -> {
            val built = buildDetectionResult(
                riskLevel = state.riskLevel,
                reply = state.reply,
                reason = state.reason,
                confidence = state.resultConfidence.toFloat(),
                probability = state.fraudProbability.toFloat(),
            )
            DetectionHistoryPreferences.prepend(
                context,
                DetectionHistoryEntry(
                    timestampMillis = System.currentTimeMillis(),
                    modalityLabel = modalityLabel,
                    riskLevel = built.riskLevel,
                    title = built.title,
                    summary = built.summary,
                    probability = built.probability,
                    confidence = built.confidence,
                ),
            )
        }
    }
}

private fun DetectionHistoryEntry.toRiskRecord(): RiskRecord {
    val icon = when (modalityLabel) {
        "图像检测" -> Icons.Filled.Image
        "视频检测" -> Icons.Filled.Videocam
        "文本检测" -> Icons.Filled.TextFields
        "语音检测" -> Icons.Filled.Mic
        else -> Icons.Filled.Warning
    }
    val iconColor = when (modalityLabel) {
        "图像检测" -> Color(0xFF9FB3DF)
        "视频检测" -> Color(0xFF9EC6F3)
        "文本检测" -> Color(0xFFB39DDB)
        "语音检测" -> Color(0xFFFF8A65)
        else -> Color(0xFF78909C)
    }
    val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestampMillis))
    val clipped = if (summary.length <= 80) summary else summary.take(80) + "…"
    return RiskRecord(
        title = title,
        date = dateStr,
        type = riskLevel,
        description = "$modalityLabel · $clipped",
        icon = icon,
        iconColor = iconColor,
    )
}
