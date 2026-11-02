# Dafeiyu companion plugins - one-shot installer for DSH desktop (Windows).
#
# What it does:
#   1. Backs up memory / mood / plugin config data to %USERPROFILE%\.dsh-backup-<time>
#   2. Installs the two .tgz packages next to this script with `dsh plugin add`
#   3. Prints what to configure next (see INSTALL.md step 3)
#
# What it does NOT do: edit any config file, touch sessions, or delete anything.
#
# Usage (from the folder that contains this script and the .tgz files, or dist\):
#   powershell -ExecutionPolicy Bypass -File .\install.ps1
#   powershell -ExecutionPolicy Bypass -File .\install.ps1 -DshProfile desktop
#
# NOT_TESTED on a real machine yet. If a step fails, copy the full red error text back.

param(
  [string]$DshProfile = 'desktop'
)

$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$dsh = Join-Path $env:USERPROFILE '.dsh'

function Find-Package([string]$pattern) {
  foreach ($dir in @($here, (Join-Path $here 'dist'))) {
    if (Test-Path $dir) {
      $hit = Get-ChildItem -Path $dir -Filter $pattern -File -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
      if ($hit) { return $hit.FullName }
    }
  }
  return $null
}

$speak = Find-Package 'dsh-speak-up-*-dafeiyu*.tgz'
$memory = Find-Package 'dsh-fish-memory-*-dafeiyu*.tgz'
if (-not $speak -or -not $memory) {
  Write-Host "Could not find both .tgz packages next to this script (or in .\dist)." -ForegroundColor Red
  Write-Host "  speak-up:    $speak"
  Write-Host "  fish-memory: $memory"
  exit 1
}

# ---- 1. backup ----
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$backup = Join-Path $env:USERPROFILE ".dsh-backup-$stamp"
New-Item -ItemType Directory -Path $backup | Out-Null
$items = @('fish-memory', 'fish-memory-backup', 'feiyu-mood',
           'fish-memory-config.json', 'speak-up-config.json', 'her-words-config.json')
foreach ($x in $items) {
  $p = Join-Path $dsh $x
  if (Test-Path $p) {
    Copy-Item -Path $p -Destination $backup -Recurse
    Write-Host "backed up  $x"
  }
}
$profilePkg = Join-Path $dsh "profiles\$DshProfile\package.json"
if (Test-Path $profilePkg) {
  Copy-Item -Path $profilePkg -Destination (Join-Path $backup "profile-$DshProfile-package.json")
  Write-Host "backed up  profiles\$DshProfile\package.json"
}
Write-Host "Backup folder: $backup" -ForegroundColor Green

# ---- 2. install ----
$cli = Get-Command dsh -ErrorAction SilentlyContinue
if (-not $cli) {
  Write-Host ""
  Write-Host "The 'dsh' command is not on PATH. Install from DSH instead:" -ForegroundColor Yellow
  Write-Host "  Settings -> Plugins -> Install, paste each path below, then restart DSH:"
  Write-Host "  $speak"
  Write-Host "  $memory"
  exit 2
}

$failed = @()
foreach ($pkg in @($speak, $memory)) {
  Write-Host ""
  Write-Host "Installing $pkg ..." -ForegroundColor Cyan
  & dsh plugin --profile $DshProfile add $pkg
  if ($LASTEXITCODE -ne 0) {
    Write-Host "Plain path failed, retrying with file: prefix ..." -ForegroundColor Yellow
    $uri = 'file:' + ($pkg -replace '\\', '/')
    & dsh plugin --profile $DshProfile add $uri
    if ($LASTEXITCODE -ne 0) { $failed += $pkg }
  }
}

Write-Host ""
if ($failed.Count -gt 0) {
  Write-Host "Install failed for:" -ForegroundColor Red
  $failed | ForEach-Object { Write-Host "  $_" }
  Write-Host "Your data is untouched; backup is in $backup. See INSTALL.md 'Method C' for fallbacks."
  exit 3
}

Write-Host "Both plugins installed. Next:" -ForegroundColor Green
Write-Host "  1. Restart DSH."
Write-Host "  2. Follow INSTALL.md step 3 (pick the target session, turn on the master switch)."
Write-Host "  3. Run the checks in VERIFY.md."
Write-Host "Rollback: dsh plugin --profile $DshProfile add dsh-speak-up@0.2.7 ; dsh plugin --profile $DshProfile add dsh-fish-memory@1.4.3"
