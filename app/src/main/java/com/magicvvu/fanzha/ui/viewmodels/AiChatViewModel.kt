package com.magicvvu.fanzha.ui.viewmodels

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.magicvvu.fanzha.data.model.ChatStreamEvent
import com.magicvvu.fanzha.data.remote.NetworkModule
import com.magicvvu.fanzha.data.repository.AssistantRepository
import com.magicvvu.fanzha.util.uriToTempFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

enum class ChatAttachmentKind {
    IMAGE,
    AUDIO,
    FILE,
}

/** 已发送消息中的附件展示 */
data class ChatAttachmentPreview(
    val uri: Uri,
    val kind: ChatAttachmentKind,
    val label: String,
)

/** 输入框待发送队列中的附件 */
data class PendingChatAttachment(
    val id: String,
    val uri: Uri,
    val kind: ChatAttachmentKind,
    val displayName: String,
)

data class ChatMessage(
    val id: String,
    val isFromUser: Boolean,
    val text: String? = null,
    val attachments: List<ChatAttachmentPreview> = emptyList(),
    val isLoading: Boolean = false,
    val isError: Boolean = false,
)

class AiChatViewModel : ViewModel() {

    companion object {
        private const val MAX_PENDING_ATTACHMENTS = 3
    }

    private val repository = AssistantRepository(
        api = NetworkModule.assistantApi,
        streamClient = NetworkModule.streamClient,
        baseUrl = NetworkModule.BASE_URL,
    )

    private val _messages = MutableStateFlow(
        listOf(
            ChatMessage(
                id = "1",
                isFromUser = false,
                text = "你好！我是您的AI反诈助手。你可以连续发送文字、截图、录音和文件，我会结合历史证据继续判断。",
            ),
        ),
    )
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _sessionId = MutableStateFlow("")
    val sessionId: StateFlow<String> = _sessionId.asStateFlow()

    private val _pendingAttachments = MutableStateFlow<List<PendingChatAttachment>>(emptyList())
    val pendingAttachments: StateFlow<List<PendingChatAttachment>> = _pendingAttachments.asStateFlow()

    fun addPickedAttachment(@Suppress("UNUSED_PARAMETER") context: Context, uri: Uri, kind: ChatAttachmentKind) {
        if (_pendingAttachments.value.size >= MAX_PENDING_ATTACHMENTS) return
        val name = uri.lastPathSegment?.ifBlank { null } ?: "附件"
        val item = PendingChatAttachment(
            id = UUID.randomUUID().toString(),
            uri = uri,
            kind = kind,
            displayName = name,
        )
        _pendingAttachments.value = _pendingAttachments.value + item
    }

    fun removePendingAttachment(id: String) {
        _pendingAttachments.value = _pendingAttachments.value.filterNot { it.id == id }
    }

    fun sendMessage(context: Context, text: String?) {
        val content = text?.trim().orEmpty()
        val pending = _pendingAttachments.value
        android.util.Log.d(
            "AiChat",
            "pending.size=${pending.size}, pending=${pending.map { "${it.kind}:${it.displayName}" }}"
        )
        if (content.isBlank() && pending.isEmpty()) return

        val previews = pending.map {
            ChatAttachmentPreview(uri = it.uri, kind = it.kind, label = it.displayName)
        }
        _pendingAttachments.value = emptyList()

        val userMsg = ChatMessage(
            id = System.currentTimeMillis().toString(),
            isFromUser = true,
            text = content.ifBlank { null },
            attachments = previews,
        )
        _messages.value = _messages.value + userMsg

        val aiMessageId = "ai_${System.currentTimeMillis()}"
        _messages.value = _messages.value + ChatMessage(
            id = aiMessageId,
            isFromUser = false,
            text = "",
            isLoading = true,
        )

        viewModelScope.launch {
            val tempFiles = mutableListOf<java.io.File>()
            try {
                val uploadParts = pending.map { item ->
                    val file = uriToTempFile(context, item.uri, "chat_upload")
                    tempFiles.add(file)
                    val modality = when (item.kind) {
                        ChatAttachmentKind.IMAGE -> "image"
                        ChatAttachmentKind.AUDIO -> "audio"
                        ChatAttachmentKind.FILE -> repository.inferAttachmentModality(file.name)
                    }
                    file to modality
                }

                collectChatStream(
                    aiMessageId = aiMessageId,
                    streamProvider = {
                        repository.streamMultimodalChat(
                            sessionId = _sessionId.value,
                            message = content,
                            uploadParts = uploadParts,
                        )
                    },
                )
            } catch (e: Exception) {
                markAiError(
                    messageId = aiMessageId,
                    errorText = normalizeErrorMessage(e, prefix = "发送失败"),
                )
            } finally {
                tempFiles.forEach { runCatching { it.delete() } }
            }
        }
    }

    private suspend fun collectChatStream(
        aiMessageId: String,
        streamProvider: () -> Flow<ChatStreamEvent>,
    ) {
        try {
            var finalReply = ""
            var finalSuggestions: List<String> = emptyList()
            var finalSafeActions: List<String> = emptyList()
            var finalFraudProbability = 0.0
            var finalResultConfidence = 0.0
            var finalReason: List<String> = emptyList()

            streamProvider().collect { event ->
                when (event) {
                    is ChatStreamEvent.Start -> {
                        if (event.sessionId.isNotBlank()) {
                            _sessionId.value = event.sessionId
                        }
                        finalSuggestions = event.suggestions
                        finalSafeActions = event.safeActions
                    }

                    is ChatStreamEvent.Delta -> {
                        appendAiDelta(aiMessageId, event.content)
                    }

                    is ChatStreamEvent.Done -> {
                        if (event.sessionId.isNotBlank()) {
                            _sessionId.value = event.sessionId
                        }
                        finalReply = event.reply
                        finalFraudProbability = event.fraudProbability
                        finalResultConfidence = event.resultConfidence
                        finalReason = event.reason

                        if (event.suggestions.isNotEmpty()) {
                            finalSuggestions = event.suggestions
                        }
                        if (event.safeActions.isNotEmpty()) {
                            finalSafeActions = event.safeActions
                        }
                    }
                }
            }

            finishAiMessage(
                messageId = aiMessageId,
                finalReply = finalReply,
                fraudProbability = finalFraudProbability,
                resultConfidence = finalResultConfidence,
                reason = finalReason,
                suggestions = finalSuggestions,
                safeActions = finalSafeActions,
            )
        } catch (e: Exception) {
            markAiError(
                messageId = aiMessageId,
                errorText = normalizeErrorMessage(e, prefix = "连接后端失败"),
            )
        }
    }

    private fun appendAiDelta(messageId: String, delta: String) {
        _messages.value = _messages.value.map { message ->
            if (message.id == messageId) {
                message.copy(
                    isLoading = false,
                    isError = false,
                    text = (message.text ?: "") + delta,
                )
            } else {
                message
            }
        }
    }

    private fun finishAiMessage(
        messageId: String,
        finalReply: String,
        fraudProbability: Double,
        resultConfidence: Double,
        reason: List<String>,
        suggestions: List<String>,
        safeActions: List<String>,
    ) {
        _messages.value = _messages.value.map { message ->
            if (message.id == messageId) {
                val streamedText = message.text.orEmpty().trim()
                val baseReply = if (streamedText.isNotBlank()) streamedText else finalReply
                message.copy(
                    isLoading = false,
                    isError = false,
                    text = buildReplyText(
                        reply = baseReply,

                        fraudProbability = fraudProbability,
                        resultConfidence = resultConfidence,
                        reason = reason,
                        suggestions = suggestions,
                        safeActions = safeActions,
                    ),
                )
            } else {
                message
            }
        }
    }

    private fun markAiError(messageId: String, errorText: String) {
        _messages.value = _messages.value.map { message ->
            if (message.id == messageId) {
                message.copy(
                    isLoading = false,
                    isError = true,
                    text = errorText,
                )
            } else {
                message
            }
        }
    }

    private fun normalizeErrorMessage(e: Exception, prefix: String): String {
        val raw = e.message.orEmpty()
        val detail = when {
            raw.contains("404") -> "接口地址不存在，请确认服务端已部署最新版多模态聊天接口。"
            raw.contains("unexpected end of stream", ignoreCase = true) -> "服务端处理中断，通常是附件识别耗时过长或反向代理超时，请稍后重试。"
            raw.contains("timeout", ignoreCase = true) -> "请求超时，通常是附件识别耗时过长，请稍后重试。"
            raw.isBlank() -> "未知错误"
            else -> raw
        }
        return "$prefix：$detail"
    }

    private fun probabilityLabel(probability: Double): String {
        return when {
            probability >= 0.85 -> "诈骗概率很高"
            probability >= 0.65 -> "诈骗概率较高"
            probability >= 0.40 -> "诈骗概率中等"
            probability >= 0.20 -> "诈骗概率较低"
            else -> "诈骗概率较低"
        }
    }

    private fun confidenceLabel(confidence: Double): String {
        return when {
            confidence >= 0.80 -> "判断可信度较高"
            confidence >= 0.55 -> "判断可信度中等"
            else -> "判断可信度较低"
        }
    }

    private fun reasonTitle(): String = "我产生这些判断的原因是："

    private fun buildReplyText(
        reply: String,
        fraudProbability: Double,
        resultConfidence: Double,
        reason: List<String>,
        suggestions: List<String>,
        safeActions: List<String>
    ): String {
        val builder = StringBuilder()

        builder.append("综合判断：")
            .append(probabilityLabel(fraudProbability))
            .append("，")
            .append(confidenceLabel(resultConfidence))
            .append("\n\n")

        builder.append(reply.ifBlank { "我暂时没有生成有效回复，请稍后重试。" })

        if (reason.isNotEmpty()) {
            builder.append("\n\n")
            builder.append(reasonTitle())
            reason.forEach { item ->
                builder.append("\n- ").append(item)
            }
        }

        if (suggestions.isNotEmpty()) {
            builder.append("\n\n建议你可以继续补充：")
            suggestions.forEach { item ->
                builder.append("\n- ").append(item)
            }
        }

        if (safeActions.isNotEmpty()) {
            builder.append("\n\n当前更稳妥的做法是：")
            safeActions.forEach { item ->
                builder.append("\n- ").append(item)
            }
        }

        return builder.toString()
    }
}
