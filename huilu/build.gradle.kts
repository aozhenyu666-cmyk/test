buildscript {
    val hasAndroidSdk = !System.getenv("ANDROID_HOME").isNullOrBlank() || !System.getenv("ANDROID_SDK_ROOT").isNullOrBlank() ||
        rootDir.resolve("local.properties").let { it.exists() && it.readText().contains("sdk.dir") }
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.21")
        if (hasAndroidSdk) classpath("com.android.tools.build:gradle:8.7.3")
    }
}
