package com.magicvvu.fanzha.data.remote

import com.magicvvu.fanzha.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {
    /**
     * 默认使用 Android 模拟器访问宿主机的地址。生产环境必须通过本地配置或环境变量覆盖。
     *
     * 若要覆盖地址（如本地调试/切换环境），在 `MyApplication/local.properties` 中设置：
     * `api.base.url=http(s)://host:port`（末尾 / 可有可无）。
     */
    private val baseUrl: String = BuildConfig.API_BASE_URL

    private val okHttpClient: OkHttpClient by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BASIC
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }
        OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    private val retrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .addConverterFactory(GsonConverterFactory.create())
            .client(okHttpClient)
            .build()
    }

    val authApi: AuthApi by lazy { retrofit.create(AuthApi::class.java) }

    val alertCommandApi: AlertCommandApi by lazy { retrofit.create(AlertCommandApi::class.java) }

    val interceptCallApi: InterceptCallApi by lazy { retrofit.create(InterceptCallApi::class.java) }

    val interceptSmsApi: InterceptSmsApi by lazy { retrofit.create(InterceptSmsApi::class.java) }

    val interceptSuspiciousAppApi: InterceptSuspiciousAppApi by lazy {
        retrofit.create(InterceptSuspiciousAppApi::class.java)
    }

    val interceptClipboardApi: InterceptClipboardApi by lazy {
        retrofit.create(InterceptClipboardApi::class.java)
    }

    val interceptWeeklyDashboardApi: InterceptWeeklyDashboardApi by lazy {
        retrofit.create(InterceptWeeklyDashboardApi::class.java)
    }

    val interceptHistoryApi: InterceptHistoryApi by lazy {
        retrofit.create(InterceptHistoryApi::class.java)
    }

    val interceptIngestApi: InterceptIngestApi by lazy {
        retrofit.create(InterceptIngestApi::class.java)
    }

    val userProfileApi: UserProfileApi by lazy {
        retrofit.create(UserProfileApi::class.java)
    }

    val familyMemberApi: FamilyMemberApi by lazy {
        retrofit.create(FamilyMemberApi::class.java)
    }

    val userSecurityScoreApi: UserSecurityScoreApi by lazy {
        retrofit.create(UserSecurityScoreApi::class.java)
    }

    val quizScoreApi: QuizScoreApi by lazy {
        retrofit.create(QuizScoreApi::class.java)
    }
}
