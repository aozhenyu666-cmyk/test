// 在 JVM 上用 Robolectric 运行真正的 Android 组件（Application、Activity、Service、通知、UsageStats）。
// 依赖 tools/build-apk.sh 的产物：编译好的 app 类、aapt 生成的资源包。先运行该脚本，再运行 `gradle :robotest:test`。
plugins {
    id("org.jetbrains.kotlin.jvm")
}

val fallback = rootDir.resolve("build/fallback")

dependencies {
    testImplementation(project(":core"))
    testImplementation(files(fallback.resolve("classes")))
    testImplementation(files(fallback.resolve("deps/shizuku-aidl-13.1.5.jar")))
    // 和 Android Gradle 插件的单元测试一样，外层类路径上要有 android.jar（Robolectric 解析注解默认值时需要）
    testImplementation(files(fallback.resolve("deps/android-all-14-robolectric-10818077.jar")))
    testImplementation("org.robolectric:robolectric:4.14.1") {
        exclude(group = "androidx.test") // 只发布在 Google Maven 上，见 src/test/java/androidx
        exclude(group = "androidx.test.espresso")
    }
    testImplementation("junit:junit:4.13.2")
}

val robolectricConfig = tasks.register("robolectricConfig") {
    val out = layout.buildDirectory.dir("generated/robolectric")
    outputs.dir(out)
    doLast {
        val f = out.get().file("com/android/tools/test_config.properties").asFile
        f.parentFile.mkdirs()
        f.writeText(
            """
            android_merged_manifest=${fallback.resolve("AndroidManifest.xml").absolutePath}
            android_resource_apk=${fallback.resolve("base.apk").absolutePath}
            android_custom_package=huilu.app
            """.trimIndent() + "\n"
        )
    }
}

sourceSets["test"].resources.srcDir(robolectricConfig)

tasks.test {
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", fallback.resolve("deps").absolutePath)
    maxHeapSize = "3g"
    // Robolectric 在 JDK 17+ 上模拟 ParcelFileDescriptor 管道时需要
    jvmArgs("--add-opens=java.base/java.io=ALL-UNNAMED")
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
