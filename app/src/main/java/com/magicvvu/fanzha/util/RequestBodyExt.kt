package com.magicvvu.fanzha.util

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

fun String.toPlainRequestBody(): RequestBody {
    return this.toRequestBody("text/plain".toMediaType())
}