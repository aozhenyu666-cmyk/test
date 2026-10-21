// 云端环境连不上谷歌的 Android SDK：总控和墨灵的纯逻辑（core/）在这里用普通 JVM 编译、跑单元测试，
// 再加一组两边约定的交叉测试（墨灵发的每种指令总控都认、状态字段一个不丢）。
// 用法：在本目录 `gradle test`（需要 JDK 17 以上和 Gradle 8）。Android 编译以 CI 为准。
plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
}

repositories {
    // Maven Central 偶尔限流（429），先走谷歌的镜像
    maven("https://maven-central.storage-download.googleapis.com/maven2/")
    mavenCentral()
}



sourceSets {
    main {
        kotlin.srcDir("../../zongkong/app/src/main/java/com/zongkong/core")
        kotlin.srcDir("../../moling/app/src/main/java/com/moling/core")
    }
    test {
        kotlin.srcDir("../../zongkong/app/src/test/java/com/zongkong/core")
        kotlin.srcDir("../../zongkong/app/src/sharedTest/java/com/zongkong/core")
        kotlin.srcDir("../../moling/app/src/test/java/com/moling/core")
        kotlin.srcDir("src/test/kotlin")
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

tasks.test {
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
