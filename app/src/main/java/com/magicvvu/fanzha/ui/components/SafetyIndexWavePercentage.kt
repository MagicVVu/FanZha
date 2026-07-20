package com.magicvvu.fanzha.ui.components

import androidx.annotation.FloatRange
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 首页安全指数专用 Wave Percentage：
 * - 浅冷白圆形底 + 更粗浅蓝外环；液面为双层错位波纹（前后半透明，配色随安全指数红→青蓝过渡）
 * - 安全系数 0-100：波纹主色在红系与青绿系之间插值（0.5s ease-in-out）
 * - 波浪边界：Cubic Bezier（Catmull-Rom -> Bezier）
 * - 硬件加速：graphicsLayer(offscreen)
 */
@Composable
fun SafetyIndexWavePercentage(
    @FloatRange(from = 0.0, to = Float.MAX_VALUE.toDouble())
    currentPercentage: Float,
    @FloatRange(from = 0.0, to = Float.MAX_VALUE.toDouble())
    maxPercentage: Float,
    circularSize: Int = 100,
    @Suppress("UNUSED_PARAMETER")
    backgroundColor: Color = Color.White.copy(alpha = 0.35f), // 保留参数以兼容调用方；液面改为双色波纹，不再使用单色蒙层
    percentageAnimationDuration: Int = 1500,
    waveFrequency: Float = 0.6f,
    waveAmplitude: Float = 13f,
    waveAnimationDuration: Int = 500,
    continuousWaveAnimationDuration: Int = 2500,
    centerTextStyle: TextStyle,
    /** 中心文字（例如保留两位小数）。为空时默认展示整数。 */
    centerText: String? = null,
    /** 为 true 时不再播放 0→当前值 的首次填充动画（切换 Tab 回到首页等场景） */
    skipInitialFillAnimation: Boolean = false,
    onInitialFillAnimationFinished: () -> Unit = {},
) {
    require(maxPercentage > 0f) { "maxPercentage must be > 0" }
    require(circularSize >= 0) { "circularSize must be >= 0" }

    val clampedCurrent = currentPercentage.coerceIn(0f, maxPercentage)
    val density = LocalDensity.current
    val outerShadowDp = with(density) { 5f.toDp() }
    val outerShadowColor = Color(0x1A000000) // rgba(0,0,0,0.1)

    // 0.5s ease-in-out (CSS-like)
    val easeInOutEasing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

    // Clamp wave cycle to 2-3s
    val waveCycleMillis = continuousWaveAnimationDuration.coerceIn(2000, 3000)

    val animatedPercentage = remember { Animatable(0f) }
    val animatedColorPercentage = remember { Animatable(0f) }
    val animatedPhase = remember { Animatable(0f) }

    val targetPercentage by rememberUpdatedState(clampedCurrent)

    val wavePathBack = remember { Path() }
    val wavePathFront = remember { Path() }
    val numPoints = 40
    val yPointsBack = remember { FloatArray(numPoints + 1) }
    val yPointsFront = remember { FloatArray(numPoints + 1) }

    val phase2Pi = (2 * PI).toFloat()

    // 首次进入：0 → 目标（波浪液面 + 数字）；之后仅同步到当前值并保留连续波纹相位动画
    LaunchedEffect(skipInitialFillAnimation) {
        if (!skipInitialFillAnimation) {
            animatedPercentage.snapTo(0f)
            animatedColorPercentage.snapTo(0f)
            coroutineScope {
                launch {
                    animatedPercentage.animateTo(
                        targetValue = targetPercentage,
                        animationSpec = tween(
                            durationMillis = percentageAnimationDuration,
                            easing = easeInOutEasing
                        )
                    )
                }
                launch {
                    animatedColorPercentage.animateTo(
                        targetValue = targetPercentage,
                        animationSpec = tween(
                            durationMillis = percentageAnimationDuration,
                            easing = easeInOutEasing
                        )
                    )
                }
            }
            onInitialFillAnimationFinished()
        }
    }

    LaunchedEffect(skipInitialFillAnimation, clampedCurrent) {
        if (skipInitialFillAnimation) {
            coroutineScope {
                launch {
                    animatedPercentage.snapTo(clampedCurrent)
                }
                launch {
                    animatedColorPercentage.snapTo(clampedCurrent)
                }
            }
        }
    }

    // 4) 连续波动：2-3秒周期
    LaunchedEffect(Unit) {
        animatedPhase.animateTo(
            targetValue = phase2Pi,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = waveCycleMillis, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            )
        )
    }

    Box(
        modifier = Modifier
            .size(circularSize.dp)
            .shadow(elevation = outerShadowDp, shape = CircleShape, clip = false, ambientColor = outerShadowColor, spotColor = outerShadowColor)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
        ) {
            val radius = size.minDimension / 2f
            val c = Offset(size.width / 2f, size.height / 2f)
            val circleClip = Path().apply {
                addOval(Rect(center = c, radius = radius))
            }

            val safetyIndex = (animatedColorPercentage.value / maxPercentage).coerceIn(0f, 1f) * 100f
            val (waveDark, waveLight) = safetyWaveLiquidColors(safetyIndex)

            val tTarget = (clampedCurrent / maxPercentage).coerceIn(0f, 1f)
            val smoothTTarget = tTarget * tTarget * (3f - 2f * tTarget)
            val amplitudeMultiplierTarget = lerp(start = 1.12f, stop = 0.25f, fraction = smoothTTarget)
            val amplitudePx = waveAmplitude * (size.height / 100f) * amplitudeMultiplierTarget

            val phaseFront = animatedPhase.value
            val phaseBack = phaseFront + PI.toFloat()

            clipPath(circleClip) {
                drawCircle(color = WaveInnerBackground, radius = radius, center = c)

                fillWaveRegionBelow(
                    path = wavePathBack,
                    yPoints = yPointsBack,
                    actualPercentageToShow = animatedPercentage.value,
                    waveFrequency = waveFrequency,
                    amplitudePx = amplitudePx,
                    wavePhase = phaseBack,
                    maxPercentage = maxPercentage,
                    numPoints = numPoints,
                )
                drawPath(wavePathBack, color = waveLight)

                fillWaveRegionBelow(
                    path = wavePathFront,
                    yPoints = yPointsFront,
                    actualPercentageToShow = animatedPercentage.value,
                    waveFrequency = waveFrequency,
                    amplitudePx = amplitudePx,
                    wavePhase = phaseFront,
                    maxPercentage = maxPercentage,
                    numPoints = numPoints,
                )
                drawPath(wavePathFront, color = waveDark)
            }

            val ringW = 6.5.dp.toPx()
            drawCircle(
                color = WaveOuterRingColor,
                radius = (radius - ringW / 2f).coerceAtLeast(0f),
                center = c,
                style = Stroke(width = ringW),
            )
        }

        Text(
            text = centerText ?: animatedPercentage.value.toInt().toString(),
            style = centerTextStyle
        )
    }
}

private fun lerp(start: Float, stop: Float, fraction: Float): Float {
    val f = fraction.coerceIn(0f, 1f)
    return start + (stop - start) * f
}

private val WaveInnerBackground = Color(0xFFF8FAFC)
/** 更浅的天蓝 + 略透明，叠在浅底上更柔和 */
private val WaveOuterRingColor = Color(0xFFB9E0FC).copy(alpha = 0.85f)

/**
 * 前层略实、后层更透；低安全偏玫红/珊瑚，高安全偏青绿并与外环蓝系协调。
 */
private fun safetyWaveLiquidColors(safetyIndex: Float): Pair<Color, Color> {
    val t = (safetyIndex / 100f).coerceIn(0f, 1f)
    val darkBase = lerpColor(Color(0xFFBE123C), Color(0xFF0E7490), t)
    val lightBase = lerpColor(Color(0xFFF97316), Color(0xFF67E8F9), t)
    val front = darkBase.copy(alpha = 0.62f)
    val back = lightBase.copy(alpha = 0.36f)
    return front to back
}

private fun lerpColor(start: Color, end: Color, t: Float): Color =
    Color(
        red = lerp(start.red, end.red, t),
        green = lerp(start.green, end.green, t),
        blue = lerp(start.blue, end.blue, t),
        alpha = lerp(start.alpha, end.alpha, t)
    )

// ---------------- Wave drawing (cubic bezier), liquid below wave line ----------------

private fun DrawScope.fillWaveRegionBelow(
    path: Path,
    yPoints: FloatArray,
    actualPercentageToShow: Float,
    waveFrequency: Float,
    amplitudePx: Float,
    wavePhase: Float,
    maxPercentage: Float,
    numPoints: Int,
) {
    val normalizedPercentage = 1f - (actualPercentageToShow / maxPercentage)
    val fillHeightFromBottom = size.height * normalizedPercentage
    val centerX = size.width / 2f
    val step = size.width / numPoints.toFloat()
    for (i in 0..numPoints) {
        val x = i * step
        val angle = (waveFrequency / size.width * 2f * PI.toFloat() * (x - centerX) + PI.toFloat() / 2f + wavePhase)
        yPoints[i] = (fillHeightFromBottom + amplitudePx * sin(angle.toDouble())).toFloat()
    }

    path.reset()
    path.moveTo(0f, yPoints[0])
    for (i in 0 until numPoints) {
        val i0 = max(0, i - 1)
        val i1 = i
        val i2 = i + 1
        val i3 = min(numPoints, i + 2)

        val x1 = i1 * step
        val x2 = i2 * step
        val y1 = yPoints[i1]
        val y2 = yPoints[i2]
        val y0 = yPoints[i0]
        val y3 = yPoints[i3]

        val c1x = x1 + step / 3f
        val c1y = y1 + (y2 - y0) / 6f
        val c2x = x2 - step / 3f
        val c2y = y2 - (y3 - y1) / 6f

        path.cubicTo(c1x, c1y, c2x, c2y, x2, y2)
    }
    path.lineTo(size.width, size.height)
    path.lineTo(0f, size.height)
    path.close()
}

