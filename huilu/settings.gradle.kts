pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "huilu"

include(":core")

// 有 Android SDK 时才加入 app 模块；没有 SDK 也能单独构建、测试 core。
val hasAndroidSdk = !System.getenv("ANDROID_HOME").isNullOrBlank() || !System.getenv("ANDROID_SDK_ROOT").isNullOrBlank() ||
    file("local.properties").let { it.exists() && it.readText().contains("sdk.dir") }
if (hasAndroidSdk) include(":app")

// Robolectric 集成测试使用备用构建（tools/build-apk.sh）的产物，产物存在时才加入。
if (file("build/fallback/base.apk").exists()) include(":robotest")
