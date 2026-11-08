param(
    [string]$Serial = '',
    [string]$Out = ''
)

# P0.1 companion audit (read-only): device/permissions, alarm, companion logs,
# topic garden, memory DB counts, and one row per requestId chain.
# It never writes to the app's data directory; the only copy is a temporary
# SQLite snapshot in %TEMP% used to count rows, deleted before exit.

$ErrorActionPreference = 'Continue'
$adb = 'D:\code\android-sdk\platform-tools\adb.exe'
if (-not (Test-Path $adb)) { $adb = 'adb' }
$pkg = 'io.github.ccch1mneyyy.dsh_app'
$dsh = 'files/runtime/rootfs/root/.dsh'
$opt = 'files/runtime/rootfs/opt'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not $Out) {
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $Out = Join-Path $root "app\build\P0-AUDIT-$stamp.md"
}

if (-not $Serial) {
    $lines = & $adb devices 2>&1
    $device = $lines | Where-Object { $_ -match "^(\S+)\s+device$" } | Select-Object -First 1
    if ($device -and $device -match "^(\S+)") { $Serial = $Matches[1] }
}
if (-not $Serial) {
    Write-Host 'No adb device. Connect the phone and retry.'
    exit 2
}

function A([string]$command) {
    & $adb -s $Serial shell $command 2>&1 | Out-String
}

function ReadGuest([string]$path) {
    # No `sh -c '...'`: `adb shell` re-parses the string and eats the quotes, which
    # leaves `sh -c cat <path>` -- cat then reads stdin and every read comes back empty.
    (& $adb -s $Serial shell "run-as $pkg cat $path" 2>$null | Out-String)
}

function Section([string]$title, [string]$body) {
    $fence = '```'
    return "## $title`n`n" + $fence + "text`n" + $body.Trim() + "`n" + $fence + "`n"
}

function Clock([object]$ms) {
    try { return [DateTimeOffset]::FromUnixTimeMilliseconds([long]$ms).ToLocalTime().ToString('MM-dd HH:mm:ss') } catch { return '' }
}

$model = (A 'getprop ro.product.model').Trim()
$sdk = (A 'getprop ro.build.version.sdk').Trim()
$update = (A "dumpsys package $pkg") -split "`n" | Where-Object { $_ -match 'versionName=|lastUpdateTime=' } | ForEach-Object { $_.Trim() }
$a11y = (A 'settings get secure enabled_accessibility_services').Trim()
$notif = A "cmd appops get $pkg POST_NOTIFICATION"
$battery = (A 'dumpsys deviceidle whitelist') -split "`n" | Where-Object { $_ -match [regex]::Escape($pkg) }
$services = A "dumpsys activity services $pkg"
$node = (A "ps -A") -split "`n" | Where-Object { $_ -match '\snode\s*$' }
$logAll = (& $adb -s $Serial shell "run-as $pkg cat files/runtime/logs/dsh.log" 2>$null | Out-String)
$logs = ($logAll -split "`n" | Where-Object { $_ -match '\[companion\]|\[memory\]|\[relationship\]|\[strict\]|speak-up' } | Select-Object -Last 40) -join "`n"
$plugins = ReadGuest "$opt/dsh-app/plugins/plugins.patch.yml"
$cfg = ReadGuest "$dsh/companion/config.json"
$ledger = ReadGuest "$dsh/companion/ledger.jsonl"
$gardenRaw = ReadGuest "$dsh/companion/topics.json"
$mem = A "run-as $pkg ls -l $dsh/fish-memory"

# AlarmManager entries for this package (raw lines + the next few after each hit).
$alarmAll = ((A 'dumpsys alarm') -split "`n")
$alarmHits = New-Object System.Collections.Generic.List[string]
for ($i = 0; $i -lt $alarmAll.Count; $i++) {
    if ($alarmAll[$i] -notmatch [regex]::Escape($pkg)) { continue }
    $end = [Math]::Min($i + 6, $alarmAll.Count - 1)
    $block = ($alarmAll[$i..$end] | ForEach-Object { $_.TrimEnd() }) -join "`n"
    if (-not $alarmHits.Contains($block)) { $alarmHits.Add($block) }
}
$alarmSection = if ($alarmHits.Count) { ($alarmHits | Select-Object -First 6) -join "`n---`n" } else { 'no line mentioning the package in dumpsys alarm' }

# Ledger rows and one chain per requestId.
$entries = @()
foreach ($line in ($ledger -split "`n")) {
    $t = $line.Trim()
    if (-not $t.StartsWith('{')) { continue }
    try { $entries += ($t | ConvertFrom-Json) } catch { }
}
$states = @{}
foreach ($e in $entries) {
    if ($e.state) { $states[$e.state] = 1 + [int]$states[$e.state] }
}
$chains = @()
foreach ($group in ($entries | Where-Object { $_.requestId } | Group-Object requestId)) {
    $items = @($group.Group | Sort-Object at)
    $first = $items[0]
    $last = $items[-1]
    $stages = ($items | ForEach-Object { "$($_.state)@$(Clock $_.at)" }) -join ' -> '
    $failure = ($items | Where-Object { $_.failure } | Select-Object -First 1).failure
    $chains += [pscustomobject]@{
        requestId = $group.Name
        topicId = ($items | Where-Object { $_.topicId } | Select-Object -First 1).topicId
        cold = ($items | Where-Object { $_.cold -eq $true }).Count -gt 0
        firstAt = [long]$first.at
        lastAt = [long]$last.at
        lastState = $last.state
        stages = $stages
        failure = if ($failure) { $failure.code } else { '' }
    }
}
$chains = $chains | Sort-Object lastAt -Descending

# Topic garden snapshot.
$garden = $null
try { if ($gardenRaw.Trim()) { $garden = $gardenRaw | ConvertFrom-Json } } catch { }
$gardenCounts = @{}
$gardenRows = @()
if ($garden -and $garden.topics) {
    foreach ($t in $garden.topics) {
        $gardenCounts[$t.status] = 1 + [int]$gardenCounts[$t.status]
        $next = if ([long]$t.nextEligibleAt -gt 0) { Clock $t.nextEligibleAt } else { '-' }
        $gardenRows += [pscustomobject]@{
            id = $t.id; kind = $t.kind; status = $t.status; title = $t.title
            attempts = $t.attempts; next = $next; lastReply = $t.lastReplyClass
        }
    }
}
$ready = 0
if ($garden -and $garden.topics) {
    $nowMs = [DateTimeOffset]::Now.ToUnixTimeMilliseconds()
    $ready = @($garden.topics | Where-Object {
        $_.status -ne 'done' -and [int]$_.attempts -lt 2 -and [long]$_.nextEligibleAt -le $nowMs
    }).Count
}

# Read-only SQLite counts: copy to %TEMP%, count, delete.
$memCounts = 'not read'
$tmpDb = Join-Path $env:TEMP ('companion-audit-' + [guid]::NewGuid().ToString('N') + '.sqlite')
# PowerShell 5.1 mangles embedded double quotes when passing -e to a native exe,
# so the script goes through a file instead of `node -e "..."`.
$tmpJs = Join-Path $env:TEMP ('companion-audit-' + [guid]::NewGuid().ToString('N') + '.cjs')
try {
    $copy = '"' + $adb + '" -s ' + $Serial + ' exec-out run-as ' + $pkg + ' cat ' + $dsh + '/fish-memory/fish-memory.sqlite > "' + $tmpDb + '"'
    & cmd.exe /c $copy | Out-Null
    if (Test-Path $tmpDb) {
        $nodeCode = @'
const { DatabaseSync } = require("node:sqlite");
const d = new DatabaseSync(process.argv[2], { readOnly: true });
const c = (sql) => { try { return d.prepare(sql).get().c } catch { return -1 } };
console.log(JSON.stringify({
  facts: c("select count(*) c from facts"),
  openTopics: c("select count(*) c from topics where status = 'open'"),
  episodes: c("select count(*) c from episodes"),
  tombstones: c("select count(*) c from tombstones")
}));
d.close();
'@
        [IO.File]::WriteAllText($tmpJs, $nodeCode, [Text.UTF8Encoding]::new($false))
        $memCounts = (& node $tmpJs $tmpDb 2>&1 | Out-String).Trim()
    }
} catch {
    $memCounts = 'copy/read failed: ' + $_.Exception.Message
} finally {
    if (Test-Path $tmpDb) { Remove-Item $tmpDb -Force -ErrorAction SilentlyContinue }
    if (Test-Path $tmpJs) { Remove-Item $tmpJs -Force -ErrorAction SilentlyContinue }
}

# Findings: the first place to look.
$findings = New-Object System.Collections.Generic.List[string]
if (-not $node) { $findings.Add('engine: no node process (engine not running)') }
# Full log, not the 40-line tail: [strict] schema noise can push the line out of the window.
if ($logAll -notmatch '\[companion\] loaded') { $findings.Add('plugin: no "[companion] loaded" in the engine log') }
if ($plugins -match 'dsh-speak-up') { $findings.Add('scheduler: plugins.patch.yml still deploys dsh-speak-up') }
if ($cfg -notmatch '"sessionId"') { $findings.Add('target: companion config has no sessionId') }
if (-not $garden) { $findings.Add('garden: topics.json missing/unreadable') }
elseif ($ready -eq 0) { $findings.Add('garden: no eligible topic now (all done, cooling, or attempts exhausted)') }
if ($memCounts -eq 'not read' -or $memCounts -match 'failed') { $findings.Add('memory: SQLite snapshot could not be read') }
if ($entries.Count -eq 0) { $findings.Add('ledger: empty (no proactive attempt reached the plugin)') }
if ($states['accepted'] -gt 0 -and $states['generated'] -eq 0) { $findings.Add('turn: accepted but never generated (model/turn/deadline)') }
if ($states['generated'] -gt 0 -and -not $states['displayed']) { $findings.Add('surface: generated but not displayed (notification/page path)') }
if ($chains | Where-Object { $_.lastState -eq 'failed' }) {
    $findings.Add('chain: at least one requestId ended failed; see the failure column')
}
if ($alarmHits.Count -eq 0) { $findings.Add('alarm: package not found in dumpsys alarm (no scheduled wake-up)') }
if ($findings.Count -eq 0) { $findings.Add('no obvious break point in the collected data') }

# Markdown.
$md = @()
$md += '# P0.1 companion audit'
$md += ''
$md += "- device: $model (API $sdk)"
$md += "- serial: $Serial"
$md += "- time: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
$md += ''
$md += '## First break point'
$md += ''
$md += ($findings | ForEach-Object { "- $_" }) -join "`n"
$md += ''
$md += Section 'Package' (($update -join "`n") + "`n`n" + (A "pm path $pkg"))
$md += Section 'Accessibility' $a11y
$md += Section 'Notification permission' $notif
$md += Section 'Battery whitelist' (($battery -join "`n"))
$md += Section 'Services' $services
$md += Section 'Engine process' (($node -join "`n"))
$md += Section 'Engine log' $logs
$md += Section 'Bundled plugin overlay' $plugins
$md += Section 'Companion config' $cfg
$md += Section 'Memory database' ($mem + "`n`nrow counts: " + $memCounts)
$md += Section 'AlarmManager' $alarmSection
$md += Section 'Topic garden counts' (($gardenCounts.GetEnumerator() | Sort-Object Name | ForEach-Object { "- $($_.Key): $($_.Value)" }) -join "`n")
$md += Section 'Topic garden rows' (($gardenRows | Select-Object -First 15 | ForEach-Object { "$($_.status) | $($_.kind) | att=$($_.attempts) | next=$($_.next) | reply=$($_.lastReply) | $($_.id) | $($_.title)" }) -join "`n")
$md += Section 'Ledger state counts' (($states.GetEnumerator() | Sort-Object Name | ForEach-Object { "- $($_.Key): $($_.Value)" }) -join "`n")
$md += Section 'Request chains' (($chains | Select-Object -First 20 | ForEach-Object { "$($_.requestId) | topic=$($_.topicId) | cold=$($_.cold) | $($_.stages) | failure=$($_.failure)" }) -join "`n")
$md += Section 'Ledger tail' (($ledger -split "`n" | Select-Object -Last 30) -join "`n")
$md += ''
$md += '## Manual checks'
$md += ''
$md += '- Phone control -> proactive: session chosen, enabled; tap "try once".'
$md += '- Expected chain: accepted -> claimed -> generated -> delivered -> displayed.'
$md += '- Reply substantively: ledger should add replied/substantive and the topic lastReplyClass.'
$md += '- Lock for 2 h / reboot: check dumpsys alarm and whether an attempt arrives at the expected time.'
$md += '- This audit is read-only: no memory/topic/persona/config write.'

New-Item -ItemType Directory -Force (Split-Path $Out) | Out-Null
[IO.File]::WriteAllText($Out, ($md -join "`n"), [Text.UTF8Encoding]::new($false))
Write-Host "wrote $Out"
Write-Host ''
Write-Host 'First break point:'
$findings | ForEach-Object { Write-Host "  - $_" }
