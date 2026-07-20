package com.magicvvu.fanzha.util


import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.TextRecognizer
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object LocalOcrEngine {

    private val recognizer: TextRecognizer by lazy {
        TextRecognition.getClient(
            ChineseTextRecognizerOptions.Builder().build()
        )
    }

    suspend fun recognizeText(context: Context, uri: Uri): String {
        val image = InputImage.fromFilePath(context, uri)

        return suspendCancellableCoroutine { continuation ->
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    if (continuation.isActive) {
                        continuation.resume(visionText.text.orEmpty())
                    }
                }
                .addOnFailureListener { e ->
                    if (continuation.isActive) {
                        continuation.resumeWithException(e)
                    }
                }
        }
    }

    fun close() {
        recognizer.close()
    }
}
