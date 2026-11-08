param([string]$Root = '')

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

$toolCp = @($kc, $sl, $rf, $sc, $tc, $co, $an) -join ';'
$libCp = @($sl, $ju, $hc) -join ';'

$M = Join-Path $Root 'app\android\app\src\main\kotlin\io\github\ccch1mneyyy\dsh_app\phone\a11y'
$T = Join-Path $Root 'app\android\app\src\test\kotlin\io\github\ccch1mneyyy\dsh_app\phone\a11y'
$src = @(
  (Join-Path $M 'A11yHealth.kt'),
  (Join-Path $M 'NowFeed.kt'),
  (Join-Path $M 'WatchdogPolicy.kt'),
  (Join-Path $T 'A11yHealthPlanTest.kt'),
  (Join-Path $T 'NowFeedTest.kt'),
  (Join-Path $T 'WatchdogPolicyTest.kt')
)

$out = Join-Path $Root 'app\build\a11y-health-out'
Remove-Item $out -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $out | Out-Null
$log = Join-Path $Root 'app\build\a11y-health-compile.log'

$args2 = @('-no-stdlib', '-no-reflect', '-cp', $libCp, '-d', $out) + $src
& $jdk -cp $toolCp org.jetbrains.kotlin.cli.jvm.K2JVMCompiler @args2 *>&1 | Out-File -FilePath $log -Encoding utf8
if ($LASTEXITCODE -ne 0) {
  Write-Host 'COMPILE FAIL'
  Get-Content $log | Select-String 'error:' | Select-Object -First 20
  exit 1
}
Write-Host 'compile OK'

$tl = Join-Path $Root 'app\build\a11y-health-test.log'
& $jdk -cp ($out + ';' + $libCp) org.junit.runner.JUnitCore 'io.github.ccch1mneyyy.dsh_app.phone.a11y.A11yHealthPlanTest' 'io.github.ccch1mneyyy.dsh_app.phone.a11y.NowFeedTest' 'io.github.ccch1mneyyy.dsh_app.phone.a11y.WatchdogPolicyTest' *>&1 | Out-File -FilePath $tl -Encoding utf8
$txt = Get-Content $tl -Raw
$ok = [regex]::Match($txt, 'OK \((\d+) tests?\)')
if ($ok.Success) {
  Write-Host ('PASS  ' + $ok.Groups[1].Value + ' tests')
} else {
  Write-Host 'FAIL'
  Get-Content $tl | Select-Object -Last 30
  exit 1
}
