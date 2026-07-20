package com.magicvvu.fanzha.data.remote

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * 与 HouDuan `FamilyMemberController`（`/family/member`）对齐：
 * - `POST family/member/add`
 * - `GET family/member/list?userId=`
 *
 * 添加成员使用 [Map] 作为请求体，避免 Kotlin data class + Gson 在部分机型上漏序列化字段
 *（确保 `userId` / `phone` / `relation` 都进入 JSON）。
 */
interface FamilyMemberApi {
    @POST("family/member/add")
    suspend fun addMember(
        @Body body: Map<@JvmSuppressWildcards String, @JvmSuppressWildcards Any?>,
    ): Response<ApiResponse<FamilyMemberDto>>

    @GET("family/member/list")
    suspend fun listMembers(@Query("userId") userId: Long): Response<ApiResponse<List<FamilyMemberDto>>>
}

data class FamilyMemberDto(
    val id: Long,
    @SerializedName("userId") val userId: Long,
    @SerializedName("relatedUserId") val relatedUserId: Long,
    val relation: String? = null,
    val name: String? = null,
    val phone: String? = null,
    @SerializedName("safetyIndex") val safetyIndex: Int? = null,
    @SerializedName("callCount") val callCount: Int? = null,
    @SerializedName("smsCount") val smsCount: Int? = null,
    @SerializedName("suspiciousAppCount") val suspiciousAppCount: Int? = null,
    @SerializedName("clipboardCount") val clipboardCount: Int? = null,
)

