package com.magicvvu.fanzha.domain

import java.time.LocalDate
import kotlin.math.pow

/**
 * 《安全指数数学模型》在客户端的冷启动复现（与 Word 报告一致）。
 *
 * | 文档 | 内容 | 实现 |
 * |-----|------|------|
 * | 2.1 | 正向极差归一化、max=min 时 x′=0.5 | [interceptMetricScore] |
 * | 2.2 | x′age=(age−7)/88，乘 Rage，V5=x′age·Rage·100 | [ageMetricScore]、[ageIntervalCoefficient] |
 * | 2.3 | V6=cgender×100（女0.95/男1.00/未知0.975） | [genderMetricScore] |
 * | 2.4 | V7=coccup×100（各大类 c 与报告表一致） | [occupationMetricScore] |
 * | 2.5 | V8=(xquiz_raw/150)×100 | [quizMetricScore] |
 * | 3.1–3.3 | AHP×熵权组合、熵权 | 需 m≥1000，端侧不计算 |
 * | 3.4 | 冷启动预设权重 | [PRESET_WEIGHTS] 作为 4.2 的 Wⱼ（示例 5.7 同表） |
 * | 4.1 | 各 Vⱼ 定义 | [compute] |
 * | 4.2 | Sbase=Σ Wⱼ·Vⱼ | [compute] |
 * | 4.3 | Δ=min(δmonth+δholiday,0.2)，λ=1−Δ | [timeRiskDelta]；S=Sbase·(1−Δ) |
 * | 4.4–4.5 | 最终 S∈[0,100]、负值浮点容差 | [compute] |
 * | 5.2 | X₁–X₄ 全局 min/max | [X*_MIN]/[X*_MAX] |
 *
 * 缺失项（报告未给显式规则）：年龄/问卷/职业大类未填时的中性插补见各函数注释。
 */
object SafetyIndexCalculator {

    /** 文档 3.4 冷启动预设权重，在 4.2 中作为 Wⱼ（等同无样本时的 W_combined）。 */
    private val PRESET_WEIGHTS = doubleArrayOf(
        0.20, // X₁ 电话拦截
        0.12, // X₂ 短信
        0.10, // X₃ APP
        0.08, // X₄ 剪切板
        0.12, // X₅ 年龄
        0.06, // X₆ 性别
        0.14, // X₇ 职业大类
        0.18, // X₈ 问卷（150 分制转百分制）
    )

    /** 文档 5.2 冷启动全局 min/max */
    private const val X1_MIN = 0.0
    private const val X1_MAX = 10.0
    private const val X2_MIN = 0.0
    private const val X2_MAX = 20.0
    private const val X3_MIN = 0.0
    private const val X3_MAX = 5.0
    private const val X4_MIN = 0.0
    private const val X4_MAX = 10.0

    private const val QUIZ_RAW_MAX = 150.0

    /**
     * 体验增强：为了让用户在“低拦截量”阶段也能感知到分数变化，对拦截类归一化结果做温和放大。
     *
     * - 不改变 0–100 的边界（放大后仍截断到 1.0）。
     * - 不影响年龄/性别/职业/问卷的计算。
     *
     * 注意：该放大系数属于“产品体验调参”，并非报告原始公式的一部分；如需严格论文级复现，可改回 1.0。
     */
    private const val INTERCEPT_SCORE_BOOST = 2.2
    /** 灵敏度增强：对低计数区间做开方型拉伸（γ<1 会放大低值差异）。 */
    private const val INTERCEPT_GAMMA = 0.65

    /**
     * @param quizRawScore 问卷原始分 0–150；未测过传 null 时用中性 50 分（百分制）参与加权。
     */
    fun compute(
        callCount: Int,
        smsCount: Int,
        suspiciousAppCount: Int,
        clipboardCount: Int,
        age: Int?,
        gender: String?,
        occupationCategoryName: String?,
        quizRawScore: Int?,
        referenceDate: LocalDate = LocalDate.now(),
    ): Int {
        val precise = computePrecise(
            callCount = callCount,
            smsCount = smsCount,
            suspiciousAppCount = suspiciousAppCount,
            clipboardCount = clipboardCount,
            age = age,
            gender = gender,
            occupationCategoryName = occupationCategoryName,
            quizRawScore = quizRawScore,
            referenceDate = referenceDate,
        )
        return toIntIndex(precise)
    }

    /**
     * 计算安全指数精确值（用于 UI 展示两位小数）。
     *
     * 返回值范围固定在 [0,100]。
     */
    fun computePrecise(
        callCount: Int,
        smsCount: Int,
        suspiciousAppCount: Int,
        clipboardCount: Int,
        age: Int?,
        gender: String?,
        occupationCategoryName: String?,
        quizRawScore: Int?,
        referenceDate: LocalDate = LocalDate.now(),
    ): Double {
        val v1 = interceptMetricScore(callCount.toDouble(), X1_MIN, X1_MAX, boost = INTERCEPT_SCORE_BOOST)
        val v2 = interceptMetricScore(smsCount.toDouble(), X2_MIN, X2_MAX, boost = INTERCEPT_SCORE_BOOST)
        val v3 = interceptMetricScore(suspiciousAppCount.toDouble(), X3_MIN, X3_MAX, boost = INTERCEPT_SCORE_BOOST)
        val v4 = interceptMetricScore(clipboardCount.toDouble(), X4_MIN, X4_MAX, boost = INTERCEPT_SCORE_BOOST)
        val v5 = ageMetricScore(age)
        val v6 = genderMetricScore(gender)
        val v7 = occupationMetricScore(occupationCategoryName)
        val v8 = quizMetricScore(quizRawScore)

        val values = doubleArrayOf(v1, v2, v3, v4, v5, v6, v7, v8)
        // 4.2 Sbase = Σ Wj·Vj（此处 Wj 为 3.4 预设权重）
        var sBase = 0.0
        for (i in values.indices) {
            sBase += PRESET_WEIGHTS[i] * values[i]
        }

        val delta = timeRiskDelta(referenceDate)
        // 4.3–4.4：λ(t)=1−Δ(t)，S=Sbase·λ(t)
        var s = sBase * (1.0 - delta)
        // 4.5：(-0.1,0) 内浮点误差归零
        if (s < 0.0 && s >= -0.1) {
            s = 0.0
        }
        s = s.coerceIn(0.0, 100.0)
        return s
    }

    /** 将精确值转换为前端展示/阈值使用的整数安全指数：直接舍弃小数部分。 */
    fun toIntIndex(precise: Double): Int =
        precise.coerceIn(0.0, 100.0).toInt().coerceIn(0, 100)

    /**
     * 文档 2.1：正向极差归一化后 Vⱼ = x′×100；max=min 时 x′=0.5。
     *
     * @param boost 体验增强用放大系数：先计算 x′，再做 x′·boost 并截断到 [0,1]。
     */
    private fun interceptMetricScore(x: Double, min: Double, max: Double, boost: Double = 1.0): Double {
        val xPrime = if (max == min) {
            0.5
        } else {
            ((x - min) / (max - min)).coerceIn(0.0, 1.0)
        }
        val curved = xPrime.pow(INTERCEPT_GAMMA)
        val amplified = (curved * boost).coerceIn(0.0, 1.0)
        return amplified * 100.0
    }

    /**
     * 文档 2.2 + 4.1：x′age=(age−7)/88，再乘区间 Rage，V5=x′age×Rage×100。
     * age 限制在 [7,95] 再代入公式，避免 x′age 越界；未填年龄：取 x′age=0.5、Rage=0.90（中性，报告未定义）。
     */
    private fun ageMetricScore(age: Int?): Double {
        if (age == null) {
            return 0.5 * 0.90 * 100.0
        }
        val clamped = age.coerceIn(7, 95)
        val xPrime = (clamped - 7) / 88.0
        val rAge = ageIntervalCoefficient(clamped)
        return xPrime * rAge * 100.0
    }

    /** 文档 2.2 年龄区间 Rage（70 岁含在 56–70 档；71 岁及以上 0.75）。 */
    private fun ageIntervalCoefficient(age: Int): Double = when (age) {
        in 7..15 -> 0.85
        in 16..25 -> 0.70
        in 26..35 -> 0.80
        in 36..45 -> 0.90
        in 46..55 -> 0.95
        in 56..70 -> 0.85
        else -> if (age > 70) 0.75 else 0.85
    }

    /** 文档 2.3：V6=cgender×100（女0.95/男1.00/未知0.975）；兼容常见英文枚举。 */
    private fun genderMetricScore(gender: String?): Double {
        val g = gender?.trim().orEmpty()
        val c = when {
            g.isEmpty() -> 0.975
            g.contains("女") -> 0.95
            g.contains("男") -> 1.00
            g.equals("f", ignoreCase = true) ||
                g.equals("female", ignoreCase = true) -> 0.95
            g.equals("m", ignoreCase = true) ||
                g.equals("male", ignoreCase = true) -> 1.00
            else -> 0.975
        }
        return c * 100.0
    }

    /** 文档 2.4：V7=coccup×100；按职业大类名称关键字映射（与库表 category_name 对齐）。 */
    private fun occupationMetricScore(categoryName: String?): Double {
        val n = categoryName?.trim().orEmpty()
        val c = if (n.isEmpty()) {
            0.95
        } else {
            when {
                n.contains("信息技术") || n.contains("软件") || n.contains("互联网") -> 1.10
                n.contains("法律") || n.contains("公共服务") || n.contains("公务员") -> 1.05
                n.contains("医疗") || n.contains("卫生") -> 1.00
                n.contains("教育") || n.contains("科研") -> 0.70
                n.contains("金融") || n.contains("商务") -> 0.85
                n.contains("生产") || n.contains("基础服务") -> 0.75
                n.contains("建筑") || n.contains("工程") -> 0.95
                n.contains("文化") || n.contains("艺术") || n.contains("创意") -> 0.95
                else -> 0.95
            }
        }
        return c * 100.0
    }

    /** 文档 2.5 + 4.1：V8=(raw/150)×100；未测过：V8=50（中性，报告未定义缺省）。 */
    private fun quizMetricScore(raw: Int?): Double {
        if (raw == null) {
            return 50.0
        }
        val r = raw.toDouble().coerceIn(0.0, QUIZ_RAW_MAX)
        return (r / QUIZ_RAW_MAX) * 100.0
    }

    /**
     * 文档 4.3：Δ(t)=min(δmonth(m)+δholiday(h), 0.2)。
     * δmonth 与表「1–2月…12月」一致；δholiday 为表中各节叠加（普通日 0）。
     */
    private fun timeRiskDelta(date: LocalDate): Double {
        val month = date.monthValue
        val day = date.dayOfMonth

        val deltaMonth = when (month) {
            1, 2 -> 0.15
            3 -> 0.08
            in 4..6 -> 0.0
            in 7..8 -> 0.10
            9 -> 0.15
            in 10..11 -> 0.12
            12 -> 0.08
            else -> 0.0
        }

        var deltaHoliday = 0.0

        // 春节前 7 天至元宵节：公历近似 1/20–2/24（农历可后续替换）
        if ((month == 1 && day >= 20) || (month == 2 && day <= 24)) {
            deltaHoliday += 0.10
        }

        // “双十一”期间（11.1–11.15）
        if (month == 11 && day in 1..15) {
            deltaHoliday += 0.08
        }

        // 高考/中考前后（6.5–6.20）
        if (month == 6 && day in 5..20) {
            deltaHoliday += 0.05
        }

        // 国庆长假
        if (month == 10 && day in 1..7) {
            deltaHoliday += 0.05
        }

        // 文档示例 5.8：9 月开学窗口额外 +0.05
        if (month == 9 && day in 1..20) {
            deltaHoliday += 0.05
        }

        return (deltaMonth + deltaHoliday).coerceAtMost(0.2)
    }
}
