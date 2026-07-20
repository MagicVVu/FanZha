package com.magicvvu.fanzha.data.model

data class KBHit(
    val doc_id: String = "",
    val title: String = "",
    val question: String = "",
    val answer: String = "",
    val fraud_type: String = "",
    val subtype: String = "",
    val risk_level: String = "",
    val warning: String = "",
    val safe_actions: List<String> = emptyList(),
    val source: String = "",
    val keywords: List<String> = emptyList(),
    val score: Double = 0.0,
    val content: String = "",
    val retrieval_mode: String = ""
)

data class AnalyzeResponse(
    val modality: String,
    val fraud_probability: Double,
    val result_confidence: Double,
    val risk_level: String,
    val reason: List<String> = emptyList(),
    val extracted_text: String = "",
    val kb_hits: List<KBHit> = emptyList(),
    val safe_actions: List<String> = emptyList(),
    val reply: String = "",
    val next_actions: List<String> = emptyList()
)