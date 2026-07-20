package com.magicvvu.fanzha.ui.viewmodels

import androidx.annotation.DrawableRes
import androidx.lifecycle.ViewModel
import com.magicvvu.fanzha.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class FraudCase(
    val id: String,
    val name: String,
    val method: String,
    val victimFeatures: String,
    val lossRange: String,
    val detailsUrl: String,
    val imageUrl: String = "",
    /** 学习页轮播卡片配图；为空则使用灰色占位 */
    @DrawableRes val carouselDrawableRes: Int? = null,
)

data class SharedCase(
    val id: String,
    val title: String,
    val summary: String,
    val author: String,
    val date: String,
    val readCount: Int,
    val imageUrl: String = "",
    val tags: List<String> = emptyList(),
    val content: String = "",
    val warningTips: List<String> = emptyList(),
    /** 详情页顶部配图，本地 drawable 资源 id；为空则显示占位 */
    @DrawableRes val heroDrawableRes: Int? = null,
)

data class GuideFraudTypeEntry(
    val title: String,
    val description: String,
)

data class GuideTopic(
    val title: String,
    val items: List<String> = emptyList(),
    val imageUrl: String = "",
    /** 非空时按「标题 + 说明」展示，并启用展开区内滚动（如常见诈骗类型） */
    val fraudTypeEntries: List<GuideFraudTypeEntry>? = null,
)

data class QuizQuestion(
    val id: Int,
    val question: String,
    val options: List<String>,
    val correctIndex: Int, // For single choice, or bitmask/list for multiple
    val type: QuizType
)

enum class QuizType {
    SINGLE_CHOICE, MULTI_CHOICE, JUDGMENT
}

data class QuizResult(
    val score: Int,
    val advice: String
)

class LearningViewModel : ViewModel() {
    private val _fraudCases = MutableStateFlow<List<FraudCase>>(emptyList())
    val fraudCases: StateFlow<List<FraudCase>> = _fraudCases.asStateFlow()

    private val _sharedCases = MutableStateFlow<List<SharedCase>>(emptyList())
    val sharedCases: StateFlow<List<SharedCase>> = _sharedCases.asStateFlow()

    private val _guideTopics = MutableStateFlow<List<GuideTopic>>(emptyList())
    val guideTopics: StateFlow<List<GuideTopic>> = _guideTopics.asStateFlow()
    
    private val _quizQuestions = MutableStateFlow<List<QuizQuestion>>(emptyList())
    val quizQuestions: StateFlow<List<QuizQuestion>> = _quizQuestions.asStateFlow()

    init {
        loadMockData()
        _quizQuestions.value = generateQuizQuestions()
    }

    private fun loadMockData() {
            _fraudCases.value = listOf(
                FraudCase(
                    id = "1",
                    name = "AI换脸可“以假乱真”",
                    method = "利用深度伪造技术冒充熟人、绕过人脸识别等实施诈骗或盗刷",
                    victimFeatures = "全年龄段",
                    lossRange = "单笔可达数十万元",
                    detailsUrl = "details_carousel_ai_face",
                    carouselDrawableRes = R.drawable.t1,
                ),
                FraudCase(
                    id = "2",
                    name = "二手交易遭遇“到手刀”等多重骗局",
                    method = "收货后恶意压价、引导脱离平台交易、伪造纠纷等实施诈骗",
                    victimFeatures = "二手平台卖家（奢侈品、电子产品等高价商品尤需警惕）",
                    lossRange = "数百元至数万元不等",
                    detailsUrl = "details_carousel_secondhand",
                    carouselDrawableRes = R.drawable.t2,
                ),
                FraudCase(
                    id = "3",
                    name = "“屏幕共享”藏陷阱！",
                    method = "冒充客服等诱导开启屏幕共享，窥视银行卡与验证码伺机盗刷",
                    victimFeatures = "全年龄段（易被“关闭业务”“理赔”等话术诱导者）",
                    lossRange = "账户余额可能全部损失",
                    detailsUrl = "details_carousel_screen_share",
                    carouselDrawableRes = R.drawable.t3,
                ),
                FraudCase(
                    id = "4",
                    name = "“投资”16万元险被骗",
                    method = "冒充证券人员、虚假盈利截图诱导下载“炒股”APP，以高额门槛等话术诱骗转账",
                    victimFeatures = "有理财炒股意愿、易被“保本补损”话术打动的人群",
                    lossRange = "本案16万元已止付（同类案件损失可达数十万至数百万）",
                    detailsUrl = "details_carousel_fake_investment",
                    carouselDrawableRes = R.drawable.t4,
                ),
                FraudCase(
                    id = "5",
                    name = "卖号有风险，交易需谨慎！",
                    method = "以“免费赠送”“高价回收”“中介担保费”等名义诱骗转账或绕开平台交易",
                    victimFeatures = "青少年及游戏账号、装备交易者",
                    lossRange = "数百元至数千元不等（本案720元）",
                    detailsUrl = "details_carousel_game_account",
                    carouselDrawableRes = R.drawable.t5,
                ),
            )

            _sharedCases.value = listOf(
                SharedCase(
                    id = "1",
                    title = "反诈最前沿丨八旬老人险失258万 起底社保卡骗局",
                    summary = "骗子冒充社保工作人员远程操控手机，杭州一八旬老人银行卡内258万元险些被骗，警方紧急止付化险为夷。",
                    author = "央视新闻",
                    date = "2025-03-21",
                    readCount = 186000,
                    tags = listOf("社保卡诈骗", "冒充公检法", "远程操控"),
                    heroDrawableRes = R.drawable.p1,
                    content = sharedCaseNewsArticle238w,
                    warningTips = listOf(
                        "凡是接到自称官方工作人员的电话，要先核实身份，切勿随意下载不明软件、点击不明链接，更不要提供银行账户、密码、验证码。",
                        "遇到可疑情况先挂断电话，可拨打96110咨询；若手机黑屏、疑似被远程控制，可先拔手机卡或断开网络再重启。",
                    ),
                ),
                SharedCase(
                    id = "2",
                    title = "AI“坑老”，守护伞要撑好！",
                    summary = "徐州开展反诈宣传；郑州李奶奶遭遇AI换脸、拟声伪造“孙子”骗款。记者调查AI如何“围猎”老年人，以及政府、社区、平台与家庭如何合力撑起“守护伞”。",
                    author = "人民日报",
                    date = "2026-03-31",
                    readCount = 98200,
                    tags = listOf("AI诈骗", "老年人保护", "换脸拟声"),
                    heroDrawableRes = R.drawable.p2,
                    content = sharedCaseAiElderlyGuardianArticle,
                    warningTips = listOf(
                        "视频里“亲友”借钱急用，务必另行打电话或当面核实，谨防AI换脸与拟声诈骗。",
                        "慎信“零基础学AI躺赚”等培训广告，勿向陌生账户、私域红包大额转账，注意留存聊天与付款凭证。",
                        "可与家人约定暗语；少在社交平台公开发布个人照片、语音；遇事可先联系社区网格员或拨打96110。",
                    ),
                ),
                SharedCase(
                    id = "3",
                    title = "拍案丨老骗局新变种 海外学子成电诈“猎物”",
                    summary = "江苏披露跨国诈骗案：冒充公检法以“验资审查”“优先调查”等话术精准围猎留学生，单案涉案金额高达200万元；黄金、虚拟货币成洗钱新手段。",
                    author = "新华社",
                    date = "2026-04-02",
                    readCount = 154000,
                    tags = listOf("留学生", "冒充公检法", "跨国电诈"),
                    heroDrawableRes = R.drawable.p3,
                    content = sharedCaseOverseasStudentScamArticle,
                    warningTips = listOf(
                        "我国司法机关不会通过电话、社交软件办案，凡要求缴纳“保证金”“验资款”的均为诈骗。",
                        "勿向陌生人提供身份证、银行卡，勿参与来源不明的黄金、虚拟币等转账，谨防成为洗钱“工具人”。",
                        "涉及汇款务必通过多种渠道向家人、使领馆或警方核实；接到“96110”等来电仍应通过官方途径二次核实。",
                    ),
                ),
                SharedCase(
                    id = "4",
                    title = "男子打赏“女神”90万元后被拉黑",
                    summary = "乐清公安捣毁大型直播诈骗团伙：女主播以恋爱为名诱导打赏，受害人王某累计充值90余万元后被拉黑；66人落网，追缴赃款500余万元。",
                    author = "温州晚报",
                    date = "2026-04-10",
                    readCount = 128000,
                    tags = listOf("直播打赏", "婚恋诈骗", "杀猪盘"),
                    heroDrawableRes = R.drawable.p4,
                    content = sharedCaseLivestreamRomanceScamArticle,
                    warningTips = listOf(
                        "网络“恋爱”中对方若以PK、救急等理由反复索要打赏、转账，务必警惕，勿因暧昧话术大额充值。",
                        "私聊中“女神”可能由团队代聊，勿轻信直播间人设；资金紧张时更应停止汇款并保留聊天记录、转账凭证。",
                        "发现被拉黑或疑似诈骗，及时向公安机关报案，便于止付与追查。",
                    ),
                ),
            )
            
            _guideTopics.value = listOf(
                GuideTopic(
                    title = "常见诈骗类型",
                    fraudTypeEntries = listOf(
                        GuideFraudTypeEntry(
                            "刷单返利诈骗",
                            "以“轻松兼职、高额返利”为诱饵，先小额返现，再诱导大额投入后拉黑。",
                        ),
                        GuideFraudTypeEntry(
                            "冒充客服诈骗",
                            "假冒电商 / 快递客服，谎称“订单异常、商品质量问题、快递丢失”，以理赔、退款为由骗钱。",
                        ),
                        GuideFraudTypeEntry(
                            "冒充公检法诈骗",
                            "谎称你涉嫌洗钱、诈骗、案件涉案，恐吓冻结账户，要求转账到“安全账户”。",
                        ),
                        GuideFraudTypeEntry(
                            "网络贷款诈骗",
                            "声称“无抵押、低利息、秒批”，以“解冻金、保证金、刷流水”为由收费，不下款反被骗。",
                        ),
                        GuideFraudTypeEntry(
                            "投资理财诈骗",
                            "拉进股票 / 基金 / 虚拟币群，用“老师带单、内幕消息、稳赚不赔”诱导在虚假平台投资，无法提现。",
                        ),
                        GuideFraudTypeEntry(
                            "婚恋交友诈骗（杀猪盘）",
                            "网上伪装高富帅 / 白富美谈恋爱，获取信任后诱导投资、赌博、借钱。",
                        ),
                        GuideFraudTypeEntry(
                            "冒充熟人 / 领导诈骗",
                            "盗号 / 伪造身份，冒充亲友、老板、老师，以急事、借钱、交学费为由转账。",
                        ),
                        GuideFraudTypeEntry(
                            "注销校园贷诈骗",
                            "针对学生 / 毕业生，谎称“校园贷未注销影响征信”，诱导转账清空额度。",
                        ),
                        GuideFraudTypeEntry(
                            "虚假购物诈骗",
                            "朋友圈、二手平台卖低价商品、口罩、宠物、手机，先付款后不发货或发假货。",
                        ),
                        GuideFraudTypeEntry(
                            "游戏账号 / 装备诈骗",
                            "低价卖号、卖皮肤、代练，私下交易后拉黑，或盗号。",
                        ),
                        GuideFraudTypeEntry(
                            "养老诈骗",
                            "以“养老投资、保健品、以房养老、免费旅游”为名，骗取老年人钱财。",
                        ),
                        GuideFraudTypeEntry(
                            "钓鱼链接 / 木马诈骗",
                            "短信 / 微信群发“积分兑换、中奖、账户异常”链接，点入后盗走账号密码、银行卡信息。",
                        ),
                    ),
                ),
                GuideTopic(
                    title = "诈骗常见手段",
                    fraudTypeEntries = listOf(
                        GuideFraudTypeEntry(
                            "冒充身份",
                            "伪造公检法、客服、亲友、领导、老师、平台官方等身份骗取信任。",
                        ),
                        GuideFraudTypeEntry(
                            "制造恐慌施压",
                            "谎称涉案、征信异常、账户封禁、家人遇险，逼你紧急操作。",
                        ),
                        GuideFraudTypeEntry(
                            "高额利诱诱饵",
                            "用刷单返利、低息秒批贷款、稳赚投资、天降中奖吸引上钩。",
                        ),
                        GuideFraudTypeEntry(
                            "索要核心信息",
                            "套取短信验证码、银行卡 / 支付密码、人脸核验、身份证信息。",
                        ),
                        GuideFraudTypeEntry(
                            "引导非官方操作",
                            "让你点陌生链接、扫私发二维码、下载非正规 APP、私下转账。",
                        ),
                        GuideFraudTypeEntry(
                            "强制限时 + 保密",
                            "要求立刻处理、不许挂断电话、不能告诉家人朋友。",
                        ),
                        GuideFraudTypeEntry(
                            "先小利后大坑",
                            "先小额返现 / 提现，获取信任后诱导大额投入再拉黑。",
                        ),
                        GuideFraudTypeEntry(
                            "伪造虚假凭证",
                            "造假公文、订单、转账记录、资质证明蒙骗。",
                        ),
                        GuideFraudTypeEntry(
                            "钓鱼盗号",
                            "发送钓鱼链接 / 木马程序，盗取账号、密码与财产信息。",
                        ),
                        GuideFraudTypeEntry(
                            "巧立名目收费",
                            "以解冻金、保证金、刷流水、担保费、手续费等名义骗钱。",
                        ),
                    ),
                ),
                GuideTopic(
                    title = "诈骗常用话术",
                    fraudTypeEntries = listOf(
                        GuideFraudTypeEntry(
                            "一、冒充公检法 / 政务类",
                            "• 你涉嫌洗钱 / 诈骗 / 涉案，名下账户已被冻结，必须配合调查\n" +
                                "• 现在全程保密通话，不许挂电话，不许告诉任何人\n" +
                                "• 把钱转到安全账户自清，核查后原路返还\n" +
                                "• 我发你通缉令 / 公文，不信自己看，不配合就抓你",
                        ),
                        GuideFraudTypeEntry(
                            "二、冒充客服 / 快递类",
                            "• 您订单异常 / 商品质量问题 / 快递丢失，可双倍理赔\n" +
                                "• 退款要走绿色通道，先交保证金 / 解冻金\n" +
                                "• 点我发的链接 / 扫二维码，填信息才能退款\n" +
                                "• 您花呗 / 白条异常，不关闭会影响征信",
                        ),
                        GuideFraudTypeEntry(
                            "三、刷单返利 / 兼职类",
                            "• 足不出户、日入上千，简单点赞 / 刷单就赚钱\n" +
                                "• 先小额任务返现，获取信任后再做大单\n" +
                                "• 任务未完成 / 卡单，必须继续投钱才能结算提现\n" +
                                "• 交押金 / 会费，做完立马返还",
                        ),
                        GuideFraudTypeEntry(
                            "四、网络贷款类",
                            "• 无抵押、低利息、秒批、黑户也能贷\n" +
                                "• 额度已到账，需交解冻金 / 保证金 / 刷流水才能提现\n" +
                                "• 先交利息 / 手续费，不下款全额退\n" +
                                "• 不交费就违约，起诉你、爆通讯录",
                        ),
                        GuideFraudTypeEntry(
                            "五、投资理财 / 杀猪盘类",
                            "• 内部消息、老师带单、稳赚不赔、只涨不跌\n" +
                                "• 平台有漏洞，短期翻倍，小投试一下\n" +
                                "• 现在充值有返利，冲得多赚得多\n" +
                                "• 先小提现让你信，大额投入就无法提现 / 封号",
                        ),
                        GuideFraudTypeEntry(
                            "六、冒充熟人 / 领导 / 老师类",
                            "• 我是你领导 / 老师 / 亲戚，换号了，急事转钱\n" +
                                "• 我在外面不方便，你先帮我转下，回头还你\n" +
                                "• 孩子出事 / 住院急需钱，快转过来",
                        ),
                        GuideFraudTypeEntry(
                            "七、注销校园贷 / 征信类",
                            "• 你有校园贷没注销，影响征信 / 房贷车贷\n" +
                                "• 按我步骤清空额度，不然终身污点\n" +
                                "• 不许查官方，只能听我操作",
                        ),
                        GuideFraudTypeEntry(
                            "八、通用施压 / 保密话术",
                            "• 限时处理，马上操作，超时就作废 / 追责",
                        ),
                    ),
                ),
                GuideTopic(
                    title = "诈骗常见特点",
                    fraudTypeEntries = listOf(
                        GuideFraudTypeEntry(
                            "要么利诱，要么胁迫",
                            "用“高额返利、稳赚投资、免费福利”诱惑，或用“涉案、征信污点、账号封禁”恐吓。",
                        ),
                        GuideFraudTypeEntry(
                            "必索核心敏感信息",
                            "索要短信验证码、银行卡 / 支付密码、身份证照片、人脸核验信息。",
                        ),
                        GuideFraudTypeEntry(
                            "全走非官方渠道",
                            "引导点陌生链接、扫私人二维码、下载非正规 APP、私下转账。",
                        ),
                        GuideFraudTypeEntry(
                            "强制限时 + 保密",
                            "要求立刻操作、超时作废，严禁告诉家人朋友，不许挂断电话。",
                        ),
                        GuideFraudTypeEntry(
                            "先给小利，再套大钱",
                            "前期小额返现 / 提现获取信任，诱导大额投入后直接拉黑、无法提现。",
                        ),
                        GuideFraudTypeEntry(
                            "伪造身份与凭证",
                            "冒充公检法、客服、领导、亲友，伪造通缉令、订单、转账记录。",
                        ),
                        GuideFraudTypeEntry(
                            "拒绝正规核验",
                            "不让你拨打官方电话、不去正规平台核实，只听其单向指挥。",
                        ),
                        GuideFraudTypeEntry(
                            "无正规合同与资质",
                            "无备案、无正规协议，以“保证金、解冻金、刷流水”等名义收费。",
                        ),
                    ),
                ),
            )
    }
    
    /** 学习页轮播图点击：与 `FraudCase.id` 对应的详情（布局同诈骗案例分享详情） */
    fun getCarouselDetailCase(fraudCaseId: String): SharedCase? = when (fraudCaseId) {
        "1" -> SharedCase(
            id = "fraud-carousel-1",
            title = "AI换脸可“以假乱真”",
            summary = "AI换脸被用于杀熟诈骗、绕过人脸盗刷账户等。记者探访技术演示，结合公安案例解析套路与防范。",
            author = "央视新闻",
            date = "2026-04-16",
            readCount = 312000,
            tags = listOf("AI换脸", "深度伪造", "人脸识别"),
            heroDrawableRes = R.drawable.t1,
            content = sharedCaseT1AiFaceArticle,
            warningTips = listOf(
                "大额转账前务必通过电话、当面等其他渠道二次核实，勿仅凭短视频或视频通话确认身份。",
                "视频通话存疑时可请对方快速转头、用手短暂遮挡面部，观察五官是否异常贴合；可疑音视频请留存并报警。",
                "勿随意发送高清正面照、勿对不明 App 授权相机与通讯录；关注国家反诈提示与正规鉴伪、反诈工具。",
            ),
        )
        "2" -> SharedCase(
            id = "fraud-carousel-2",
            title = "二手交易遭遇“到手刀”等多重骗局",
            summary = "从个案纠纷到有组织诈骗，“到手刀”、脱离平台交易等套路如何侵害卖家？专家解析平台规则与留证维权要点。",
            author = "中央广播电视总台",
            date = "2026-04-12",
            readCount = 228000,
            tags = listOf("二手交易", "到手刀", "平台诈骗"),
            heroDrawableRes = R.drawable.t2,
            content = sharedCaseT2SecondhandScamArticle,
            warningTips = listOf(
                "坚持在平台内完成咨询、下单、付款与售后，拒绝对方以“方便”“省手续费”等理由引导至微信、支付宝私下交易。",
                "发货前后留存聊天记录、商品实拍与打包视频、物流单号；遇异常压价、拒收后扯皮，及时向平台投诉并保存证据。",
                "对异常低价求购、反复砍价、身份信息与收货信息不一致等保持警惕，纠纷升级时可向公安机关或消协等部门反映。",
            ),
        )
        "3" -> SharedCase(
            id = "fraud-carousel-3",
            title = "“屏幕共享”藏陷阱！",
            summary = "常州警方联动社区紧急劝阻：当事人屏幕共享16分钟、三张卡余额百万余元险遭转走。冒充客服以“关闭百万医疗险”设局，警方拆解套路与防范要点。",
            author = "上游新闻",
            date = "2026-04-10",
            readCount = 196000,
            tags = listOf("屏幕共享", "冒充客服", "反诈劝阻"),
            heroDrawableRes = R.drawable.t3,
            content = sharedCaseT3ScreenShareArticle,
            warningTips = listOf(
                "“屏幕共享”会让对方实时看到你的操作界面，切勿对陌生人开启；客服、公检法不会以此方式“指导关闭业务”或验资。",
                "不点击陌生链接、不下载来源不明的会议或远程软件；短信验证码、支付密码绝不向他人透露或在共享画面下输入。",
                "接到96110或属地警方预警来电请务必接听；发现可能被骗立即挂断、断网并报警，争取止付与挂失时间。",
            ),
        )
        "4" -> SharedCase(
            id = "fraud-carousel-4",
            title = "“投资”16万元险被骗",
            summary = "兰溪市民童先生被冒充券商人员以虚假盈利截图、“更高申购门槛”诱导两次转账共16万元；民警厉楠紧急止付全额拦截，当事人送锦旗致谢。",
            author = "人民法治",
            date = "2026-04-12",
            readCount = 184000,
            tags = listOf("虚假投资", "炒股诈骗", "紧急止付"),
            heroDrawableRes = R.drawable.t4,
            content = sharedCaseT4FakeInvestmentArticle,
            warningTips = listOf(
                "投资理财只选持牌正规机构与官方渠道，对陌生来电、群聊推荐的“老师带单”“内幕消息”一律保持警惕。",
                "不下载来源不明的“炒股”“理财”APP，不向陌生个人或非备案账户汇款；盈利截图、承诺“亏了就赔”多为伪造话术。",
                "牢记“保本保息、稳赚不赔”违反投资常识，多为诈骗；转账前与家人商量，发现异常立即报警并申请止付，可拨打110或96110。",
            ),
        )
        "5" -> SharedCase(
            id = "fraud-carousel-5",
            title = "卖号有风险，交易需谨慎！",
            summary = "杭州钱塘区未成年人小付出售游戏账号遭“中介担保费”骗局损失720元；网警结合案例提示：务必走官方平台，警惕保证金话术，家长需管好支付密码。",
            author = "杭州网警",
            date = "2024-12-20",
            readCount = 158000,
            tags = listOf("游戏账号", "未成年人保护", "网络诈骗"),
            heroDrawableRes = R.drawable.t5,
            content = sharedCaseT5GameAccountArticle,
            warningTips = listOf(
                "账号、装备交易只在游戏官方或平台认证的渠道完成，拒绝私下加微信、扫码付“担保费”“保证金”。",
                "凡以冻结、提现失败、解封为由要求继续充值的，一律视为诈骗，停止转账并保留证据报警。",
                "家长应保管手机与支付密码，教育孩子不扫陌生码、不点不明链接；被骗后第一时间告诉家长并拨打110。",
            ),
        )
        else -> null
    }

    fun generateQuizQuestions(): List<QuizQuestion> {
        // Randomly generate or select 10 questions
        return listOf(
            QuizQuestion(1, "接到自称公安局电话，说你涉嫌洗钱，要求转账到安全账户，你该怎么办？", listOf("立即转账", "挂断电话并拨打110核实", "询问对方警号", "告诉对方银行卡密码"), 1, QuizType.SINGLE_CHOICE),
            QuizQuestion(2, "收到短信称ETC过期，点击链接重新认证，这是诈骗吗？", listOf("是", "不是"), 0, QuizType.JUDGMENT),
            QuizQuestion(3, "朋友微信发来语音借钱，声音很像，应该？", listOf("直接转账", "视频或电话核实", "让他发身份证照片", "问他借多少"), 1, QuizType.SINGLE_CHOICE),
            QuizQuestion(4, "网购后客服打电话说商品有质量问题要退款，让你扫码，应该？", listOf("按对方指示操作", "在官方平台联系卖家核实", "提供银行卡号", "下载对方提供的APP"), 1, QuizType.SINGLE_CHOICE),
            QuizQuestion(5, "有人拉你进股票群，说有内幕消息，稳赚不赔，这是？", listOf("天上掉馅饼", "诈骗杀猪盘", "也是一种投资", "可以试试"), 1, QuizType.SINGLE_CHOICE),
            QuizQuestion(6, "收到“航班取消”短信，含改签链接，应该？", listOf("点击链接改签", "拨打航空公司官方电话核实", "回复短信询问", "转发给朋友"), 1, QuizType.SINGLE_CHOICE),
            QuizQuestion(7, "有人在网上高价收购你的银行卡，可以卖吗？", listOf("可以，反正没钱", "不可以，涉嫌帮信罪", "看价格", "只卖不用的卡"), 1, QuizType.SINGLE_CHOICE),
            QuizQuestion(8, "刷单兼职，做任务返利，靠谱吗？", listOf("靠谱", "不靠谱，这是诈骗", "看平台", "试试小额"), 1, QuizType.SINGLE_CHOICE),
            QuizQuestion(9, "收到“领导”微信添加好友，让你转账应急，应该？", listOf("立即转账", "当面或电话核实", "不好意思拒绝", "问他是不是本人"), 1, QuizType.SINGLE_CHOICE),
            QuizQuestion(10, "下载APP贷款，客服说卡号填错要交解冻费，这是？", listOf("正常流程", "诈骗", "系统故障", "银行规定"), 1, QuizType.SINGLE_CHOICE)
        )
    }
}

private val sharedCaseNewsArticle238w = """
    |案例一：八旬老人险失258万
    |起底社保卡骗局
    |
    |骗子盯上老年人，养老钱成了他们的诈骗目标。冒充社保工作人员、谎称新卡旧卡同时使用违规，这是专门针对老年人的社保卡骗局，浙江杭州一名八旬老人就落入了这样的圈套，她的手机被远程控制，银行卡里的258万元差点没保住，幸亏警方及时上门劝阻。
    |
    |3月20日下午1时左右，杭州市公安局上城区分局凯旋派出所接到反诈预警称，辖区82岁的陈某疑似遭遇电信网络诈骗，凯旋派出所反诈队员苏世伟第一时间拨打了她的电话，电话那头传来的却是一名年轻男子的声音，未等反诈队员苏世伟表明身份，对方就直接挂断了电话，凭借多年反诈经验，反诈队员苏世伟敏锐察觉到异常，陈某的手机极有可能被设置了呼叫转移。于是迅速赶到了陈某家中。
    |
    |杭州市公安局上城区分局凯旋派出所反诈队员 苏世伟：我问当事人，我说你刚才是不是接到一个电话，她说有的，我说是什么内容，她说是医保局，说她有医保卡违规了，那我说这个是诈骗电话，然后我说你手机在哪里，她把手机从床头柜上拿起来给我，我当时一看黑屏，这个就是比较典型的被远程控制的状态。
    |
    |被远程操控的手机无法执行关机等任何操作，反诈队员苏世伟赶紧拔掉了无线路由器，成功将手机重启。
    |
    |杭州市公安局上城区分局凯旋派出所反诈队员 苏世伟：重启之后，把正在运行的视频会议软件卸载掉了。我看她的手机上面有银行短信提示有钱转出去的，我问她卡里有多少钱，她说大约有几百万，一看短信，发现其中有一笔20万已经支出到其他账户上去了，在看到明确这一笔转账的接收账户之后，我马上把相关的资料发到我们的工作群，申请对这笔转账账户进行紧急止付。
    |
    |在杭州市公安局上城区分局反诈中心开展紧急处置工作的同时，反诈队员苏世伟迅速陪同陈某前往银行，对她名下的其他账户进行了安全性核查。
    |
    |杭州市公安局上城区分局凯旋派出所反诈队员 苏世伟：骗子正在转还没有被转走的是238万元，已经转走的20万元，在我们反诈中心的工作操作下，也是在嫌疑人账户中被全额止付住了。
    |
    |据民警事后询问得知，当天陈某接到一个自称“杭州社保局工作人员”的电话，对方称她名下有两张医保卡，需配合注销其中一张，陈某没多想便同意了。随后，在对方引导下，陈某下载了一款远程操控软件并开启相应权限。骗子获得授权后，立即远程操控她的手机设置呼叫转移，随即着手转移账户资金。
    |
    |杭州市公安局上城区分局反诈中心民警 陈林军：这是一起典型的冒充身份类诈骗，骗子冒充医保局的工作人员，谎称受害人名下有两张社保卡同时在使用，涉嫌违规，需要处理。实际上这里有一个关键的常识需要大家了解，目前在我们杭州，只要您申领并激活了新的第三代社保卡，旧卡就会自动失效，并不存在两张卡冲突的情况，所以这就是一个骗局。
    |
    |警方提醒：凡是接到自称官方工作人员的电话，首要任务是核实对方的身份，切勿随意下载不明软件或点击不明链接更不要向对方提供银行账户、密码、验证码等重要信息。
    |
    |杭州市公安局上城区分局反诈中心民警 陈林军：遇到这种情况千万别慌，先挂断电话，然后第一时间到就近的公安机关或者拨打96110反诈专线进行咨询。如果您发现身边的亲人朋友遇到类似的骗局，手机出现黑屏等异常情况，也可以采取以下的阻断措施，先拔出手机卡再重启手机，或者直接断开手机的网络连接，阻断骗子的远程操控。
    |
    |案例二：老人沉迷“刷单”赚钱
    |40万元险被骗
    |
    |同样针对老年群体，诈骗套路层出不穷，江苏南京一位老人被刷单返利诱惑，40万元的积蓄险些被骗。
    |
    |南京市公安局雨花台分局赛虹桥派出所民警 余昭希：我们接到了来自反诈中心下发来的反诈预警指令，称我们辖区有一名老人，疑似下载了一款名为“光信”的涉诈App，我们第一时间与这名老人说明了情况，希望她有空可以到所里来一趟。
    |
    |老人称家里有事不愿到派出所。担心其继续被诱导，民警决定直接上门。
    |
    |老人说，点赞视频就能拿“佣金”，对方又以“长期合作、赚得更多”为由，诱导她下载涉诈软件。民警现场劝阻后，老人仍不愿相信，甚至质疑民警身份，家人劝说也无效。更让人揪心的是，其卡内还有40万元。民警随即将老人及其家人带回派出所继续劝防。
    |
    |南京市公安局雨花台分局赛虹桥派出所民警 余昭希：到所后，我们发现老人手机里的涉诈软件下载时间并不长，犯罪分子正在引导其填写身份信息，注册网店，向老人推荐虚拟“畅销产品”，一步步引诱老人向骗子转账。
    |
    |民警耐心剖析刷单诈骗套路后，老人逐渐认清本质，并在民警帮助下当场卸载涉诈软件。随后老人认识到刷单风险，承诺不再参与此类活动。
    |
    |来源：央视新闻
""".trimMargin()
