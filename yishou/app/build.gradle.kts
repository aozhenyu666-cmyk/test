plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.yishou.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.yishou.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 20
        versionName = "0.20.0"
    }

    signingConfigs {
        // 固定的调试签名，提交在仓库里。每次 CI 打出的 APK 签名一致，
        // 手机上可以直接覆盖安装，不用卸载（卸载会清掉本机数据）。
        getByName("debug") {
            storeFile = file("debug.keystore")
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
    buildFeatures {
        compose = true
        buildConfig = true
    }

    // 两条线：主线（一手）和岔路（探索版）。岔路有自己的包名，能和主线同时装，数据完全分开。
    flavorDimensions += "line"
    productFlavors {
        create("mainline") {
            dimension = "line"
            buildConfigField("boolean", "CHALU", "false")
            buildConfigField("String", "CHALU_NAME", "\"\"")
        }
        create("chalu") {
            dimension = "line"
            applicationIdSuffix = ".chalu"
            versionNameSuffix = "-chalu1"
            buildConfigField("boolean", "CHALU", "true")
            buildConfigField("String", "CHALU_NAME", "\"岔路 1\"")
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.3")

    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("androidx.security:security-crypto:1.0.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    // Android 自带的 org.json 在本地单元测试里只是空壳，换成真实实现
    testImplementation("org.json:json:20240303")
}

// 两条路共用一份数据库结构文件（schemas/）：岔路的 KSP 等主线写完再跑，免得读到写了一半的文件
tasks.configureEach {
    if (name.startsWith("kspChalu") && name.endsWith("Kotlin")) {
        mustRunAfter(name.replace("kspChalu", "kspMainline"))
    }
}
