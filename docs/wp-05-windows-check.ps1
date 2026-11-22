# WP-05 Windows check: the suite run (R-36, R-39) and the stop check (R-38) of docs/wp-05-windows-check.md.
# Run in Windows PowerShell 5.1, in the desktop session:
#   powershell -NoProfile -ExecutionPolicy Bypass -File docs\wp-05-windows-check.ps1 -Mode Probe
#   powershell -NoProfile -ExecutionPolicy Bypass -File docs\wp-05-windows-check.ps1 -Mode Full
# Mode Probe: 2 copies of pilot.json, suite run only. It tests the reset scenario.
# Mode Full: 20 copies, the suite run, then 20 runs with --scenarios.
# The script writes the console output of each run to C:\smoke\out\wp05-<run>.txt
# and a summary to C:\smoke\out\wp05-summary.txt.

param(
    [Parameter(Mandatory = $true)][ValidateSet('Probe', 'Full')][string]$Mode,
    [string]$Java8 = 'C:\Program Files\Java\jdk1.8.0_341\bin\java.exe',
    [string]$Tools = 'C:\tools',
    [string]$Smoke = 'C:\smoke',
    [string]$P2 = 'D:\dev\p.osgi.ds\distribution\target\products\com.kk.pde.ds.product\win32\win32\x86_64',
    [string]$Osgi = 'plugins\org.eclipse.osgi_3.23.0.v20250228-0640.jar'
)

$ErrorActionPreference = 'Stop'
$count = 2
if ($Mode -eq 'Full') { $count = 20 }
$out = Join-Path $Smoke 'out'
$cli = Join-Path $Tools 'smoker-cli.jar'
$agent = Join-Path $Tools 'smoker-agent.jar'
$pilot = Join-Path $Smoke 'pilot.json'
$pilotGolden = Join-Path $Smoke 'pilot.golden.json'
$suiteDir = Join-Path $Smoke 'suite'
$reset = Join-Path $suiteDir 'reset.json'
$scenarios = Join-Path $suiteDir 'scenarios'
$suiteFile = Join-Path $suiteDir 'suite.json'
$summaryName = 'wp05-summary.txt'
if ($Mode -eq 'Probe') { $summaryName = 'wp05-probe-summary.txt' }
$summary = Join-Path $out $summaryName

# Preconditions.
foreach ($f in @($Java8, $cli, $agent, $P2, $pilot, $pilotGolden)) {
    if (-not (Test-Path $f)) { throw "Not found: $f" }
}
if (-not (Test-Path $reset)) {
    throw "Not found: $reset. Record the reset scenario first (step 4 of docs/wp-05-windows-check.md)."
}
New-Item -ItemType Directory -Force -Path $out | Out-Null
Set-Content -Path $summary -Value "WP-05 Windows check, mode $Mode, $count copies, $(Get-Date -Format s)"

# Copies of pilot.json and pilot.golden.json: pilot01 ... pilotNN.
# A copy gets its own name in the "name" field and in the "scenario" field of the golden.
if (Test-Path $scenarios) { Remove-Item -Recurse -Force $scenarios }
New-Item -ItemType Directory -Force -Path $scenarios | Out-Null
$utf8 = New-Object System.Text.UTF8Encoding($false)
$scenarioText = [System.IO.File]::ReadAllText($pilot, $utf8)
$goldenText = [System.IO.File]::ReadAllText($pilotGolden, $utf8)
$nameRegex = New-Object System.Text.RegularExpressions.Regex('"name"\s*:\s*"pilot"')
$scenarioRegex = New-Object System.Text.RegularExpressions.Regex('"scenario"\s*:\s*"pilot"')
$names = @()
for ($i = 1; $i -le $count; $i++) {
    $n = 'pilot{0:00}' -f $i
    $names += $n
    $s = $nameRegex.Replace($scenarioText, ('"name": "' + $n + '"'), 1)
    $g = $scenarioRegex.Replace($goldenText, ('"scenario": "' + $n + '"'), 1)
    [System.IO.File]::WriteAllText((Join-Path $scenarios "$n.json"), $s, $utf8)
    [System.IO.File]::WriteAllText((Join-Path $scenarios "$n.golden.json"), $g, $utf8)
}

# The CLI receives --app as one argument. Each inner quote is \" (Windows PowerShell 5.1, docs/wp-01-handoff.md 4.2).
function App() {
    return '\"' + $Java8 + '\" {agent} -jar ' + $Osgi + ' -configuration configuration -consoleLog'
}

# Suite run. The out path has ; and a space (R-39). The path is in quotes.
$suiteName = 'wp05 suite;1'
if ($Mode -eq 'Probe') { $suiteName = 'wp05 probe;1' }
$suiteOut = Join-Path $out $suiteName
if (Test-Path $suiteOut) { Remove-Item -Recurse -Force $suiteOut }
# The suite file has plain quotes in "app": the file is not an argument of a command line.
$suite = [ordered]@{
    app           = '"' + $Java8 + '" {agent} -jar ' + $Osgi + ' -configuration configuration -consoleLog'
    workDir       = $P2
    resetScenario = $reset
    scenarios     = @($scenarios)
}
[System.IO.File]::WriteAllText($suiteFile, ($suite | ConvertTo-Json -Depth 5), $utf8)

$suiteLog = Join-Path $out "wp05-suite-$($Mode.ToLowerInvariant()).txt"
$ErrorActionPreference = 'Continue'
& java -jar $cli run --agent $agent --suite $suiteFile --out "$suiteOut" *>&1 | Tee-Object -FilePath $suiteLog
$code = $LASTEXITCODE
$ErrorActionPreference = 'Stop'
Add-Content -Path $summary -Value "suite exit=$code out=$suiteOut"
$resultFile = Join-Path $suiteOut 'suite-result.json'
if (Test-Path $resultFile) {
    $r = Get-Content -Raw -Path $resultFile | ConvertFrom-Json
    Add-Content -Path $summary -Value "suite applicationStarts=$($r.applicationStarts)"
    foreach ($e in $r.scenarios) {
        Add-Content -Path $summary -Value ("suite {0} {1} attempts={2} note={3}" -f $e.name, $e.status, $e.attempts, $e.note)
    }
    foreach ($e in $r.commandErrors) { Add-Content -Path $summary -Value "suite commandError: $e" }
} else {
    Add-Content -Path $summary -Value 'suite: no suite-result.json'
}

if ($Mode -eq 'Probe') {
    Get-Content $summary
    return
}

# 20 runs with --scenarios. Each scenario has its own JVM and its own stop (R-38).
$conf = Join-Path $P2 'configuration'
$before = Join-Path $out 'wp05-configuration-before.txt'
$after = Join-Path $out 'wp05-configuration-after.txt'
Get-ChildItem -Path $conf -Recurse -File -ErrorAction SilentlyContinue |
    ForEach-Object { $_.FullName.Substring($conf.Length) + ' ' + $_.Length } | Sort-Object | Set-Content -Path $before

$scenariosOut = Join-Path $out 'wp05-scenarios'
if (Test-Path $scenariosOut) { Remove-Item -Recurse -Force $scenariosOut }
$scenariosLog = Join-Path $out 'wp05-scenarios.txt'
Push-Location $P2
try {
    $ErrorActionPreference = 'Continue'
    & java -jar $cli run --agent $agent --scenarios $scenarios --out $scenariosOut --app (App) *>&1 |
        Tee-Object -FilePath $scenariosLog
    $code = $LASTEXITCODE
} finally {
    $ErrorActionPreference = 'Stop'
    Pop-Location
}
Add-Content -Path $summary -Value "scenarios exit=$code out=$scenariosOut"

Get-ChildItem -Path $conf -Recurse -File -ErrorAction SilentlyContinue |
    ForEach-Object { $_.FullName.Substring($conf.Length) + ' ' + $_.Length } | Sort-Object | Set-Content -Path $after
$diff = Compare-Object -ReferenceObject @(Get-Content $before) -DifferenceObject @(Get-Content $after)
if ($diff) {
    Add-Content -Path $summary -Value 'configuration: the file list is different before and after:'
    foreach ($d in $diff) { Add-Content -Path $summary -Value "  $($d.SideIndicator) $($d.InputObject)" }
} else {
    Add-Content -Path $summary -Value 'configuration: the file list is the same before and after'
}

# Stop lines of each run, and the checks of R-38.
$stopsFile = Join-Path $out 'wp05-stops.txt'
Set-Content -Path $stopsFile -Value "Stop lines of each run, $(Get-Date -Format s)"
$closing = 0; $sysExit = 0; $halt = 0; $startupFail = 0; $missing = 0
foreach ($n in $names) {
    $dir = Join-Path $scenariosOut $n
    $appLog = Join-Path $dir 'app-output.log'
    if (-not (Test-Path $appLog)) {
        $missing++
        Add-Content -Path $stopsFile -Value "$n : no app-output.log"
        continue
    }
    $lines = @(Select-String -Path $appLog -SimpleMatch '[smoker] stop:' | ForEach-Object { $_.Line.Trim() })
    Add-Content -Path $stopsFile -Value "$n :"
    foreach ($l in $lines) { Add-Content -Path $stopsFile -Value "    $l" }
    if (@($lines | Where-Object { $_.Contains('[smoker] stop: closing the windows') }).Count -gt 0) { $closing++ }
    if (@($lines | Where-Object { $_.Contains('[smoker] stop: System.exit(') }).Count -gt 0) { $sysExit++ }
    if (@($lines | Where-Object { $_.Contains('[smoker] stop: halt(') }).Count -gt 0) { $halt++ }
    $rr = Join-Path $dir 'run-result.json'
    if (Test-Path $rr) {
        $res = Get-Content -Raw -Path $rr | ConvertFrom-Json
        $st = @($res.steps | Where-Object { $_.stepId -eq 'startup' })
        if ($st.Count -eq 0 -or $st[0].status -ne 'PASS') { $startupFail++ }
    } else {
        $startupFail++
    }
}
Add-Content -Path $summary -Value "R-38: runs=$($names.Count) missingAppOutput=$missing closingTheWindows=$closing systemExit=$sysExit halt=$halt startupNotPass=$startupFail"
Add-Content -Path $summary -Value "Stop lines: $stopsFile. File lists: $before and $after."

Get-Content $summary
