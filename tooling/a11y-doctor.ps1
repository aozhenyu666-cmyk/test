param(
  [string]$Package = 'io.github.ccch1mneyyy.dsh_app',
  [string]$Service = 'io.github.ccch1mneyyy.dsh_app/io.github.ccch1mneyyy.dsh_app.phone.a11y.PhoneA11yService',
  [string]$Adb = '',
  [string]$OutFile = ''
)

$ErrorActionPreference = 'Continue'

function Find-Adb {
  if ($Adb) { return $Adb }
  $candidates = @()
  if ($env:ANDROID_HOME) { $candidates += (Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe') }
  if ($env:ANDROID_SDK_ROOT) { $candidates += (Join-Path $env:ANDROID_SDK_ROOT 'platform-tools\adb.exe') }
  $candidates += 'D:\code\android-sdk\platform-tools\adb.exe'
  $candidates += "C:\Users\$env:USERNAME\AppData\Local\Android\Sdk\platform-tools\adb.exe"
  foreach ($c in $candidates) {
    if ($c -and (Test-Path $c)) { return $c }
  }
  return 'adb'
}

$adb = Find-Adb

function Invoke-Adb([string[]]$Arguments) {
  & $adb @Arguments 2>&1
}

function Section([string]$Title) {
  Write-Output ''
  Write-Output "===== $Title ====="
}

$report = New-Object System.Collections.Generic.List[string]
function Say([string]$Line) {
  $report.Add($Line) | Out-Null
  Write-Output $Line
}

Say 'R4 accessibility doctor'
Say "adb: $adb"
Say "package: $Package"
Say "service: $Service"

$devices = Invoke-Adb @('devices', '-l')
Section 'Devices'
Say ($devices -join "`n")
if (-not (($devices -join "`n") -match '\sdevice\s')) {
  Say 'FAIL No authorized USB device. Enable Developer options -> USB debugging, then allow this computer.'
  exit 1
}

$model = (Invoke-Adb @('shell', 'getprop', 'ro.product.model')) -join ' '
$brand = (Invoke-Adb @('shell', 'getprop', 'ro.product.brand')) -join ' '
$release = (Invoke-Adb @('shell', 'getprop', 'ro.build.version.release')) -join ' '
Section 'Device'
Say "brand=$brand model=$model android=$release"

$enabled = (Invoke-Adb @('shell', 'settings', 'get', 'secure', 'enabled_accessibility_services')) -join ''
$a11yOn = (Invoke-Adb @('shell', 'settings', 'get', 'secure', 'accessibility_enabled')) -join ''
Section 'System switches'
Say "accessibility_enabled=$a11yOn"
Say "enabled_accessibility_services=$enabled"
if ($enabled -match [regex]::Escape($Package)) {
  Say 'PASS The accessibility service is listed by the system.'
} else {
  Say 'FAIL The app is not listed. Open Settings -> Accessibility -> Downloaded services and enable it.'
}

$accessibility = (Invoke-Adb @('shell', 'dumpsys', 'accessibility')) -join "`n"
Section 'dumpsys accessibility (filtered)'
$lines = $accessibility -split "`n" | Where-Object { $_ -match [regex]::Escape($Package) -or $_ -match 'Bound services|Enabled services|Accessibility Service' }
Say ($lines -join "`n")
if (($lines -join "`n") -match [regex]::Escape($Package) -and $accessibility -match 'Bound services') {
  Say 'PASS The accessibility service appears bound.'
} else {
  Say 'WARN No bound record. Could be enabled-but-not-connected, or truncated dumpsys output.'
}

$appPid = (Invoke-Adb @('shell', 'pidof', $Package)) -join ''
Section 'Process'
if ($appPid) { Say "PASS $Package is running pid=$appPid" } else { Say 'FAIL Process is not running. Open the app and keep the foreground service alive.' }

$services = (Invoke-Adb @('shell', 'dumpsys', 'activity', 'services', $Package)) -join "`n"
Section 'Foreground services'
$svcLines = $services -split "`n" | Where-Object { $_ -match 'ServiceRecord|isForeground|startRequested|app=' }
Say ($svcLines -join "`n")
if (($svcLines -join "`n") -match 'isForeground=true') {
  Say 'PASS isForeground=true found.'
} else {
  Say 'WARN No isForeground=true. Check the persistent notification and whether the system downgraded it.'
}

$whitelist = (Invoke-Adb @('shell', 'dumpsys', 'deviceidle', 'whitelist')) -join "`n"
Section 'Battery whitelist'
if ($whitelist -match [regex]::Escape($Package)) {
  Say 'PASS App is in the deviceidle whitelist.'
} else {
  Say 'WARN Not in the deviceidle whitelist. Settings -> Apps -> DSH Go -> Battery -> Unrestricted.'
}

$appops = (Invoke-Adb @('shell', 'cmd', 'appops', 'get', $Package)) -join "`n"
Section 'AppOps (selected)'
Say (($appops -split "`n" | Where-Object { $_ -match 'RUN_IN_BACKGROUND|WAKE_LOCK|POST_NOTIFICATION|GET_USAGE_STATS|SYSTEM_ALERT_WINDOW' }) -join "`n")

$power = (Invoke-Adb @('shell', 'dumpsys', 'power')) -join "`n"
Section 'Screen / power'
Say (($power -split "`n" | Where-Object { $_ -match 'mWakefulness|mScreenOn|Display Power' }) -join "`n")

$log = (Invoke-Adb @('logcat', '-d', '-t', '1000')) -join "`n"
Section 'Recent log (filtered)'
$logLines = $log -split "`n" | Where-Object {
  $_ -match 'PhoneA11yService|AccessibilityService|ANR in|FATAL EXCEPTION|Force stopping|lowmemorykiller|am_kill' -or $_ -match [regex]::Escape($Package)
} | Select-Object -Last 60
Say ($logLines -join "`n")

Section 'Verdict'
Say '1) Enabled but not bound: this is the "silently dead" case. Build automatic rebind.'
Say '2) Process missing: apply the iQOO whitelist first, then check the foreground service.'
Say '3) am_kill / lowmemorykiller in the log: the system is killing it. Check background high-power, autostart, sleep mode.'
Say '4) Bound but no events: watch lastEventAt and rootInActiveWindow, then toggle rebind if stale.'

if ($OutFile) {
  $report -join "`n" | Set-Content -Path $OutFile -Encoding UTF8
  Write-Output "Report written to $OutFile"
}
