package com.magicvvu.fanzha

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.magicvvu.fanzha.data.local.AuthPreferences
import androidx.core.content.ContextCompat
import com.magicvvu.fanzha.ui.components.BubbleNavigationBar
import com.magicvvu.fanzha.ui.components.BubbleNavItem
import com.magicvvu.fanzha.security.SecurityCallLogMonitor
import com.magicvvu.fanzha.security.SecurityClipboardMonitor
import com.magicvvu.fanzha.security.SecurityInterceptSync
import com.magicvvu.fanzha.security.SecurityRealtimeIngestCoordinator
import com.magicvvu.fanzha.security.SecuritySmsMonitor
import com.magicvvu.fanzha.notifications.RiskNotifications
import com.magicvvu.fanzha.sms.SmsFraudResultBridge
import com.magicvvu.fanzha.ui.screens.AiChatScreen
import com.magicvvu.fanzha.ui.screens.FamilyHubScreen
import com.magicvvu.fanzha.ui.screens.FamilyScreen
import com.magicvvu.fanzha.ui.screens.Guardian
import com.magicvvu.fanzha.ui.screens.HomeScreen
import com.magicvvu.fanzha.ui.screens.IdentificationScreen
import com.magicvvu.fanzha.ui.screens.InterceptChannelHistoryScreen
import com.magicvvu.fanzha.ui.screens.InterceptRecordsScreen
import com.magicvvu.fanzha.ui.screens.InterceptSection
import com.magicvvu.fanzha.ui.screens.LearningScreen
import com.magicvvu.fanzha.ui.screens.FraudReportScreen
import com.magicvvu.fanzha.ui.screens.ForgotPasswordScreen
import com.magicvvu.fanzha.ui.screens.LoginScreen
import com.magicvvu.fanzha.ui.screens.ProfileScreen
import com.magicvvu.fanzha.ui.screens.SmsVerificationScreen
import com.magicvvu.fanzha.ui.theme.ElderlyModePreferenceController
import com.magicvvu.fanzha.ui.theme.FontSizePreferenceController
import com.magicvvu.fanzha.ui.theme.LocalElderlyModePreferenceController
import com.magicvvu.fanzha.ui.theme.LocalFontSizePreferenceController
import com.magicvvu.fanzha.ui.theme.LocalThemePreferenceController
import com.magicvvu.fanzha.ui.theme.MyApplicationTheme
import com.magicvvu.fanzha.ui.theme.ThemePreferenceController
import kotlinx.coroutines.launch


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val themeController = remember {
                ThemePreferenceController(applicationContext)
            }
            val elderlyModeController = remember {
                ElderlyModePreferenceController(applicationContext)
            }
            val fontSizeController = remember {
                FontSizePreferenceController(applicationContext)
            }
            CompositionLocalProvider(
                LocalThemePreferenceController provides themeController,
                LocalElderlyModePreferenceController provides elderlyModeController,
                LocalFontSizePreferenceController provides fontSizeController,
            ) {
                MyApplicationTheme(
                    darkTheme = themeController.isDarkTheme,
                    elderlyModeController = elderlyModeController,
                    fontSizeController = fontSizeController,
                ) {
                    MainApp()
                }
            }
        }
    }
}


enum class Screen(val title: String, val icon: ImageVector) {
    Home("首页", Icons.Default.Home),
    Learning("学习", Icons.Default.MenuBook),
    Family("家庭", Icons.Default.Face),
    Mine("我的", Icons.Default.Person),
    Identification("识别", Icons.Default.Search)
}

private enum class LoginDest { Login, SmsLogin, ForgotPassword }

/** 「我的」主界面与子页切换（推入：新页从右入、旧页向左出；返回相反）。 */
private sealed class MineSubDestination {
    data object Main : MineSubDestination()
    data object PersonalInfo : MineSubDestination()
    data object ReportProgressList : MineSubDestination()
    data class ReportProgressDetail(val reportId: String) : MineSubDestination()
    data object EditProfile : MineSubDestination()
    data object Favorites : MineSubDestination()
    data object History : MineSubDestination()
    data object Notifications : MineSubDestination()
    data object Verification : MineSubDestination()
    data object SecurityCenter : MineSubDestination()
    data object ElderlyMode : MineSubDestination()
    data object Settings : MineSubDestination()
    data object AboutUs : MineSubDestination()
}

/** 首页：主界面与拦截记录 / 识别页切换（与「我的」子页相同的推入转场）。 */
/**
 * 从拨号/系统桌面返回应用时再次同步通话记录（系统写入 CallLog 略晚于挂断；此前仅首登跑一次会漏掉刚打的电话）。
 */
@Composable
private fun SecurityInterceptResumeSync() {
    val context = LocalContext.current
    val activity = context as? ComponentActivity ?: return
    val scope = rememberCoroutineScope()
    DisposableEffect(activity) {
        val obs = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_RESUME) return@LifecycleEventObserver
            if (AuthPreferences.getUserId(context) == null) return@LifecycleEventObserver
            val prefs = context.getSharedPreferences("security_collection", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("consent_granted", false)) return@LifecycleEventObserver
            scope.launch {
                // 间隔过短会与「登录后立即同步」叠层，且易在系统抖动 DATE 时放大重复上报；45s 仍足够覆盖挂断后补写通话记录。
                SecurityInterceptSync.syncIfConsentLoggedIn(context, minIntervalMs = 45_000L)
            }
        }
        activity.lifecycle.addObserver(obs)
        onDispose { activity.lifecycle.removeObserver(obs) }
    }
}

private sealed class HomeSubDestination {
    data object Main : HomeSubDestination()
    data class Intercept(val section: InterceptSection) : HomeSubDestination()
    /** 某通道拦截「全部记录」页，栈深于 [Intercept]。 */
    data class InterceptHistory(val section: InterceptSection) : HomeSubDestination()
    data class Identify(val tabIndex: Int) : HomeSubDestination()
    /** 一键举报叠在识别页之上，返回时恢复对应检测 Tab。 */
    data class FraudReport(val returnTabIndex: Int) : HomeSubDestination()
}

private fun HomeSubDestination.stackDepth(): Int = when (this) {
    HomeSubDestination.Main -> 0
    is HomeSubDestination.Intercept -> 1
    is HomeSubDestination.InterceptHistory -> 2
    is HomeSubDestination.Identify -> 1
    is HomeSubDestination.FraudReport -> 2
}

private fun MineSubDestination.stackDepth(): Int = when (this) {
    MineSubDestination.Main -> 0
    is MineSubDestination.ReportProgressDetail -> 2
    else -> 1
}

/** 家庭 Tab：成员列表与本人/其他成员的通道拦截子页。 */
private sealed class FamilySubDestination {
    data object Main : FamilySubDestination()
    data class SelfInterceptHistory(val section: InterceptSection) : FamilySubDestination()
    data class MemberInterceptHistory(val guardian: Guardian, val section: InterceptSection) :
        FamilySubDestination()
}

private fun FamilySubDestination.stackDepth(): Int = when (this) {
    FamilySubDestination.Main -> 0
    is FamilySubDestination.SelfInterceptHistory,
    is FamilySubDestination.MemberInterceptHistory,
    -> 1
}

@Composable
fun MainApp() {
    val context = LocalContext.current
    val appContext = context.applicationContext
    // 与 LoginScreen 写入的 user_id 一致：进程重启后仍视为已登录，直至用户主动退出。
    var isLoggedIn by remember {
        mutableStateOf(AuthPreferences.getUserId(appContext) != null)
    }
    var loginDest by remember { mutableStateOf(LoginDest.Login) }

    Box(modifier = Modifier.fillMaxSize()) {
        if (isLoggedIn) {
            MainContent(
                onLogout = {
                    AuthPreferences.clear(context.applicationContext)
                    loginDest = LoginDest.Login
                    isLoggedIn = false
                },
            )
        } else {
            AnimatedContent(
                targetState = loginDest,
                transitionSpec = {
                    val goForward = targetState != LoginDest.Login
                    val enter = if (goForward) {
                        slideInHorizontally(
                            animationSpec = tween(360, easing = FastOutSlowInEasing)
                        ) { it } + fadeIn(tween(280, easing = FastOutSlowInEasing))
                    } else {
                        slideInHorizontally(
                            animationSpec = tween(360, easing = FastOutSlowInEasing)
                        ) { -it } + fadeIn(tween(280, easing = FastOutSlowInEasing))
                    }
                    val exit = if (goForward) {
                        slideOutHorizontally(
                            animationSpec = tween(300, easing = FastOutSlowInEasing)
                        ) { -it / 3 } + fadeOut(tween(220, easing = FastOutSlowInEasing))
                    } else {
                        slideOutHorizontally(
                            animationSpec = tween(300, easing = FastOutSlowInEasing)
                        ) { it / 3 } + fadeOut(tween(220, easing = FastOutSlowInEasing))
                    }
                    enter togetherWith exit
                },
                label = "loginNav"
            ) { dest ->
                when (dest) {
                    LoginDest.Login -> LoginScreen(
                        onLoginSuccess = { isLoggedIn = true },
                        onNavigateToSmsLogin = { loginDest = LoginDest.SmsLogin },
                        onNavigateToForgotPassword = { loginDest = LoginDest.ForgotPassword }
                    )
                    LoginDest.SmsLogin -> SmsVerificationScreen(
                        onBack = { loginDest = LoginDest.Login },
                        onLoginSuccess = {
                            loginDest = LoginDest.Login
                            isLoggedIn = true
                        }
                    )
                    LoginDest.ForgotPassword -> ForgotPasswordScreen(
                        onBack = { loginDest = LoginDest.Login },
                        onSuccess = { loginDest = LoginDest.Login }
                    )
                }
            }
        }
        StartupSecurityConsentGate(isLoggedIn = isLoggedIn)
        StartupRiskNotificationsScheduler()
        StartupSmsFraudListener()
    }
}

@Composable
private fun StartupSecurityConsentGate(isLoggedIn: Boolean) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("security_collection", Context.MODE_PRIVATE) }
    var consentGranted by remember { mutableStateOf(prefs.getBoolean("consent_granted", false)) }
    var showConsentDialog by remember { mutableStateOf(!consentGranted) }

    val requiredPermissions = remember {
        arrayOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_CALL_LOG,
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val callLogOk = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) ==
            PackageManager.PERMISSION_GRANTED
        val smsOk = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED
        val allGranted = requiredPermissions.all { permission ->
            result[permission] == true || ContextCompat.checkSelfPermission(
                context,
                permission,
            ) == PackageManager.PERMISSION_GRANTED
        }
        Toast.makeText(
            context,
            "权限结果：通话记录${if (callLogOk) "已授予" else "未授予"}，短信${if (smsOk) "已授予" else "未授予"}",
            Toast.LENGTH_LONG,
        ).show()
        if (allGranted) {
            prefs.edit().putBoolean("consent_granted", true).apply()
            consentGranted = true
            showConsentDialog = false
        } else {
            prefs.edit().putBoolean("consent_granted", false).apply()
            consentGranted = false
            showConsentDialog = false
            Toast.makeText(context, "未授予全部必要权限，无法读取通话记录做诈骗比对", Toast.LENGTH_SHORT).show()
        }
    }

    // 已同意且已登录：注册剪切板/短信/通话记录「仅变更时读」监听，再采集上报；登出/撤回时注销，避免无意义轮询。
    LaunchedEffect(consentGranted, isLoggedIn) {
        if (consentGranted && isLoggedIn && AuthPreferences.getUserId(context) != null) {
            SecurityClipboardMonitor.register(context)
            SecuritySmsMonitor.register(context)
            SecurityCallLogMonitor.register(context)
            // 实时监听：变更即触发增量上报（无定时扫描）
            SecurityRealtimeIngestCoordinator.start(context, this)
        } else {
            SecurityRealtimeIngestCoordinator.stop()
            SecurityClipboardMonitor.unregister(context)
            SecuritySmsMonitor.unregister(context)
            SecurityCallLogMonitor.unregister(context)
        }
        if (!consentGranted || !isLoggedIn) return@LaunchedEffect
        if (AuthPreferences.getUserId(context) == null) return@LaunchedEffect
        SecurityInterceptSync.syncIfConsentLoggedIn(context, minIntervalMs = 0L)
    }

    if (showConsentDialog) {
        AlertDialog(
            onDismissRequest = { showConsentDialog = false },
            title = { Text("安全数据访问授权") },
            text = {
                Text(
                    "为进行风险识别，需要在你同意后访问：接收短信、短信记录、通话记录、已安装应用列表。仅用于安全分析，你可在系统设置中随时撤回权限。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val allGranted = requiredPermissions.all { permission ->
                            ContextCompat.checkSelfPermission(
                                context,
                                permission,
                            ) == PackageManager.PERMISSION_GRANTED
                        }
                        if (allGranted) {
                            prefs.edit().putBoolean("consent_granted", true).apply()
                            consentGranted = true
                            showConsentDialog = false
                            Toast.makeText(
                                context,
                                "通话记录与短信权限已具备，登录后将自动比对诈骗号码库",
                                Toast.LENGTH_SHORT,
                            ).show()
                        } else {
                            launcher.launch(requiredPermissions)
                        }
                    },
                ) {
                    Text("同意并继续")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        prefs.edit().putBoolean("consent_granted", false).apply()
                        consentGranted = false
                        showConsentDialog = false
                    },
                ) {
                    Text("暂不同意")
                }
            },
        )
    }
}

@Composable
private fun StartupRiskNotificationsScheduler() {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            RiskNotifications.scheduleRiskNotifications(context)
        }
    }

    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val ok = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (ok) {
                RiskNotifications.scheduleRiskNotifications(context)
            } else {
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            RiskNotifications.scheduleRiskNotifications(context)
        }
    }
}

@Composable
private fun StartupSmsFraudListener() {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        SmsFraudResultBridge.startListening(context)
    }
}

@Composable
fun MainContent(
    onLogout: () -> Unit = {},
) {
    val context = LocalContext.current
    // 须每次读取 SharedPreferences：登录写入 user_id 后同一进程内会重组到此；remember 会卡住为 null。
    val currentUserId = AuthPreferences.getUserId(context)

    var currentScreen by remember { mutableStateOf(Screen.Home) }
    var showReport by remember { mutableStateOf(false) }
    var homeSubDestination by remember { mutableStateOf<HomeSubDestination>(HomeSubDestination.Main) }
    var showAiSheet by remember { mutableStateOf(false) }
    var mineSubDestination by remember { mutableStateOf<MineSubDestination>(MineSubDestination.Main) }
    /** 家庭 Tab：先展示创建/加入入口，完成任一操作后进入原家庭页（进程内记忆）。 */
    var familyEntryComplete by rememberSaveable { mutableStateOf(false) }
    var familySubDestination by remember { mutableStateOf<FamilySubDestination>(FamilySubDestination.Main) }

    val bottomBarScreens = listOf(Screen.Home, Screen.Learning, Screen.Family, Screen.Mine)

    LaunchedEffect(currentScreen) {
        if (currentScreen != Screen.Mine) {
            mineSubDestination = MineSubDestination.Main
        }
        if (currentScreen != Screen.Home) {
            homeSubDestination = HomeSubDestination.Main
            showReport = false
        }
        if (currentScreen != Screen.Family) {
            familySubDestination = FamilySubDestination.Main
        }
    }

    LaunchedEffect(familyEntryComplete) {
        if (!familyEntryComplete) {
            familySubDestination = FamilySubDestination.Main
        }
    }

    // Map Screen enum to BubbleNavItem
    val navItems = bottomBarScreens.map { screen ->
        // Use theme colors for navigation items
        val color = when (screen) {
            Screen.Home -> MaterialTheme.colorScheme.primary
            Screen.Learning -> MaterialTheme.colorScheme.secondary
            Screen.Identification -> MaterialTheme.colorScheme.secondary
            Screen.Family -> MaterialTheme.colorScheme.tertiary
            Screen.Mine -> MaterialTheme.colorScheme.primary
        }
        BubbleNavItem(
            title = screen.title,
            icon = screen.icon,
            color = color
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        SecurityInterceptResumeSync()
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                // 首页子栈、安全分析报告等一律保留底栏，与其它 Tab 一致。
                BubbleNavigationBar(
                    items = navItems,
                    selectedItemIndex = bottomBarScreens.indexOf(currentScreen),
                    onItemSelected = { index ->
                        if (index >= 0 && index < bottomBarScreens.size) {
                            val target = bottomBarScreens[index]
                            if (target == Screen.Home && currentScreen == Screen.Home) {
                                homeSubDestination = HomeSubDestination.Main
                                showReport = false
                            }
                            if (target == Screen.Family && currentScreen == Screen.Family) {
                                familySubDestination = FamilySubDestination.Main
                            }
                            currentScreen = target
                        }
                    },
                )
            }
        ) { innerPadding ->
            Box(modifier = Modifier.padding(innerPadding)) {
                AnimatedContent(
                    targetState = showReport,
                    modifier = Modifier.fillMaxSize(),
                    transitionSpec = {
                        val duration = 300
                        val easing = FastOutSlowInEasing
                        if (targetState) {
                            (slideInHorizontally(
                                animationSpec = tween(duration, easing = easing)
                            ) { it } + fadeIn(tween(duration, easing = easing))) togetherWith
                                (slideOutHorizontally(
                                    animationSpec = tween(duration, easing = easing)
                                ) { -it } + fadeOut(tween(duration, easing = easing)))
                        } else {
                            (slideInHorizontally(
                                animationSpec = tween(duration, easing = easing)
                            ) { -it } + fadeIn(tween(duration, easing = easing))) togetherWith
                                (slideOutHorizontally(
                                    animationSpec = tween(duration, easing = easing)
                                ) { it } + fadeOut(tween(duration, easing = easing)))
                        }
                    },
                    label = "securityReportOverlay",
                ) { reportVisible ->
                    if (reportVisible) {
                        com.magicvvu.fanzha.ui.screens.ReportScreen(
                            onBack = { showReport = false },
                            currentUserId = currentUserId,
                        )
                    } else {
                        AnimatedContent(
                            targetState = currentScreen,
                            modifier = Modifier.fillMaxSize(),
                            transitionSpec = {
                                val fromIdx = bottomBarScreens.indexOf(initialState)
                                val toIdx = bottomBarScreens.indexOf(targetState)
                                val forward = toIdx > fromIdx
                                val duration = 300
                                val easing = FastOutSlowInEasing
                                if (forward) {
                                    (slideInHorizontally(
                                        animationSpec = tween(duration, easing = easing)
                                    ) { it } + fadeIn(tween(duration, easing = easing))) togetherWith
                                        (slideOutHorizontally(
                                            animationSpec = tween(duration, easing = easing)
                                        ) { -it } + fadeOut(tween(duration, easing = easing)))
                                } else {
                                    (slideInHorizontally(
                                        animationSpec = tween(duration, easing = easing)
                                    ) { -it } + fadeIn(tween(duration, easing = easing))) togetherWith
                                        (slideOutHorizontally(
                                            animationSpec = tween(duration, easing = easing)
                                        ) { it } + fadeOut(tween(duration, easing = easing)))
                                }
                            },
                            label = "mainTabs",
                        ) { screen ->
                        when (screen) {
                            Screen.Home -> {
                                AnimatedContent(
                                    targetState = homeSubDestination,
                                    modifier = Modifier.fillMaxSize(),
                                    transitionSpec = {
                                        val duration = 300
                                        val easing = FastOutSlowInEasing
                                        val fromD = initialState.stackDepth()
                                        val toD = targetState.stackDepth()
                                        val push = toD > fromD
                                        val pop = toD < fromD
                                        when {
                                            push -> {
                                                (slideInHorizontally(
                                                    animationSpec = tween(duration, easing = easing)
                                                ) { it } + fadeIn(tween(duration, easing = easing))) togetherWith
                                                    (slideOutHorizontally(
                                                        animationSpec = tween(duration, easing = easing)
                                                    ) { -it } + fadeOut(tween(duration, easing = easing)))
                                            }
                                            pop -> {
                                                (slideInHorizontally(
                                                    animationSpec = tween(duration, easing = easing)
                                                ) { -it } + fadeIn(tween(duration, easing = easing))) togetherWith
                                                    (slideOutHorizontally(
                                                        animationSpec = tween(duration, easing = easing)
                                                    ) { it } + fadeOut(tween(duration, easing = easing)))
                                            }
                                            else -> {
                                                (slideInHorizontally(
                                                    animationSpec = tween(duration, easing = easing)
                                                ) { it } + fadeIn(tween(duration, easing = easing))) togetherWith
                                                    (slideOutHorizontally(
                                                        animationSpec = tween(duration, easing = easing)
                                                    ) { -it } + fadeOut(tween(duration, easing = easing)))
                                            }
                                        }
                                    },
                                    label = "homeSubNav"
                                ) { dest ->
                                    when (dest) {
                                        HomeSubDestination.Main -> HomeScreen(
                                            currentUserId = currentUserId,
                                            onNavigateToIdentify = { tabIndex ->
                                                homeSubDestination = HomeSubDestination.Identify(tabIndex)
                                            },
                                            onNavigateToReport = {
                                                showReport = true
                                            },
                                            onNavigateToInterceptRecord = { section ->
                                                homeSubDestination = HomeSubDestination.Intercept(section)
                                            },
                                            onShowAiSheet = { showAiSheet = true }
                                        )
                                        is HomeSubDestination.Intercept ->
                                            InterceptRecordsScreen(
                                                initialExpandedSection = dest.section,
                                                currentUserId = currentUserId,
                                                onBack = { homeSubDestination = HomeSubDestination.Main },
                                                onOpenChannelHistory = { section ->
                                                    homeSubDestination =
                                                        HomeSubDestination.InterceptHistory(section)
                                                },
                                            )
                                        is HomeSubDestination.InterceptHistory ->
                                            InterceptChannelHistoryScreen(
                                                section = dest.section,
                                                currentUserId = currentUserId,
                                                onBack = {
                                                    homeSubDestination =
                                                        HomeSubDestination.Intercept(dest.section)
                                                },
                                            )
                                        is HomeSubDestination.Identify ->
                                            IdentificationScreen(
                                                initialTab = dest.tabIndex,
                                                onBack = { homeSubDestination = HomeSubDestination.Main },
                                                onReport = {
                                                    homeSubDestination =
                                                        HomeSubDestination.FraudReport(dest.tabIndex)
                                                }
                                            )
                                        is HomeSubDestination.FraudReport ->
                                            FraudReportScreen(
                                                onBack = {
                                                    homeSubDestination =
                                                        HomeSubDestination.Identify(dest.returnTabIndex)
                                                }
                                            )
                                    }
                                }
                            }
                            Screen.Learning -> LearningScreen()
                            Screen.Family -> {
                                AnimatedContent(
                                    targetState = familyEntryComplete,
                                    modifier = Modifier.fillMaxSize(),
                                    transitionSpec = {
                                        fadeIn(tween(280, easing = FastOutSlowInEasing)) togetherWith
                                            fadeOut(tween(280, easing = FastOutSlowInEasing))
                                    },
                                    label = "familyEntry",
                                ) { entered ->
                                    if (entered) {
                                        AnimatedContent(
                                            targetState = familySubDestination,
                                            modifier = Modifier.fillMaxSize(),
                                            transitionSpec = {
                                                val duration = 300
                                                val easing = FastOutSlowInEasing
                                                val fromD = initialState.stackDepth()
                                                val toD = targetState.stackDepth()
                                                val push = toD > fromD
                                                val pop = toD < fromD
                                                when {
                                                    push -> {
                                                        (slideInHorizontally(
                                                            animationSpec = tween(duration, easing = easing)
                                                        ) { it } + fadeIn(tween(duration, easing = easing))) togetherWith
                                                            (slideOutHorizontally(
                                                                animationSpec = tween(duration, easing = easing)
                                                            ) { -it } + fadeOut(tween(duration, easing = easing)))
                                                    }
                                                    pop -> {
                                                        (slideInHorizontally(
                                                            animationSpec = tween(duration, easing = easing)
                                                        ) { -it } + fadeIn(tween(duration, easing = easing))) togetherWith
                                                            (slideOutHorizontally(
                                                                animationSpec = tween(duration, easing = easing)
                                                            ) { it } + fadeOut(tween(duration, easing = easing)))
                                                    }
                                                    else -> {
                                                        (slideInHorizontally(
                                                            animationSpec = tween(duration, easing = easing)
                                                        ) { it } + fadeIn(tween(duration, easing = easing))) togetherWith
                                                            (slideOutHorizontally(
                                                                animationSpec = tween(duration, easing = easing)
                                                            ) { -it } + fadeOut(tween(duration, easing = easing)))
                                                    }
                                                }
                                            },
                                            label = "familySubNav",
                                        ) { dest ->
                                            when (dest) {
                                                FamilySubDestination.Main -> FamilyScreen(
                                                    currentUserId = currentUserId,
                                                    onDissolveFamily = { familyEntryComplete = false },
                                                    onOpenSelfInterceptChannel = { section ->
                                                        familySubDestination =
                                                            FamilySubDestination.SelfInterceptHistory(section)
                                                    },
                                                    onOpenMemberInterceptChannel = { guardian, section ->
                                                        familySubDestination =
                                                            FamilySubDestination.MemberInterceptHistory(
                                                                guardian,
                                                                section,
                                                            )
                                                    },
                                                )
                                                is FamilySubDestination.SelfInterceptHistory ->
                                                    InterceptChannelHistoryScreen(
                                                        section = dest.section,
                                                        currentUserId = currentUserId,
                                                        onBack = {
                                                            familySubDestination = FamilySubDestination.Main
                                                        },
                                                    )
                                                is FamilySubDestination.MemberInterceptHistory ->
                                                    InterceptChannelHistoryScreen(
                                                        section = dest.section,
                                                        currentUserId = currentUserId,
                                                        onBack = {
                                                            familySubDestination = FamilySubDestination.Main
                                                        },
                                                        suppressHistoryFetch = true,
                                                    )
                                            }
                                        }
                                    } else {
                                        FamilyHubScreen(
                                            onCreateFamily = { familyEntryComplete = true },
                                            onJoinFamilySuccess = { familyEntryComplete = true },
                                        )
                                    }
                                }
                            }
                            Screen.Mine -> {
                                        AnimatedContent(
                                            targetState = mineSubDestination,
                                            modifier = Modifier.fillMaxSize(),
                                            transitionSpec = {
                                                val duration = 300
                                                val easing = FastOutSlowInEasing
                                                val fromD = initialState.stackDepth()
                                                val toD = targetState.stackDepth()
                                                val push = toD > fromD
                                                val pop = toD < fromD
                                                when {
                                                    push -> {
                                                        (slideInHorizontally(
                                                            animationSpec = tween(duration, easing = easing)
                                                        ) { it } + fadeIn(tween(duration, easing = easing))) togetherWith
                                                            (slideOutHorizontally(
                                                                animationSpec = tween(duration, easing = easing)
                                                            ) { -it } + fadeOut(tween(duration, easing = easing)))
                                                    }
                                                    pop -> {
                                                        (slideInHorizontally(
                                                            animationSpec = tween(duration, easing = easing)
                                                        ) { -it } + fadeIn(tween(duration, easing = easing))) togetherWith
                                                            (slideOutHorizontally(
                                                                animationSpec = tween(duration, easing = easing)
                                                            ) { it } + fadeOut(tween(duration, easing = easing)))
                                                    }
                                                    else -> {
                                                        (slideInHorizontally(
                                                            animationSpec = tween(duration, easing = easing)
                                                        ) { it } + fadeIn(tween(duration, easing = easing))) togetherWith
                                                            (slideOutHorizontally(
                                                                animationSpec = tween(duration, easing = easing)
                                                            ) { -it } + fadeOut(tween(duration, easing = easing)))
                                                    }
                                                }
                                            },
                                            label = "mineSubNav"
                                        ) { dest ->
                                            when (dest) {
                                                MineSubDestination.Main -> ProfileScreen(
                                                    sessionUserId = currentUserId,
                                                    onNavigateToPersonalInfo = {
                                                        mineSubDestination = MineSubDestination.PersonalInfo
                                                    },
                                                    onNavigateToReportProgress = {
                                                        mineSubDestination = MineSubDestination.ReportProgressList
                                                    },
                                                    onNavigateToElderlyMode = {
                                                        mineSubDestination = MineSubDestination.ElderlyMode
                                                    },
                                                    onNavigateToEditProfile = {
                                                        mineSubDestination = MineSubDestination.EditProfile
                                                    },
                                                    onNavigateToFavorites = {
                                                        mineSubDestination = MineSubDestination.Favorites
                                                    },
                                                    onNavigateToHistory = {
                                                        mineSubDestination = MineSubDestination.History
                                                    },
                                                    onNavigateToNotifications = {
                                                        mineSubDestination = MineSubDestination.Notifications
                                                    },
                                                    onNavigateToVerification = {
                                                        mineSubDestination = MineSubDestination.Verification
                                                    },
                                                    onNavigateToSecurityCenter = {
                                                        mineSubDestination = MineSubDestination.SecurityCenter
                                                    },
                                                    onNavigateToSettings = {
                                                        mineSubDestination = MineSubDestination.Settings
                                                    },
                                                    onNavigateToAboutUs = {
                                                        mineSubDestination = MineSubDestination.AboutUs
                                                    },
                                                    onLogout = onLogout
                                                )
                                                MineSubDestination.PersonalInfo ->
                                                    com.magicvvu.fanzha.ui.screens.PersonalInfoScreen(
                                                        sessionUserId = currentUserId,
                                                        onBack = { mineSubDestination = MineSubDestination.Main },
                                                    )
                                                MineSubDestination.ReportProgressList ->
                                                    com.magicvvu.fanzha.ui.screens.ReportProgressListScreen(
                                                        onBack = { mineSubDestination = MineSubDestination.Main },
                                                        onNavigateToDetail = { id ->
                                                            mineSubDestination =
                                                                MineSubDestination.ReportProgressDetail(id)
                                                        }
                                                    )
                                                is MineSubDestination.ReportProgressDetail ->
                                                    com.magicvvu.fanzha.ui.screens.ReportProgressDetailScreen(
                                                        reportId = dest.reportId,
                                                        onBack = {
                                                            mineSubDestination =
                                                                MineSubDestination.ReportProgressList
                                                        }
                                                    )
                                                MineSubDestination.EditProfile ->
                                                    com.magicvvu.fanzha.ui.screens.EditProfileScreen(
                                                        sessionUserId = currentUserId,
                                                        onBack = { mineSubDestination = MineSubDestination.Main },
                                                    )
                                                MineSubDestination.Favorites ->
                                                    com.magicvvu.fanzha.ui.screens.FavoritesScreen {
                                                        mineSubDestination = MineSubDestination.Main
                                                    }
                                                MineSubDestination.History ->
                                                    com.magicvvu.fanzha.ui.screens.HistoryScreen {
                                                        mineSubDestination = MineSubDestination.Main
                                                    }
                                                MineSubDestination.Notifications ->
                                                    com.magicvvu.fanzha.ui.screens.NotificationsScreen {
                                                        mineSubDestination = MineSubDestination.Main
                                                    }
                                                MineSubDestination.Verification ->
                                                    com.magicvvu.fanzha.ui.screens.VerificationScreen {
                                                        mineSubDestination = MineSubDestination.Main
                                                    }
                                                MineSubDestination.SecurityCenter ->
                                                    com.magicvvu.fanzha.ui.screens.SecurityCenterScreen {
                                                        mineSubDestination = MineSubDestination.Main
                                                    }
                                                MineSubDestination.ElderlyMode ->
                                                    com.magicvvu.fanzha.ui.screens.ElderlyModeScreen {
                                                        mineSubDestination = MineSubDestination.Main
                                                    }
                                                MineSubDestination.Settings ->
                                                    com.magicvvu.fanzha.ui.screens.SettingsScreen {
                                                        mineSubDestination = MineSubDestination.Main
                                                    }
                                                MineSubDestination.AboutUs ->
                                                    com.magicvvu.fanzha.ui.screens.AboutUsScreen {
                                                        mineSubDestination = MineSubDestination.Main
                                                    }
                                            }
                                        }
                                    }
                            Screen.Identification -> Box(Modifier.fillMaxSize())
                        }
                    }
                    }
                }
            }
        }

        // AI Assistant Sheet Overlay
        AnimatedVisibility(
            visible = showAiSheet,
            enter = fadeIn(animationSpec = tween(300)),
            exit = fadeOut(animationSpec = tween(300))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable { showAiSheet = false }
            )
        }

        AnimatedVisibility(
            visible = showAiSheet,
            enter = slideInVertically(
                initialOffsetY = { it },
                animationSpec = tween(300)
            ),
            exit = slideOutVertically(
                targetOffsetY = { it },
                animationSpec = tween(300)
            ),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.75f)
                    .clickable(enabled = false) {} // Prevent click propagation
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                AiChatScreen()
            }
        }
    }
}
