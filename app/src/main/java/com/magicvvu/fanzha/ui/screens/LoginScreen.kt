package com.magicvvu.fanzha.ui.screens

import android.view.LayoutInflater
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Message
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.magicvvu.fanzha.data.local.AuthPreferences
import com.magicvvu.fanzha.ui.theme.LocalThemePreferenceController
import com.magicvvu.fanzha.ui.viewmodels.LoginViewModel
import com.magicvvu.fanzha.ui.components.AppTextField
import com.magicvvu.fanzha.ui.components.LiquidButton
import com.magicvvu.fanzha.ui.components.SvgAssetImage
import com.magicvvu.fanzha.R
import nl.joery.animatedbottombar.AnimatedBottomBar

private val LoginScreenEdgePadding = 20.dp

/**
 * 与个人信息页职业弹窗选项一致；注册第 4 步可视区域为三行，列表在网格内纵向滚动。
 * @see com.magicvvu.fanzha.ui.screens.ProfileSubScreens
 */
private val registerOccupationPickerOptions = listOf(
    "医师", "教师", "律师",
    "护士", "建造师", "会计",
    "社会工作者", "消防员", "学生",
    "农民", "公务员", "IT技术人员",
    "科研人员", "金融服务人员", "建筑师",
    "工程师", "设计师", "艺术家",
    "销售人员", "餐饮从业者", "酒店从业者",
    "文艺从业者", "自由职业者", "工人",
    "其他",
)

private val registerOccupationCellHeight = 52.dp
private val registerOccupationRowGap = 10.dp
private const val registerOccupationVisibleRows = 3

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LoginScreen(
    viewModel: LoginViewModel = viewModel(),
    onLoginSuccess: () -> Unit,
    onNavigateToSmsLogin: () -> Unit = {},
    onNavigateToForgotPassword: () -> Unit = {}
) {
    val context = LocalContext.current

    LaunchedEffect(viewModel.loginSuccess) {
        if (viewModel.loginSuccess) {
            viewModel.userInfoAfterLogin?.let { user ->
                AuthPreferences.saveUserId(context.applicationContext, user.id)
            }
            onLoginSuccess()
            viewModel.acknowledgeLoginSuccess()
        }
    }

    var isAccountLogin by remember { mutableStateOf(true) } // true: 账号登录，false: 注册账号
    val primaryBlue = Color(0xFF4A90E2)
    val secondaryBlue = Color(0xFF7AB8FF)

    LaunchedEffect(viewModel.shouldSwitchToLogin) {
        if (viewModel.shouldSwitchToLogin) {
            isAccountLogin = true
            viewModel.consumeSwitchToLogin()
        }
    }

    val themeController = LocalThemePreferenceController.current
    val colorScheme = MaterialTheme.colorScheme
    val screenBackground = if (!themeController.isDarkTheme) {
        Brush.verticalGradient(
            colors = listOf(
                Color(0xFFEAF4FF),
                Color(0xFFF5FAFF),
                Color(0xFFFFFFFF),
            ),
        )
    } else {
        Brush.verticalGradient(
            colors = listOf(
                colorScheme.background,
                colorScheme.surface,
                colorScheme.background,
            ),
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(brush = screenBackground)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = LoginScreenEdgePadding)
                .padding(bottom = LoginScreenEdgePadding),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = "欢迎登录",
                style = MaterialTheme.typography.displayMedium.copy(
                    fontWeight = FontWeight.ExtraBold,
                    color = Color(0xFF0A0A0A),
                    letterSpacing = 0.3.sp,
                    lineHeight = 44.sp
                ),
                modifier = Modifier.align(Alignment.Start)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "我们等你很久了！",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Medium,
                    color = secondaryBlue,
                    letterSpacing = 0.2.sp
                ),
                modifier = Modifier.align(Alignment.Start)
            )

            Spacer(modifier = Modifier.weight(1f))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 448.dp)
                    .shadow(elevation = 6.dp, shape = RoundedCornerShape(24.dp), spotColor = Color.Black.copy(alpha = 0.08f)),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 28.dp)
                ) {
                    AndroidView(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 18.dp, end = 18.dp, top = 20.dp)
                            .height(56.dp)
                            .clip(RoundedCornerShape(14.dp)),
                        factory = { context ->
                            LayoutInflater.from(context)
                                .inflate(R.layout.view_login_mode_animated_bar, null, false) as AnimatedBottomBar
                        },
                        update = { bar ->
                            bar.onTabSelected = { tab ->
                                isAccountLogin = tab.id == R.id.tab_account_login
                                viewModel.resetRegisterFlow()
                            }
                            val targetId =
                                if (isAccountLogin) R.id.tab_account_login else R.id.tab_register
                            if (bar.selectedTab?.id != targetId) {
                                bar.selectTabById(targetId, animate = true)
                            }
                        }
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp)
                    ) {
                        Spacer(modifier = Modifier.height(22.dp))

                        AnimatedContent(
                            targetState = isAccountLogin,
                            transitionSpec = {
                                val enterSlide = if (targetState) -1 else 1
                                val exitSlide = if (targetState) 1 else -1
                                (fadeIn(
                                    animationSpec = tween(
                                        durationMillis = 280,
                                        easing = FastOutSlowInEasing
                                    )
                                ) + slideInHorizontally(
                                    animationSpec = tween(
                                        durationMillis = 300,
                                        easing = FastOutSlowInEasing
                                    )
                                ) { full -> enterSlide * full / 6 }) togetherWith (fadeOut(
                                    animationSpec = tween(
                                        durationMillis = 220,
                                        easing = FastOutSlowInEasing
                                    )
                                ) + slideOutHorizontally(
                                    animationSpec = tween(
                                        durationMillis = 260,
                                        easing = FastOutSlowInEasing
                                    )
                                ) { full -> exitSlide * full / 6 })
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = "loginRegisterForm"
                        ) { loginMode ->
                            if (loginMode) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    AppTextField(
                                        value = viewModel.email,
                                        onValueChange = { if (it.length <= 11) viewModel.onEmailChange(it) },
                                        label = "手机号",
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    AppTextField(
                                        value = viewModel.password,
                                        onValueChange = { viewModel.onPasswordChange(it) },
                                        label = "密码",
                                        visualTransformation = PasswordVisualTransformation()
                                    )

                                    Spacer(modifier = Modifier.height(10.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End
                                    ) {
                                        Text(
                                            "忘记密码",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color(0xFF4A90E2),
                                            modifier = Modifier.clickable { onNavigateToForgotPassword() }
                                        )
                                    }
                                }
                            } else {
                                AnimatedContent(
                                    targetState = viewModel.registerStep,
                                    transitionSpec = {
                                        val forward = targetState > initialState
                                        val enterSlide = if (forward) 1 else -1
                                        val exitSlide = if (forward) -1 else 1
                                        (fadeIn(
                                            animationSpec = tween(
                                                durationMillis = 280,
                                                easing = FastOutSlowInEasing,
                                            ),
                                        ) + slideInHorizontally(
                                            animationSpec = tween(
                                                durationMillis = 300,
                                                easing = FastOutSlowInEasing,
                                            ),
                                        ) { full -> enterSlide * full / 6 }) togetherWith (fadeOut(
                                            animationSpec = tween(
                                                durationMillis = 220,
                                                easing = FastOutSlowInEasing,
                                            ),
                                        ) + slideOutHorizontally(
                                            animationSpec = tween(
                                                durationMillis = 260,
                                                easing = FastOutSlowInEasing,
                                            ),
                                        ) { full -> exitSlide * full / 6 })
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = "register_wizard_step",
                                ) { step ->
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        when (step) {
                                            1 -> {
                                                AppTextField(
                                                    value = viewModel.registerEmail,
                                                    onValueChange = { if (it.length <= 11) viewModel.onRegisterEmailChange(it) },
                                                    label = "手机号",
                                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
                                                )
                                                Spacer(modifier = Modifier.height(14.dp))
                                                AppTextField(
                                                    value = viewModel.registerOtp,
                                                    onValueChange = { if (it.length <= 6) viewModel.onRegisterOtpChange(it) },
                                                    label = "验证码",
                                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                                    trailingIcon = {
                                                        val phoneReady = viewModel.registerEmail.trim().length == 11
                                                        TextButton(
                                                            onClick = {
                                                                viewModel.requestRegisterOtp()
                                                            },
                                                            enabled = phoneReady,
                                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                                        ) {
                                                            Text("接收验证码")
                                                        }
                                                    },
                                                )
                                            }

                                            2 -> {
                                                AppTextField(
                                                    value = viewModel.registerPassword,
                                                    onValueChange = { viewModel.onRegisterPasswordChange(it) },
                                                    label = "密码",
                                                    visualTransformation = PasswordVisualTransformation()
                                                )
                                                Spacer(modifier = Modifier.height(14.dp))
                                                AppTextField(
                                                    value = viewModel.registerConfirmPassword,
                                                    onValueChange = { viewModel.onRegisterConfirmPasswordChange(it) },
                                                    label = "确认密码",
                                                    visualTransformation = PasswordVisualTransformation()
                                                )
                                            }

                                            3 -> {
                                                AppTextField(
                                                    value = viewModel.registerUsername,
                                                    onValueChange = { viewModel.onRegisterUsernameChange(it) },
                                                    label = "用户名",
                                                )
                                                Spacer(modifier = Modifier.height(14.dp))
                                                AppTextField(
                                                    value = viewModel.registerAge,
                                                    onValueChange = { viewModel.onRegisterAgeChange(it) },
                                                    label = "用户年龄",
                                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                                )
                                                Spacer(modifier = Modifier.height(14.dp))
                                                Text(
                                                    text = "用户性别",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = Color(0xFF8AA0B8),
                                                )
                                                Spacer(modifier = Modifier.height(8.dp))
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                ) {
                                                    listOf("男", "女", "保密").forEach { option ->
                                                        FilterChip(
                                                            selected = viewModel.registerGender == option,
                                                            onClick = { viewModel.onRegisterGenderChange(option) },
                                                            label = { Text(option) },
                                                        )
                                                    }
                                                }
                                            }

                                            4 -> {
                                                RegisterOccupationPicker(
                                                    selected = viewModel.registerOccupation,
                                                    onSelect = { viewModel.onRegisterOccupationChange(it) },
                                                )
                                            }

                                            else -> Unit
                                        }
                                    }
                                }
                            }
                        }

                        viewModel.errorMessage?.let { msg ->
                            Spacer(modifier = Modifier.height(14.dp))
                            Text(
                                text = msg,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        viewModel.successMessage?.let { msg ->
                            Spacer(modifier = Modifier.height(14.dp))
                            Text(
                                text = msg,
                                color = Color(0xFF2E7D32),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        LiquidButton(
                            onClick = {
                                when {
                                    isAccountLogin -> viewModel.login()
                                    viewModel.registerStep == 1 -> viewModel.registerStep1Next()
                                    viewModel.registerStep == 2 -> viewModel.registerStep2Next()
                                    viewModel.registerStep == 3 -> viewModel.registerStep3Next()
                                    viewModel.registerStep == 4 -> viewModel.registerComplete()
                                    else -> Unit
                                }
                            },
                            enabled = !viewModel.isLoading,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp),
                            shape = RoundedCornerShape(25.dp)
                        ) {
                            Text(
                                text = when {
                                    isAccountLogin -> "登录"
                                    viewModel.registerStep == 4 -> "立即注册"
                                    else -> "下一步"
                                },
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            CompositionLocalProvider(LocalMinimumInteractiveComponentEnforcement provides false) {
                                RadioButton(
                                    selected = viewModel.isAgreementChecked,
                                    onClick = { viewModel.onAgreementChange(!viewModel.isAgreementChecked) },
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = primaryBlue,
                                        unselectedColor = Color.LightGray
                                    ),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "我已阅读并同意《用户协议》和《隐私授权》",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF8AA0B8),
                                fontSize = 10.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(28.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.Top
                        ) {
                            SocialLoginChannel(
                                label = "短信",
                                onClick = onNavigateToSmsLogin
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Message,
                                    contentDescription = "短信登录",
                                    tint = primaryBlue,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            SocialLoginChannel(
                                label = "QQ",
                                circleBackground = Color.Transparent,
                                onClick = {
                                    launchThirdPartyAppOrToast(
                                        context,
                                        "com.tencent.mobileqq",
                                        "请安装QQ"
                                    )
                                }
                            ) {
                                SvgAssetImage(
                                    assetName = "QQ.svg",
                                    contentDescription = "QQ登录",
                                    modifier = Modifier.size(46.dp)
                                )
                            }
                            SocialLoginChannel(
                                label = "微信",
                                circleBackground = Color.Transparent,
                                onClick = {
                                    launchThirdPartyAppOrToast(
                                        context,
                                        "com.tencent.mm",
                                        "请安装微信"
                                    )
                                }
                            ) {
                                SvgAssetImage(
                                    assetName = "wechat.svg",
                                    contentDescription = "微信登录",
                                    modifier = Modifier.size(46.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RegisterOccupationPicker(
    selected: String,
    onSelect: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "选择职业",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(12.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(registerOccupationRowGap),
            modifier = Modifier
                .fillMaxWidth()
                .height(
                    registerOccupationCellHeight * registerOccupationVisibleRows +
                        registerOccupationRowGap * (registerOccupationVisibleRows - 1),
                ),
        ) {
            items(
                items = registerOccupationPickerOptions,
                key = { it },
            ) { opt ->
                val isSel = selected == opt
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(registerOccupationCellHeight)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (isSel) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
                            },
                        )
                        .clickable { onSelect(opt) },
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
                        text = opt,
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
}

@Composable
private fun SocialLoginChannel(
    label: String,
    onClick: () -> Unit,
    circleBackground: Color = Color(0xFFEAF4FF),
    content: @Composable () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(76.dp)
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(circleBackground),
            contentAlignment = Alignment.Center
        ) {
            content()
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF5E7891)
        )
    }
}

private fun launchThirdPartyAppOrToast(
    context: android.content.Context,
    packageName: String,
    notInstalledMessage: String
) {
    val intent = context.packageManager.getLaunchIntentForPackage(packageName)
    if (intent != null) {
        context.startActivity(intent)
    } else {
        Toast.makeText(context, notInstalledMessage, Toast.LENGTH_SHORT).show()
    }
}
