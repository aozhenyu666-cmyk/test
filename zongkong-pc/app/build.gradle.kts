import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
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
    testImplementation("junit:junit:4.13.2")
}

compose.desktop {
    application {
        mainClass = "com.zongkong.pc.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "ZongKong"
            // MSI 要求主版本号大于 0；界面上叫“电脑版 0.2.0”。每次发版都要加，不然覆盖安装不生效
            packageVersion = "1.0.4"
            includeAllModules = true
            description = "总控 电脑版"
            vendor = "zongkong"
            windows {
                menuGroup = "总控"
                perUserInstall = true
                shortcut = true
                upgradeUuid = "3b8f5a61-2c4d-4e9a-8f17-6d2c9e4b7a05"
            }
        }
    }
}

tasks.test {
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
