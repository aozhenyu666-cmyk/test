plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dshdriver.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dshdriver.app"
        minSdk = 26 // 播放状态回调（AudioPlaybackCallback）要 Android 8.0
        targetSdk = 34
        versionCode = 2
        versionName = "0.2.0"
    }

    signingConfigs {
        // 固定的调试签名，提交在仓库里：每次 CI 打出的 APK 签名一致，可以直接覆盖安装
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        debug { signingConfig = signingConfigs.getByName("debug") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // Curbox 的 AIDL 接口（src/main/aidl/neth/iecal/curbox/api/ICurboxApi.aidl）
    // 拦截改成内置后不再用 Curbox 的 AIDL
    buildFeatures { aidl = false }
    lint {
        // 调试版只要能装能跑；lint 的警告不挡打包
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(project(":core"))
    // 用 Shizuku 的 shell 身份把无障碍写回去（抄 Curbox 的同一条路：IShizukuService.newProcess）
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
