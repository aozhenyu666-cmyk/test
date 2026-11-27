import java.io.FileInputStream
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

fun buildMinuteVersionCode(): Int {
    val t = OffsetDateTime.now(ZoneOffset.ofHours(8))
    return (t.year - 2026) * 100_000_000 + t.monthValue * 1_000_000 + t.dayOfMonth * 10_000 + t.hour * 100 + t.minute
}

val keyPropertiesFile = rootProject.file("key.properties")
val keyProperties = Properties()
if (keyPropertiesFile.exists()) {
    FileInputStream(keyPropertiesFile).use { keyProperties.load(it) }
}

android {
    namespace = "io.github.ccch1mneyyy.dsh_app.fish.guardian"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.ccch1mneyyy.dsh_app.fish.guardian"
        minSdk = 26
        targetSdk = 34
        versionCode = buildMinuteVersionCode()
        versionName = "1.0.0-fish-guardian"
    }

    signingConfigs {
        if (keyPropertiesFile.exists() && !keyProperties.getProperty("storeFile").isNullOrBlank()) {
            create("release") {
                storeFile = file(keyProperties.getProperty("storeFile"))
                storePassword = keyProperties.getProperty("storePassword")
                keyAlias = keyProperties.getProperty("keyAlias")
                keyPassword = keyProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (keyPropertiesFile.exists() && !keyProperties.getProperty("storeFile").isNullOrBlank()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
