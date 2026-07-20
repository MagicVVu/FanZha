package com.magicvvu.fanzha.data.remote

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * 与 HouDuan `UserSecurityScoreController`（`/user/security-score`）对齐：
 * - `GET user/security-score?userId=`
 */
interface UserSecurityScoreApi {
    @GET("user/security-score")
    suspend fun getSecurityScore(@Query("userId") userId: Long): Response<ApiResponse<UserSecurityScoreDto>>
}

data class UserSecurityScoreDto(
    @SerializedName("userId") val userId: Long,
    @SerializedName("securityScore") val securityScore: Int,
    @SerializedName("updateTime") val updateTime: String? = null,
)

