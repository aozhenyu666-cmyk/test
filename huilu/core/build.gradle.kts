plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    // Android 运行时自带 org.json；core 只在编译和测试时引用它。
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
    // 生成普通类而不是 invokedynamic，备用构建用的 dx 不做 lambda 脱糖
    compilerOptions.freeCompilerArgs.addAll("-Xlambdas=class", "-Xsam-conversions=class")
}

dependencies {
    compileOnly("org.json:json:20240303")
    testImplementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
}
