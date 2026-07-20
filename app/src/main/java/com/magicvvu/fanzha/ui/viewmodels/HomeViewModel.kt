package com.magicvvu.fanzha.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.magicvvu.fanzha.data.remote.ApiClient
import com.magicvvu.fanzha.domain.SafetyIndexCalculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

enum class UserRole(val displayName: String) {
    ELDERLY("老年用户"),
    STUDENT("学生用户"),
    GENERAL("普通用户")
}

data class PersonalizedSuggestion(
    val title: String,
    val description: String,
    val riskLevel: String
)

data class HomeUiState(
    val userRole: UserRole = UserRole.STUDENT,
    val suggestions: List<PersonalizedSuggestion> = emptyList(),
    /** 安全指数（整数）：用于档位判定、震动/播报等阈值逻辑。 */
    val safetyIndex: Int = 70,
    /** 安全指数（精确值，保留两位小数展示）；用于首页波纹与中心数字显示。 */
    val safetyIndexPrecise: Double = 70.0,
    /** 本周拦截电话次数（`intercept_call` 周聚合，与拦截记录页同源）；「…」加载中，「—」无用户或失败 */
    val phoneWeekInterceptDisplay: String = "…",
    /** 本周拦截短信次数（`intercept_sms` 周聚合） */
    val smsWeekInterceptDisplay: String = "…",
    /** 本周拦截可疑 APP 次数（`intercept_suspicious_app` 周聚合） */
    val appWeekInterceptDisplay: String = "…",
    /** 本周拦截剪切板次数（`intercept_clipboard` 周聚合） */
    val clipboardWeekInterceptDisplay: String = "…",
)

class HomeViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    /** 安全指数波纹：仅第一次进入首页时播放 0→目标 的填充动画，之后直接落在当前值并保留波纹 */
    private val _safetyIndexWaveIntroDone = MutableStateFlow(false)
    val safetyIndexWaveIntroDone: StateFlow<Boolean> = _safetyIndexWaveIntroDone.asStateFlow()

    fun markSafetyIndexWaveIntroDone() {
        _safetyIndexWaveIntroDone.value = true
    }

    /**
     * 手动设置安全指数（用于弹窗“我已知晓”等交互立刻消除高危态）。
     *
     * 注意：这不会覆盖后续从服务端数据重算的结果；下次刷新仍会以模型计算为准。
     */
    fun setSafetyIndex(index: Int) {
        val clamped = index.coerceIn(0, 100)
        _uiState.value = _uiState.value.copy(
            safetyIndex = clamped,
            safetyIndexPrecise = clamped.toDouble(),
        )
    }

    init {
        // 初始加载时生成建议
        updateUserRole(UserRole.STUDENT)
    }

    fun updateUserRole(role: UserRole) {
        val suggestions = generateSuggestions(role)
        _uiState.value = _uiState.value.copy(
            userRole = role,
            suggestions = suggestions,
        )
    }

    /**
     * 拉取本周四类拦截次数、用户资料（年龄/性别/职业大类）与问卷得分，并按《安全指数数学模型》计算安全指数。
     *
     * 任意单接口失败不应阻断计算：用已有数据/缺省值继续算，避免 UI 长期停留旧值。
     */
    fun refreshCallAndSmsInterceptWeek(currentUserId: Long?) {
        if (currentUserId == null) {
            val precise = SafetyIndexCalculator.computePrecise(
                callCount = 0,
                smsCount = 0,
                suspiciousAppCount = 0,
                clipboardCount = 0,
                age = null,
                gender = null,
                occupationCategoryName = null,
                quizRawScore = null,
                referenceDate = LocalDate.now(),
            )
            val idx = SafetyIndexCalculator.toIntIndex(precise)
            _uiState.value = _uiState.value.copy(
                phoneWeekInterceptDisplay = "—",
                smsWeekInterceptDisplay = "—",
                appWeekInterceptDisplay = "—",
                clipboardWeekInterceptDisplay = "—",
                safetyIndex = idx,
                safetyIndexPrecise = precise,
            )
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                phoneWeekInterceptDisplay = "…",
                smsWeekInterceptDisplay = "…",
                appWeekInterceptDisplay = "…",
                clipboardWeekInterceptDisplay = "…",
            )
            supervisorScope {
                val dashboardDef = async(Dispatchers.IO) {
                    runCatching { ApiClient.interceptWeeklyDashboardApi.weeklyDashboard(currentUserId) }.getOrNull()
                }
                val profileDef = async(Dispatchers.IO) {
                    runCatching { ApiClient.userProfileApi.getProfile(currentUserId) }.getOrNull()
                }
                val quizDef = async(Dispatchers.IO) {
                    runCatching { ApiClient.quizScoreApi.getLatestScore(currentUserId) }.getOrNull()
                }
                val scoreDef = async(Dispatchers.IO) {
                    runCatching { ApiClient.userSecurityScoreApi.getSecurityScore(currentUserId) }.getOrNull()
                }

                val dashResp = dashboardDef.await()
                val profileResp = profileDef.await()
                val quizResp = quizDef.await()
                val scoreResp = scoreDef.await()

                val dashDto = dashResp?.let { r ->
                    val b = r.body()
                    if (r.isSuccessful && b?.success == true) b.data else null
                }
                val profileDto = profileResp?.let { r ->
                    val b = r.body()
                    if (r.isSuccessful && b?.success == true) b.data else null
                }
                val quizDto = quizResp?.let { r ->
                    val b = r.body()
                    if (r.isSuccessful && b?.success == true) b.data else null
                }

                val storedScore = scoreResp?.let { r ->
                    val b = r.body()
                    if (r.isSuccessful && b?.success == true) b.data?.securityScore else null
                }

                // 前端轻量化：优先使用服务端已落库的 user_security_score；无记录时才回退到本地计算（兼容旧后端/冷启动）。
                val idx = storedScore?.coerceIn(0, 100)
                    ?: run {
                        val precise = SafetyIndexCalculator.computePrecise(
                            callCount = dashDto?.call?.weekTotal ?: 0,
                            smsCount = dashDto?.sms?.weekTotal ?: 0,
                            suspiciousAppCount = dashDto?.suspiciousApp?.weekTotal ?: 0,
                            clipboardCount = dashDto?.clipboard?.weekTotal ?: 0,
                            age = profileDto?.age,
                            gender = profileDto?.gender,
                            occupationCategoryName = profileDto?.categoryName,
                            quizRawScore = quizDto?.latestScore,
                            referenceDate = LocalDate.now(),
                        )
                        SafetyIndexCalculator.toIntIndex(precise)
                    }

                _uiState.value = _uiState.value.copy(
                    phoneWeekInterceptDisplay = dashDto?.call?.weekTotal?.toString() ?: "—",
                    smsWeekInterceptDisplay = dashDto?.sms?.weekTotal?.toString() ?: "—",
                    appWeekInterceptDisplay = dashDto?.suspiciousApp?.weekTotal?.toString() ?: "—",
                    clipboardWeekInterceptDisplay = dashDto?.clipboard?.weekTotal?.toString() ?: "—",
                    safetyIndex = idx,
                    safetyIndexPrecise = idx.toDouble(),
                )
            }
        }
    }

    private fun generateSuggestions(role: UserRole): List<PersonalizedSuggestion> {
        return listOf(
            PersonalizedSuggestion(
                "开启短信读取权限",
                "为确保我们能及时为您拦截诈骗短信，请前往系统设置开启短信读取权限。",
                "高优先级"
            ),
            PersonalizedSuggestion(
                "阅读最新诈骗案例",
                "近期“兼职刷单”诈骗频发，建议前往“学习”页面了解相关案例，提高防范意识。",
                "建议操作"
            ),
            PersonalizedSuggestion(
                "完成防诈能力测试",
                "您还未完成本周的防诈能力测试，快去“学习”页面测一测您的防骗指数吧！",
                "建议操作"
            ),
            PersonalizedSuggestion(
                "添加家庭成员",
                "前往“家庭”页面添加您的家人，共同开启家庭安全防护网络。",
                "日常建议"
            )
        )
    }
}
