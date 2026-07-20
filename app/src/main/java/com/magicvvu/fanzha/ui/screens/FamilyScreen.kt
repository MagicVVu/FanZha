package com.magicvvu.fanzha.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.magicvvu.fanzha.R
import com.magicvvu.fanzha.ui.components.AppCard
import com.magicvvu.fanzha.ui.components.AppTextField
import com.magicvvu.fanzha.ui.components.AppTopBar
import com.magicvvu.fanzha.ui.components.UserProfileAvatar
import com.magicvvu.fanzha.data.remote.ApiClient
import com.magicvvu.fanzha.data.remote.ApiResponse
import com.magicvvu.fanzha.data.remote.FamilyMemberDto
import com.magicvvu.fanzha.ui.viewmodels.HomeViewModel
import com.magicvvu.fanzha.ui.viewmodels.ProfileViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import retrofit2.Response
import java.util.UUID
import kotlin.math.roundToInt

/**
 * 非 2xx 或业务失败时，尽量从 Retrofit [Response] 中解析后端 `message`（含 errorBody JSON）。
 */
private fun parseApiFailureMessage(
    response: Response<*>,
    body: ApiResponse<*>?,
): String {
    val fromWrapper = body?.message?.takeIf { it.isNotBlank() }
    if (fromWrapper != null) return fromWrapper
    val raw = runCatching { response.errorBody()?.string() }.getOrNull().orEmpty().trim()
    if (raw.isEmpty()) return "请求失败（${response.code()}）"
    val fromJson = runCatching {
        com.google.gson.JsonParser.parseString(raw).asJsonObject.get("message")?.asString?.trim()
    }.getOrNull()
    return (fromJson ?: raw).take(240)
}

// --- Data Models ---
data class Guardian(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val phone: String,
    val relation: String,
    val avatarColor: Color = Color.Gray,
    val avatarUri: String? = null,
    val safetyIndex: Int = 100,
    val isOnline: Boolean = false,
    val interceptedCalls: Int = 0,
    val interceptedSms: Int = 0,
    val interceptedApps: Int = 0,
    val interceptedClipboard: Int = 0,
    /** 当前登录用户本人，家庭列表中不展示编辑入口 */
    val isSelf: Boolean = false,
    /** 与首页一致的周拦截展示文案（如数字、「…」「—」）；非空时优先于对应 Int 展示 */
    val interceptedCallsDisplay: String? = null,
    val interceptedSmsDisplay: String? = null,
    val interceptedAppsDisplay: String? = null,
    val interceptedClipboardDisplay: String? = null,
)

data class FamilyStats(
    val interceptedCalls: Int = 0,
    val interceptedSms: Int = 0,
    val interceptedApps: Int = 0,
    val interceptedClipboard: Int = 0
)

data class SuspiciousActivity(
    val id: String = UUID.randomUUID().toString(),
    val guardianName: String,
    val type: String,
    val description: String,
    val time: String,
    val isResolved: Boolean = false
)

private const val SELF_GUARDIAN_ID = "__self__"

private val guardianRelationOptions = listOf(
    "父亲", "母亲", "爷爷", "奶奶", "外公", "外婆", "儿子", "女儿", "孙子", "孙女", "外孙", "外孙女",
    "丈夫", "妻子", "公公", "婆婆", "岳父", "岳母", "被监护人",
)

// --- ViewModel ---
class FamilyViewModel {
    val guardians = mutableStateListOf<Guardian>()
    val familyStats = mutableStateOf(FamilyStats())
    val suspiciousActivities = mutableStateListOf<SuspiciousActivity>()
    var isLoading = mutableStateOf(false)
    var errorMsg = mutableStateOf<String?>(null)

    suspend fun loadGuardians(currentUserId: Long?): Boolean {
        if (currentUserId == null) return false
        isLoading.value = true
        errorMsg.value = null
        return try {
            val resp = ApiClient.familyMemberApi.listMembers(currentUserId)
            val body = resp.body()
            if (resp.isSuccessful && body?.success == true) {
                val rows = body.data.orEmpty()
                guardians.clear()
                guardians.addAll(rows.mapIndexed { index, dto -> dto.toGuardian(index) })
                true
            } else {
                errorMsg.value = parseApiFailureMessage(resp, body)
                false
            }
        } catch (e: Exception) {
            errorMsg.value = "家庭成员加载异常：${e.message ?: e.javaClass.simpleName}"
            false
        } finally {
            isLoading.value = false
        }
    }

    suspend fun addGuardian(currentUserId: Long?, phone: String, relation: String): Boolean {
        if (currentUserId == null) {
            errorMsg.value = "当前未登录，无法添加家庭成员"
            return false
        }
        isLoading.value = true
        errorMsg.value = null

        if (phone.length != 11) {
            errorMsg.value = "手机号格式不正确"
            isLoading.value = false
            return false
        }
        if (guardians.any { it.phone == phone }) {
            errorMsg.value = "该监护人已存在"
            isLoading.value = false
            return false
        }

        return try {
            // 用 Map 保证 Gson 将「关系」等字段完整写入 JSON（与后端 AddRequest 对齐：userId, phone, relation）
            val payload: Map<String, Any?> = buildMap {
                put("userId", currentUserId)
                put("phone", phone.trim())
                put("relation", relation.trim())
            }
            val resp = ApiClient.familyMemberApi.addMember(payload)
            val body = resp.body()
            if (resp.isSuccessful && body?.success == true) {
                val dto = body.data
                if (dto == null) {
                    errorMsg.value = "添加成功但返回数据为空"
                    false
                } else {
                    val existing = guardians.indexOfFirst { it.phone == (dto.phone ?: phone) }
                    val item = dto.toGuardian(guardians.size).copy(
                        // 名称优先使用后端 users 中的真实昵称，未配置时回退输入值
                        name = dto.name?.takeIf { it.isNotBlank() } ?: "未命名成员"
                    )
                    if (existing >= 0) guardians[existing] = item else guardians.add(item)
                    true
                }
            } else {
                errorMsg.value = parseApiFailureMessage(resp, body)
                false
            }
        } catch (e: Exception) {
            errorMsg.value = "添加异常：${e.message ?: e.javaClass.simpleName}"
            false
        } finally {
            isLoading.value = false
        }
    }

    suspend fun updateGuardian(id: String, name: String, phone: String, relation: String): Boolean {
        isLoading.value = true
        delay(800)
        isLoading.value = false

        if (phone.length != 11) {
            errorMsg.value = "手机号格式不正确"
            return false
        }

        val index = guardians.indexOfFirst { it.id == id }
        if (index != -1) {
            // Check if phone exists for OTHER guardians
            if (guardians.any { it.phone == phone && it.id != id }) {
                errorMsg.value = "该手机号已存在"
                return false
            }

            guardians[index] = guardians[index].copy(name = name, phone = phone, relation = relation)
            return true
        }
        return false
    }

    suspend fun deleteGuardian(id: String): Boolean {
        isLoading.value = true
        delay(500)
        isLoading.value = false
        guardians.removeIf { it.id == id }
        return true
    }

    private fun FamilyMemberDto.toGuardian(index: Int): Guardian {
        val fallbackColor = if (index % 2 == 0) Color(0xFF1976D2) else Color(0xFFE91E63)
        val safeScore = (safetyIndex ?: 100).coerceIn(0, 100)
        return Guardian(
            id = id.toString(),
            name = name?.takeIf { it.isNotBlank() } ?: "未命名成员",
            phone = phone.orEmpty(),
            relation = relation?.takeIf { it.isNotBlank() } ?: "家庭成员",
            avatarColor = fallbackColor,
            safetyIndex = safeScore,
            isOnline = false,
            interceptedCalls = (callCount ?: 0).coerceAtLeast(0),
            interceptedSms = (smsCount ?: 0).coerceAtLeast(0),
            interceptedApps = (suspiciousAppCount ?: 0).coerceAtLeast(0),
            interceptedClipboard = (clipboardCount ?: 0).coerceAtLeast(0),
        )
    }
}

// --- Screen ---

@Composable
fun FamilyScreen(
    currentUserId: Long? = null,
    onDissolveFamily: () -> Unit = {},
    /** 「本人」卡片四项拦截统计：进入与首页一致的通道拦截详情页 */
    onOpenSelfInterceptChannel: (InterceptSection) -> Unit = {},
    /** 其他家庭成员四项：进入对应通道页（布局与本人一致，数据为成员看板口径） */
    onOpenMemberInterceptChannel: (Guardian, InterceptSection) -> Unit = { _, _ -> },
) {
    val homeViewModel: HomeViewModel = viewModel()
    val homeUiState by homeViewModel.uiState.collectAsState()
    val profileViewModel: ProfileViewModel = viewModel()
    val viewModel = remember { FamilyViewModel() }
    LaunchedEffect(currentUserId) {
        homeViewModel.refreshCallAndSmsInterceptWeek(currentUserId)
        viewModel.loadGuardians(currentUserId)
    }

    val scope = rememberCoroutineScope()
    var showAddDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf<Guardian?>(null) }
    var showDeleteConfirm by remember { mutableStateOf<Guardian?>(null) }
    var showDissolveFirstConfirm by remember { mutableStateOf(false) }
    var showDissolveSecondConfirm by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppTopBar(title = "家庭")
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Family Summary Module
                item {
                    FamilySummaryCard(
                        stats = viewModel.familyStats.value,
                        activities = viewModel.suspiciousActivities
                    )
                }

                item {
                    Text(
                        text = "家庭成员",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }

                item(key = SELF_GUARDIAN_ID) {
                    val me = profileViewModel.currentUser
                    val selfGuardian = Guardian(
                        id = SELF_GUARDIAN_ID,
                        name = me.name,
                        phone = "",
                        relation = "本人",
                        avatarColor = Color((me.avatarColor and 0xFFFFFFFFL).toInt()),
                        avatarUri = me.avatarUri,
                        safetyIndex = homeUiState.safetyIndex,
                        isOnline = true,
                        isSelf = true,
                        interceptedCallsDisplay = homeUiState.phoneWeekInterceptDisplay,
                        interceptedSmsDisplay = homeUiState.smsWeekInterceptDisplay,
                        interceptedAppsDisplay = homeUiState.appWeekInterceptDisplay,
                        interceptedClipboardDisplay = homeUiState.clipboardWeekInterceptDisplay,
                    )
                    GuardianExpandableItem(
                        guardian = selfGuardian,
                        onEdit = null,
                        onSelfInterceptSection = onOpenSelfInterceptChannel,
                    )
                }

                items(viewModel.guardians, key = { it.id }) { guardian ->
                    GuardianExpandableItem(
                        guardian = guardian,
                        onEdit = { showEditDialog = guardian },
                        onMemberInterceptSection = onOpenMemberInterceptChannel,
                    )
                }

                item {
                    val outline = MaterialTheme.colorScheme.outline
                    val addLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                    val addInteraction = remember { MutableInteractionSource() }
                    val addPressed by addInteraction.collectIsPressedAsState()
                    val pressOverlayAlpha by animateFloatAsState(
                        targetValue = if (addPressed) 0.12f else 0f,
                        animationSpec = tween(durationMillis = 75, easing = FastOutSlowInEasing),
                        label = "addMemberPress",
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .border(
                                width = 2.dp,
                                color = outline.copy(alpha = 0.7f),
                                shape = RoundedCornerShape(16.dp),
                            )
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                            .clickable(
                                interactionSource = addInteraction,
                                indication = null,
                                onClick = { showAddDialog = true },
                            ),
                    ) {
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .background(
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = pressOverlayAlpha),
                                ),
                        )
                        Row(
                            modifier = Modifier
                                .matchParentSize()
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = null,
                                tint = addLabelColor,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "点击以添加家庭成员",
                                color = addLabelColor,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }

                item {
                    OutlinedButton(
                        onClick = { showDissolveFirstConfirm = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                        border = BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.error.copy(alpha = 0.55f),
                        ),
                    ) {
                        Text(
                            text = "解散家庭",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }

    // Add/Edit Dialog
    if (showAddDialog || showEditDialog != null) {
        val isEdit = showEditDialog != null
        val initialName = showEditDialog?.name ?: ""
        val initialPhone = showEditDialog?.phone ?: ""
        val initialRelation = showEditDialog?.relation ?: guardianRelationOptions.first()

        GuardianDialog(
            isEdit = isEdit,
            initialName = initialName,
            initialPhone = initialPhone,
            initialRelation = initialRelation,
            onRequestDelete = if (isEdit) {
                {
                    val g = showEditDialog
                    showEditDialog = null
                    showDeleteConfirm = g
                }
            } else null,
            onDismiss = {
                showAddDialog = false
                showEditDialog = null
            },
            // 添加：仅传「手机号 + 关系」给后端；与纯 UI 列表 guardianRelationOptions 解耦，避免与姓名字段混用
            onConfirmAdd = { phone, selfToTargetRelation ->
                scope.launch {
                    val success = viewModel.addGuardian(currentUserId, phone, selfToTargetRelation)
                    if (success) {
                        showAddDialog = false
                        showEditDialog = null
                        snackbarHostState.showSnackbar("添加成功")
                    } else {
                        viewModel.errorMsg.value?.let { snackbarHostState.showSnackbar(it) }
                    }
                }
            },
            onConfirmEdit = { name, phone, relation ->
                scope.launch {
                    val success = viewModel.updateGuardian(showEditDialog!!.id, name, phone, relation)
                    if (success) {
                        showAddDialog = false
                        showEditDialog = null
                        snackbarHostState.showSnackbar("修改成功")
                    } else {
                        viewModel.errorMsg.value?.let { snackbarHostState.showSnackbar(it) }
                    }
                }
            },
            isLoading = viewModel.isLoading.value
        )
    }

    // Delete Confirm Dialog
    if (showDeleteConfirm != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text("确认删除？", style = MaterialTheme.typography.titleLarge) },
            text = { Text("删除后将无法接收其安全提醒，确定要删除 ${showDeleteConfirm?.name} 吗？", style = MaterialTheme.typography.bodyMedium) },
            containerColor = MaterialTheme.colorScheme.surface,
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            viewModel.deleteGuardian(showDeleteConfirm!!.id)
                            showDeleteConfirm = null
                            snackbarHostState.showSnackbar("已删除")
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteConfirm = null },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("取消")
                }
            }
        )
    }

    if (showDissolveFirstConfirm) {
        AlertDialog(
            onDismissRequest = { showDissolveFirstConfirm = false },
            title = { Text("解散家庭", style = MaterialTheme.typography.titleLarge) },
            text = {
                Text(
                    "确定要解散当前家庭吗？解散后将返回创建/加入家庭入口。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            containerColor = MaterialTheme.colorScheme.surface,
            confirmButton = {
                TextButton(
                    onClick = {
                        showDissolveFirstConfirm = false
                        showDissolveSecondConfirm = true
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("继续")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDissolveFirstConfirm = false },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary),
                ) {
                    Text("取消")
                }
            },
        )
    }

    if (showDissolveSecondConfirm) {
        AlertDialog(
            onDismissRequest = { showDissolveSecondConfirm = false },
            title = { Text("再次确认", style = MaterialTheme.typography.titleLarge) },
            text = {
                Text(
                    "此操作不可撤销，将退出当前家庭并返回上一页。请再次确认是否解散家庭。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            containerColor = MaterialTheme.colorScheme.surface,
            confirmButton = {
                TextButton(
                    onClick = {
                        showDissolveSecondConfirm = false
                        onDissolveFamily()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("确认解散")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDissolveSecondConfirm = false },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary),
                ) {
                    Text("取消")
                }
            },
        )
    }
}

// --- Components ---

@Composable
fun FamilySummaryCard(stats: FamilyStats, activities: List<SuspiciousActivity>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "家庭防护概要",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Stats Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem(
                    icon = Icons.Filled.Phone,
                    iconTint = Color(0xFFACCAB2),
                    iconBgColor = Color(0xFFACCAB2).copy(alpha = 0.15f),
                    count = stats.interceptedCalls,
                    label = "拦截电话"
                )
                StatItem(
                    icon = Icons.Filled.Email,
                    iconTint = Color(0xFFE9A752),
                    iconBgColor = Color(0xFFE9A752).copy(alpha = 0.15f),
                    count = stats.interceptedSms,
                    label = "拦截短信"
                )
                StatItem(
                    icon = Icons.Filled.Apps,
                    iconTint = Color(0xFFD44720),
                    iconBgColor = Color(0xFFD44720).copy(alpha = 0.15f),
                    count = stats.interceptedApps,
                    label = "可疑App"
                )
                StatItem(
                    icon = Icons.Filled.ContentPaste,
                    iconTint = Color(0xFF78614D),
                    iconBgColor = Color(0xFF78614D).copy(alpha = 0.15f),
                    count = stats.interceptedClipboard,
                    label = "拦截剪切板"
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "近期可疑动态",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (activities.isEmpty()) {
                Text(
                    text = "暂无异常动态，家庭成员环境安全",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            } else {
                activities.forEach { activity ->
                    ActivityItem(activity)
                }
            }
        }
    }
}

@Composable
fun StatItem(
    icon: ImageVector,
    iconTint: Color,
    iconBgColor: Color,
    count: Int,
    label: String
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(iconBgColor),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun ActivityItem(activity: SuspiciousActivity) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            tint = if (activity.isResolved) Color.Gray else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${activity.guardianName} ",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = activity.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
            Text(
                text = activity.time,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (!activity.isResolved) {
            Text(
                text = "需处理",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun guardianCardContainerColor(safetyIndex: Int): Color {
    val surface = MaterialTheme.colorScheme.surface
    val critical = safetyIndex < 25
    val infinite = rememberInfiniteTransition(label = "guardianDangerBg")
    val phase by infinite.animateFloat(
        initialValue = 0.28f,
        targetValue = 0.82f,
        animationSpec = infiniteRepeatable(
            animation = tween(750, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "phase",
    )
    return if (critical) lerp(surface, Color(0xFFFFCDD2), phase) else surface
}

@Composable
fun GuardianExpandableItem(
    guardian: Guardian,
    /** 为 `null` 时不展示编辑按钮（如本人「我」） */
    onEdit: (() -> Unit)?,
    /** 仅当 [Guardian.isSelf] 时生效：点击四项统计进入对应拦截通道详情 */
    onSelfInterceptSection: ((InterceptSection) -> Unit)? = null,
    /** 非本人时生效：点击四项统计进入该成员对应通道页 */
    onMemberInterceptSection: ((Guardian, InterceptSection) -> Unit)? = null,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = guardianCardContainerColor(guardian.safetyIndex)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            val editIconGray = Color(0xFF424242)
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Avatar with Online Status
                Box(contentAlignment = Alignment.BottomEnd) {
                    UserProfileAvatar(
                        name = guardian.name,
                        avatarUri = guardian.avatarUri,
                        fallbackAvatarColor = guardian.avatarColor,
                        size = 48.dp,
                        textStyle = MaterialTheme.typography.titleLarge,
                    )

                    // Online Status Indicator
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(2.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(CircleShape)
                                .background(if (guardian.isOnline) Color(0xFF4CAF50) else Color.Gray)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))

                // Name and Relation
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = guardian.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = guardian.relation,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (onEdit != null) {
                    IconButton(onClick = onEdit) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "编辑",
                            tint = editIconGray,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

            // Details Content
            Column(modifier = Modifier.padding(top = 16.dp)) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(16.dp))

                    // Safety Index
                    Text("安全系数", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val progressTarget = (guardian.safetyIndex / 100f).coerceIn(0f, 1f)
                        var hasPlayedIntro by rememberSaveable(guardian.id) { mutableStateOf(false) }
                        val progressAnim = remember(guardian.id) { Animatable(if (hasPlayedIntro) progressTarget else 0f) }
                        LaunchedEffect(progressTarget) {
                            if (!hasPlayedIntro) {
                                progressAnim.snapTo(0f)
                                progressAnim.animateTo(
                                    targetValue = progressTarget,
                                    animationSpec = tween(durationMillis = 900, easing = FastOutSlowInEasing)
                                )
                                hasPlayedIntro = true
                            } else {
                                // Do not replay the intro animation; just reflect the latest value.
                                progressAnim.snapTo(progressTarget)
                            }
                        }

                        // Color transitions from red (low) to green (high).
                        val low = Color(0xFFEF4444)
                        val high = Color(0xFF22C55E)
                        val barColor = lerp(low, high, progressTarget)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(14.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(barColor.copy(alpha = 0.18f))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(progressAnim.value)
                                    .background(barColor.copy(alpha = 0.85f))
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            "${guardian.safetyIndex}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = barColor
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Per-member intercept stats (2×2 grid)
                    val channelClick: (InterceptSection) -> (() -> Unit)? = { s ->
                        when {
                            guardian.isSelf ->
                                onSelfInterceptSection?.let { cb -> { cb(s) } }
                            else ->
                                onMemberInterceptSection?.let { cb -> { cb(guardian, s) } }
                        }
                    }
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            MemberStatChip(
                                icon = Icons.Filled.Phone,
                                iconTint = Color(0xFFACCAB2),
                                primaryText = guardian.interceptedCallsDisplay
                                    ?: guardian.interceptedCalls.toString(),
                                label = "拦截电话",
                                modifier = Modifier.weight(1f),
                                onClick = channelClick(InterceptSection.Call),
                            )
                            MemberStatChip(
                                icon = Icons.Filled.Email,
                                iconTint = Color(0xFFE9A752),
                                primaryText = guardian.interceptedSmsDisplay
                                    ?: guardian.interceptedSms.toString(),
                                label = "拦截短信",
                                modifier = Modifier.weight(1f),
                                onClick = channelClick(InterceptSection.Sms),
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            MemberStatChip(
                                icon = Icons.Filled.Apps,
                                iconTint = Color(0xFFD44720),
                                primaryText = guardian.interceptedAppsDisplay
                                    ?: guardian.interceptedApps.toString(),
                                label = "可疑App",
                                modifier = Modifier.weight(1f),
                                onClick = channelClick(InterceptSection.App),
                            )
                            MemberStatChip(
                                icon = Icons.Filled.ContentPaste,
                                iconTint = Color(0xFF78614D),
                                primaryText = guardian.interceptedClipboardDisplay
                                    ?: guardian.interceptedClipboard.toString(),
                                label = "拦截剪切板",
                                modifier = Modifier.weight(1f),
                                onClick = channelClick(InterceptSection.Clipboard),
                            )
                        }
                    }
                }
        }
    }
}

@Composable
private fun MemberStatChip(
    icon: ImageVector,
    iconTint: Color,
    primaryText: String,
    label: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .clickable(
                enabled = onClick != null,
                onClick = { onClick?.invoke() },
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(26.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = primaryText,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun GuardianRelationPicker(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    scrollAlignKey: String,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(scrollAlignKey) {
        val i = options.indexOf(selected)
        if (i >= 0) listState.scrollToItem(i)
    }
    Column(modifier = modifier) {
        Text(
            text = "关系",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )
        LazyRow(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(end = 8.dp),
        ) {
            items(options, key = { it }) { option ->
                val isSelected = option == selected
                val borderColor =
                    if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline
                val containerColor =
                    if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    else MaterialTheme.colorScheme.surface
                val labelColor =
                    if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(24.dp))
                        .border(BorderStroke(1.dp, borderColor), RoundedCornerShape(24.dp))
                        .background(containerColor)
                        .clickable { onSelect(option) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = option,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = labelColor,
                    )
                }
            }
        }
    }
}

@Composable
fun GuardianDialog(
    isEdit: Boolean,
    initialName: String = "",
    initialPhone: String = "",
    initialRelation: String = guardianRelationOptions.first(),
    onRequestDelete: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    /** 添加成员：手机号 + 用户选择的关系（写入后端 `relation` / `self_to_target`） */
    onConfirmAdd: (phone: String, selfToTargetRelation: String) -> Unit,
    /** 编辑成员：姓名 + 手机号 + 关系（本地 mock，尚未接后端） */
    onConfirmEdit: (name: String, phone: String, relation: String) -> Unit,
    isLoading: Boolean
) {
    var name by remember(initialName, initialPhone, initialRelation, isEdit) { mutableStateOf(initialName) }
    var phone by remember(initialName, initialPhone, initialRelation, isEdit) { mutableStateOf(initialPhone) }
    val menuOptions = remember(initialRelation) {
        buildList {
            if (initialRelation.isNotBlank() && initialRelation !in guardianRelationOptions) {
                add(initialRelation)
            }
            addAll(guardianRelationOptions)
        }
    }
    var relation by remember(initialName, initialPhone, initialRelation, isEdit) {
        val start = when {
            initialRelation in guardianRelationOptions -> initialRelation
            initialRelation.isNotBlank() -> initialRelation
            else -> guardianRelationOptions.first()
        }
        mutableStateOf(start)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if(isEdit) "编辑家庭成员" else "添加家庭成员", style = MaterialTheme.typography.headlineSmall) },
        containerColor = MaterialTheme.colorScheme.surface,
        text = {
            Column {
                if (isEdit) {
                    AppTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = "姓名",
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                AppTextField(
                    value = phone,
                    onValueChange = { if (it.length <= 11) phone = it },
                    label = "手机号",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                GuardianRelationPicker(
                    options = menuOptions,
                    selected = relation,
                    onSelect = { relation = it },
                    scrollAlignKey = "${initialName}|${initialPhone}|${initialRelation}|$isEdit",
                    modifier = Modifier.fillMaxWidth(),
                )
                if (onRequestDelete != null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    TextButton(
                        onClick = onRequestDelete,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("删除联系人")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (isEdit) onConfirmEdit(name, phone, relation) else onConfirmAdd(phone, relation)
                },
                enabled = !isLoading && phone.length == 11 && (!isEdit || name.isNotBlank()),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary,
                    disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                ),
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Text(
                        text = "确定",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isLoading,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary),
            ) {
                Text("取消", style = MaterialTheme.typography.labelLarge)
            }
        }
    )
}
