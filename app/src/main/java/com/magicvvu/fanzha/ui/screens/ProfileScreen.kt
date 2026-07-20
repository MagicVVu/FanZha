package com.magicvvu.fanzha.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Elderly
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.magicvvu.fanzha.ui.components.AppButton
import com.magicvvu.fanzha.ui.components.AppCard
import com.magicvvu.fanzha.ui.components.AppTopBar
import com.magicvvu.fanzha.ui.components.UserProfileAvatar
import com.magicvvu.fanzha.ui.viewmodels.ProfileViewModel
import com.magicvvu.fanzha.ui.viewmodels.UserProfile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    /** 与 `AuthPreferences.getUserId()` 一致；用于从服务端 `users` 表同步姓名/年龄/性别/职业并展示在本页。 */
    sessionUserId: Long?,
    viewModel: ProfileViewModel = viewModel(),
    onNavigateToEditProfile: () -> Unit = {},
    onNavigateToFavorites: () -> Unit = {},
    onNavigateToHistory: () -> Unit = {},
    onNavigateToNotifications: () -> Unit = {},
    onNavigateToPersonalInfo: () -> Unit = {},
    onNavigateToReportProgress: () -> Unit = {},
    onNavigateToElderlyMode: () -> Unit = {},
    onNavigateToVerification: () -> Unit = {},
    onNavigateToSecurityCenter: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onNavigateToAboutUs: () -> Unit = {},
    onLogout: () -> Unit = {}
) {
    var showUserSwitcher by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val loggedIn = sessionUserId != null && sessionUserId > 0L

    LaunchedEffect(sessionUserId) {
        val uid = sessionUserId
        if (uid != null && uid > 0L) {
            viewModel.refreshProfileAndOccupations(uid)
        }
    }

    // Error Handling
    LaunchedEffect(viewModel.switchError) {
        viewModel.switchError?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            AppTopBar(
                title = "我的",
                actions = {
                    // 已登录时不允许切到演示账号，避免看起来与 users 表不同步。
                    if (!loggedIn) {
                        TextButton(
                            onClick = { showUserSwitcher = true },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.SwapHoriz,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(15.dp)
                                )
                                Text(
                                    text = "切换账户",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = 12.sp,
                                    lineHeight = 15.sp
                                )
                            }
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    viewModel.profileRemoteHint?.let { hint ->
                        item {
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
                    }
                    if (!loggedIn) {
                        item {
                            Text(
                                text = "未登录：以下展示为本地演示数据，与服务器 users 表不同步。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = 4.dp),
                            )
                        }
                    }
                    // 1. User Avatar Area（透明背景）
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 20.dp, horizontal = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            UserProfileAvatar(
                                user = viewModel.currentUser,
                                size = 120.dp,
                                textStyle = MaterialTheme.typography.displayLarge,
                            )

                            Spacer(modifier = Modifier.height(18.dp))

                            Text(
                                text = viewModel.currentUser.name,
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Bold
                            )

                            Spacer(modifier = Modifier.height(4.dp))

                            Text(
                                text = demographicSummary(viewModel.currentUser),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // 2. Quick Actions Area
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 20.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                QuickActionItem(
                                    icon = Icons.Default.Bookmark,
                                    label = "我的收藏",
                                    iconTint = Color(0xFFF59E0B),
                                    onClick = onNavigateToFavorites
                                )
                                Box(
                                    modifier = Modifier
                                        .height(34.dp)
                                        .width(1.dp)
                                        .background(
                                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                                        )
                                )
                                QuickActionItem(
                                    icon = Icons.Default.History,
                                    label = "浏览记录",
                                    iconTint = Color(0xFF3B82F6),
                                    onClick = onNavigateToHistory
                                )
                                Box(
                                    modifier = Modifier
                                        .height(34.dp)
                                        .width(1.dp)
                                        .background(
                                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                                        )
                                )
                                QuickActionItem(
                                    icon = Icons.Default.Notifications,
                                    label = "消息中心",
                                    iconTint = Color(0xFF8B5CF6),
                                    onClick = onNavigateToNotifications
                                )
                            }
                        }
                    }

                    // 3. Service Management Area
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = "服务管理",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp)
                                )
                                MenuItemCard(
                                    icon = Icons.Default.AccountBox,
                                    label = "个人信息",
                                    iconTint = Color(0xFF3B82F6),
                                    iconBgColor = Color(0xFF3B82F6).copy(alpha = 0.12f),
                                    onClick = onNavigateToPersonalInfo
                                )
                                MenuItemCard(
                                    icon = Icons.Default.Security,
                                    label = "举报进度查询",
                                    iconTint = Color(0xFFEF4444),
                                    iconBgColor = Color(0xFFEF4444).copy(alpha = 0.12f),
                                    onClick = onNavigateToReportProgress
                                )
                                MenuItemCard(
                                    icon = Icons.Default.Elderly,
                                    label = "老年人模式",
                                    iconTint = Color(0xFF8B5CF6),
                                    iconBgColor = Color(0xFF8B5CF6).copy(alpha = 0.12f),
                                    onClick = onNavigateToElderlyMode
                                )
                                MenuItemCard(
                                    icon = Icons.Default.VerifiedUser,
                                    label = "实名认证",
                                    iconTint = Color(0xFF10B981),
                                    onClick = onNavigateToVerification
                                )
                            }
                        }
                    }

                    // 4. Settings Entry Area
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                MenuItemCard(
                                    icon = Icons.Default.Settings,
                                    label = "通用设置",
                                    iconTint = Color(0xFF64748B),
                                    iconBgColor = Color(0xFF64748B).copy(alpha = 0.12f),
                                    onClick = onNavigateToSettings
                                )
                                MenuItemCard(
                                    icon = Icons.Default.Info,
                                    label = "关于我们",
                                    iconTint = Color(0xFF06B6D4),
                                    iconBgColor = Color(0xFF06B6D4).copy(alpha = 0.12f),
                                    onClick = onNavigateToAboutUs
                                )
                                MenuItemCard(
                                    icon = Icons.Default.ExitToApp,
                                    label = "退出登录",
                                    textColor = MaterialTheme.colorScheme.error,
                                    iconTint = MaterialTheme.colorScheme.error,
                                    iconBgColor = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                                    showArrow = false,
                                    onClick = onLogout
                                )
                            }
                        }
                    }
                    
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "当前版本 v1.0.0", 
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Loading Overlay
            if (viewModel.isSwitching) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f))
                        .clickable(enabled = false) {}, // Block clicks
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        // User Switcher Bottom Sheet
        if (showUserSwitcher && !loggedIn) {
            ModalBottomSheet(
                onDismissRequest = { showUserSwitcher = false },
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "切换账号",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(bottom = 16.dp),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    
                    LazyColumn {
                        items(viewModel.availableUsers.size) { index ->
                            val user = viewModel.availableUsers[index]
                            val isSelected = user.id == viewModel.currentUser.id
                            
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.switchUser(user)
                                        showUserSwitcher = false
                                    }
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                UserProfileAvatar(
                                    user = user,
                                    size = 40.dp,
                                    textStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        user.name, 
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                    Text(
                                        demographicSummary(user),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (isSelected) {
                                    Icon(Icons.Default.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
        }
    }
}

@Composable
fun QuickActionItem(
    icon: ImageVector,
    label: String,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    iconBgColor: Color = Color.Transparent,
    onClick: () -> Unit = {}
) {
    val interactionSource = remember { MutableInteractionSource() }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) { onClick() }
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = iconTint,
            modifier = Modifier.size(40.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun MenuItemCard(
    icon: ImageVector,
    label: String,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    iconBgColor: Color = Color.Transparent,
    showArrow: Boolean = true,
    onClick: () -> Unit = {}
) {
    val interactionSource = remember { MutableInteractionSource() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) { onClick() }
            .padding(horizontal = 16.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = iconTint,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(14.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = textColor,
            modifier = Modifier.weight(1f)
        )
        if (showArrow) {
            Icon(
                Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

private fun demographicSummary(user: UserProfile): String {
    val agePart = if (user.age > 0) "${user.age}岁" else "-"
    val genderPart = user.gender.trim().ifBlank { "-" }
    val jobPart = user.occupation.trim().ifBlank { "-" }
    return "$agePart · $genderPart · $jobPart"
}
