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
        versionCode = 1
        versionName = "0.1.0"
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
    lint {
        // 调试版只要能装能跑；lint 的警告不挡打包
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(project(":core"))
}
