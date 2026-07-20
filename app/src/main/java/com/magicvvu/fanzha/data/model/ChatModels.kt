package com.magicvvu.fanzha.data.model

import com.google.gson.annotations.SerializedName

data class ChatRequest(
    @SerializedName("session_id")
    val sessionId: String = "",
    val message: String = "",
)

data class ChatResponse(
    @SerializedName("session_id")
    val sessionId: String = "",
    val reply: String = "",
    @SerializedName("fraud_probability")
    val fraudProbability: Double = 0.0,
    @SerializedName("result_confidence")
    val resultConfidence: Double = 0.0,
    @SerializedName("risk_level")
    val riskLevel: String = "",
    val reason: List<String> = emptyList(),
    @SerializedName("safe_actions")
    val safeActions: List<String> = emptyList(),
    val suggestions: List<String> = emptyList(),
    @SerializedName("evidence_count")
    val evidenceCount: Int = 0,
)

sealed class ChatStreamEvent {
    data class Start(
        val sessionId: String = "",
        val safeActions: List<String> = emptyList(),
        val suggestions: List<String> = emptyList(),
    ) : ChatStreamEvent()

    data class Delta(
        val content: String,
    ) : ChatStreamEvent()

    data class Done(
        val sessionId: String = "",
        val reply: String = "",
        val riskLevel: String = "",
        val fraudProbability: Double = 0.0,
        val resultConfidence: Double = 0.0,
        val reason: List<String> = emptyList(),
        val safeActions: List<String> = emptyList(),
        val suggestions: List<String> = emptyList(),
    ) : ChatStreamEvent()
}
