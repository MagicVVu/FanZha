package com.magicvvu.fanzha.data.remote

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/** 与 HouDuan `QuizScoreController`（`/quiz/score`）对齐。 */
interface QuizScoreApi {
    @POST("quiz/score")
    suspend fun submitScore(@Body body: QuizScoreSubmitRequest): Response<ApiResponse<QuizScoreSubmitResponse>>

    @GET("quiz/score")
    suspend fun getLatestScore(@Query("userId") userId: Long): Response<ApiResponse<QuizScoreLatestDto>>
}

data class QuizScoreSubmitRequest(
    @SerializedName("userId") val userId: Long,
    @SerializedName("totalScore") val totalScore: Int,
)

data class QuizScoreSubmitResponse(
    @SerializedName("userId") val userId: Long,
    /** 服务端保存后的最近一次得分（quiz_score.total_score）。 */
    @SerializedName("latestScore") val latestScore: Int,
)

data class QuizScoreLatestDto(
    @SerializedName("userId") val userId: Long,
    @SerializedName("latestScore") val latestScore: Int,
    /** 服务端 quiz_score.update_time（通常为 yyyy-MM-dd HH:mm:ss）。 */
    @SerializedName("updateTime") val updateTime: String? = null,
)

