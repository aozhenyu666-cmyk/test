<#
  总控的本地验证脚本（Windows）。做两件事：
    1. 编译：gradlew compileDebugKotlin compileDebugUnitTestKotlin
    2. 跑单测：绕开 Gradle 的 Test 任务，直接用 JUnitCore 跑

  为什么要绕：本机路径里含中文时，Gradle 的 Test Executor 起不来
  （Java argfile 编码问题，报 ClassNotFoundException: GradleWorkerMain），
  那不是用例失败。直接调 JUnit 能拿到真实的通过/失败。

  用法：
    pwsh -File scripts/verify.ps1              # 编译 + 全部单测
    pwsh -File scripts/verify.ps1 -Test Class  # 只跑某个测试类（如 ChatModeTest）
    pwsh -File scripts/verify.ps1 -SkipTests   # 只编译
#>
param(
    [string]$Test = "",
    [switch]$SkipTests
)

$ErrorActionPreference = "Stop"
$proj = Split-Path -Parent $PSScriptRoot
Push-Location $proj
try {
    # ---------- JDK / Gradle home ----------
    if (-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
        $cand = @(
            "C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot",
            "C:\Program Files\Microsoft\jdk-17.0.13.11-hotspot",
            "C:\Program Files\Java\jdk-17"
        ) | Where-Object { Test-Path (Join-Path $_ "bin\java.exe") } | Select-Object -First 1
        if (-not $cand) { throw "找不到可用的 JDK。请设置 JAVA_HOME 指向 JDK 17 或 21。" }
        $env:JAVA_HOME = $cand
    }
    $java = Join-Path $env:JAVA_HOME "bin\java.exe"
    Write-Host "JAVA_HOME = $env:JAVA_HOME" -ForegroundColor DarkGray

    # gradlew 默认去 ~/.gradle；这个仓库把 Gradle 缓存放在工作区里（见 docs / 历史记录）
    if (-not $env:GRADLE_USER_HOME) {
        $alt = Join-Path (Split-Path -Parent $proj) "_gradle-home"
        $alt2 = Join-Path (Split-Path -Parent (Split-Path -Parent $proj)) "_gradle-home"
        foreach ($a in @($alt, $alt2)) {
            if (Test-Path (Join-Path $a "wrapper")) { $env:GRADLE_USER_HOME = $a; break }
        }
    }
    if ($env:GRADLE_USER_HOME) { Write-Host "GRADLE_USER_HOME = $env:GRADLE_USER_HOME" -ForegroundColor DarkGray }

    # ---------- 1. 编译 ----------
    # in-process：本机路径含中文时，Kotlin 编译守护进程会把路径转义成 uXXXX 而找不到插件 jar，
    # 在同一个 JVM 里编译就绕开这一步（慢一点，但稳）。
    $tasks = @(":app:compileDebugKotlin")
    if (-not $SkipTests) { $tasks += ":app:compileDebugUnitTestKotlin" }
    Write-Host "`n== 编译 ==" -ForegroundColor Cyan
    & .\gradlew.bat --offline --console=plain -q "-Pkotlin.compiler.execution.strategy=in-process" @tasks
    if ($LASTEXITCODE -ne 0) { throw "编译失败（exit $LASTEXITCODE）" }
    Write-Host "编译通过" -ForegroundColor Green

    if ($SkipTests) { return }

    # ---------- 2. 取出单测运行时 classpath ----------
    $initScript = Join-Path $env:TEMP "zk-printtestcp.gradle"
    @'
allprojects {
    tasks.register("zkPrintTestCp") {
        doLast {
            def cfg = project.configurations.findByName("debugUnitTestRuntimeClasspath")
            if (cfg != null) {
                println "ZKCPSTART"
                println cfg.files.join(File.pathSeparator)
                println "ZKCPEND"
            }
        }
    }
}
'@ | Set-Content -Path $initScript -Encoding utf8

    Write-Host "`n== 解析测试 classpath ==" -ForegroundColor Cyan
    $out = & .\gradlew.bat --offline --console=plain -q -I $initScript :app:zkPrintTestCp 2>&1
    $lines = @($out | ForEach-Object { "$_" })
    $i = [array]::IndexOf($lines, "ZKCPSTART")
    if ($i -lt 0) { throw "取不到测试 classpath。原始输出：`n$($lines -join "`n")" }
    $raw = $lines[$i + 1]

    # .aar 不能直接放进 java -cp；纯逻辑单测不需要 Compose 的类，用 android.jar 顶上
    $cpEntries = @($raw -split ';' | Where-Object { $_ -and $_ -notlike '*.aar' })
    $sdk = ""
    $localProps = Join-Path $proj "local.properties"
    if (Test-Path $localProps) {
        $m = Select-String -Path $localProps -Pattern '^sdk\.dir=(.+)$'
        if ($m) { $sdk = $m.Matches[0].Groups[1].Value -replace '\\:', ':'; $sdk = $sdk -replace '\\\\', '\' }
    }
    $androidJar = if ($sdk) { Join-Path $sdk "platforms\android-34\android.jar" } else { "" }
    if (-not (Test-Path $androidJar)) {
        $alt34 = "C:\Android\sdk\platforms\android-34\android.jar"
        if (Test-Path $alt34) { $androidJar = $alt34 } else { $androidJar = "" }
    }

    $classesMain = Join-Path $proj "app\build\tmp\kotlin-classes\debug"
    $classesTest = Join-Path $proj "app\build\tmp\kotlin-classes\debugUnitTest"
    if (-not (Test-Path $classesTest)) { throw "找不到编译产物：$classesTest" }

    $full = @($classesTest, $classesMain) + $cpEntries
    if ($androidJar) { $full += $androidJar }
    $cp = ($full -join ';')

    # ---------- 3. 跑 JUnit ----------
    $names = @(Get-ChildItem $classesTest -Recurse -Filter "*Test.class" |
        Where-Object { $_.Name -notmatch '\$' } |
        ForEach-Object { $_.FullName.Substring($classesTest.Length + 1).Replace('\', '.').Replace('.class', '') } |
        Sort-Object)
    if ($Test) { $names = @($names | Where-Object { $_ -like "*$Test*" }) }
    if ($names.Count -eq 0) { throw "没有找到匹配的测试类（-Test $Test）" }

    Write-Host "`n== 跑单测（$($names.Count) 个类）==" -ForegroundColor Cyan
    Write-Host ($names -join "`n") -ForegroundColor DarkGray
    & $java -cp $cp org.junit.runner.JUnitCore @names
    if ($LASTEXITCODE -ne 0) { throw "有测试没通过（exit $LASTEXITCODE）" }
    Write-Host "`n全部通过" -ForegroundColor Green
}
finally {
    Pop-Location
}
