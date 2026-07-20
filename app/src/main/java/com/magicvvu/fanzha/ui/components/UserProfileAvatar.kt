package com.magicvvu.fanzha.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.magicvvu.fanzha.ui.viewmodels.UserProfile
import java.io.File

@Composable
fun UserProfileAvatar(
    user: UserProfile,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    textStyle: TextStyle = MaterialTheme.typography.titleLarge,
) {
    UserProfileAvatar(
        name = user.name,
        avatarUri = user.avatarUri,
        fallbackAvatarColor = Color((user.avatarColor and 0xFFFFFFFFL).toInt()),
        modifier = modifier,
        size = size,
        textStyle = textStyle,
    )
}

@Composable
fun UserProfileAvatar(
    name: String,
    avatarUri: String?,
    fallbackAvatarColor: Color,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    textStyle: TextStyle = MaterialTheme.typography.titleLarge,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (!avatarUri.isNullOrBlank()) {
            val model: Any = if (avatarUri.startsWith("/")) File(avatarUri) else avatarUri
            AsyncImage(
                model = model,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(fallbackAvatarColor),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = name.firstOrNull()?.toString() ?: "用",
                    style = textStyle,
                    color = Color.White,
                )
            }
        }
    }
}
