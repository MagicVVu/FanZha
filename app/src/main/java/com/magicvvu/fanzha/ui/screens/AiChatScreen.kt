package com.magicvvu.fanzha.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.magicvvu.fanzha.ui.viewmodels.AiChatViewModel
import com.magicvvu.fanzha.ui.viewmodels.ChatAttachmentKind
import com.magicvvu.fanzha.ui.viewmodels.ChatMessage
import com.magicvvu.fanzha.ui.viewmodels.PendingChatAttachment

private const val MAX_CHAT_ATTACHMENTS = 3

@Composable
fun AiChatScreen(viewModel: AiChatViewModel = viewModel()) {
    val messages by viewModel.messages.collectAsState()
    val pendingAttachments by viewModel.pendingAttachments.collectAsState()
    var inputText by remember { mutableStateOf("") }
    val context = LocalContext.current

    val listState = rememberLazyListState()

    fun handleSend() {
        if (inputText.isBlank() && pendingAttachments.isEmpty()) return
        viewModel.sendMessage(context, inputText)
        inputText = ""
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    // Responsive layout container
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isWideScreen = maxWidth > 600.dp

        if (isWideScreen) {
            // Tablet/Landscape mode: Side by side
            Row(modifier = Modifier.fillMaxSize()) {
                // Messages List (Left)
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    MessageList(messages, listState, Modifier.fillMaxSize())
                }

                VerticalDivider(modifier = Modifier.fillMaxHeight().width(1.dp))

                // Input Area (Right)
                Box(modifier = Modifier.width(350.dp).fillMaxHeight(), contentAlignment = Alignment.BottomCenter) {
                    ChatInputArea(
                        inputText = inputText,
                        onInputTextChanged = { inputText = it },
                        onSend = { handleSend() },
                        pendingAttachments = pendingAttachments,
                        onRemovePendingAttachment = viewModel::removePendingAttachment,
                        maxAttachments = MAX_CHAT_ATTACHMENTS,
                        onAttachmentPicked = { uri, kind -> viewModel.addPickedAttachment(context, uri, kind) },
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                }
            }
        } else {
            // Mobile mode: Top/Bottom
            Column(modifier = Modifier.fillMaxSize()) {
                // Messages List
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    MessageList(messages, listState, Modifier.fillMaxSize())
                }

                // Input Area
                ChatInputArea(
                    inputText = inputText,
                    onInputTextChanged = { inputText = it },
                    onSend = { handleSend() },
                    pendingAttachments = pendingAttachments,
                    onRemovePendingAttachment = viewModel::removePendingAttachment,
                    maxAttachments = MAX_CHAT_ATTACHMENTS,
                    onAttachmentPicked = { uri, kind -> viewModel.addPickedAttachment(context, uri, kind) },
                )
            }
        }
    }
}

@Composable
fun MessageList(
    messages: List<ChatMessage>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        state = listState,
        modifier = modifier.padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(messages) { msg ->
            MessageBubble(msg)
        }
    }
}

@Composable
fun MessageBubble(message: ChatMessage) {
    val alignment = if (message.isFromUser) Alignment.CenterEnd else Alignment.CenterStart
    val bgColor = if (message.isFromUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (message.isFromUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    // topStart, topEnd, bottomEnd, bottomStart — 直角改到靠头像一侧的上方（左上 / 右上）
    val shape = if (message.isFromUser) {
        RoundedCornerShape(16.dp, 0.dp, 16.dp, 16.dp)
    } else {
        RoundedCornerShape(0.dp, 16.dp, 16.dp, 16.dp)
    }

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = alignment
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = if (message.isFromUser) Arrangement.End else Arrangement.Start
        ) {
            if (!message.isFromUser) {
                // AI Avatar：与气泡顶部对齐，使头像与首行文字同高（避免多行时贴底对齐到末行）
                Box(
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Text("AI", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelSmall)
                }
                Spacer(modifier = Modifier.width(8.dp))
            }

            Column(
                modifier = Modifier
                    .clip(shape)
                    .background(bgColor)
                    .padding(12.dp)
                    .widthIn(max = 280.dp)
            ) {
                if (message.isLoading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("思考中...", style = MaterialTheme.typography.bodyMedium, color = textColor)
                    }
                } else if (message.isError) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = "Error",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(message.text ?: "Error", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    }
                } else {
                    message.attachments.forEachIndexed { index, att ->
                        if (index > 0) Spacer(modifier = Modifier.height(8.dp))
                        when (att.kind) {
                            ChatAttachmentKind.IMAGE -> AsyncImage(
                                model = att.uri,
                                contentDescription = att.label,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 200.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop
                            )

                            ChatAttachmentKind.AUDIO -> Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.AudioFile,
                                    contentDescription = null,
                                    tint = textColor,
                                    modifier = Modifier.size(22.dp),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = att.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = textColor,
                                )
                            }

                            ChatAttachmentKind.FILE -> Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.AttachFile,
                                    contentDescription = null,
                                    tint = textColor,
                                    modifier = Modifier.size(22.dp),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = att.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = textColor,
                                )
                            }
                        }
                    }
                    if (message.attachments.isNotEmpty() && message.text != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    if (message.text != null) {
                        Text(
                            text = message.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = textColor
                        )
                    }
                }
            }

            if (message.isFromUser) {
                Spacer(modifier = Modifier.width(8.dp))
                // User Avatar：与气泡顶部对齐
                Box(
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondary),
                    contentAlignment = Alignment.Center
                ) {
                    Text("我", color = MaterialTheme.colorScheme.onSecondary, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
fun ChatInputArea(
    inputText: String,
    onInputTextChanged: (String) -> Unit,
    onSend: () -> Unit,
    pendingAttachments: List<PendingChatAttachment>,
    onRemovePendingAttachment: (String) -> Unit,
    maxAttachments: Int,
    onAttachmentPicked: (Uri, ChatAttachmentKind) -> Unit,
    modifier: Modifier = Modifier
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val canAddMore = pendingAttachments.size < maxAttachments

    LaunchedEffect(canAddMore) {
        if (!canAddMore) menuExpanded = false
    }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri ->
            uri?.let { onAttachmentPicked(it, ChatAttachmentKind.IMAGE) }
        },
    )
    val audioPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri ->
            uri?.let { onAttachmentPicked(it, ChatAttachmentKind.AUDIO) }
        },
    )
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri ->
            uri?.let { onAttachmentPicked(it, ChatAttachmentKind.FILE) }
        },
    )

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .fillMaxWidth(),
        ) {
            if (pendingAttachments.isNotEmpty()) {
                PendingAttachmentPreviewRow(
                    items = pendingAttachments,
                    onRemove = onRemovePendingAttachment,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom
            ) {
            Box {
                val scheme = MaterialTheme.colorScheme
                FilledTonalIconButton(
                    onClick = { menuExpanded = true },
                    enabled = canAddMore,
                    modifier = Modifier
                        .size(48.dp)
                        .semantics {
                            contentDescription = if (canAddMore) "添加附件" else "添加附件（已达上限）"
                        },
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = scheme.primaryContainer.copy(alpha = 0.72f),
                        contentColor = scheme.primary,
                        disabledContainerColor = scheme.surfaceVariant.copy(alpha = 0.55f),
                        disabledContentColor = scheme.onSurfaceVariant.copy(alpha = 0.38f),
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(26.dp),
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    modifier = Modifier.width(296.dp),
                    shape = RoundedCornerShape(22.dp),
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp,
                    shadowElevation = 8.dp,
                ) {
                    AttachmentMenuPanel(
                        remainingSlots = (maxAttachments - pendingAttachments.size).coerceAtLeast(0),
                        maxAttachments = maxAttachments,
                        onPickImage = {
                            menuExpanded = false
                            if (canAddMore) imagePicker.launch("image/*")
                        },
                        onPickAudio = {
                            menuExpanded = false
                            if (canAddMore) audioPicker.launch("audio/*")
                        },
                        onPickFile = {
                            menuExpanded = false
                            if (canAddMore) filePicker.launch("*/*")
                        },
                    )
                }
            }

            OutlinedTextField(
                value = inputText,
                onValueChange = onInputTextChanged,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
                    .semantics { contentDescription = "输入消息" },
                placeholder = { Text("输入您的问题...") },
                maxLines = 4,
                shape = RoundedCornerShape(20.dp),
                colors = TextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedIndicatorColor = MaterialTheme.colorScheme.primary
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() })
            )

            FilledIconButton(
                onClick = onSend,
                enabled = inputText.isNotBlank() || pendingAttachments.isNotEmpty(),
                modifier = Modifier
                    .size(48.dp)
                    .semantics { contentDescription = "发送消息" },
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                ),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "发送",
                    modifier = Modifier.size(22.dp),
                )
            }
            }
        }
    }
}

@Composable
private fun PendingAttachmentPreviewRow(
    items: List<PendingChatAttachment>,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = scheme.surfaceVariant.copy(alpha = 0.55f),
        tonalElevation = 0.dp,
    ) {
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(items, key = { it.id }) { item ->
                Box(
                    modifier = Modifier
                        .size(width = 76.dp, height = 64.dp),
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 4.dp, end = 4.dp),
                        shape = RoundedCornerShape(10.dp),
                        color = scheme.surface,
                        shadowElevation = 0.dp,
                        tonalElevation = 1.dp,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(4.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            when (item.kind) {
                                ChatAttachmentKind.IMAGE -> AsyncImage(
                                    model = item.uri,
                                    contentDescription = item.displayName,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(6.dp)),
                                    contentScale = ContentScale.Crop,
                                )

                                ChatAttachmentKind.AUDIO -> Icon(
                                    imageVector = Icons.Default.AudioFile,
                                    contentDescription = item.displayName,
                                    tint = scheme.tertiary,
                                    modifier = Modifier.size(32.dp),
                                )

                                ChatAttachmentKind.FILE -> Icon(
                                    imageVector = Icons.Default.AttachFile,
                                    contentDescription = item.displayName,
                                    tint = scheme.secondary,
                                    modifier = Modifier.size(32.dp),
                                )
                            }
                        }
                    }
                    IconButton(
                        onClick = { onRemove(item.id) },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(26.dp)
                            .offset(x = 4.dp, y = (-2).dp),
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = scheme.errorContainer,
                            contentColor = scheme.onErrorContainer,
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "移除附件",
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachmentMenuPanel(
    remainingSlots: Int,
    maxAttachments: Int,
    onPickImage: () -> Unit,
    onPickAudio: () -> Unit,
    onPickFile: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            text = "添加附件",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = scheme.onSurface,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = if (remainingSlots <= 0) {
                "已达到上限（${maxAttachments} 个），发送后可继续添加"
            } else {
                "还可添加 $remainingSlots 项，与文字一并发送（最多 ${maxAttachments} 项）"
            },
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Top,
        ) {
            // 实心主题色底 + on* 图标色，保证与弹窗浅色底对比明显，且图标与圆底易区分
            AttachmentOptionTile(
                label = "图片",
                icon = Icons.Default.Image,
                circleColor = scheme.primary,
                iconTint = scheme.onPrimary,
                onClick = onPickImage,
            )
            AttachmentOptionTile(
                label = "音频",
                icon = Icons.Default.AudioFile,
                circleColor = scheme.tertiary,
                iconTint = scheme.onTertiary,
                onClick = onPickAudio,
            )
            AttachmentOptionTile(
                label = "文件",
                icon = Icons.Default.AttachFile,
                circleColor = scheme.secondary,
                iconTint = scheme.onSecondary,
                onClick = onPickFile,
            )
        }
    }
}

@Composable
private fun AttachmentOptionTile(
    label: String,
    icon: ImageVector,
    circleColor: Color,
    iconTint: Color,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .widthIn(min = 76.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 6.dp),
    ) {
        Surface(
            shape = CircleShape,
            color = circleColor,
            shadowElevation = 0.dp,
            tonalElevation = 0.dp,
            modifier = Modifier
                .size(56.dp)
                .border(1.dp, scheme.outline.copy(alpha = 0.35f), CircleShape),
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(30.dp),
                )
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            color = scheme.onSurface,
        )
    }
}
