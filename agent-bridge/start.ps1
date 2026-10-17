param([int]$Port = 8765, [switch]$NoBrowser)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
if (-not (Test-Path '.\.venv\Scripts\python.exe')) {
    & "$PSScriptRoot\install.ps1"
}
$BridgeArgs = @('bridge.py', '--port', "$Port")
if ($NoBrowser) { $BridgeArgs += '--no-browser' }
& '.\.venv\Scripts\python.exe' @BridgeArgs
if ($LASTEXITCODE -ne 0) { throw "Agent Bridge exited with code $LASTEXITCODE" }
