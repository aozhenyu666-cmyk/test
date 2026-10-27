#!/usr/bin/env pwsh
# 发布门禁回归测试：验证 companion-pet-release.yml 的判定逻辑。
#
# 为什么要有这个文件：这段判断以前写在 YAML 里，靠人工阅读保证正确，
# 结果留下了一个 fail-open 漏洞（查询失败就放行低版本）。现在把判定逻辑
# 抽成可执行的等价实现，用它证明各个分支的行为，改 YAML 时必须同步改这里。
#
# 用法：pwsh tools/release_gate_test.ps1

$ErrorActionPreference = 'Stop'

# 与 workflow 中 "Publish to tongzhuo-stable" 步骤等价的判定。
# 返回 @{ Allow; Reason }
function Test-ReleaseGate {
    param(
        [int]$Code,
        [bool]$ApkExists,        # APK 产物是否存在且非空
        [bool]$ReleaseExists,    # tongzhuo-stable 是否已存在
        [bool]$QuerySucceeded,   # gh release view 是否成功
        [string]$Body = ''       # 既有 release 的说明文本
    )

    if (-not $ApkExists) {
        return @{ Allow = $false; Reason = 'apk missing or empty' }
    }

    if (-not $ReleaseExists) {
        return @{ Allow = $true; Reason = 'first official release' }
    }

    # fail-closed：release 在，但读不到内容，就拒绝，绝不盲发
    if (-not $QuerySucceeded) {
        return @{ Allow = $false; Reason = 'cannot read existing release' }
    }

    $m = [regex]::Match($Body, 'versionCode\s+(\d+)')
    if (-not $m.Success) {
        return @{ Allow = $false; Reason = 'no versionCode parseable' }
    }

    $old = [int]$m.Groups[1].Value
    if ($Code -le $old) {
        return @{ Allow = $false; Reason = "versionCode $Code <= published $old" }
    }

    return @{ Allow = $true; Reason = "versionCode $Code > published $old" }
}

# 旧实现，仅用于证明漏洞曾经存在。
function Test-LegacyReleaseGate {
    param([int]$Code, [bool]$QuerySucceeded, [string]$Body = '')
    $text = if ($QuerySucceeded) { $Body } else { '' }
    $m = [regex]::Match($text, 'versionCode\s+(\d+)')
    $old = if ($m.Success) { $m.Groups[1].Value } else { '' }
    if ((-not [string]::IsNullOrEmpty($old)) -and ($Code -le [int]$old)) {
        return @{ Allow = $false; Reason = "refused: $Code <= $old" }
    }
    return @{ Allow = $true; Reason = "ALLOWED (old='$old')" }
}

$pass = 0; $fail = 0
function Assert-Gate {
    param([string]$Name, [bool]$ExpectedAllow, [hashtable]$Result)
    if ($Result.Allow -eq $ExpectedAllow) {
        Write-Host ("  PASS  {0}  [{1}]" -f $Name, $Result.Reason)
        $script:pass++
    } else {
        Write-Host ("  FAIL  {0}  expected allow={1}, got {2} [{3}]" -f $Name, $ExpectedAllow, $Result.Allow, $Result.Reason)
        $script:fail++
    }
}

Write-Host '=== 当前实现 ==='

Assert-Gate '首次发布放行' $true (Test-ReleaseGate -Code 39 -ApkExists $true -ReleaseExists $false -QuerySucceeded $false)
Assert-Gate '版本提升放行' $true (Test-ReleaseGate -Code 39 -ApkExists $true -ReleaseExists $true -QuerySucceeded $true -Body '同桌 0.7.0（versionCode 30），正式版。')
Assert-Gate '版本相同拒绝' $false (Test-ReleaseGate -Code 39 -ApkExists $true -ReleaseExists $true -QuerySucceeded $true -Body '同桌 0.7.9（versionCode 39），正式版。')
Assert-Gate '版本倒退拒绝（0.5.5 覆盖 0.5.8 事故）' $false (Test-ReleaseGate -Code 23 -ApkExists $true -ReleaseExists $true -QuerySucceeded $true -Body '同桌 0.5.8（versionCode 26），正式版。')
Assert-Gate 'Release 在但解析不出版本号 -> 拒绝' $false (Test-ReleaseGate -Code 39 -ApkExists $true -ReleaseExists $true -QuerySucceeded $true -Body '说明里没有版本号')
Assert-Gate '查询失败 -> 拒绝（fail-closed）' $false (Test-ReleaseGate -Code 39 -ApkExists $true -ReleaseExists $true -QuerySucceeded $false)
Assert-Gate 'APK 缺失 -> 拒绝' $false (Test-ReleaseGate -Code 39 -ApkExists $false -ReleaseExists $false -QuerySucceeded $false)

Write-Host ''
Write-Host '=== 旧实现：证明漏洞确实存在 ==='

$legacyOk = Test-LegacyReleaseGate -Code 23 -QuerySucceeded $true -Body '同桌 0.5.8（versionCode 26），正式版。'
Assert-Gate '旧逻辑/查询成功时能挡住倒退' $false $legacyOk

$legacyBug = Test-LegacyReleaseGate -Code 23 -QuerySucceeded $false
if ($legacyBug.Allow) {
    Write-Host ("  PASS  旧逻辑/查询失败时放行低版本 -> 漏洞复现 [{0}]" -f $legacyBug.Reason)
    $pass++
} else {
    Write-Host '  FAIL  旧逻辑漏洞未能复现，本测试失去意义'
    $fail++
}

Write-Host ''
Write-Host ("=== {0} passed, {1} failed ===" -f $pass, $fail)
if ($fail -ne 0) { exit 1 }
