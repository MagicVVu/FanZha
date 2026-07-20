import java.util.Properties

// AGP 9+ 自带 Kotlin，勿再应用 org.jetbrains.kotlin.android（会与内置 kotlin 扩展冲突）
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val localProperties = Properties()
val localPropsFile = rootProject.file("local.properties")
if (localPropsFile.exists()) {
    localPropsFile.inputStream().use { localProperties.load(it) }
}
fun resolveBaseUrl(propertyName: String, environmentName: String): String {
    val configured = localProperties.getProperty(propertyName)?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: providers.environmentVariable(environmentName).orNull?.trim()
            ?.takeIf { it.isNotEmpty() }
        ?: "http://10.0.2.2:8080/"
    return if (configured.endsWith("/")) configured else "$configured/"
}

val coreApiBaseUrl = resolveBaseUrl("api.base.url", "FANZHA_API_BASE_URL")
val aiApiBaseUrl = resolveBaseUrl("ai.api.base.url", "FANZHA_AI_API_BASE_URL")
val registrationOtp = localProperties.getProperty("registration.otp")?.trim()
    ?: providers.environmentVariable("FANZHA_REGISTRATION_OTP").orNull?.trim().orEmpty()

android {
    namespace = "com.magicvvu.fanzha"
    // 使用整型 API 级别，避免新 DSL（release + minorApiLevel）在部分环境下解析失败导致整份脚本无法同步
    compileSdk = 36

    defaultConfig {
        applicationId = "com.magicvvu.fanzha"
        minSdk = 27
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["usesCleartextTraffic"] =
            (coreApiBaseUrl.startsWith("http://") || aiApiBaseUrl.startsWith("http://")).toString()
        buildConfigField(
            "String",
            "API_BASE_URL",
            "\"${coreApiBaseUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"",
        )
        buildConfigField(
            "String",
            "AI_API_BASE_URL",
            "\"${aiApiBaseUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"",
        )
        buildConfigField(
            "String",
            "REGISTRATION_OTP",
            "\"${registrationOtp.replace("\\", "\\\\").replace("\"", "\\\"")}\"",
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    // 显式引入，避免 IDE/解析链仅依赖 material3 时出现 foundation、animation 符号无法解析
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.coil.svg)
    implementation(libs.percentages.with.animation.compose)
    implementation(libs.joery.animated.bottom.bar)

    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.gson)


    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.robolectric:robolectric:4.14.1")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")

}
