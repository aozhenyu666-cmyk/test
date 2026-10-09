import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.1")
    implementation("net.java.dev.jna:jna-platform:5.15.0")
    implementation("org.json:json:20240303")
}

compose.desktop {
    application {
        mainClass = "com.yishou.pc.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "Yishou"
            // MSI 要求主版本号大于 0；界面上叫“电脑版 0.1”
            packageVersion = "1.0.1"
            includeAllModules = true
            description = "一手 电脑版：自律守门"
            vendor = "yishou"
            windows {
                menuGroup = "一手"
                perUserInstall = true
                shortcut = true
                upgradeUuid = "6c3f1d2e-8a4b-4f7e-9c1d-2b5a7e9f0a31"
            }
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
