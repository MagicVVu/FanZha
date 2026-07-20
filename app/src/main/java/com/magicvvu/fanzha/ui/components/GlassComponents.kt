package com.magicvvu.fanzha.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.ui.geometry.Offset

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp),
    /** 安全指数等需要与页面背景明显区分的模块：更不透明、偏冷灰蓝，拟态玻璃感更强 */
    distinctPanelBackground: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    val glassGradient = if (distinctPanelBackground) {
        val hi = MaterialTheme.colorScheme.surfaceContainerHigh
        Brush.linearGradient(
            colors = listOf(
                Color(0xFFF5F8FF).copy(alpha = 0.97f),
                hi.copy(alpha = 0.94f),
                Color(0xFFE3EAF5).copy(alpha = 0.96f)
            ),
            start = Offset(0f, 0f),
            end = Offset(0f, Float.POSITIVE_INFINITY)
        )
    } else {
        Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.4f),
                Color.White.copy(alpha = 0.1f)
            ),
            start = Offset(0f, 0f),
            end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
        )
    }

    val glassBorder = if (distinctPanelBackground) {
        Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.75f),
                Color.White.copy(alpha = 0.25f),
                Color(0xFF90A4AE).copy(alpha = 0.22f)
            ),
            start = Offset(0f, 0f),
            end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
        )
    } else {
        Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.5f),
                Color.White.copy(alpha = 0.1f)
            ),
            start = Offset(0f, 0f),
            end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
        )
    }

    Box(
        modifier = modifier
            .clip(shape)
            .background(glassGradient)
            .border(1.dp, glassBorder, shape)
            // Note: In a real Android 12+ app, we could use RenderEffect for background blur.
            // For general compatibility, we rely on the semi-transparent gradient.
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            content = content
        )
    }
}

@Composable
fun LiquidButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(18.dp),
    gradientColors: List<Color> = listOf(
        Color(0xFF9BD1FF),
        Color(0xFF72BAFF),
        Color(0xFF4FA7F7)
    ),
    contentColor: Color = Color.White,
    content: @Composable RowScope.() -> Unit
) {
    // Gradient simulating "Liquid Glass" in light-blue tone
    val liquidGradient = Brush.linearGradient(
        colors = gradientColors
    )

    // Shine effect for glass look
    val shineGradient = Brush.linearGradient(
        colors = listOf(
            Color.White.copy(alpha = 0.4f),
            Color.Transparent,
            Color.Transparent
        ),
        start = androidx.compose.ui.geometry.Offset(0f, 0f),
        end = androidx.compose.ui.geometry.Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
    )

    Box(
        modifier = modifier
            .clip(shape)
            .clickable(enabled = enabled, onClick = onClick)
            .background(liquidGradient) // Base liquid color
            .border(1.dp, Color.White.copy(alpha = 0.3f), shape), // Subtle rim
        contentAlignment = Alignment.Center
    ) {
        // Shine Overlay
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(shineGradient)
        )

        // Content
        ProvideTextStyle(value = MaterialTheme.typography.titleMedium.copy(color = contentColor)) {
            Row(
                modifier = Modifier
                    .defaultMinSize(
                        minWidth = ButtonDefaults.MinWidth,
                        minHeight = ButtonDefaults.MinHeight
                    )
                    .padding(PaddingValues(horizontal = 24.dp, vertical = 8.dp)),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                content = content
            )
        }
    }
}
