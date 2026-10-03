plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "huilu.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "huilu.app"
        minSdk = 26
        // 34：避免 Android 15 强制的全面屏绘制改变这套纯代码界面的布局
        targetSdk = 34
        versionCode = 2
        versionName = "0.2.0"
    }

    signingConfigs {
        // 仓库里的公开调试密钥：保证不同机器、CI 打出的包可以互相覆盖安装（覆盖安装才不会丢本机日志）。
        // 不要用它发布到应用商店。
        getByName("debug") {
            storeFile = rootProject.file("tools/debug.keystore")
            storePassword = "android"
            keyAlias = "huilu"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    // 只用 Shizuku 的 AIDL 接口，客户端逻辑自己实现（见 Shizuku.kt）
    implementation("dev.rikka.shizuku:aidl:13.1.5")
}
