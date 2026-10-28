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

    # .aar 不能直接放进 java -cp；纯逻辑单测不需要 Compose 的类，用 mockable android.jar 顶上。
    #
    # 为什么必须是 **mockable** 而不是 SDK 里那个 android.jar：
    # 真实 android.jar 里所有方法体都是 `throw new RuntimeException("Stub!")`。
    # AGP 生成的 mockable jar 把它们改成了「返回默认值」，单元测试（unitTests.isReturnDefaultValues）
    # 就是靠这个才能直接调 android.util.Log 之类。用错 jar 会让一堆本来正常的测试报
    # ExceptionInInitializerError / Stub!，看着像代码坏了，其实是运行器选错了。
    $cpEntries = @($raw -split ';' | Where-Object { $_ -and $_ -notlike '*.aar' })

    # 磁盘上会有好几个 android.jar（SDK 原版 26MB、各种 transform 产物 3–5MB），
    # 光看大小/时间都不靠谱。唯一可靠的判据是 **Log.class 里那句错误文案**：
    #   · 真实 jar / 未处理的 transform 产物 → 方法体抛 "not mocked"
    #   · mockable 版 → 方法体是 `return 0`，既没有 "not mocked" 也没有 "Stub!"
    # 实测这里 8.9 下两个 transform 产物一个是原版、一个才是 mockable，只能靠内容分辨。
    function Test-Mockable([string]$path) {
        try {
            Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction SilentlyContinue
            $zip = [System.IO.Compression.ZipFile]::OpenRead($path)
            $entry = $null
            foreach ($z in $zip.Entries) { if ($z.FullName -eq 'android/util/Log.class') { $entry = $z; break } }
            if ($null -eq $entry) { $zip.Dispose(); return $false }
            $ms = New-Object System.IO.MemoryStream
            $s = $entry.Open(); $s.CopyTo($ms); $s.Close()
            $txt = [System.Text.Encoding]::ASCII.GetString($ms.ToArray())
            $ms.Dispose(); $zip.Dispose()
            # 干净 = 既没有 not mocked 也没有 Stub!
            $clean = ($txt.IndexOf('not mocked') -lt 0) -and ($txt.IndexOf('Stub!') -lt 0)
            return $clean
        } catch {
            Write-Host "   (读不了 $path : $($_.Exception.Message))" -ForegroundColor DarkYellow
            return $false
        }
    }

    # 先请 AGP 把 mockable jar 生成出来（UP-TO-DATE 时很快返回）
    & .\gradlew.bat --offline --console=plain -q :app:createMockableJar 2>$null | Out-Null
    $gh = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $env:USERPROFILE ".gradle" }
    Write-Host "gradle home = $gh" -ForegroundColor DarkGray

    $androidJar = ""
    $all = @(Get-ChildItem $gh -Recurse -Filter "android.jar" -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -match 'transforms' } |
        Sort-Object LastWriteTime -Descending)
    foreach ($c in $all) {
        if (Test-Mockable $c.FullName) { $androidJar = $c.FullName; break }
    }
    if (-not $androidJar) { throw "找不到 mockable android.jar（在 $gh 的 transform 产物里没有一份去掉了 not mocked / Stub!）" }
    Write-Host "android jar = $androidJar" -ForegroundColor DarkGray

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
