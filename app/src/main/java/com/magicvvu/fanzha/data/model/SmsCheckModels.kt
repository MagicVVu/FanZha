package com.magicvvu.fanzha.data.model

import com.google.gson.annotations.SerializedName

data class SmsCheckRequest(
    @SerializedName("sender")
    val sender: String,
    @SerializedName("message")
    val message: String,
)

data class SmsCheckResponse(
    @SerializedName(value = "is_fraud", alternate = ["isFraud"])
    val isFraud: Boolean = false,
)
