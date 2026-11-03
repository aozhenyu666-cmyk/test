// 只编译、测试不依赖 Android 的 DriverReflex.kt（反射判定 + Live 推断）。
//   cd android/jvm-check && gradle test -Pkotlin.compiler.execution.strategy=in-process
plugins { kotlin("jvm") version "2.0.21" }
dependencies { testImplementation(kotlin("test")) }
sourceSets {
    main { kotlin { srcDir("../kotlin"); include("DriverReflex.kt") } }
    test { kotlin { srcDir("test") } }
}
tasks.test { useJUnitPlatform(); testLogging { events("passed", "failed") } }
