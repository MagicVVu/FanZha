package com.magicvvu.fanzha.data.remote

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * 与 HouDuan 后端 `AuthController`（`/auth`）对齐：
 * - 登录：`POST /auth/login`（勿使用裸 `/login` 或臆测的 `/api/login`）
 * - 注册：`POST /auth/register`
 *
 * `BuildConfig.API_BASE_URL` 须以 `/` 结尾；此处使用相对路径 `auth/...` 与 Retrofit 规范拼接。
 */
interface AuthApi {
    @POST("auth/register")
    suspend fun register(@Body body: RegisterRequest): Response<ApiResponse<UserInfo>>

    @POST("auth/login")
    suspend fun login(@Body body: LoginRequest): Response<ApiResponse<UserInfo>>
}

data class RegisterRequest(
    @SerializedName(value = "account", alternate = ["phone", "email"])
    val account: String,
    val password: String,
    @SerializedName(value = "confirmPassword", alternate = ["confirm_password"])
    val confirmPassword: String? = null,
    /** 资料字段：后端若暂未使用可忽略；客户端注册流程仍会收集。 */
    @SerializedName(value = "username", alternate = ["nickname", "name"])
    val username: String? = null,
    val age: Int? = null,
    val gender: String? = null,
    @SerializedName(value = "occupation", alternate = ["job", "profession", "career"])
    val occupation: String? = null,
)

data class LoginRequest(
    @SerializedName(value = "account", alternate = ["phone", "email"])
    val account: String,
    val password: String,
)

data class ApiResponse<T>(
    val success: Boolean,
    val message: String,
    val data: T?,
)

data class UserInfo(
    val id: Long,
    val account: String,
    val email: String? = null,
    val phone: String? = null,
)
