package com.magicvvu.fanzha.ui.viewmodels

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.magicvvu.fanzha.data.model.ReportInterceptOverviewRequest
import com.magicvvu.fanzha.data.model.ReportRiskBehaviorRequest
import com.magicvvu.fanzha.data.model.ReportUserProfileRequest
import com.magicvvu.fanzha.data.remote.NetworkModule
import com.magicvvu.fanzha.data.repository.AssistantRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.random.Random

// --- Data Models ---

enum class ReportType {
    WEEKLY, MONTHLY
}

/** 与首页「诈骗拦截」四项一一对应，供报告页图表使用 */
data class RiskStat(
    val label: String,
    val count: Int,
    val color: Color,
)

data class TrendPoint(
    val date: String,
    val value: Int
)

data class RiskBehavior(
    val title: String,
    val description: String,
    val level: String, // High, Medium, Low
    val frequency: Int
)

data class DefenseSuggestion(
    val id: Int,
    var content: String,
    val isSystemGenerated: Boolean = true
)

private data class GeneratedReportData(
    val trendData: List<TrendPoint>,
    val riskBehaviors: List<RiskBehavior>,
    val defenseSuggestions: List<DefenseSuggestion>,
)

class ReportViewModel : ViewModel() {

    private val assistantRepository = AssistantRepository(
        api = NetworkModule.assistantApi,
        streamClient = NetworkModule.streamClient,
        baseUrl = NetworkModule.BASE_URL,
    )

    /** 递增序号，用于丢弃切换报告类型时已过期的协程结果 */
    private var reportRequestSeq = 0
    private var adviceRequestSeq = 0

    var currentReportType by mutableStateOf(ReportType.WEEKLY)
        private set

    var isLoading by mutableStateOf(false)
        private set

    var isGeneratingAdvice by mutableStateOf(false)
        private set

    var adviceSummary by mutableStateOf("")
        private set

    var adviceRiskLevel by mutableStateOf("low")
        private set

    var adviceReasons by mutableStateOf<List<String>>(emptyList())
        private set

    var adviceErrorMessage by mutableStateOf<String?>(null)
        private set

    /** 用于内容区切换动效（数据更新后递增） */
    var reportContentKey by mutableStateOf(0)
        private set

    var trendData by mutableStateOf<List<TrendPoint>>(emptyList())
        private set

    var riskBehaviors by mutableStateOf<List<RiskBehavior>>(emptyList())
        private set

    var defenseSuggestions = mutableStateListOf<DefenseSuggestion>()
        private set

    var showPushDialog by mutableStateOf(false)
    var pushSuccessMessage by mutableStateOf<String?>(null)

    init {
        generateReport(ReportType.WEEKLY)
    }

    fun generateReport(type: ReportType) {
        currentReportType = type
        val requestId = ++reportRequestSeq
        val firstLoad = trendData.isEmpty()
        if (firstLoad) isLoading = true

        // Mock Data Generation（主线程同步生成，切换周/月立即反映到界面）；拦截统计由首页同源数据驱动
        val generated = when (type) {
            ReportType.WEEKLY -> GeneratedReportData(
                trendData = (0..6).map {
                    TrendPoint(
                        LocalDate.now().minusDays(it.toLong()).format(DateTimeFormatter.ofPattern("MM-dd")),
                        Random.nextInt(2, 10)
                    )
                }.reversed(),
                riskBehaviors = listOf(
                    RiskBehavior("高频境外来电", "短时间内多次接听来自未知境外号码的来电", "High", 5),
                    RiskBehavior("短信包含不明链接", "接收到含有短链接的博彩/贷款类短信", "Medium", 12),
                    RiskBehavior("夜间异常登录", "凌晨2-4点检测到社交账号异地登录尝试", "High", 2)
                ),
                defenseSuggestions = listOf(
                    DefenseSuggestion(1, "正在结合当前风险行为生成个性化建议…"),
                    DefenseSuggestion(2, "生成完成后，这里会自动替换为 agent 返回的建议。"),
                ),
            )
            ReportType.MONTHLY -> GeneratedReportData(
                trendData = (0..29).step(5).map {
                    TrendPoint(
                        LocalDate.now().minusDays(it.toLong()).format(DateTimeFormatter.ofPattern("MM-dd")),
                        Random.nextInt(10, 40)
                    )
                }.reversed(),
                riskBehaviors = listOf(
                    RiskBehavior("长期浏览高危网站", "访问记录显示多次进入涉赌/涉黄网站", "High", 25),
                    RiskBehavior("安装非官方应用", "检测到安装未经过应用商店认证的APK", "Medium", 8)
                ),
                defenseSuggestions = listOf(
                    DefenseSuggestion(1, "正在结合当前风险行为生成个性化建议…"),
                    DefenseSuggestion(2, "生成完成后，这里会自动替换为 agent 返回的建议。"),
                ),
            )
        }

        if (requestId != reportRequestSeq) return

        trendData = generated.trendData
        riskBehaviors = generated.riskBehaviors
        defenseSuggestions.clear()
        defenseSuggestions.addAll(generated.defenseSuggestions)
        adviceSummary = ""
        adviceRiskLevel = "low"
        adviceReasons = emptyList()
        adviceErrorMessage = null
        reportContentKey = reportContentKey + 1
        isLoading = false
    }

    fun refreshPersonalizedSuggestions(
        homeUiState: HomeUiState,
        userProfile: UserProfile? = null,
    ) {
        val behaviorsSnapshot = riskBehaviors
        if (behaviorsSnapshot.isEmpty()) return

        val requestId = ++adviceRequestSeq
        isGeneratingAdvice = true
        adviceErrorMessage = null

        viewModelScope.launch {
            try {
                val response = assistantRepository.generateReportAdvice(
                    reportType = currentReportType.name.lowercase(),
                    riskBehaviors = behaviorsSnapshot.map {
                        ReportRiskBehaviorRequest(
                            title = it.title,
                            description = it.description,
                            level = it.level,
                            frequency = it.frequency,
                        )
                    },
                    interceptOverview = ReportInterceptOverviewRequest(
                        phoneWeekInterceptCount = parseInterceptWeekCount(homeUiState.phoneWeekInterceptDisplay),
                        smsWeekInterceptCount = parseInterceptWeekCount(homeUiState.smsWeekInterceptDisplay),
                        appWeekInterceptCount = parseInterceptWeekCount(homeUiState.appWeekInterceptDisplay),
                        clipboardWeekInterceptCount = parseInterceptWeekCount(homeUiState.clipboardWeekInterceptDisplay),
                    ),
                    userProfile = userProfile?.let {
                        ReportUserProfileRequest(
                            userId = it.id,
                            name = it.name,
                            age = it.age,
                            gender = it.gender,
                            occupation = it.occupation,
                        )
                    },
                )

                if (requestId != adviceRequestSeq) return@launch

                val mappedSuggestions = response.suggestions.mapIndexed { index, item ->
                    val content = item.content.ifBlank { item.title }
                    DefenseSuggestion(
                        id = item.id.takeIf { it > 0 } ?: (index + 1),
                        content = content,
                        isSystemGenerated = true,
                    )
                }

                if (mappedSuggestions.isNotEmpty()) {
                    defenseSuggestions.clear()
                    defenseSuggestions.addAll(mappedSuggestions)
                }
                adviceSummary = response.summary
                adviceRiskLevel = response.riskLevel
                adviceReasons = response.reason
            } catch (e: Exception) {
                if (requestId != adviceRequestSeq) return@launch
                adviceErrorMessage = e.message ?: "个性化建议生成失败"
            } finally {
                if (requestId == adviceRequestSeq) {
                    isGeneratingAdvice = false
                }
            }
        }
    }

    private fun parseInterceptWeekCount(display: String): Int {
        val t = display.trim()
        if (t == "…" || t == "—" || t.isEmpty()) return 0
        return t.toIntOrNull() ?: 0
    }

    fun updateSuggestion(id: Int, newContent: String) {
        val index = defenseSuggestions.indexOfFirst { it.id == id }
        if (index != -1) {
            defenseSuggestions[index] = defenseSuggestions[index].copy(content = newContent)
        }
    }

    fun addSuggestion(content: String) {
        val newId = (defenseSuggestions.maxOfOrNull { it.id } ?: 0) + 1
        defenseSuggestions.add(DefenseSuggestion(newId, content, false))
    }

    fun deleteSuggestion(id: Int) {
        defenseSuggestions.removeIf { it.id == id }
    }

    fun sendReport(email: String, isSystemPush: Boolean) {
        viewModelScope.launch {
            delay(1000)
            showPushDialog = false
            pushSuccessMessage = if (isSystemPush) {
                "报告已成功推送至 $email，并同步发送系统通知"
            } else {
                "报告已成功推送至 $email"
            }
            delay(3000)
            pushSuccessMessage = null
        }
    }

    fun exportReport() {
        viewModelScope.launch {
            pushSuccessMessage = "正在生成图片..."
            delay(1000)
            pushSuccessMessage = "报告图片已保存至相册"
            delay(3000)
            pushSuccessMessage = null
        }
    }
}
