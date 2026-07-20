package com.magicvvu.fanzha.ui.screens

import android.Manifest
import android.content.Context
import android.provider.Settings
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import kotlin.math.abs
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.magicvvu.fanzha.ui.components.UserProfileAvatar
import com.magicvvu.fanzha.data.local.AuthPreferences
import com.magicvvu.fanzha.ui.components.AppTopBar
import com.magicvvu.fanzha.ui.theme.AppFontSizeOption
import com.magicvvu.fanzha.ui.theme.LocalElderlyModePreferenceController
import com.magicvvu.fanzha.ui.theme.LocalFontSizePreferenceController
import com.magicvvu.fanzha.ui.theme.LocalThemePreferenceController
import com.magicvvu.fanzha.ui.viewmodels.BrowsingHistoryRepository
import com.magicvvu.fanzha.ui.viewmodels.FavoritesRepository
import com.magicvvu.fanzha.ui.viewmodels.SharedCase
import com.magicvvu.fanzha.data.remote.OccupationOptionDto
import com.magicvvu.fanzha.ui.viewmodels.ProfileViewModel
import com.google.gson.Gson
import kotlinx.coroutines.launch
import com.google.gson.reflect.TypeToken

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimpleSubScreen(
    title: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            content()
        }
    }
}

// 1. 修改个人信息：与「个人信息」同一套逻辑（users.occupation_id ↔ occupation / occupation_category）
@Composable
fun EditProfileScreen(
    sessionUserId: Long?,
    onBack: () -> Unit,
) {
    PersonalInfoScreen(sessionUserId = sessionUserId, onBack = onBack)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonalInfoScreen(
    /** 与 [AuthPreferences] 一致；重组时随登录写入变化，用于触发拉库。 */
    sessionUserId: Long?,
    onBack: () -> Unit,
    viewModel: ProfileViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
) {
    val user = viewModel.currentUser
    val pickAvatar = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.toString()?.let { viewModel.updateAvatar(it) }
    }
    var editingField by remember { mutableStateOf<EditField?>(null) }

    LaunchedEffect(sessionUserId) {
        if (sessionUserId != null) {
            viewModel.refreshProfileAndOccupations(sessionUserId)
        }
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = "个人信息",
                onBack = onBack,
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            viewModel.profileRemoteHint?.let { hint ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
                    ),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = hint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { viewModel.clearProfileRemoteHint() }) {
                            Text("关闭", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
            if (sessionUserId == null) {
                Text(
                    text = "未登录：资料不会与服务器同步。登录后进入本页将自动拉取数据库中的个人信息。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            if (editingField != null) {
                EditFieldDialog(
                    field = editingField!!,
                    user = user,
                    occupationOptions = viewModel.occupationOptions,
                    onDismiss = { editingField = null },
                    onSaveName = { viewModel.updateName(it) },
                    onSaveAge = { viewModel.updateAge(it) },
                    onSaveGender = { viewModel.updateGender(it) },
                    onSaveOccupationId = { viewModel.updateOccupationById(it) },
                )
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        UserProfileAvatar(
                            user = user,
                            size = 56.dp,
                            textStyle = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { pickAvatar.launch("image/*") }
                                .padding(horizontal = 6.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(28.dp),
                            )
                        }
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    InfoRow(
                        label = "用户名",
                        value = user.name,
                        showArrow = true,
                        onClick = { editingField = EditField.Name }
                    )
                    Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f))
                    InfoRow(
                        label = "年龄",
                        value = "${user.age}岁",
                        showArrow = true,
                        onClick = { editingField = EditField.Age }
                    )
                    Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f))
                    InfoRow(
                        label = "性别",
                        value = user.gender.ifBlank { "-" },
                        showArrow = true,
                        onClick = { editingField = EditField.Gender }
                    )
                    Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f))
                    InfoRow(
                        label = "职业",
                        value = user.occupation.ifBlank { "-" },
                        showArrow = true,
                        onClick = { editingField = EditField.Occupation }
                    )
                }
            }

            Text(
                text = "我们会根据您的个人信息动态调整防护强度，保障您的信息安全和使用舒适性~",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, start = 4.dp, end = 4.dp),
            )
        }
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    showArrow: Boolean = false,
    onClick: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = showArrow, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(80.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
        if (showArrow) {
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

private enum class EditField { Name, Age, Gender, Occupation }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EditFieldDialog(
    field: EditField,
    user: com.magicvvu.fanzha.ui.viewmodels.UserProfile,
    occupationOptions: List<OccupationOptionDto>,
    onDismiss: () -> Unit,
    onSaveName: (String) -> Unit,
    onSaveAge: (Int) -> Unit,
    onSaveGender: (String) -> Unit,
    onSaveOccupationId: (Int) -> Unit,
) {
    when (field) {
        EditField.Name -> {
            var v by remember(user.name) { mutableStateOf(user.name) }
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("修改用户名", fontWeight = FontWeight.Bold) },
                containerColor = MaterialTheme.colorScheme.surface,
                text = {
                    OutlinedTextField(
                        value = v,
                        onValueChange = { v = it },
                        label = { Text("用户名") },
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val nv = v.trim()
                            if (nv.isNotEmpty()) onSaveName(nv)
                            onDismiss()
                        }
                    ) { Text("保存") }
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
                shape = RoundedCornerShape(20.dp),
            )
        }

        EditField.Age -> {
            val ageRange = remember { (0..120).toList() }
            val itemHeight = 48.dp
            val visibleRows = 5
            val pickerHeight = itemHeight * visibleRows
            val targetAge = user.age.coerceIn(0, 120)
            val listState = rememberLazyListState(initialFirstVisibleItemIndex = targetAge)
            val flingBehavior = rememberSnapFlingBehavior(lazyListState = listState)
            val surface = MaterialTheme.colorScheme.surface

            val selectedAge by remember(user.age) {
                derivedStateOf {
                    val li = listState.layoutInfo
                    val infos = li.visibleItemsInfo
                    if (infos.isEmpty()) return@derivedStateOf targetAge
                    val mid = (li.viewportStartOffset + li.viewportEndOffset) / 2
                    val best = infos.minByOrNull { info ->
                        abs(info.offset + info.size / 2 - mid)
                    }
                    best?.index?.coerceIn(0, 120) ?: targetAge
                }
            }

            // 打开时直接对齐当前年龄，不再从 0 岁滚入
            LaunchedEffect(targetAge) {
                listState.scrollToItem(targetAge)
            }

            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("修改年龄", fontWeight = FontWeight.Bold) },
                containerColor = surface,
                text = {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(pickerHeight),
                    ) {
                        LazyColumn(
                            state = listState,
                            flingBehavior = flingBehavior,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(vertical = itemHeight * 2),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            items(
                                count = ageRange.size,
                                key = { ageRange[it] },
                            ) { idx ->
                                val age = ageRange[idx]
                                val dist = abs(idx - selectedAge).coerceAtMost(8)
                                val scale = (1.06f - dist * 0.06f).coerceIn(0.78f, 1.06f)
                                val alpha = (1f - dist * 0.12f).coerceIn(0.35f, 1f)
                                Box(
                                    modifier = Modifier
                                        .height(itemHeight)
                                        .fillMaxWidth()
                                        .graphicsLayer {
                                            scaleX = scale
                                            scaleY = scale
                                            this.alpha = alpha
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = age.toString(),
                                        style = if (idx == selectedAge) {
                                            MaterialTheme.typography.headlineSmall
                                        } else {
                                            MaterialTheme.typography.titleMedium
                                        },
                                        fontWeight = if (idx == selectedAge) FontWeight.Bold else FontWeight.Medium,
                                        color = if (idx == selectedAge) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
                                        },
                                        textAlign = TextAlign.Center,
                                    )
                                }
                            }
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.9f)
                                .height(itemHeight)
                                .align(Alignment.Center)
                                .border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                                    shape = RoundedCornerShape(12.dp),
                                ),
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(itemHeight + 16.dp)
                                .align(Alignment.TopCenter)
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(surface, surface.copy(alpha = 0f)),
                                    ),
                                ),
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(itemHeight + 16.dp)
                                .align(Alignment.BottomCenter)
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(Color.Transparent, surface),
                                    ),
                                ),
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onSaveAge(selectedAge)
                            onDismiss()
                        },
                    ) { Text("保存") }
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
                shape = RoundedCornerShape(20.dp),
            )
        }

        EditField.Gender -> {
            val options = listOf("男", "女", "未知")
            var selected by remember(user.gender) {
                mutableStateOf(user.gender.takeIf { it in options } ?: "未知")
            }

            AlertDialog(
                onDismissRequest = onDismiss,
                title = {
                    Text(
                        "修改性别",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                containerColor = MaterialTheme.colorScheme.surface,
                text = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        options.forEach { opt ->
                            val isSel = selected == opt
                            Surface(
                                onClick = { selected = opt },
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSel) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                },
                                border = BorderStroke(
                                    width = if (isSel) 2.dp else 1.dp,
                                    color = if (isSel) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                                    },
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(52.dp),
                            ) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = opt,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Medium,
                                        color = if (isSel) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f)
                                        },
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onSaveGender(selected)
                            onDismiss()
                        },
                    ) { Text("保存") }
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
                shape = RoundedCornerShape(20.dp),
            )
        }

        EditField.Occupation -> {
            var selectedId by remember(user.occupationId, occupationOptions) {
                val pref = user.occupationId
                val matchPref = pref != null && occupationOptions.any { it.id == pref }
                val initial = when {
                    matchPref -> pref!!
                    occupationOptions.isNotEmpty() -> occupationOptions.first().id
                    else -> -1
                }
                mutableStateOf(initial)
            }
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("修改职业", fontWeight = FontWeight.Bold) },
                containerColor = MaterialTheme.colorScheme.surface,
                text = {
                    if (occupationOptions.isEmpty()) {
                        Text(
                            text = "职业列表加载中或网络异常，请稍后重试。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 420.dp),
                        ) {
                            items(
                                items = occupationOptions,
                                key = { it.id },
                            ) { opt ->
                                val isSel = selectedId == opt.id
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(52.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(
                                            if (isSel) {
                                                MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                            } else {
                                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
                                            },
                                        )
                                        .clickable { selectedId = opt.id },
                                ) {
                                    if (isSel) {
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .padding(4.dp)
                                                .size(18.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.primary),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = null,
                                                tint = Color.White,
                                                modifier = Modifier.size(12.dp),
                                            )
                                        }
                                    }
                                    Text(
                                        text = opt.occupationName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier
                                            .align(Alignment.Center)
                                            .padding(horizontal = 6.dp),
                                        textAlign = TextAlign.Center,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            if (selectedId > 0) {
                                onSaveOccupationId(selectedId)
                            }
                            onDismiss()
                        },
                    ) { Text("保存") }
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
                shape = RoundedCornerShape(20.dp),
            )
        }
    }
}

// 2. 我的收藏页面
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(onBack: () -> Unit) {
    val favorites = FavoritesRepository.favorites
    var selectedCase by remember { mutableStateOf<SharedCase?>(null) }

    if (selectedCase != null) {
        SharedCaseDetailScreen(
            case = selectedCase!!,
            onBack = { selectedCase = null }
        )
        return
    }

    Scaffold(
        topBar = { AppTopBar(title = "我的收藏", onBack = onBack) },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        if (favorites.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Bookmark,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "暂无收藏内容",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "在学习页面阅读文章时，点击收藏按钮即可保存",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(favorites) { case ->
                    SharedCaseSummaryCard(
                        case = case,
                        onClick = { selectedCase = case },
                        markerIcon = Icons.Default.Bookmark,
                        markerTint = Color(0xFFF59E0B)
                    )
                }
            }
        }
    }
}

@Composable
private fun SharedCaseSummaryCard(
    case: SharedCase,
    onClick: () -> Unit,
    markerIcon: ImageVector,
    markerTint: Color,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = case.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Icon(
                    imageVector = markerIcon,
                    contentDescription = null,
                    tint = markerTint,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = case.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = case.author.take(1),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = case.author,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = case.date,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

// 3. 浏览记录页面
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(onBack: () -> Unit) {
    val history = BrowsingHistoryRepository.history
    var selectedCase by remember { mutableStateOf<SharedCase?>(null) }

    if (selectedCase != null) {
        SharedCaseDetailScreen(
            case = selectedCase!!,
            onBack = { selectedCase = null }
        )
        return
    }

    Scaffold(
        topBar = { AppTopBar(title = "浏览记录", onBack = onBack) },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        if (history.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "暂无浏览记录",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "在学习页「诈骗案例分享」中打开文章后，将自动显示在这里",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(history) { case ->
                    SharedCaseSummaryCard(
                        case = case,
                        onClick = { selectedCase = case },
                        markerIcon = Icons.Default.History,
                        markerTint = Color(0xFF3B82F6)
                    )
                }
            }
        }
    }
}

// 4. 消息中心页面
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = { AppTopBar(title = "消息中心", onBack = onBack) },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.Notifications,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.outline
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "暂无新消息",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "安全提醒与系统通知将在此显示",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

// 5. 实名认证页面
@Composable
fun VerificationScreen(onBack: () -> Unit) {
    var idFrontUri by remember { mutableStateOf<String?>(null) }
    var idBackUri by remember { mutableStateOf<String?>(null) }

    val pickIdFront = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        idFrontUri = uri?.toString()
    }
    val pickIdBack = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        idBackUri = uri?.toString()
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = "实名认证",
                onBack = onBack,
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "请上传身份证正反面照片以完成认证",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            IdImageUploadCard(
                label = "身份证正面",
                imageUri = idFrontUri,
                onPick = { pickIdFront.launch("image/*") },
            )

            IdImageUploadCard(
                label = "身份证反面",
                imageUri = idBackUri,
                onPick = { pickIdBack.launch("image/*") },
            )
        }
    }
}

@Composable
private fun IdImageUploadCard(
    label: String,
    imageUri: String?,
    onPick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPick() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )

            if (!imageUri.isNullOrBlank()) {
                AsyncImage(
                    model = imageUri,
                    contentDescription = label,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(12.dp)),
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "点击上传",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// 6. 安全守护中心页面
@Composable
fun SecurityCenterScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val callLogOk = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) ==
        PackageManager.PERMISSION_GRANTED
    val smsOk = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
        PackageManager.PERMISSION_GRANTED
    SimpleSubScreen(title = "安全守护中心", onBack = onBack) {
        Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(64.dp).padding(bottom = 16.dp), tint = MaterialTheme.colorScheme.primary)
        Text("您的账号当前处于安全状态", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(20.dp))
        Text("本机数据采集权限（用于比对诈骗特征库并写入拦截记录）", style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "通话记录：${if (callLogOk) "已授权" else "未授权（无法读取最近来电号码）"}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "短信：${if (smsOk) "已授权" else "未授权"}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            "重要：只有对方号码在服务端特征表 fraud_phone 中命中时，才会写入存储表 intercept_call；" +
                "普通来电不会入库，这是当前产品设计。测试时请先在库里加入该号码。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "流程：读取最近通话 → 回到本应用后自动同步（约每 5 秒最多一次）→ 服务端比对 → 命中则入库。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

// ── 通用设置内部组件 ──────────────────────────────────────────────────

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp, top = 4.dp)
    )
}

@Composable
private fun SettingRow(
    icon: ImageVector? = null,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    label: String,
    value: String = "",
    showArrow: Boolean = true,
    trailingContent: @Composable (() -> Unit)? = null,
    onClick: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = showArrow || trailingContent == null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(14.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        if (trailingContent != null) {
            trailingContent()
        } else {
            if (value.isNotEmpty()) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            if (showArrow) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun SettingCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        content = content
    )
}

@Composable
private fun SettingDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 16.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    )
}

// ── 修改密码对话框 ─────────────────────────────────────────────────────

@Composable
private fun ChangePasswordDialog(onDismiss: () -> Unit) {
    var oldPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("修改密码", fontWeight = FontWeight.Bold) },
        containerColor = MaterialTheme.colorScheme.surface,
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = oldPassword,
                    onValueChange = { oldPassword = it },
                    label = { Text("当前密码") },
                    visualTransformation = PasswordVisualTransformation(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = newPassword,
                    onValueChange = { newPassword = it },
                    label = { Text("新密码") },
                    visualTransformation = PasswordVisualTransformation(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = { confirmPassword = it },
                    label = { Text("确认新密码") },
                    visualTransformation = PasswordVisualTransformation(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(10.dp),
                elevation = ButtonDefaults.buttonElevation(0.dp)
            ) { Text("确认修改") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        shape = RoundedCornerShape(20.dp)
    )
}

// ── 账号绑定对话框 ─────────────────────────────────────────────────────

@Composable
private fun BindAccountDialog(title: String, placeholder: String, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        containerColor = MaterialTheme.colorScheme.surface,
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                placeholder = { Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)) },
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                ),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(10.dp),
                elevation = ButtonDefaults.buttonElevation(0.dp)
            ) { Text("绑定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        shape = RoundedCornerShape(20.dp)
    )
}

@Composable
private fun FontSizePickerDialog(
    current: AppFontSizeOption,
    onDismiss: () -> Unit,
    onApply: (AppFontSizeOption) -> Unit,
) {
    var selected by remember(current) { mutableStateOf(current) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("字体大小", fontWeight = FontWeight.Bold) },
        containerColor = MaterialTheme.colorScheme.surface,
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AppFontSizeOption.entries.forEach { opt ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { selected = opt }
                            .padding(horizontal = 4.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selected == opt,
                            onClick = { selected = opt },
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(opt.labelZh, style = MaterialTheme.typography.bodyLarge)
                            val hint = when (opt) {
                                AppFontSizeOption.Small -> "略小于系统默认"
                                AppFontSizeOption.Standard -> "跟随系统显示设置"
                                AppFontSizeOption.Large -> "应用内整体放大一档"
                                AppFontSizeOption.ExtraLarge -> "应用内整体放大两档"
                            }
                            Text(
                                hint,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onApply(selected)
                    onDismiss()
                },
                shape = RoundedCornerShape(10.dp),
                elevation = ButtonDefaults.buttonElevation(0.dp),
            ) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        shape = RoundedCornerShape(20.dp),
    )
}

// 7. 通用设置页面
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val themeController = LocalThemePreferenceController.current
    val fontSizeController = LocalFontSizePreferenceController.current
    var showDeviceBinding by remember { mutableStateOf(false) }
    var notifications by remember { mutableStateOf(true) }
    var showPasswordDialog by remember { mutableStateOf(false) }
    var showPhoneDialog by remember { mutableStateOf(false) }
    var showEmailDialog by remember { mutableStateOf(false) }
    var showFontSizeDialog by remember { mutableStateOf(false) }
    var cacheSizeLabel by remember { mutableStateOf("12.3 MB") }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    if (showDeviceBinding) {
        DeviceBindingScreen(onBack = { showDeviceBinding = false })
        return
    }

    if (showPasswordDialog) ChangePasswordDialog { showPasswordDialog = false }
    if (showPhoneDialog) BindAccountDialog("绑定手机号", "请输入手机号") { showPhoneDialog = false }
    if (showEmailDialog) BindAccountDialog("绑定微信", "请输入微信号") { showEmailDialog = false }
    if (showFontSizeDialog) {
        FontSizePickerDialog(
            current = fontSizeController.option,
            onDismiss = { showFontSizeDialog = false },
            onApply = { fontSizeController.setOption(it) },
        )
    }

    Scaffold(
        topBar = { AppTopBar(title = "通用设置", onBack = onBack) },
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            // ── 账号安全 ──────────────────────────────────────────
            item {
                SectionLabel("账号安全")
                SettingCard {
                    SettingRow(
                        label = "修改密码",
                        onClick = { showPasswordDialog = true }
                    )
                    SettingDivider()
                    SettingRow(
                        label = "手机绑定",
                        value = "138****8888",
                        onClick = { showPhoneDialog = true }
                    )
                    SettingDivider()
                    SettingRow(
                        label = "微信绑定",
                        value = "未绑定",
                        onClick = { showEmailDialog = true }
                    )
                    SettingDivider()
                    SettingRow(
                        label = "QQ绑定",
                        value = "未绑定"
                    )
                    SettingDivider()
                    SettingRow(
                        label = "设备绑定",
                        value = "查看",
                        onClick = { showDeviceBinding = true }
                    )
                }
            }

            // ── 偏好设置 ──────────────────────────────────────────
            item {
                SectionLabel("偏好设置")
                SettingCard {
                    SettingRow(
                        label = "夜间模式",
                        showArrow = false,
                        trailingContent = {
                            CompositionLocalProvider(LocalIndication provides NoRippleIndication) {
                                val isrc = remember { MutableInteractionSource() }
                                Switch(
                                    checked = themeController.isDarkTheme,
                                    onCheckedChange = { themeController.setDarkTheme(it) },
                                    interactionSource = isrc,
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.White,
                                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                                        uncheckedThumbColor = MaterialTheme.colorScheme.surface,
                                        uncheckedTrackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.38f),
                                        uncheckedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.70f),
                                    )
                                )
                            }
                        }
                    )
                    SettingDivider()
                    SettingRow(
                        label = "消息通知",
                        showArrow = false,
                        trailingContent = {
                            CompositionLocalProvider(LocalIndication provides NoRippleIndication) {
                                val isrc = remember { MutableInteractionSource() }
                                Switch(
                                    checked = notifications,
                                    onCheckedChange = { notifications = it },
                                    interactionSource = isrc,
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.White,
                                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                                        uncheckedThumbColor = MaterialTheme.colorScheme.surface,
                                        uncheckedTrackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.38f),
                                        uncheckedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.70f),
                                    )
                                )
                            }
                        }
                    )
                    SettingDivider()
                    SettingRow(
                        label = "字体大小",
                        value = fontSizeController.option.labelZh,
                        onClick = { showFontSizeDialog = true },
                    )
                }
            }

            // ── 存储与缓存 ────────────────────────────────────────
            item {
                SectionLabel("存储与缓存")
                SettingCard {
                    SettingRow(
                        label = "清除缓存",
                        value = cacheSizeLabel,
                        onClick = {
                            cacheSizeLabel = "0 MB"
                            scope.launch {
                                snackbarHostState.showSnackbar("缓存已清理")
                            }
                        },
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(8.dp)) }
        }
    }
}

// 老年人模式（服务管理入口）：全应用大字号 + 高对比度主题
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ElderlyModeScreen(onBack: () -> Unit) {
    val elderlyController = LocalElderlyModePreferenceController.current

    Scaffold(
        topBar = { AppTopBar(title = "老年人模式", onBack = onBack) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
        ) {
            item {
                Text(
                    text = "开启后将放大全应用界面文字，并采用高对比度配色，便于阅读与区分重要信息。关闭后恢复默认显示。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 4.dp),
                )
            }
            item {
                SettingCard {
                    SettingRow(
                        label = "启用老年人模式",
                        showArrow = false,
                        trailingContent = {
                            CompositionLocalProvider(LocalIndication provides NoRippleIndication) {
                                val isrc = remember { MutableInteractionSource() }
                                Switch(
                                    checked = elderlyController.isElderlyModeEnabled,
                                    onCheckedChange = { elderlyController.setElderlyModeEnabled(it) },
                                    interactionSource = isrc,
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.White,
                                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                                        uncheckedThumbColor = MaterialTheme.colorScheme.surface,
                                        uncheckedTrackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.38f),
                                        uncheckedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.70f),
                                    ),
                                )
                            }
                        },
                    )
                }
            }
            item {
                Text(
                    text = "提示：若已开启系统「显示大小/字体大小」，本模式会在其基础上进一步放大应用内文字。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }
}

// 8. 关于我们页面（布局与「浏览记录」一致：AppTopBar + LazyColumn + 圆角卡片列表）
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutUsScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = { AppTopBar(title = "关于我们", onBack = onBack) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                AboutUsSummaryCard(
                    title = "AI反诈助手",
                    summary = "致力于为您和家人的网络安全保驾护航",
                    versionLabel = "v1.0.0",
                )
            }
        }
    }
}

/** 与 [SharedCaseSummaryCard] 相同的卡片节奏：标题行 + 摘要 + 底栏说明。 */
@Composable
private fun AboutUsSummaryCard(
    title: String,
    summary: String,
    versionLabel: String,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = title.take(1).ifEmpty { "·" },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "移动客户端",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = versionLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

@Composable
private fun DeviceBindingScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val userId = remember { AuthPreferences.getUserId(context) }
    val repo = remember { DeviceBindingRepo(context) }
    val current = remember { repo.currentDevice() }

    var devices by remember { mutableStateOf(emptyList<LoggedDevice>()) }
    var pendingRemove by remember { mutableStateOf<LoggedDevice?>(null) }

    LaunchedEffect(userId) {
        if (userId == null) return@LaunchedEffect
        repo.upsertLoginDevice(userId, current)
        devices = repo.listDevices(userId)
    }

    if (pendingRemove != null) {
        val target = pendingRemove!!
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text("移除设备", fontWeight = FontWeight.Bold) },
            text = {
                Text("移除后，该设备将需要重新登录才可继续使用。确定移除「${target.model}」吗？")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val uid = userId
                        if (uid != null) {
                            repo.removeDevice(uid, target.deviceId)
                            devices = repo.listDevices(uid)
                        }
                        pendingRemove = null
                    }
                ) { Text("移除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemove = null }) { Text("取消") }
            },
            shape = RoundedCornerShape(20.dp),
        )
    }

    Scaffold(
        topBar = { AppTopBar(title = "设备绑定", onBack = onBack) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
        ) {
            item {
                SectionLabel("登录设备")
                Text(
                    text = "以下为当前账号登录过的设备列表。你可以移除不再使用或陌生的设备。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 4.dp),
                )
            }

            item {
                if (userId == null) {
                    SettingCard {
                        SettingRow(label = "状态", value = "未登录，无法查看设备列表", showArrow = false)
                    }
                } else if (devices.isEmpty()) {
                    SettingCard {
                        SettingRow(label = "暂无设备记录", value = "", showArrow = false)
                    }
                } else {
                    devices.forEachIndexed { idx, d ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                SettingRow(
                                    label = d.model,
                                    value = if (d.deviceId == current.deviceId) "本机" else "",
                                    showArrow = false,
                                    trailingContent = {
                                        IconButton(
                                            onClick = { pendingRemove = d },
                                            enabled = d.deviceId != current.deviceId,
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.DeleteOutline,
                                                contentDescription = "移除设备",
                                                tint = if (d.deviceId == current.deviceId)
                                                    MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                                                else
                                                    MaterialTheme.colorScheme.error
                                            )
                                        }
                                    },
                                )
                                SettingDivider()
                                SettingRow(
                                    label = "系统版本",
                                    value = d.osVersion,
                                    showArrow = false,
                                )
                                SettingDivider()
                                SettingRow(
                                    label = "最近登录",
                                    value = d.lastLoginText(),
                                    showArrow = false,
                                )
                            }
                        }
                        if (idx != devices.lastIndex) {
                            Spacer(Modifier.height(2.dp))
                        }
                    }
                }
            }

            item {
                Text(
                    text = "提示：移除设备后，对方需要重新登录；“本机”设备不可在此页面移除。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(start = 4.dp, top = 6.dp, end = 4.dp),
                )
            }
        }
    }
}

private data class LoggedDevice(
    val deviceId: String,
    val model: String,
    val osVersion: String,
    val lastLoginAt: Long,
)

private fun LoggedDevice.lastLoginText(): String {
    val seconds = ((System.currentTimeMillis() - lastLoginAt) / 1000L).coerceAtLeast(0L)
    val minutes = seconds / 60
    val hours = minutes / 60
    val days = hours / 24
    return when {
        minutes < 1 -> "刚刚"
        hours < 1 -> "${minutes}分钟前"
        days < 1 -> "${hours}小时前"
        else -> "${days}天前"
    }
}

private class DeviceBindingRepo(context: Context) {
    private val appContext = context.applicationContext
    private val gson = Gson()
    private val prefs = appContext.getSharedPreferences("device_binding", Context.MODE_PRIVATE)

    fun currentDevice(): LoggedDevice {
        val androidId = runCatching {
            Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
        }.getOrDefault("")
        val id = if (androidId.isBlank()) "unknown" else androidId
        val model = "${Build.MANUFACTURER} ${Build.MODEL}".trim().ifBlank { "Android设备" }
        val os = "Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）"
        return LoggedDevice(
            deviceId = id,
            model = model,
            osVersion = os,
            lastLoginAt = System.currentTimeMillis(),
        )
    }

    fun listDevices(userId: Long): List<LoggedDevice> {
        val raw = prefs.getString(key(userId), null) ?: return emptyList()
        val type = object : TypeToken<List<LoggedDevice>>() {}.type
        val parsed: List<LoggedDevice> = runCatching {
            @Suppress("UNCHECKED_CAST")
            (gson.fromJson(raw, type) as? List<LoggedDevice>) ?: emptyList()
        }.getOrDefault(emptyList())
        return parsed.sortedByDescending { it.lastLoginAt }
    }

    fun upsertLoginDevice(userId: Long, device: LoggedDevice) {
        val list = listDevices(userId).toMutableList()
        val idx = list.indexOfFirst { it.deviceId == device.deviceId }
        if (idx >= 0) {
            list[idx] = device.copy(lastLoginAt = System.currentTimeMillis())
        } else {
            list.add(device)
        }
        save(userId, list)
    }

    fun removeDevice(userId: Long, deviceId: String) {
        val list = listDevices(userId).filterNot { it.deviceId == deviceId }
        save(userId, list)
    }

    private fun save(userId: Long, list: List<LoggedDevice>) {
        prefs.edit().putString(key(userId), gson.toJson(list)).apply()
    }

    private fun key(userId: Long) = "devices_$userId"
}

private object NoRippleIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = NoRippleNode

    // 只绘制内容，不叠加任何按压/聚焦指示（ripple）
    private object NoRippleNode : Modifier.Node(), DrawModifierNode {
        override fun ContentDrawScope.draw() {
            drawContent()
        }
    }

    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = javaClass.hashCode()
}
