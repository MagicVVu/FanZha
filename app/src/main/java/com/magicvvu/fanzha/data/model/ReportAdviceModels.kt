package com.magicvvu.fanzha.data.model

import com.google.gson.annotations.SerializedName

data class ReportRiskBehaviorRequest(
    @SerializedName("title")
    val title: String,
    @SerializedName("description")
    val description: String,
    @SerializedName("level")
    val level: String,
    @SerializedName("frequency")
    val frequency: Int,
)

data class ReportInterceptOverviewRequest(
    @SerializedName("phone_week_intercept_count")
    val phoneWeekInterceptCount: Int,
    @SerializedName("sms_week_intercept_count")
    val smsWeekInterceptCount: Int,
    @SerializedName("app_week_intercept_count")
    val appWeekInterceptCount: Int,
    @SerializedName("clipboard_week_intercept_count")
    val clipboardWeekInterceptCount: Int,
)

data class ReportUserProfileRequest(
    @SerializedName("user_id")
    val userId: String = "",
    @SerializedName("name")
    val name: String = "",
    @SerializedName("age")
    val age: Int = 0,
    @SerializedName("gender")
    val gender: String = "",
    @SerializedName("occupation")
    val occupation: String = "",
)

data class ReportAdviceRequest(
    @SerializedName("report_type")
    val reportType: String,
    @SerializedName("risk_behaviors")
    val riskBehaviors: List<ReportRiskBehaviorRequest>,
    @SerializedName("intercept_overview")
    val interceptOverview: ReportInterceptOverviewRequest,
    @SerializedName("user_profile")
    val userProfile: ReportUserProfileRequest? = null,
)

data class ReportAdviceItem(
    @SerializedName("id")
    val id: Int,
    @SerializedName("title")
    val title: String = "",
    @SerializedName("content")
    val content: String = "",
    @SerializedName("priority")
    val priority: String = "medium",
)

data class ReportAdviceResponse(
    @SerializedName("report_type")
    val reportType: String = "weekly",
    @SerializedName("summary")
    val summary: String = "",
    @SerializedName("risk_level")
    val riskLevel: String = "low",
    @SerializedName("reason")
    val reason: List<String> = emptyList(),
    @SerializedName("suggestions")
    val suggestions: List<ReportAdviceItem> = emptyList(),
)
