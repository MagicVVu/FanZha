package com.magicvvu.fanzha.data.remote

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PUT
import retrofit2.http.Query

/**
 * 与 HouDuan `UserProfileController`（`/user/profile`、`/user/occupations`）对齐：
 * - `GET user/profile?userId=`
 * - `PUT user/profile?userId=`
 * - `GET user/occupations`
 */
interface UserProfileApi {
    @GET("user/profile")
    suspend fun getProfile(@Query("userId") userId: Long): Response<ApiResponse<UserProfileResponseDto>>

    @PUT("user/profile")
    suspend fun updateProfile(
        @Query("userId") userId: Long,
        @Body body: UserProfileUpdateRequestDto,
    ): Response<ApiResponse<UserProfileResponseDto>>

    @GET("user/occupations")
    suspend fun listOccupations(): Response<ApiResponse<List<OccupationOptionDto>>>
}

data class UserProfileResponseDto(
    @SerializedName("userId") val userId: Long,
    val account: String? = null,
    val phone: String? = null,
    val name: String? = null,
    val age: Int? = null,
    val gender: String? = null,
    @SerializedName("occupationId") val occupationId: Int? = null,
    @SerializedName("occupationName") val occupationName: String? = null,
    @SerializedName("categoryName") val categoryName: String? = null,
)

data class UserProfileUpdateRequestDto(
    val name: String? = null,
    val age: Int? = null,
    val gender: String? = null,
    @SerializedName("occupationId") val occupationId: Int? = null,
)

data class OccupationOptionDto(
    val id: Int,
    @SerializedName("occupationName") val occupationName: String,
    @SerializedName("categoryId") val categoryId: Int,
    @SerializedName("categoryName") val categoryName: String,
)
