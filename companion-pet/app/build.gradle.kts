plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "cn.companion.pet"
    compileSdk = 35

    defaultConfig {
        applicationId = "cn.companion.pet"
        minSdk = 30
        targetSdk = 35
        versionCode = 44
        versionName = "0.8.4-reliability-preview"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // 固定签名：每次构建（本地或 GitHub Actions）都用同一把钥匙，新 APK 才能直接覆盖安装，
    // 不用卸载重装、不丢权限和数据。没有这段时，CI 每次随机生成调试签名，每次都只能卸载重装。
    // 这是只给本自用项目的调试钥匙，密码公开；仓库是公开的，别人也能用它签名——不要拿它签别的应用。
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("keystore/tongzhuo-debug.jks")
            storePassword = "android"
            keyAlias = "tongzhuo"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.work.runtime)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
