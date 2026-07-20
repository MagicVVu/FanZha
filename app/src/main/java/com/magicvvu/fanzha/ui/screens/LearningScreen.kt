package com.magicvvu.fanzha.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.magicvvu.fanzha.data.local.AuthPreferences
import com.magicvvu.fanzha.data.remote.ApiClient
import com.magicvvu.fanzha.data.remote.QuizScoreSubmitRequest
import com.magicvvu.fanzha.ui.viewmodels.FraudCase
import com.magicvvu.fanzha.ui.viewmodels.BrowsingHistoryRepository
import com.magicvvu.fanzha.ui.viewmodels.FavoritesRepository
import com.magicvvu.fanzha.ui.viewmodels.GuideTopic
import com.magicvvu.fanzha.ui.components.AppTopBar
import com.magicvvu.fanzha.ui.viewmodels.LearningViewModel
import com.magicvvu.fanzha.ui.viewmodels.SharedCase
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.LocalContext

private const val QUIZ_SCORE_TAG = "QuizScoreSync"

/** 学习页全屏切换（列表 / 闯关 / 文章），与「我的」子页相同的 push/pop 动效 */
private sealed class LearningPresentation {
    data object List : LearningPresentation()
    data object Quiz : LearningPresentation()
    data class CaseDetail(val case: SharedCase) : LearningPresentation()
}

private fun LearningPresentation.stackDepth(): Int = when (this) {
    LearningPresentation.List -> 0
    LearningPresentation.Quiz -> 1
    is LearningPresentation.CaseDetail -> 1
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LearningScreen(
    viewModel: LearningViewModel = viewModel()
) {
    val scrollState = rememberScrollState()
    val fraudCases by viewModel.fraudCases.collectAsState()
    val sharedCases by viewModel.sharedCases.collectAsState()
    val guideTopics by viewModel.guideTopics.collectAsState()
    val context = LocalContext.current

    var presentation by remember { mutableStateOf<LearningPresentation>(LearningPresentation.List) }
    var quizLatestScore by rememberSaveable { mutableIntStateOf(0) }
    var quizLatestTime by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val uid = AuthPreferences.getUserId(context) ?: return@LaunchedEffect
        runCatching {
            val r = ApiClient.quizScoreApi.getLatestScore(uid)
            val d = r.body()?.data
            if (r.isSuccessful && r.body()?.success == true && d != null) {
                quizLatestScore = d.latestScore
                quizLatestTime = d.updateTime
            }
        }
    }

    val onFraudCaseClick: (FraudCase) -> Unit = { fc ->
        viewModel.getCarouselDetailCase(fc.id)?.let { detail ->
            presentation = LearningPresentation.CaseDetail(detail)
        }
    }

    AnimatedContent(
        targetState = presentation,
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
        label = "learningPresentation",
    ) { dest ->
        when (dest) {
            LearningPresentation.List -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    AppTopBar(title = "学习")
                    BoxWithConstraints(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        val isWideScreen = maxWidth > 768.dp

                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(scrollState)
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(24.dp)
                        ) {
                            if (isWideScreen) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(24.dp)
                                ) {
                                    Box(modifier = Modifier.weight(1f)) {
                                        FraudCarousel(
                                            fraudCases,
                                            onFraudCaseClick = onFraudCaseClick,
                                        )
                                    }
                                    Box(modifier = Modifier.weight(1f)) {
                                        SharedCasesSection(
                                            sharedCases,
                                            onCaseClick = { item ->
                                                presentation = LearningPresentation.CaseDetail(item)
                                            },
                                        )
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(24.dp)
                                ) {
                                    Box(modifier = Modifier.weight(1f)) {
                                        FraudGuide(guideTopics)
                                    }
                                    Box(modifier = Modifier.weight(1f)) {
                                        QuizEntryCard(onStartQuiz = { presentation = LearningPresentation.Quiz })
                                    }
                                }
                            } else {
                                FraudCarousel(
                                    fraudCases,
                                    onFraudCaseClick = onFraudCaseClick,
                                )
                                SharedCasesSection(
                                    sharedCases,
                                    onCaseClick = { item ->
                                        presentation = LearningPresentation.CaseDetail(item)
                                    },
                                )
                                FraudGuide(guideTopics)
                                QuizEntryCard(onStartQuiz = { presentation = LearningPresentation.Quiz })
                            }

                            Spacer(modifier = Modifier.height(32.dp))
                        }
                    }
                }
            }
            LearningPresentation.Quiz -> {
                QuizOverlay(
                    onClose = { presentation = LearningPresentation.List },
                    latestScore = quizLatestScore,
                    latestTime = quizLatestTime,
                    onLatestRecordChange = { score, time ->
                        quizLatestScore = score
                        quizLatestTime = time
                    },
                )
            }
            is LearningPresentation.CaseDetail -> {
                SharedCaseDetailScreen(
                    case = dest.case,
                    onBack = { presentation = LearningPresentation.List },
                )
            }
        }
    }
}

private val FraudCarouselPlaceholderGray = Color(0xFFE8EAED)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FraudCarousel(
    cases: List<FraudCase>,
    onFraudCaseClick: (FraudCase) -> Unit = {},
) {
    Column {
        if (cases.isEmpty()) {
            Text("暂无数据")
        } else {
            val pagerState = rememberPagerState(pageCount = { cases.size })

            LaunchedEffect(cases.size) {
                if (cases.size <= 1) return@LaunchedEffect
                while (true) {
                    delay(4200)
                    try {
                        val nextPage = (pagerState.currentPage + 1) % cases.size
                        pagerState.animateScrollToPage(
                            page = nextPage,
                            animationSpec = tween(
                                durationMillis = 520,
                                easing = FastOutSlowInEasing
                            )
                        )
                    } catch (_: Exception) {
                    }
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 0.dp),
                pageSpacing = 12.dp,
                beyondViewportPageCount = 1
            ) { page ->
                val fraudCase = cases[page]
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clickable { onFraudCaseClick(fraudCase) },
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        val drawable = fraudCase.carouselDrawableRes
                        if (drawable != null) {
                            Image(
                                painter = painterResource(id = drawable),
                                contentDescription = fraudCase.name,
                                modifier = Modifier
                                    .matchParentSize()
                                    .clip(RoundedCornerShape(16.dp)),
                                contentScale = ContentScale.Crop,
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(FraudCarouselPlaceholderGray)
                            )
                        }
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .clip(RoundedCornerShape(16.dp))
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            Color.Transparent,
                                            Color.Black.copy(alpha = 0.25f),
                                            Color.Black.copy(alpha = 0.62f)
                                        ),
                                        startY = 0f,
                                        endY = Float.POSITIVE_INFINITY
                                    )
                                )
                        )
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.Bottom,
                            horizontalAlignment = Alignment.Start
                        ) {
                            Text(
                                text = fraudCase.name,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(cases.size) { iteration ->
                    val selected = pagerState.currentPage == iteration
                    val width by animateDpAsState(
                        targetValue = if (selected) 22.dp else 7.dp,
                        animationSpec = tween(280, easing = FastOutSlowInEasing),
                        label = "carousel_dot_w"
                    )
                    val dotColor by animateColorAsState(
                        targetValue = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                        animationSpec = tween(280, easing = FastOutSlowInEasing),
                        label = "carousel_dot_c"
                    )
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 3.dp)
                            .height(7.dp)
                            .width(width)
                            .clip(RoundedCornerShape(4.dp))
                            .background(dotColor)
                    )
                }
            }
        }
    }
}

@Composable
fun SharedCasesSection(cases: List<SharedCase>, onCaseClick: (SharedCase) -> Unit = {}) {
    Column {
        Text(
            text = "诈骗案例分享",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        if (cases.isEmpty()) {
            Text("暂无案例分享")
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                cases.forEach { sharedCase ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onCaseClick(sharedCase) },
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = sharedCase.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = sharedCase.summary,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2
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
                                            .size(24.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primaryContainer),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = sharedCase.author.take(1),
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = sharedCase.author,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        text = "${sharedCase.readCount} 阅读",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                    Text(
                                        text = sharedCase.date,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FraudGuide(topics: List<GuideTopic>) {
    Column {
        Text(
            text = "如何识别诈骗",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        topics.forEach { topic ->
            GuideItem(topic)
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

private val FraudTypesScrollMaxHeight = 228.dp

@Composable
fun GuideItem(topic: GuideTopic) {
    var expanded by remember { mutableStateOf(false) }
    val fraudScroll = rememberScrollState()
    val headerInteraction = remember { MutableInteractionSource() }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = headerInteraction,
                        indication = null,
                        onClick = { expanded = !expanded },
                    ),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = topic.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null
                )
            }

            AnimatedVisibility(visible = expanded) {
                val entries = topic.fraudTypeEntries
                if (entries != null) {
                    Column(
                        modifier = Modifier
                            .padding(top = 10.dp)
                            .heightIn(max = FraudTypesScrollMaxHeight)
                            .fillMaxWidth()
                            .verticalScroll(fraudScroll)
                    ) {
                        entries.forEachIndexed { index, entry ->
                            if (index > 0) {
                                Spacer(modifier = Modifier.height(12.dp))
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Top
                            ) {
                                Text(
                                    text = "• ",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = entry.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = entry.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        lineHeight = 18.sp
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
                            )
                        }
                    }
                } else {
                    Column(modifier = Modifier.padding(top = 8.dp)) {
                        topic.items.forEach { item ->
                            Row(
                                modifier = Modifier.padding(vertical = 4.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Text("• ", fontWeight = FontWeight.Bold)
                                Text(
                                    text = item,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                        Button(
                            onClick = { /* Copy logic */ },
                            modifier = Modifier.padding(top = 8.dp),
                            elevation = ButtonDefaults.buttonElevation(
                                defaultElevation = 0.dp,
                                pressedElevation = 0.dp,
                                hoveredElevation = 0.dp,
                                focusedElevation = 0.dp,
                                disabledElevation = 0.dp
                            )
                        ) {
                            Text("一键复制检查清单")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun QuizEntryCard(onStartQuiz: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onStartQuiz),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.padding(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "防骗能力测一测",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = "测测你能拿多少分？生成你的专属防骗报告",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                )
            }
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

private const val QuizTotalQuestions = 15
private const val QuizSecondsPerQuestion = 20
private const val QuizScorePerCorrect = 10

private data class QuizChallengeQuestion(
    val title: String,
    val options: List<String>,
    val correctIndex: Int,
)

private enum class QuizStage {
    Lobby,
    Playing,
    Result,
}

private fun QuizStage.stackDepth(): Int = when (this) {
    QuizStage.Lobby -> 0
    QuizStage.Playing -> 1
    QuizStage.Result -> 2
}

private val quizChallengeQuestions = listOf(
    QuizChallengeQuestion(
        title = "接到自称“公检法”的电话，说你涉嫌洗钱，要求你保密并立刻转账，你应该？",
        options = listOf("按指示转账以自证清白", "挂断电话，拨打110或到派出所核实", "先转一小笔试试对方真假"),
        correctIndex = 1,
    ),
    QuizChallengeQuestion("收到“退款理赔”电话时最安全的做法是？", listOf("按对方步骤操作", "在官方平台核实", "直接提供验证码"), 1),
    QuizChallengeQuestion("“刷单返利”最典型的套路是？", listOf("先返小额再套大额", "只收手续费", "先签合同"), 0),
    QuizChallengeQuestion("收到陌生链接提示“账户异常”，应？", listOf("立即点击处理", "转发给朋友", "不点并走官方渠道核验"), 2),
    QuizChallengeQuestion("自称公检法要求转账到“安全账户”，应？", listOf("立刻转账", "挂断并拨打110核实", "先转小额试试"), 1),
    QuizChallengeQuestion("以下哪项属于敏感信息，不应透露？", listOf("验证码", "天气情况", "快递单号"), 0),
    QuizChallengeQuestion("网络贷款要求先交“解冻金”，通常是？", listOf("正常流程", "诈骗手段", "银行规定"), 1),
    QuizChallengeQuestion("“领导”临时让你转账，最佳做法？", listOf("先电话/当面核实", "立即转账", "先发身份证"), 0),
    QuizChallengeQuestion("杀猪盘常见特征是？", listOf("短期恋爱后诱导投资", "官方客服回访", "线下签约理财"), 0),
    QuizChallengeQuestion("以下哪种说法最可疑？", listOf("不许挂电话，马上转账", "请到营业厅办理", "稍后官方短信确认"), 0),
    QuizChallengeQuestion("收到“中奖”短信要求先缴税，正确做法？", listOf("按流程缴费", "不理会并核验来源", "先交小额"), 1),
    QuizChallengeQuestion("社交平台低价卖手机，先付款后发货，风险是？", listOf("正常促销", "可能虚假购物诈骗", "只要截图就安全"), 1),
    QuizChallengeQuestion("下列哪项是防诈正确习惯？", listOf("只走官方渠道", "私下扫码转账", "给陌生人共享屏幕"), 0),
    QuizChallengeQuestion("遇到“账号冻结”恐吓时，应优先？", listOf("转账解冻", "下载指定APP", "冷静核验并联系官方客服"), 2),
    QuizChallengeQuestion("关于防诈，下列说法正确的是？", listOf("越急越先核实", "只要返利高就可信", "验证码可以给客服"), 0),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuizOverlay(
    onClose: () -> Unit,
    latestScore: Int,
    latestTime: String?,
    onLatestRecordChange: (score: Int, time: String?) -> Unit,
) {
    var stage by remember { mutableStateOf(QuizStage.Lobby) }
    var currentIndex by remember { mutableIntStateOf(0) }
    var selectedIndex by remember { mutableStateOf<Int?>(null) }
    var currentScore by remember { mutableIntStateOf(0) }
    var secondsLeft by remember { mutableIntStateOf(QuizSecondsPerQuestion) }
    var lastRoundScore by remember { mutableIntStateOf(0) }
    var roundId by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    var lastSavedTime by remember(latestTime) { mutableStateOf(latestTime) }

    fun resetRound() {
        currentIndex = 0
        selectedIndex = null
        currentScore = 0
        secondsLeft = QuizSecondsPerQuestion
    }

    fun finishRound() {
        lastRoundScore = currentScore
        stage = QuizStage.Result
        roundId += 1
    }

    fun moveNextQuestion() {
        if (currentIndex >= QuizTotalQuestions - 1) {
            finishRound()
        } else {
            currentIndex += 1
            selectedIndex = null
            secondsLeft = QuizSecondsPerQuestion
        }
    }

    LaunchedEffect(stage, currentIndex, selectedIndex) {
        if (stage != QuizStage.Playing || selectedIndex != null) return@LaunchedEffect
        while (secondsLeft > 0 && stage == QuizStage.Playing && selectedIndex == null) {
            delay(1000)
            secondsLeft -= 1
        }
        if (stage == QuizStage.Playing && selectedIndex == null && secondsLeft <= 0) {
            moveNextQuestion()
        }
    }

    LaunchedEffect(stage, currentIndex, selectedIndex) {
        if (stage != QuizStage.Playing || selectedIndex == null) return@LaunchedEffect
        delay(450)
        moveNextQuestion()
    }

    // 每轮结束：用 (roundId, lastRoundScore) 作为键，保证与本轮分数同一拍提交；0 分也应入库。
    LaunchedEffect(roundId, lastRoundScore) {
        if (roundId <= 0) return@LaunchedEffect
        val uid = AuthPreferences.getUserId(context)
        if (uid == null) {
            Log.w(QUIZ_SCORE_TAG, "skip submit: not logged in")
            return@LaunchedEffect
        }
        runCatching {
            val submitResult = withContext(Dispatchers.IO) {
                val resp = ApiClient.quizScoreApi.submitScore(
                    QuizScoreSubmitRequest(
                        userId = uid,
                        totalScore = lastRoundScore,
                    ),
                )
                val body = resp.body()
                if (!resp.isSuccessful || body == null || !body.success) {
                    Log.w(
                        QUIZ_SCORE_TAG,
                        "submit failed code=${resp.code()} success=${body?.success} msg=${body?.message}",
                    )
                    return@withContext null
                }
                val r2 = ApiClient.quizScoreApi.getLatestScore(uid)
                val d2 = r2.body()?.data
                if (r2.isSuccessful && r2.body()?.success == true && d2 != null) {
                    Pair(d2.latestScore, d2.updateTime)
                } else {
                    Pair(body.data?.latestScore ?: lastRoundScore, null as String?)
                }
            }
            if (submitResult != null) {
                val (savedScore, time) = submitResult
                lastSavedTime = time ?: lastSavedTime
                onLatestRecordChange(savedScore, time)
            }
        }.onFailure { e ->
            Log.w(QUIZ_SCORE_TAG, "submit exception: ${e.message}")
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            AppTopBar(
                title = when (stage) {
                    QuizStage.Lobby -> "防骗测评"
                    QuizStage.Playing -> "闯关中"
                    QuizStage.Result -> "闯关结果"
                },
                onBack = {
                    when (stage) {
                        QuizStage.Lobby -> onClose()
                        QuizStage.Playing, QuizStage.Result -> stage = QuizStage.Lobby
                    }
                },
            )
            AnimatedContent(
                targetState = stage,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
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
                label = "quizStageNav",
            ) { s ->
                when (s) {
                    QuizStage.Lobby -> {
                        QuizChallengeLobby(
                            latestScore = latestScore,
                            latestTime = latestTime,
                            onStartChallenge = {
                                resetRound()
                                stage = QuizStage.Playing
                            },
                        )
                    }
                    QuizStage.Playing -> {
                        QuizChallengePlayScreen(
                            question = quizChallengeQuestions[currentIndex],
                            questionIndex = currentIndex,
                            totalQuestions = QuizTotalQuestions,
                            currentScore = currentScore,
                            secondsLeft = secondsLeft,
                            selectedIndex = selectedIndex,
                            onSelect = { selected ->
                                if (selectedIndex != null) return@QuizChallengePlayScreen
                                selectedIndex = selected
                                if (selected == quizChallengeQuestions[currentIndex].correctIndex) {
                                    currentScore += QuizScorePerCorrect
                                }
                            },
                        )
                    }
                    QuizStage.Result -> {
                        QuizChallengeResult(
                            score = lastRoundScore,
                            highScore = lastRoundScore,
                            onRestart = {
                                resetRound()
                                stage = QuizStage.Playing
                            },
                            onBackLobby = { stage = QuizStage.Lobby },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QuizChallengeLobby(
    latestScore: Int,
    latestTime: String?,
    onStartChallenge: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "防诈能力大闯关",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(28.dp))
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "最近得分：",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "$latestScore",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = " 分",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!latestTime.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "最近答题时间：$latestTime",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(modifier = Modifier.height(40.dp))
        QuizStartChallengeButton(onClick = onStartChallenge)
    }
}

@Composable
private fun QuizChallengePlayScreen(
    question: QuizChallengeQuestion,
    questionIndex: Int,
    totalQuestions: Int,
    currentScore: Int,
    secondsLeft: Int,
    selectedIndex: Int?,
    onSelect: (Int) -> Unit,
) {
    val progress = ((questionIndex + 1).toFloat() / totalQuestions.toFloat()).coerceIn(0f, 1f)
    val targetTimeProgress = (secondsLeft.toFloat() / QuizSecondsPerQuestion.toFloat()).coerceIn(0f, 1f)
    val timeProgress by animateFloatAsState(
        targetValue = targetTimeProgress,
        animationSpec = tween(durationMillis = 850, easing = LinearEasing),
        label = "quiz_time_progress",
    )
    val optionKeys = listOf("A", "B", "C")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "当前得分 ",
                style = MaterialTheme.typography.labelLarge,
                color = Color(0xFF1E3A8A),
            )
            Text(
                text = currentScore.toString(),
                style = MaterialTheme.typography.headlineSmall,
                fontSize = 30.sp,
                color = Color(0xFF1D4ED8),
                fontWeight = FontWeight.ExtraBold,
            )
        }

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
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        progress = { timeProgress },
                        modifier = Modifier.size(72.dp),
                        color = Color(0xFF2563EB),
                        strokeWidth = 7.dp,
                        trackColor = Color(0xFFE5E7EB),
                    )
                    Text(
                        text = "${secondsLeft}s",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFF1D4ED8),
                    )
                }

                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = "问题 ",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF0F172A),
                    )
                    Text(
                        text = "${questionIndex + 1}/$totalQuestions",
                        style = MaterialTheme.typography.titleLarge,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1D4ED8),
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(100))
                        .background(Color(0xFFE5E7EB)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress)
                            .fillMaxHeight()
                            .background(Color(0xFF3B82F6), RoundedCornerShape(100)),
                    )
                }

                AnimatedContent(
                    targetState = questionIndex,
                    transitionSpec = {
                        (slideInHorizontally { it / 2 } + fadeIn()).togetherWith(
                            slideOutHorizontally { -it / 3 } + fadeOut()
                        )
                    },
                    label = "quiz_question_transition",
                ) { _ ->
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = question.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.Black,
                            fontWeight = FontWeight.Bold,
                        )

                        question.options.forEachIndexed { index, option ->
                            val isSelected = selectedIndex == index
                            val isCorrect = question.correctIndex == index
                            val bgColor = when {
                                selectedIndex == null -> Color(0xFFF8FAFC)
                                isSelected && isCorrect -> Color(0xFFDCFCE7)
                                isSelected && !isCorrect -> Color(0xFFFEE2E2)
                                !isSelected && isCorrect -> Color(0xFFECFDF5)
                                else -> Color(0xFFF8FAFC)
                            }
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(
                                        enabled = selectedIndex == null,
                                        onClick = { onSelect(index) },
                                    ),
                                shape = RoundedCornerShape(24.dp),
                                color = bgColor,
                                shadowElevation = 0.dp,
                                tonalElevation = 0.dp,
                            ) {
                                Text(
                                    text = "${optionKeys[index]}  $option",
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = Color(0xFF0F172A),
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
private fun QuizStartChallengeButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = tween(durationMillis = 120, easing = FastOutSlowInEasing),
        label = "quiz_start_scale",
    )
    val gradient = Brush.linearGradient(
        colors = listOf(
            Color(0xFFFF9A56),
            Color(0xFFFF5E5E),
            Color(0xFF9B5CFF),
        ),
        start = Offset(0f, 0f),
        end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
    )
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(32.dp))
            .background(gradient)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 52.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "开始闯关",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.ExtraBold,
            color = Color.White,
            letterSpacing = 1.sp,
        )
    }
}

@Composable
private fun QuizChallengeResult(
    score: Int,
    highScore: Int,
    onRestart: () -> Unit,
    onBackLobby: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "本轮得分：$score 分",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "最近得分：$highScore 分",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(32.dp))
        QuizStartChallengeButton(
            onClick = onRestart,
            modifier = Modifier.width(240.dp),
        )
        Spacer(modifier = Modifier.height(12.dp))
        TextButton(onClick = onBackLobby) {
            Text("返回闯关大厅")
        }
    }
}

// ─── 文章详情页 ─────────────────────────────────────────────────────────────────

private val ArticleTagColors = listOf(
    Color(0xFFEFF6FF) to Color(0xFF3B82F6),
    Color(0xFFFFF7ED) to Color(0xFFF97316),
    Color(0xFFF0FDF4) to Color(0xFF22C55E),
    Color(0xFFFDF4FF) to Color(0xFFA855F7),
    Color(0xFFFFF1F2) to Color(0xFFE11D48),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharedCaseDetailScreen(
    case: SharedCase,
    onBack: () -> Unit,
) {
    LaunchedEffect(case.id) {
        BrowsingHistoryRepository.record(case)
    }

    var liked by remember { mutableStateOf(false) }
    var likeCount by remember { mutableIntStateOf((case.readCount * 0.06).toInt()) }
    var favorited by remember { mutableStateOf(FavoritesRepository.isFavorited(case.id)) }

    Scaffold(
        topBar = {
            AppTopBar(
                title = case.title,
                onBack = onBack,
            )
        },
        bottomBar = {
            ArticleBottomBar(
                liked = liked,
                likeCount = likeCount,
                onLikeToggle = {
                    if (!liked) likeCount++ else likeCount--
                    liked = !liked
                },
                favorited = favorited,
                onFavoriteToggle = {
                    FavoritesRepository.toggle(case)
                    favorited = !favorited
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState()),
        ) {
            // 头图：本地 drawable（heroDrawableRes）或占位
            if (case.heroDrawableRes != null) {
                Image(
                    painter = painterResource(id = case.heroDrawableRes),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color(0xFFCBD5E1), Color(0xFFE2E8F0))
                            )
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = Color(0xFF94A3B8),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "诈骗案例",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFF94A3B8),
                        )
                    }
                }
            }

            // Article card
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 0.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp)) {
                    // Title
                    Text(
                        text = case.title,
                        style = MaterialTheme.typography.headlineSmall.copy(fontSize = 22.sp, lineHeight = 32.sp),
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF0F172A),
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // Tags row
                    if (case.tags.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            case.tags.forEachIndexed { i, tag ->
                                val (bg, fg) = ArticleTagColors[i % ArticleTagColors.size]
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = bg,
                                ) {
                                    Text(
                                        text = tag,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = fg,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    HorizontalDivider(color = Color(0xFFF1F5F9))
                    Spacer(modifier = Modifier.height(20.dp))

                    // Article body（正文用双换行分段；段内单换行保留为换行）
                    val paragraphs = case.content.split("\n\n").filter { it.isNotBlank() }
                    paragraphs.forEach { para ->
                        val block = para.trim()
                        val isSubheading = (
                            block.startsWith("案例一") || block.startsWith("案例二") ||
                                block.startsWith("来源：") || block.startsWith("警方提醒") ||
                                block.startsWith("利用二手交易平台诈骗") ||
                                block.startsWith("如何防范二手交易诈骗") ||
                                block.startsWith("真实案例")
                            ) && block.length <= 80 && !block.contains("。")
                        Text(
                            text = block,
                            style = if (isSubheading) {
                                MaterialTheme.typography.titleMedium.copy(
                                    lineHeight = 26.sp,
                                    letterSpacing = 0.2.sp,
                                )
                            } else {
                                MaterialTheme.typography.bodyLarge.copy(
                                    lineHeight = 28.sp,
                                    letterSpacing = 0.3.sp,
                                )
                            },
                            fontWeight = if (isSubheading) FontWeight.Bold else FontWeight.Normal,
                            color = Color(0xFF334155),
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    // 来源/作者/阅读量：放在正文之后、「防骗提醒」之前
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = case.author.take(1),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = case.author,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFF1E293B),
                                )
                                Text(
                                    text = case.date,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF94A3B8),
                                )
                            }
                        }
                        Text(
                            text = "${case.readCount} 阅读",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF94A3B8),
                        )
                    }
                }
            }

            // Warning tips card
            if (case.warningTips.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 0.dp),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(4.dp, 20.dp)
                                    .background(Color(0xFFEF4444), RoundedCornerShape(2.dp))
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "防骗提醒",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF991B1B),
                            )
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFFFFF1F2),
                        ) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                case.warningTips.forEach { tip ->
                                    Row(verticalAlignment = Alignment.Top) {
                                        Text(
                                            text = "⚠",
                                            fontSize = 14.sp,
                                            color = Color(0xFFDC2626),
                                            modifier = Modifier.padding(top = 2.dp),
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = tip,
                                            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                                            color = Color(0xFF7F1D1D),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun ArticleBottomBar(
    liked: Boolean,
    likeCount: Int,
    onLikeToggle: () -> Unit,
    favorited: Boolean = false,
    onFavoriteToggle: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 0.dp,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 点赞按钮
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable { onLikeToggle() }
                    .background(
                        if (liked) Color(0xFFFFF1F2) else Color(0xFFF1F5F9),
                        RoundedCornerShape(50)
                    )
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = if (liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = if (liked) "已点赞" else "点赞",
                    tint = if (liked) Color(0xFFE11D48) else Color(0xFF64748B),
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = likeCount.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (liked) Color(0xFFE11D48) else Color(0xFF64748B),
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 收藏按钮
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (favorited) Color(0xFFFFFBEB) else Color(0xFFF1F5F9),
                ) {
                    Row(
                        modifier = Modifier
                            .clickable { onFavoriteToggle() }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = if (favorited) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                            contentDescription = if (favorited) "取消收藏" else "收藏",
                            tint = if (favorited) Color(0xFFF59E0B) else Color(0xFF64748B),
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = if (favorited) "已收藏" else "收藏",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (favorited) Color(0xFFF59E0B) else Color(0xFF64748B),
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }

                // 分享按钮
                Surface(
                    shape = RoundedCornerShape(50),
                    color = Color(0xFFF1F5F9),
                ) {
                    Row(
                        modifier = Modifier
                            .clickable { }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "分享",
                            tint = Color(0xFF64748B),
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = "分享",
                            style = MaterialTheme.typography.labelLarge,
                            color = Color(0xFF64748B),
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}
