package com.magicvvu.fanzha.data.remote

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * 与 HouDuan `InterceptWeeklyDashboardController`（`/intercept/weekly-dashboard`）对齐：
 * 周合计与按日分布为明细表聚合；各通道可选返回：
 * - `recentIntercepts`：最近若干条（建议按时间倒序，客户端仅展示前 3 条）；
 * - 或仅 `lastInterceptAt` / `lastInterceptContent` 单条（兼容旧版）。
 */
interface InterceptWeeklyDashboardApi {
    @GET("intercept/weekly-dashboard")
    suspend fun weeklyDashboard(@Query("userId") userId: Long): Response<ApiResponse<InterceptWeeklyDashboardDto>>
}

/**
 * 与 HouDuan `InterceptHistoryController`（`/intercept/history`）对齐：
 * [channel]：`CALL` | `SMS` | `SUSPICIOUS_APP` | `CLIPBOARD`。
 */
interface InterceptHistoryApi {
    /** [limit] 默认 20，与后端拦截历史接口一致。 */
    @GET("intercept/history")
    suspend fun history(
        @Query("userId") userId: Long,
        @Query("channel") channel: String,
        @Query("limit") limit: Int = 20,
    ): Response<ApiResponse<List<InterceptRecentEntryDto>>>
}

data class InterceptRecentEntryDto(
    /** 明细表主键，列表项稳定 key（HouDuan {@code InterceptHistoryEntry.recordId}）。 */
    @SerializedName("recordId") val recordId: Long? = null,
    @SerializedName("interceptAt") val interceptAt: String? = null,
    @SerializedName("interceptContent") val interceptContent: String? = null,
)

data class InterceptWeeklyDashboardDto(
    val call: InterceptCallWeeklyStatsDto = InterceptCallWeeklyStatsDto(),
    val sms: InterceptCallWeeklyStatsDto = InterceptCallWeeklyStatsDto(),
    @SerializedName("suspiciousApp") val suspiciousApp: InterceptCallWeeklyStatsDto = InterceptCallWeeklyStatsDto(),
    val clipboard: InterceptCallWeeklyStatsDto = InterceptCallWeeklyStatsDto(),
)

/** 与 HouDuan `InterceptCallController`（`/intercept/call`）对齐。 */
interface InterceptCallApi {
    @GET("intercept/call/weekly-stats")
    suspend fun weeklyStats(@Query("userId") userId: Long): Response<ApiResponse<InterceptCallWeeklyStatsDto>>
}

data class InterceptCallWeeklyStatsDto(
    @SerializedName("dailyCounts") val dailyCounts: List<Int> = emptyList(),
    @SerializedName("weekTotal") val weekTotal: Int = 0,
    /** ISO-8601，如 `2026-04-19T14:30:00Z`；与 HouDuan 周看板聚合中「该通道最近一条」时间一致。 */
    @SerializedName("lastInterceptAt") val lastInterceptAt: String? = null,
    /** 电话/短信：对方号码；可疑 APP：应用名；剪切板：敏感片段或全文摘要。 */
    @SerializedName("lastInterceptContent") val lastInterceptContent: String? = null,
    /** 最近拦截列表（新）；为空时客户端回退到 lastIntercept* 单条。 */
    @SerializedName("recentIntercepts") val recentIntercepts: List<InterceptRecentEntryDto> = emptyList(),
)

/** 与 HouDuan `InterceptSmsController`（`/intercept/sms`）对齐；JSON 结构与电话周统计相同。 */
interface InterceptSmsApi {
    @GET("intercept/sms/weekly-stats")
    suspend fun weeklyStats(@Query("userId") userId: Long): Response<ApiResponse<InterceptCallWeeklyStatsDto>>
}

/** 与 HouDuan `InterceptSuspiciousAppController`（`/intercept/suspicious-app`）对齐。 */
interface InterceptSuspiciousAppApi {
    @GET("intercept/suspicious-app/weekly-stats")
    suspend fun weeklyStats(@Query("userId") userId: Long): Response<ApiResponse<InterceptCallWeeklyStatsDto>>
}

/** 与 HouDuan `InterceptClipboardController`（`/intercept/clipboard`）对齐。 */
interface InterceptClipboardApi {
    @GET("intercept/clipboard/weekly-stats")
    suspend fun weeklyStats(@Query("userId") userId: Long): Response<ApiResponse<InterceptCallWeeklyStatsDto>>
}

/** 特征库命中后才写入拦截明细；未命中不落库。 */
interface InterceptIngestApi {
    @POST("intercept/ingest")
    suspend fun ingest(@Body body: InterceptIngestRequest): Response<ApiResponse<InterceptIngestResultDto>>

    /** 批量上报：一次提交多条通话/短信/APP/剪切板，减少网络往返，提高同步速度。 */
    @POST("intercept/ingest/batch")
    suspend fun ingestBatch(@Body body: InterceptIngestBatchRequest): Response<ApiResponse<List<InterceptIngestResultDto>>>
}

data class InterceptIngestRequest(
    val userId: Long,
    val channel: String,
    val raw: String,
    val location: String? = null,
    val smsSenderNumber: String? = null,
    val appDisplayName: String? = null,
    val riskLevel: String? = null,
)

data class InterceptIngestBatchRequest(
    val items: List<InterceptIngestRequest>,
)

data class InterceptIngestResultDto(
    val matched: Boolean = false,
    val stored: Boolean = false,
    val message: String? = null,
)
