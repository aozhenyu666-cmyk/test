// 思维驾驶员：独立 APP。
//   :core  纯 Kotlin（状态机、判断规则、议程、去重、反射判定），任何有 JDK 的机器都能测
//   :app   Android 外壳（无障碍、前台服务、断点卡、悬浮卡、首页），需要 Android SDK
// 没有 Android SDK 时（比如只想跑 core 的测试）只包含 :core。
pluginManagement {
    val android = System.getenv("ANDROID_HOME") != null || System.getenv("ANDROID_SDK_ROOT") != null || File(rootDir, "local.properties").exists()
    repositories {
        if (android) google()
        // Maven Central 的 Google 镜像：有的网络里直连 Central 会被限流
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        gradlePluginPortal()
    }
}

fun androidSdkPresent(): Boolean =
    System.getenv("ANDROID_HOME") != null || System.getenv("ANDROID_SDK_ROOT") != null || File(rootDir, "local.properties").exists()

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (androidSdkPresent()) google()
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
    }
}

rootProject.name = "dsh-driver-app"
include(":core")
if (androidSdkPresent()) include(":app")
