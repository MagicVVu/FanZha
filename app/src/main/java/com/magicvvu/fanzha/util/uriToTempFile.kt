package com.magicvvu.fanzha.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

fun uriToTempFile(context: Context, uri: Uri, prefix: String = "upload"): File {
    val resolver = context.contentResolver

    val fileName = resolver.query(uri, null, null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (cursor.moveToFirst() && nameIndex >= 0) cursor.getString(nameIndex) else null
    } ?: "${prefix}_${System.currentTimeMillis()}"

    val safeName = fileName.ifBlank { "${prefix}_${System.currentTimeMillis()}" }
    val tempFile = File(context.cacheDir, safeName)

    resolver.openInputStream(uri)?.use { input ->
        tempFile.outputStream().use { output ->
            input.copyTo(output)
        }
    } ?: throw IllegalArgumentException("无法读取所选文件")

    return tempFile
}
