package com.magicvvu.fanzha.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.magicvvu.fanzha.ui.theme.LocalThemePreferenceController
import com.magicvvu.fanzha.ui.components.AppTextField
import com.magicvvu.fanzha.ui.components.AppTopBar
import com.magicvvu.fanzha.ui.components.LiquidButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ForgotPasswordScreen(
    onBack: () -> Unit,
    onSuccess: () -> Unit
) {
    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var countdown by remember { mutableIntStateOf(0) }
    var showSuccess by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val primaryBlue = Color(0xFF4A90E2)

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
            AppTopBar(title = "找回密码", onBack = onBack)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
            ) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "验证手机号后即可重置密码",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF8AA0B8)
                )

                Spacer(modifier = Modifier.weight(1f))

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(
                            elevation = 6.dp,
                            shape = RoundedCornerShape(24.dp),
                            spotColor = Color.Black.copy(alpha = 0.07f)
                        ),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 28.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        AppTextField(
                            value = phone,
                            onValueChange = { if (it.length <= 11) phone = it.filter { c -> c.isDigit() } },
                            label = "手机号",
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
                        )

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
                                shape = RoundedCornerShape(18.dp),
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = primaryBlue
                                )
                            ) {
                                Text(
                                    text = if (countdown > 0) "${countdown}s 后重发" else "发送验证码",
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 13.sp
                                )
                            }
                        }

                        AppTextField(
                            value = newPassword,
                            onValueChange = { newPassword = it },
                            label = "新密码",
                            visualTransformation = PasswordVisualTransformation()
                        )

                        AppTextField(
                            value = confirmPassword,
                            onValueChange = { confirmPassword = it },
                            label = "确认新密码",
                            visualTransformation = PasswordVisualTransformation()
                        )

                        errorMessage?.let { msg ->
                            Text(
                                text = msg,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        LiquidButton(
                            onClick = {
                                errorMessage = when {
                                    phone.length != 11 -> "请输入正确的手机号"
                                    code.length < 4 -> "请输入验证码"
                                    newPassword.length < 6 -> "密码至少需要 6 位"
                                    newPassword != confirmPassword -> "两次密码输入不一致"
                                    else -> null
                                }
                                if (errorMessage == null) {
                                    showSuccess = true
                                    scope.launch {
                                        delay(1800)
                                        onSuccess()
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            shape = RoundedCornerShape(25.dp)
                        ) {
                            Text(
                                text = "重置密码",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.weight(1f))
            }
        }

        // 成功蒙层
        AnimatedVisibility(
            visible = showSuccess,
            enter = fadeIn(tween(300)) + slideInVertically(tween(380)) { it / 4 }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xF2FFFFFF)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = primaryBlue,
                        modifier = Modifier.size(72.dp)
                    )
                    Text(
                        "密码重置成功",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1A202C)
                        )
                    )
                    Text(
                        "即将返回登录页面...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF64748B)
                    )
                }
            }
        }
    }
}
