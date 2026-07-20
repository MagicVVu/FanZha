package com.magicvvu.fanzha.data.remote

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.GET

/**
 * 三类安全告警指令接口（与 HouDuan 约定路径；若后端路径不同，仅改此处注解路径即可）。
 *
 * 期望 JSON 外层与项目统一 [ApiResponse]，内层 [AlertCommandPayload]：
 * - `success`：请求是否成功
 * - `data.should_notify`：为 true 时客户端弹出该类通知（一次轮询周期内可重复为 true，由后端控制节奏）
 */
interface AlertCommandApi {

    /** 1. 诈骗短信类告警指令 */
    @GET("api/alerts/commands/fraud-sms")
    suspend fun fetchFraudSmsCommand(): Response<ApiResponse<AlertCommandPayload>>

    /** 2. 关键信息泄露类告警指令 */
    @GET("api/alerts/commands/info-leak")
    suspend fun fetchInfoLeakCommand(): Response<ApiResponse<AlertCommandPayload>>

    /** 3. 被监护人涉诈类告警指令（可选返回被监护人姓名） */
    @GET("api/alerts/commands/ward-fraud")
    suspend fun fetchWardFraudCommand(): Response<ApiResponse<AlertCommandPayload>>
}

data class AlertCommandPayload(
    @SerializedName("should_notify") val shouldNotify: Boolean = false,
    @SerializedName(value = "wardName", alternate = ["ward_name", "name"])
    val wardName: String? = null,
)
