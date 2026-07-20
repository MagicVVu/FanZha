package com.magicvvu.fanzha.data.repository

import com.magicvvu.fanzha.data.model.AnalyzeResponse
import com.magicvvu.fanzha.data.model.ChatRequest
import com.magicvvu.fanzha.data.model.ChatResponse
import com.magicvvu.fanzha.data.model.ChatStreamEvent
import com.magicvvu.fanzha.data.model.ReportAdviceRequest
import com.magicvvu.fanzha.data.model.ReportAdviceResponse
import com.magicvvu.fanzha.data.model.ReportInterceptOverviewRequest
import com.magicvvu.fanzha.data.model.ReportRiskBehaviorRequest
import com.magicvvu.fanzha.data.model.ReportUserProfileRequest
import com.magicvvu.fanzha.data.model.SmsCheckRequest
import com.magicvvu.fanzha.data.model.SmsCheckResponse
import com.magicvvu.fanzha.data.remote.AssistantApi
import com.magicvvu.fanzha.util.toPlainRequestBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException

class AssistantRepository(
    private val api: AssistantApi,
    private val streamClient: OkHttpClient,
    private val baseUrl: String,
) {

    suspend fun chat(sessionId: String, message: String): ChatResponse {
        return api.chat(ChatRequest(sessionId = sessionId, message = message))
    }

    suspend fun checkSms(sender: String, message: String): SmsCheckResponse {
        return api.checkSms(
            SmsCheckRequest(
                sender = sender,
                message = message,
            )
        )
    }


    suspend fun generateReportAdvice(
        reportType: String,
        riskBehaviors: List<ReportRiskBehaviorRequest>,
        interceptOverview: ReportInterceptOverviewRequest,
        userProfile: ReportUserProfileRequest? = null,
    ): ReportAdviceResponse {
        return api.generateReportAdvice(
            ReportAdviceRequest(
                reportType = reportType,
                riskBehaviors = riskBehaviors,
                interceptOverview = interceptOverview,
                userProfile = userProfile,
            )
        )
    }

    fun streamChat(
        sessionId: String,
        message: String,
    ): Flow<ChatStreamEvent> {
        val requestJson = JSONObject()
            .put("session_id", sessionId)
            .put("message", message)
            .toString()

        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/assistant/chat/stream")
            .post(
                requestJson.toRequestBody(
                    "application/json; charset=utf-8".toMediaType()
                )
            )
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .build()

        return executeSseRequest(request)
    }

    fun streamMultimodalChat(
        sessionId: String,
        message: String,
        uploadParts: List<Pair<File, String>> = emptyList(),
    ): Flow<ChatStreamEvent> {
        val multipartBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("session_id", sessionId)
            .addFormDataPart("message", message)

        val attachmentModality = when {
            uploadParts.isEmpty() -> "auto"
            uploadParts.size == 1 -> {
                val file = uploadParts[0].first
                uploadParts[0].second.ifBlank { inferAttachmentModality(file.name) }
            }
            else -> "auto"
        }
        multipartBuilder.addFormDataPart("attachment_modality", attachmentModality)

        for ((uploadFile, modalityHint) in uploadParts) {
            val normalizedModality = modalityHint.ifBlank { inferAttachmentModality(uploadFile.name) }
            val fileName = ensureUploadFileName(normalizedModality, uploadFile.name)
            val mediaType = detectMediaType(normalizedModality, fileName).toMediaTypeOrNull()
            multipartBuilder.addFormDataPart(
                name = "file",
                filename = fileName,
                body = uploadFile.asRequestBody(mediaType),
            )
        }

        android.util.Log.d(
            "AiChat",
            "uploadParts.size=${uploadParts.size}, uploadParts=${uploadParts.map { "${it.second}:${it.first.name}" }}"
        )

        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/assistant/chat/stream/multimodal")
            .post(multipartBuilder.build())
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .build()

        return executeSseRequest(request)
    }

    private fun executeSseRequest(request: Request): Flow<ChatStreamEvent> = flow {
        streamClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}: ${response.message}")
            }

            val body = response.body ?: throw IOException("Empty response body")
            val source = body.source()
            var currentEvent = ""

            while (!source.exhausted()) {
                val rawLine = source.readUtf8Line() ?: break
                val line = rawLine.trim()

                if (line.isBlank()) {
                    currentEvent = ""
                    continue
                }

                if (line.startsWith("event:")) {
                    currentEvent = line.removePrefix("event:").trim()
                    continue
                }

                if (!line.startsWith("data:")) continue

                val dataText = line.removePrefix("data:").trim()
                val json = JSONObject(dataText)
                val type = if (currentEvent.isNotBlank()) currentEvent else json.optString("type")

                when (type) {
                    "start" -> {
                        emit(
                            ChatStreamEvent.Start(
                                sessionId = json.optString("session_id"),
                                safeActions = json.toStringList("safe_actions"),
                                suggestions = json.toStringList("suggestions"),
                            )
                        )
                    }

                    "delta" -> {
                        val content = json.optString("content")
                        if (content.isNotBlank()) {
                            emit(ChatStreamEvent.Delta(content))
                        }
                    }

                    "done" -> {
                        emit(
                            ChatStreamEvent.Done(
                                sessionId = json.optString("session_id"),
                                reply = json.optString("reply"),
                                riskLevel = json.optString("risk_level"),
                                fraudProbability = json.optDouble("fraud_probability", 0.0),
                                resultConfidence = json.optDouble("result_confidence", 0.0),
                                reason = json.toStringList("reason"),
                                safeActions = json.toStringList("safe_actions"),
                                suggestions = json.toStringList("suggestions"),
                            )
                        )
                    }
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    suspend fun analyzeText(text: String): AnalyzeResponse {
        return api.analyze(
            modality = "text".toPlainRequestBody(),
            text = text.toPlainRequestBody(),
            url = "".toPlainRequestBody(),
            file = null
        )
    }

    suspend fun analyzeWebsite(url: String): AnalyzeResponse {
        return api.analyze(
            modality = "website".toPlainRequestBody(),
            text = "".toPlainRequestBody(),
            url = url.toPlainRequestBody(),
            file = null
        )
    }

    suspend fun analyzeFile(modality: String, file: File): AnalyzeResponse {
        val normalizedModality = modality.lowercase()
        val uploadFileName = ensureUploadFileName(normalizedModality, file.name)
        val mediaType = detectMediaType(normalizedModality, uploadFileName).toMediaTypeOrNull()

        val requestFile = file.asRequestBody(mediaType)
        val filePart = MultipartBody.Part.createFormData(
            name = "file",
            filename = uploadFileName,
            body = requestFile
        )

        return api.analyze(
            modality = normalizedModality.toPlainRequestBody(),
            text = "".toPlainRequestBody(),
            url = "".toPlainRequestBody(),
            file = filePart
        )
    }

    fun inferAttachmentModality(fileName: String): String {
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") ||
                    lower.endsWith(".bmp") || lower.endsWith(".webp") -> "image"

            lower.endsWith(".mp3") || lower.endsWith(".wav") || lower.endsWith(".m4a") ||
                    lower.endsWith(".aac") || lower.endsWith(".ogg") -> "audio"

            lower.endsWith(".mp4") || lower.endsWith(".mov") || lower.endsWith(".mkv") ||
                    lower.endsWith(".avi") -> "video"

            else -> "file"
        }
    }

    private fun detectMediaType(modality: String, fileName: String): String {
        val lower = fileName.lowercase()
        return when (modality) {
            "image" -> when {
                lower.endsWith(".png") -> "image/png"
                lower.endsWith(".bmp") -> "image/bmp"
                lower.endsWith(".webp") -> "image/webp"
                else -> "image/jpeg"
            }

            "audio" -> when {
                lower.endsWith(".wav") -> "audio/wav"
                lower.endsWith(".m4a") -> "audio/mp4"
                lower.endsWith(".aac") -> "audio/aac"
                lower.endsWith(".ogg") -> "audio/ogg"
                else -> "audio/mpeg"
            }

            "video" -> when {
                lower.endsWith(".mov") -> "video/quicktime"
                lower.endsWith(".mkv") -> "video/x-matroska"
                lower.endsWith(".avi") -> "video/x-msvideo"
                else -> "video/mp4"
            }

            "file" -> when {
                lower.endsWith(".pdf") -> "application/pdf"
                lower.endsWith(".docx") -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                lower.endsWith(".txt") -> "text/plain"
                lower.endsWith(".md") -> "text/markdown"
                lower.endsWith(".json") -> "application/json"
                else -> "application/octet-stream"
            }

            else -> "application/octet-stream"
        }
    }

    private fun ensureUploadFileName(modality: String, originalName: String): String {
        val lower = originalName.lowercase()

        return when (modality) {
            "image" -> when {
                lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
                        lower.endsWith(".png") || lower.endsWith(".bmp") ||
                        lower.endsWith(".webp") -> originalName
                else -> "upload.jpg"
            }

            "audio" -> ensureFileNameWithFallback(originalName, ".mp3")
            "video" -> ensureFileNameWithFallback(originalName, ".mp4")
            "file" -> originalName.ifBlank { "upload.bin" }
            else -> originalName.ifBlank { "upload.bin" }
        }
    }

    private fun ensureFileNameWithFallback(originalName: String, fallbackExt: String): String {
        val dotIndex = originalName.lastIndexOf('.')
        return if (dotIndex > 0 && dotIndex < originalName.length - 1) {
            originalName
        } else {
            "upload$fallbackExt"
        }
    }

    private fun JSONObject.toStringList(key: String): List<String> {
        val array = optJSONArray(key) ?: return emptyList()
        val result = mutableListOf<String>()
        for (i in 0 until array.length()) {
            val value = array.optString(i).trim()
            if (value.isNotEmpty()) {
                result.add(value)
            }
        }
        return result
    }
}