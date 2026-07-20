package com.magicvvu.fanzha.ui.viewmodels

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.magicvvu.fanzha.data.remote.NetworkModule
import com.magicvvu.fanzha.data.repository.AssistantRepository
import com.magicvvu.fanzha.util.LocalOcrEngine
import com.magicvvu.fanzha.util.uriToTempFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.SocketTimeoutException

data class IdentificationUiState(
    val loading: Boolean = false,
    val fraudProbability: Double = 0.0,
    val resultConfidence: Double = 0.0,
    val riskLevel: String = "",
    val reason: List<String> = emptyList(),
    val extractedText: String = "",
    val reply: String = "",
    val safeActions: List<String> = emptyList(),
    val error: String? = null
)

class IdentificationViewModel : ViewModel() {

    /** 切换检测类型时清空结果，避免上一类型的结果残留在界面与历史中误触发。 */
    fun resetUiState() {
        _uiState.value = IdentificationUiState()
    }

    private val repository = AssistantRepository(
        api = NetworkModule.assistantApi,
        streamClient = NetworkModule.streamClient,
        baseUrl = NetworkModule.BASE_URL
    )

    private val _uiState = MutableStateFlow(IdentificationUiState())
    val uiState: StateFlow<IdentificationUiState> = _uiState.asStateFlow()

    fun analyzeTextContent(text: String) {
        if (text.isBlank()) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true, error = null)
            try {
                val result = repository.analyzeText(text.trim())
                updateUiWithResult(result)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    loading = false,
                    error = e.message ?: "文本检测失败"
                )
            }
        }
    }

    /**
     * 图片检测（可多张）：对每张图依次分析（本地 OCR 优先策略与单张一致），再聚合展示。
     */
    fun analyzeImageListPreferLocalOcr(context: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true, error = null)
            try {
                val results = uris.map { analyzeSingleImagePreferLocalOcr(context, it) }
                updateUiWithResult(aggregateAnalyzeResults(results))
            } catch (_: SocketTimeoutException) {
                _uiState.value = _uiState.value.copy(
                    loading = false,
                    error = "图片分析超时：请优先上传截图类图片，或重试一次"
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    loading = false,
                    error = e.message ?: "图片检测失败"
                )
            }
        }
    }

    /**
     * 语音检测（可多段）：依次上传分析后聚合展示。
     */
    fun analyzeAudioList(context: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true, error = null)
            try {
                val results = uris.map { analyzeSingleAudioFile(context, it) }
                updateUiWithResult(aggregateAnalyzeResults(results))
            } catch (_: SocketTimeoutException) {
                _uiState.value = _uiState.value.copy(
                    loading = false,
                    error = "语音分析超时，请稍后重试"
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    loading = false,
                    error = e.message ?: "语音检测失败"
                )
            }
        }
    }

    /**
     * 单张图片：优先本地 OCR；文字足够则走文本分析，否则上传图片分析；OCR 异常时回退上传分析。
     */
    private suspend fun analyzeSingleImagePreferLocalOcr(context: Context, uri: Uri): com.magicvvu.fanzha.data.model.AnalyzeResponse {
        return try {
            val localText = withContext(Dispatchers.IO) {
                LocalOcrEngine.recognizeText(context, uri)
                    .replace("\r", "\n")
                    .trim()
            }
            val nonBlankLines = localText.lines().count { it.isNotBlank() }
            val shouldUseTextPath =
                localText.length >= 20 || nonBlankLines >= 2

            if (shouldUseTextPath) {
                repository.analyzeText(localText)
            } else {
                val uploadFile = withContext(Dispatchers.IO) {
                    compressImageForUpload(context, uri)
                }
                try {
                    repository.analyzeFile("image", uploadFile)
                } finally {
                    uploadFile.delete()
                }
            }
        } catch (e: SocketTimeoutException) {
            throw e
        } catch (_: Exception) {
            val uploadFile = withContext(Dispatchers.IO) {
                compressImageForUpload(context, uri)
            }
            try {
                repository.analyzeFile("image", uploadFile)
            } finally {
                uploadFile.delete()
            }
        }
    }

    private suspend fun analyzeSingleAudioFile(context: Context, uri: Uri): com.magicvvu.fanzha.data.model.AnalyzeResponse {
        val uploadFile = withContext(Dispatchers.IO) {
            prepareUploadFile(context, "audio", uri)
        }
        return try {
            repository.analyzeFile("audio", uploadFile)
        } finally {
            uploadFile.delete()
        }
    }

    private fun aggregateAnalyzeResults(
        results: List<com.magicvvu.fanzha.data.model.AnalyzeResponse>,
    ): com.magicvvu.fanzha.data.model.AnalyzeResponse {
        require(results.isNotEmpty())
        fun rank(level: String) = when (level.lowercase()) {
            "high" -> 3
            "medium" -> 2
            "safe", "low" -> 1
            else -> 0
        }
        val worst = results.maxByOrNull { rank(it.risk_level) }!!
        val fraudProbability = results.maxOf { it.fraud_probability }
        val resultConfidence = results.map { it.result_confidence }.average()
        val reply = if (results.size == 1) {
            results.first().reply
        } else {
            results.mapIndexed { i, r ->
                "【第${i + 1}项】\n${r.reply}"
            }.joinToString("\n\n")
        }
        val reason = results.flatMap { it.reason }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        val extracted = results.map { it.extracted_text.trim() }.filter { it.isNotEmpty() }.distinct()
            .joinToString("\n")
        val safeActions = results.flatMap { it.safe_actions }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val kbHits = results.flatMap { it.kb_hits }
        val nextActions = results.flatMap { it.next_actions }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        return com.magicvvu.fanzha.data.model.AnalyzeResponse(
            modality = results.first().modality,
            fraud_probability = fraudProbability,
            result_confidence = resultConfidence,
            risk_level = worst.risk_level,
            reason = reason,
            extracted_text = extracted,
            kb_hits = kbHits,
            safe_actions = safeActions,
            reply = reply,
            next_actions = nextActions,
        )
    }

    fun analyzeFile(context: Context, modality: String, uri: Uri) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true, error = null)

            try {
                val uploadFile = withContext(Dispatchers.IO) {
                    prepareUploadFile(context, modality, uri)
                }

                val result = repository.analyzeFile(modality, uploadFile)
                updateUiWithResult(result)
            } catch (_: SocketTimeoutException) {
                _uiState.value = _uiState.value.copy(
                    loading = false,
                    error = "图片分析超时：请优先上传截图类图片，或重试一次"
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    loading = false,
                    error = e.message ?: "文件检测失败"
                )
            }
        }
    }

    private fun updateUiWithResult(
        result: com.magicvvu.fanzha.data.model.AnalyzeResponse,
        preferredExtractedText: String? = null
    ) {
        _uiState.value = IdentificationUiState(
            loading = false,
            fraudProbability = result.fraud_probability,
            resultConfidence = result.result_confidence,
            riskLevel = result.risk_level,
            reason = result.reason,
            extractedText = preferredExtractedText ?: result.extracted_text,
            reply = result.reply,
            safeActions = result.safe_actions
        )
    }

    private fun prepareUploadFile(context: Context, modality: String, uri: Uri): File {
        return if (modality.lowercase() == "image") {
            compressImageForUpload(context, uri)
        } else {
            uriToTempFile(context, uri, "detect_$modality")
        }
    }

    private fun compressImageForUpload(context: Context, uri: Uri): File {
        val resolver = context.contentResolver

        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        resolver.openInputStream(uri).use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        }

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IOException("无法读取图片尺寸")
        }

        val maxSide = 1600
        val inSampleSize = calculateInSampleSize(
            width = bounds.outWidth,
            height = bounds.outHeight,
            reqWidth = maxSide,
            reqHeight = maxSide
        )

        val decodeOptions = BitmapFactory.Options().apply {
            this.inSampleSize = inSampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        val decodedBitmap = resolver.openInputStream(uri).use { input ->
            BitmapFactory.decodeStream(input, null, decodeOptions)
        } ?: throw IOException("图片解码失败")

        var workingBitmap = decodedBitmap

        val rotation = resolver.openInputStream(uri).use { input ->
            if (input == null) 0 else readExifRotation(input)
        }

        if (rotation != 0) {
            val rotated = rotateBitmap(workingBitmap, rotation)
            if (rotated !== workingBitmap) {
                workingBitmap.recycle()
                workingBitmap = rotated
            }
        }

        val scaled = scaleBitmapIfNeeded(workingBitmap, maxSide)
        if (scaled !== workingBitmap) {
            workingBitmap.recycle()
            workingBitmap = scaled
        }

        val outFile = File.createTempFile("detect_image_", ".jpg", context.cacheDir)
        FileOutputStream(outFile).use { output ->
            val success = workingBitmap.compress(Bitmap.CompressFormat.JPEG, 82, output)
            if (!success) {
                throw IOException("图片压缩失败")
            }
            output.flush()
        }

        workingBitmap.recycle()
        return outFile
    }

    private fun calculateInSampleSize(
        width: Int,
        height: Int,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        var inSampleSize = 1
        val halfWidth = width / 2
        val halfHeight = height / 2

        while ((halfWidth / inSampleSize) >= reqWidth &&
            (halfHeight / inSampleSize) >= reqHeight
        ) {
            inSampleSize *= 2
        }
        return inSampleSize.coerceAtLeast(1)
    }

    private fun scaleBitmapIfNeeded(bitmap: Bitmap, maxSide: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val currentMax = maxOf(width, height)

        if (currentMax <= maxSide) return bitmap

        val scale = maxSide.toFloat() / currentMax.toFloat()
        val newWidth = (width * scale).toInt().coerceAtLeast(1)
        val newHeight = (height * scale).toInt().coerceAtLeast(1)

        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    private fun readExifRotation(inputStream: java.io.InputStream): Int {
        return try {
            val exif = ExifInterface(inputStream)
            when (
                exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            ) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } catch (_: Exception) {
            0
        }
    }

    private fun rotateBitmap(bitmap: Bitmap, rotation: Int): Bitmap {
        if (rotation == 0) return bitmap
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            matrix,
            true
        )
    }

    override fun onCleared() {
        super.onCleared()
        LocalOcrEngine.close()
    }
}
