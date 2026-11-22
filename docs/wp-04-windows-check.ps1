# WP-04 Windows check: the 5 pilot runs of docs/wp-04-windows-check.md.
# Run in Windows PowerShell 5.1, in the desktop session:
#   powershell -NoProfile -ExecutionPolicy Bypass -File docs\wp-04-windows-check.ps1 -Java17 "C:\...\bin\java.exe"
# The script writes the console output of each run to C:\smoke\out\wp04-<run>.txt
# and a summary of the exit codes to C:\smoke\out\wp04-summary.txt.

param(
    [Parameter(Mandatory = $true)][string]$Java17,
    [string]$Java8 = 'C:\Program Files\Java\jdk1.8.0_341\bin\java.exe',
    [string]$Tools = 'C:\tools',
    [string]$Smoke = 'C:\smoke',
    [string]$Spike = 'D:\dev\p.osgi.ds\distribution\target\products\com.kk.pde.ds.spike.master.product\win32\win32\x86_64',
    [string]$P2 = 'D:\dev\p.osgi.ds\distribution\target\products\com.kk.pde.ds.product\win32\win32\x86_64',
    [string]$Osgi = 'plugins\org.eclipse.osgi_3.23.0.v20250228-0640.jar'
)

$ErrorActionPreference = 'Stop'
$out = Join-Path $Smoke 'out'
$cli = Join-Path $Tools 'smoker-cli.jar'
$agent = Join-Path $Tools 'smoker-agent.jar'
$summary = Join-Path $out 'wp04-summary.txt'

foreach ($f in @($Java17, $Java8, $cli, $agent, $Spike, $P2,
                 (Join-Path $Smoke 'crash\crash.json'), (Join-Path $Smoke 'hang\hang.json'), (Join-Path $Smoke 'pilot.json'))) {
    if (-not (Test-Path $f)) { throw "Not found: $f" }
}
New-Item -ItemType Directory -Force -Path $out | Out-Null
Set-Content -Path $summary -Value "WP-04 Windows check $(Get-Date -Format s)"

# The CLI receives --app as one argument. Each inner quote is \" (Windows PowerShell 5.1, docs/wp-01-handoff.md 4.2).
function App([string]$java) {
    return '\"' + $java + '\" {agent} -jar ' + $Osgi + ' -configuration configuration -consoleLog'
}

function Run-Smoke([string]$name, [string]$folder, [string]$scenario, [string]$java) {
    $log = Join-Path $out "wp04-$name.txt"
    Push-Location $folder
    try {
        $ErrorActionPreference = 'Continue'
        & java -jar $cli run --agent $agent --scenarios $scenario --out (Join-Path $out "wp04-$name") --app (App $java) *>&1 |
            Tee-Object -FilePath $log
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = 'Stop'
        Pop-Location
    }
    Add-Content -Path $summary -Value "$name exit=$code"
}

# Run 1: spike.master on JRE 8. Expected: startup FAIL, 28 stdout hits.
Run-Smoke 'jre8' $Spike (Join-Path $Smoke 'crash\crash.json') $Java8
# Run 2: spike.master on JRE 17, crash.json. Expected: s001 ERROR, exited during step s001; exit code 7.
Run-Smoke 'crash17' $Spike (Join-Path $Smoke 'crash\crash.json') $Java17
# Run 3: spike.master on JRE 17, hang.json. Expected: HANG with a thread dump.
Run-Smoke 'hang17' $Spike (Join-Path $Smoke 'hang\hang.json') $Java17
# Run 4: p2.product on JRE 8, pilot.json, no --log. Expected: PASS.
Run-Smoke 'pilot' $P2 (Join-Path $Smoke 'pilot.json') $Java8

# Run 5: approve on an empty folder. Expected: the message and exit code 2.
$empty = Join-Path $out 'wp04-empty'
if (Test-Path $empty) { Remove-Item -Recurse -Force $empty }
New-Item -ItemType Directory -Path $empty | Out-Null
$ErrorActionPreference = 'Continue'
& java -jar $cli approve --run $empty --scenario (Join-Path $Smoke 'pilot.json') *>&1 |
    Tee-Object -FilePath (Join-Path $out 'wp04-approve.txt')
$code = $LASTEXITCODE
$ErrorActionPreference = 'Stop'
Add-Content -Path $summary -Value "approve exit=$code"

Get-Content $summary
