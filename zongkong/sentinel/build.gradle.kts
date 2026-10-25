plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 哨兵：独立的小应用（单独的包名、单独的进程和用户 ID）。
// 总控崩了、被系统清掉、被“强行停止”，都碰不到它；它也不碰网络、AI、Compose，几乎没有会崩的东西。
android {
    namespace = "com.zongkong.sentinel"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.zongkong.sentinel"
        minSdk = 26
        targetSdk = 34
        // v2 健康协议：独立 :watch 进程 + 设备管理员锁屏 + 多客户端健康协议（协议版本 2）
        versionCode = 3
        versionName = "1.2.0-health-v2"
    }

    signingConfigs {
        // 和总控同一把签名：两边靠它认出对方（别的应用连不上）
        getByName("debug") {
            storeFile = file("../app/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
