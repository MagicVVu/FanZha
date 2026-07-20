package com.magicvvu.fanzha.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.magicvvu.fanzha.ui.theme.LocalThemePreferenceController
import com.magicvvu.fanzha.ui.components.AppTextField
import com.magicvvu.fanzha.ui.components.AppTopBar
import com.magicvvu.fanzha.ui.components.LiquidButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SmsVerificationScreen(
    onBack: () -> Unit,
    onLoginSuccess: () -> Unit
) {
    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var countdown by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

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
        Column(modifier = Modifier.fillMaxSize()) {
            AppTopBar(title = "短信登录", onBack = onBack)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(top = 8.dp)
            ) {
                Text(
                    text = "请输入手机号，获取短信验证码完成登录",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(20.dp))

                AppTextField(
                    value = phone,
                    onValueChange = { if (it.length <= 11) phone = it.filter { c -> c.isDigit() } },
                    label = "手机号",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
                )
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AppTextField(
                        value = code,
                        onValueChange = { if (it.length <= 6) code = it.filter { c -> c.isDigit() } },
                        label = "验证码",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = {
                            if (phone.length != 11 || countdown > 0) return@TextButton
                            scope.launch {
                                countdown = 60
                                while (countdown > 0) {
                                    delay(1000)
                                    countdown--
                                }
                            }
                        },
                        enabled = phone.length == 11 && countdown == 0,
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Text(
                            text = if (countdown > 0) "${countdown}s" else "发送验证码",
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Spacer(modifier = Modifier.height(28.dp))

                LiquidButton(
                    onClick = {
                        if (phone.length == 11 && code.length >= 4) {
                            onLoginSuccess()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(25.dp)
                ) {
                    Text(
                        text = "登录",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }
        }
    }
}
