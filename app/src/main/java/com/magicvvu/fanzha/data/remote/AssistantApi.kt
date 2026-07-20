package com.magicvvu.fanzha.data.remote

import com.magicvvu.fanzha.data.model.AnalyzeResponse
import com.magicvvu.fanzha.data.model.ChatRequest
import com.magicvvu.fanzha.data.model.ChatResponse
import com.magicvvu.fanzha.data.model.ReportAdviceRequest
import com.magicvvu.fanzha.data.model.ReportAdviceResponse
import com.magicvvu.fanzha.data.model.SmsCheckRequest
import com.magicvvu.fanzha.data.model.SmsCheckResponse
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

interface AssistantApi {

    @POST("api/assistant/chat")
    suspend fun chat(
        @Body request: ChatRequest
    ): ChatResponse

    @Multipart
    @POST("api/assistant/analyze")
    suspend fun analyze(
        @Part("modality") modality: RequestBody,
        @Part("text") text: RequestBody,
        @Part("url") url: RequestBody,
        @Part file: MultipartBody.Part? = null
    ): AnalyzeResponse

    @POST("api/assistant/check-sms")
    suspend fun checkSms(
        @Body request: SmsCheckRequest
    ): SmsCheckResponse

    @POST("api/assistant/report/advice")
    suspend fun generateReportAdvice(
        @Body request: ReportAdviceRequest
    ): ReportAdviceResponse
}
