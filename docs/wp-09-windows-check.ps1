# WP-09 follow-up Windows check: the naming assistant without a run, on p2.product (docs/wp-09-windows-check.md).
# Run in Windows PowerShell 5.1, in the desktop session:
#   powershell -NoProfile -ExecutionPolicy Bypass -File docs\wp-09-windows-check.ps1 -Mode Record
#   powershell -NoProfile -ExecutionPolicy Bypass -File docs\wp-09-windows-check.ps1 -Mode Stop
#   powershell -NoProfile -ExecutionPolicy Bypass -File docs\wp-09-windows-check.ps1 -Mode Check
# Mode Record: removes C:\smoke\names, then starts record with C:\smoke\names\walk.json. The folder does not exist
#   at the start (item 12). The command ends when the application stops. The owner does the clicks meanwhile.
# Mode Stop: stops the application JVM of the record session. It does not stop the record command.
# Mode Check: reads the files of the record session, runs lint and run, and writes C:\smoke\out\wp09-summary.txt.

param(
    [Parameter(Mandatory = $true)][ValidateSet('Record', 'Stop', 'Check')][string]$Mode,
    [string]$Java8 = 'C:\Program Files\Java\jdk1.8.0_341\bin\java.exe',
    [string]$Tools = 'C:\tools',
    [string]$Smoke = 'C:\smoke',
    [string]$P2 = 'D:\dev\p.osgi.ds\distribution\target\products\com.kk.pde.ds.product\win32\win32\x86_64',
    [string]$Osgi = 'plugins\org.eclipse.osgi_3.23.0.v20250228-0640.jar'
)

$ErrorActionPreference = 'Stop'
$out = Join-Path $Smoke 'out'
$cli = Join-Path $Tools 'smoker-cli.jar'
$agent = Join-Path $Tools 'smoker-agent.jar'
$names = Join-Path $Smoke 'names'
$walk = Join-Path $names 'walk.json'
$lintFile = Join-Path $names 'walk.lint.json'
$recordLog = Join-Path $names 'walk.record.log'
$recordOut = Join-Path $out 'wp09-record.txt'
$recordExit = Join-Path $out 'wp09-record-exit.txt'
$summary = Join-Path $out 'wp09-summary.txt'

foreach ($f in @($Java8, $cli, $agent, $P2)) {
    if (-not (Test-Path $f)) { throw "Not found: $f" }
}
New-Item -ItemType Directory -Force -Path $out | Out-Null

# The CLI receives --app as one argument. Each inner quote is \" (Windows PowerShell 5.1, docs/wp-01-handoff.md 4.2).
function App() {
    return '\"' + $Java8 + '\" {agent} -jar ' + $Osgi + ' -configuration configuration -consoleLog'
}

# The JVM of the application has -javaagent:...smoker-agent.jar=config=... in its command line.
# The record command has --agent ...smoker-agent.jar without "=config=": it does not match.
function AppProcesses() {
    return @(Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
        Where-Object { $_.CommandLine -like '*-javaagent:*smoker-agent.jar=config=*' })
}

if ($Mode -eq 'Record') {
    if (@(AppProcesses).Count -gt 0) { throw 'An application JVM with the agent runs. Stop it first (-Mode Stop).' }
    if (Test-Path $names) { Remove-Item -Recurse -Force $names }
    if (Test-Path $names) { throw "Cannot remove $names" }
    # Tee-Object of PowerShell 5.1 writes UTF-16. It makes the file alone: no other command writes to it.
    foreach ($f in @($recordOut, $recordExit)) { if (Test-Path $f) { Remove-Item -Force $f } }
    Write-Output "$names does not exist. Start record, $(Get-Date -Format s)."
    Push-Location $P2
    try {
        $ErrorActionPreference = 'Continue'
        & java -jar $cli record --agent $agent --scenario $walk --app (App) *>&1 | Tee-Object -FilePath $recordOut
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = 'Stop'
        Pop-Location
    }
    Set-Content -Path $recordExit -Value "record exit=$code"
    Write-Output "record exit=$code"
    return
}

if ($Mode -eq 'Stop') {
    $p = AppProcesses
    if ($p.Count -eq 0) { Write-Output 'No application JVM with the agent runs.'; return }
    foreach ($x in $p) {
        Write-Output "Stop process $($x.ProcessId)"
        Stop-Process -Id $x.ProcessId -Force
    }
    return
}

# Mode Check.
Set-Content -Path $summary -Value "WP-09 follow-up Windows check, $(Get-Date -Format s)"
function Line([string]$s) { Add-Content -Path $summary -Value $s }

# N-1 (item 12): the record command made the folder, and gave no stack trace.
$trace = @(Select-String -Path $recordOut -Pattern 'Exception|^\s+at ' -ErrorAction SilentlyContinue).Count
$recExit = 'unknown'
if (Test-Path $recordExit) {
    $m = [regex]::Match([System.IO.File]::ReadAllText($recordExit), 'record exit=(-?\d+)')
    if ($m.Success) { $recExit = $m.Groups[1].Value }
}
Line ("N-1: folder={0} recordLog={1} exceptionLines={2} recordExit={3}" -f (Test-Path $names), (Test-Path $recordLog), $trace, $recExit)

# N-2 (item 8): the saves of the session. The first save comes before any step.
$saved = @()
if (Test-Path $recordLog) {
    $saved = @(Select-String -Path $recordLog -SimpleMatch '[smoker] saved ' | ForEach-Object { $_.Line.Trim() })
}
Line "N-2: saves=$($saved.Count)"
foreach ($s in $saved) { Line "    $s" }
$first = ''
if ($saved.Count -gt 0) { $first = $saved[0] }
$zeroStep = [regex]::Match($first, '\(0 steps, 0 snapshots\); (\d+) component\(s\) without a real name')
$zeroCount = ''
if ($zeroStep.Success) { $zeroCount = $zeroStep.Groups[1].Value }
$lintLine = @(Select-String -Path $recordOut -SimpleMatch 'lint file: ' -ErrorAction SilentlyContinue).Count
$sumLine = @(Select-String -Path $recordOut -Pattern '^\d+ unnamed interactive component\(s\)' -ErrorAction SilentlyContinue |
    ForEach-Object { $_.Line.Trim() })
Line ("N-2: firstSaveZeroSteps={0} firstSaveComponents={1} lintFile={2} recordPrintsLintFile={3}" -f `
    $zeroStep.Success, $zeroCount, (Test-Path $lintFile), $lintLine)
foreach ($s in $sumLine) { Line "N-2: record summary: $s" }

$steps = -1
if (Test-Path $walk) {
    $sc = Get-Content -Raw -Encoding UTF8 -Path $walk | ConvertFrom-Json
    $steps = @($sc.steps).Count
    Line "walk.json: steps=$steps"
    foreach ($st in $sc.steps) { Line "    $($st.id) $($st.name)" }
}

# Record log: the [smoker] lines have no character above 127 (PR #8 rule). The lines of the application do not count.
if (Test-Path $recordLog) {
    $nonAscii = 0
    foreach ($l in @(Select-String -Path $recordLog -SimpleMatch '[smoker]' | ForEach-Object { $_.Line })) {
        foreach ($ch in $l.ToCharArray()) { if ([int]$ch -gt 127) { $nonAscii++ } }
    }
    Line "recordLog smokerLinesNonAscii=$nonAscii"
}

$summaryRegex = '(\d+) unnamed interactive component\(s\), (\d+) with a proposed name \((\d+) %: (\d+) from fields, (\d+) from text, (\d+) from labels\), (\d+) with an automatic name'

# N-3 and N-4 (items 8, 10, 11): lint of the scenario folder, with the patch.
$patch = Join-Path $out 'wp09-names.patch'
$lintRecordOut = Join-Path $out 'wp09-lint-record.txt'
$ErrorActionPreference = 'Continue'
& java -jar $cli lint --run $names --emit-patch $patch *>&1 | Tee-Object -FilePath $lintRecordOut | Out-Null
$code = $LASTEXITCODE
$ErrorActionPreference = 'Stop'
$m = [regex]::Match([System.IO.File]::ReadAllText($lintRecordOut), $summaryRegex)
if ($m.Success) {
    Line ("N-3: lint exit={0} components={1} proposals={2} percent={3} fields={4} text={5} labels={6} automatic={7}" -f `
        $code, $m.Groups[1].Value, $m.Groups[2].Value, $m.Groups[3].Value, $m.Groups[4].Value, $m.Groups[5].Value,
        $m.Groups[6].Value, $m.Groups[7].Value)
} else {
    Line "N-3: lint exit=$code, no summary line. See $lintRecordOut"
}
if (Test-Path $lintFile) {
    $rec = @((Get-Content -Raw -Encoding UTF8 -Path $lintFile | ConvertFrom-Json).lint)
    $windows = @($rec | ForEach-Object { $_.window } | Sort-Object -Unique)
    Line "N-3: windows: $($windows -join ', ')"
}
if (Test-Path $patch) {
    $setName = @(Select-String -Path $patch -Pattern '^\w+\.setName\(').Count
    $noField = @(Select-String -Path $patch -SimpleMatch 'has no field').Count
    $superclass = @(Select-String -Path $patch -SimpleMatch 'is declared in').Count
    Line "N-4: patch setNameLines=$setName noFieldComments=$noField superclassComments=$superclass"
} else {
    Line 'N-4: no patch file'
}

# N-5 (item 8): run on the scenario folder. The folder holds walk.lint.json: it is not a scenario.
$runOut = Join-Path $out 'wp09-run'
$runLog = Join-Path $out 'wp09-run.txt'
if (Test-Path $runOut) { Remove-Item -Recurse -Force $runOut }
Push-Location $P2
try {
    $ErrorActionPreference = 'Continue'
    & java -jar $cli run --agent $agent --scenarios $names --out $runOut --app (App) *>&1 | Tee-Object -FilePath $runLog | Out-Null
    $code = $LASTEXITCODE
} finally {
    $ErrorActionPreference = 'Stop'
    Pop-Location
}
$count = [regex]::Match([System.IO.File]::ReadAllText($runLog), '(\d+) scenario\(s\), (\d+) failed')
$runResult = Join-Path $runOut 'walk\run-result.json'
$status = 'none'
$run = @()
if (Test-Path $runResult) {
    $rr = Get-Content -Raw -Encoding UTF8 -Path $runResult | ConvertFrom-Json
    $status = $rr.status
    $run = @($rr.lint)
}
Line ("N-5: run exit={0} line='{1}' walkStatus={2}" -f $code, $count.Value, $status)

# N-6: the lint of the run has the same proposals as the lint of the record session.
if ((Test-Path $lintFile) -and $run.Count -gt 0) {
    $a = @($rec | ForEach-Object { "$($_.type) $($_.proposedName)" } | Sort-Object -Unique)
    $b = @($run | ForEach-Object { "$($_.type) $($_.proposedName)" } | Sort-Object -Unique)
    $d = @(Compare-Object -ReferenceObject $a -DifferenceObject $b)
    Line "N-6: recordEntries=$($a.Count) runEntries=$($b.Count) differences=$($d.Count)"
    foreach ($x in $d) { Line "    $($x.SideIndicator) $($x.InputObject)" }
} else {
    Line 'N-6: no comparison (lint file or run lint missing)'
}

Line "Files: $recordOut, $lintRecordOut, $patch, $runLog, $runOut"
Get-Content $summary
