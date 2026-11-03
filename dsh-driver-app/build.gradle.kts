// 插件版本只在这里写一次。AGP 只在有 Android SDK 时加载（见 settings.gradle.kts）。
buildscript {
    val android = System.getenv("ANDROID_HOME") != null || System.getenv("ANDROID_SDK_ROOT") != null || rootProject.file("local.properties").exists()
    repositories {
        if (android) google()
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.21")
        if (android) classpath("com.android.tools.build:gradle:8.5.2")
    }
}
