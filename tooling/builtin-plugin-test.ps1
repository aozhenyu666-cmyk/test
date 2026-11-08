param([string]$Root = '')

# Gradle's test worker cannot start from this checkout's non-ASCII user path
# (GradleWorkerMain ClassNotFoundException), so compile and run this pair with
# kotlinc directly, the same way tooling/a11y-health-test.ps1 does.

$ErrorActionPreference = 'Continue'
if (-not $Root) { $Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path }

$jdk = 'C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot\bin\java.exe'
if (-not (Test-Path $jdk)) { $jdk = 'java' }

$base = Join-Path $env:USERPROFILE '.gradle\caches\modules-2\files-2.1'
function J($p) { (Get-ChildItem $p -Recurse -Filter '*.jar' -ErrorAction SilentlyContinue | Select-Object -First 1).FullName }

$kc = J (Join-Path $base 'org.jetbrains.kotlin\kotlin-compiler-embeddable\2.2.21')
$sl = J (Join-Path $base 'org.jetbrains.kotlin\kotlin-stdlib\2.2.21')
$rf = J (Join-Path $base 'org.jetbrains.kotlin\kotlin-reflect\2.2.21')
$sc = J (Join-Path $base 'org.jetbrains.kotlin\kotlin-script-runtime\2.2.21')
$tc = J (Join-Path $base 'org.jetbrains.kotlin\kotlin-daemon-embeddable\2.2.21')
$co = J (Join-Path $base 'org.jetbrains.kotlinx\kotlinx-coroutines-core-jvm')
$an = J (Join-Path $base 'org.jetbrains\annotations')
$ju = J (Join-Path $base 'junit\junit\4.13.2')
$hc = J (Join-Path $base 'org.hamcrest\hamcrest-core\1.3')

$androidJar = Get-ChildItem (Join-Path $Root '..\..\android-sdk\platforms') -Filter 'android.jar' -Recurse -ErrorAction SilentlyContinue |
  Sort-Object FullName | Select-Object -Last 1 -ExpandProperty FullName
if (-not $androidJar) { $androidJar = 'D:\code\android-sdk\platforms\android-36\android.jar' }

$toolCp = @($kc, $sl, $rf, $sc, $tc, $co, $an) -join ';'
$libCp = @($sl, $ju, $hc, $androidJar) -join ';'

$M = Join-Path $Root 'app\android\app\src\main\kotlin\io\github\ccch1mneyyy\dsh_app\runtime'
$T = Join-Path $Root 'app\android\app\src\test\kotlin\io\github\ccch1mneyyy\dsh_app\runtime'
$src = @(
  (Join-Path $M 'SessionSearchGuest.kt'),
  (Join-Path $M 'BuiltinPluginGuest.kt'),
  (Join-Path $M 'FishMcpGuest.kt'),
  (Join-Path $T 'BuiltinPluginGuestTest.kt'),
  (Join-Path $T 'FishMcpGuestTest.kt')
)

$out = Join-Path $Root 'app\build\builtin-plugin-out'
Remove-Item $out -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $out | Out-Null
$log = Join-Path $Root 'app\build\builtin-plugin-compile.log'

$args2 = @('-no-stdlib', '-no-reflect', '-cp', $libCp, '-d', $out) + $src
& $jdk -cp $toolCp org.jetbrains.kotlin.cli.jvm.K2JVMCompiler @args2 *>&1 | Out-File -FilePath $log -Encoding utf8
if ($LASTEXITCODE -ne 0) {
  Write-Host 'COMPILE FAIL'
  Get-Content $log | Select-String 'error:' | Select-Object -First 20
  exit 1
}
Write-Host 'compile OK'

$tl = Join-Path $Root 'app\build\builtin-plugin-test.log'
& $jdk -cp ($out + ';' + $libCp) org.junit.runner.JUnitCore 'io.github.ccch1mneyyy.dsh_app.runtime.BuiltinPluginGuestTest' 'io.github.ccch1mneyyy.dsh_app.runtime.FishMcpGuestTest' *>&1 | Out-File -FilePath $tl -Encoding utf8
$txt = Get-Content $tl -Raw
$ok = [regex]::Match($txt, 'OK \((\d+) tests?\)')
if ($ok.Success) {
  Write-Host ('PASS  ' + $ok.Groups[1].Value + ' tests')
} else {
  Write-Host 'FAIL'
  Get-Content $tl | Select-Object -Last 30
  exit 1
}
