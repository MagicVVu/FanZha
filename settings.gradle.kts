pluginManagement {
    repositories {
        // 与常见 Android 模板一致；带 content filter 的 google() 偶发导致插件解析失败，进而无法生成 libs 目录的访问器
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "FanZha"
include(":app")
 
