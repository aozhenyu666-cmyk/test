$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$PythonExe = $null
$PythonArgs = @()
if (Get-Command py -ErrorAction SilentlyContinue) {
    & py -3 -c "import sys; sys.exit(0 if sys.version_info >= (3,10) else 1)" 2>$null
    if ($LASTEXITCODE -eq 0) { $PythonExe = 'py'; $PythonArgs = @('-3') }
}
if (-not $PythonExe -and (Get-Command python -ErrorAction SilentlyContinue)) {
    & python -c "import sys; sys.exit(0 if sys.version_info >= (3,10) else 1)" 2>$null
    if ($LASTEXITCODE -eq 0) { $PythonExe = 'python' }
}
if (-not $PythonExe) {
    throw 'Python 3.10+ is required. Install it from python.org, enable Add Python to PATH, then run again.'
}
Write-Host 'Creating a local Python environment...'
& $PythonExe @PythonArgs -m venv .venv
if ($LASTEXITCODE -ne 0) { throw 'Failed to create the Python environment.' }
& '.\.venv\Scripts\python.exe' -m pip install -r requirements-windows.txt
if ($LASTEXITCODE -ne 0) { throw 'Dependency installation failed. Check your internet connection and rerun install.ps1.' }
Write-Host 'Ready. Double-click start.cmd to open Agent Bridge.' -ForegroundColor Green
