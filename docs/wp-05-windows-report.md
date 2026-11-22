# WP-05 Windows test

Date: 2026-11-22

Procedure: `docs/wp-05-windows-check.md`. Script: `docs/wp-05-windows-check.ps1`.

All four checks passed. The first reset scenario was not complete. The second recording corrected it (section "Reset scenario").
The stop of `p2.product` did not end at stage 1. Stage 2 stopped the application in each run (section "R-38: stop stages").

## Environment

- OS: Windows 11 Pro 10.0.26300.
- Shell: PowerShell 5.1.26100.10068.
- Build: Maven 3.9.6, JDK 25.0.1 (Eclipse Adoptium). `mvn -B package -DskipTests`: BUILD SUCCESS.
- JRE 8: `C:\Program Files\Java\jdk1.8.0_341\bin\java.exe`, 1.8.0_341.
- Commit: `8799100` (branch `wp-05-suite-mode`, PR #15).
- Application: p2.product of `p.osgi.ds`
  (`D:\dev\p.osgi.ds\distribution\target\products\com.kk.pde.ds.product\win32\win32\x86_64`).
  Scenario `C:\smoke\pilot.json` with its golden `C:\smoke\pilot.golden.json`. No product with the hardware simulator was available.

## Results

| Requirement | Check | Expected result | Actual result | Result |
|---|---|---|---|---|
| R-36 | Suite with 20 copies of `pilot.json` (`pilot01` to `pilot20`) and `reset.json` | Exit code 0. `applicationStarts=1`. 20 times PASS with `attempts=1` and an empty note. Console line `application starts: 1`. | As expected. Exit code 0. `applicationStarts=1`. 20 times PASS, `attempts=1`, empty note. Console: `application starts: 1` and `20 scenario(s), 0 failed`. | Pass |
| R-39 | `--out` path `C:\smoke\out\wp05 suite;1` (`;` and a space) | The run works. The folder has `suite-result.json`, `junit.xml` and `report.html`. | As expected. The folder also has `agent-1.cfg`, `app-output-1.log` and `cli-alive`. `agent-1.cfg` is in the path with `;` and a space, and the agent read it. | Pass |
| R-38 | `run --scenarios` on the 20 copies, one JVM and one stop each | `runs=20`, `missingAppOutput=0`, `closingTheWindows=20`, `systemExit=0`, `halt=0`, `startupNotPass=0`. | `runs=20 missingAppOutput=0 closingTheWindows=20 systemExit=0 halt=0 startupNotPass=0`. Exit code 0, `20 scenario(s), 0 failed`. | Pass |
| R-37 | Windows CI runs `SelfTestIT.simulatorRunsBeforeResetAndAfter` | Job `windows` passes. | The jobs `windows (default)`, `windows (locale-tr)` and `windows (locale-tr-jre8)` pass. Each job log has `simulatorRunsBeforeResetAndAfter` and `Tests run: 16, Failures: 0, Errors: 0` in `SelfTestIT`. | Pass |

Files:

- Suite run: `C:\smoke\out\wp05 suite;1`. Console output: `C:\smoke\out\wp05-suite-full.txt`.
- Scenario runs: `C:\smoke\out\wp05-scenarios`. Console output: `C:\smoke\out\wp05-scenarios.txt`.
- Summary: `C:\smoke\out\wp05-summary.txt`. Stop lines: `C:\smoke\out\wp05-stops.txt`.

## Reset scenario (step 4)

The owner recorded the reset scenario two times. The test with 2 copies of `pilot.json` ran after each recording.
`--out` folder: `C:\smoke\out\wp05 probe;1`. The second test wrote over the folder of the first test.

| Recording | Steps of `reset.json` | `applicationStarts` | `pilot01` | `pilot02` |
|---|---|---|---|---|
| 1 (`C:\smoke\suite\reset-v1.json`) | CLICK `@InputPanel.clearButton` | 2 | PASS, attempts 1, no note | PASS, attempts 2, note `failed in a shared JVM, passed in a new JVM` |
| 2 (`C:\smoke\suite\reset.json`) | CLICK `@InputPanel.clearButton`, SET_TEXT `@InputPanel.textArea` = `"heloooooo"`, KEY Ctrl+A, SET_TEXT `@InputPanel.textArea` = `""`, CLICK `@InputPanel.clearButton` | 1 | PASS, attempts 1, no note | PASS, attempts 1, no note |

Cause of the failure with recording 1: step s004 of `pilot.json` writes `HELLO` in the input area `@InputPanel.textArea`.
Step s005 clicks `Clear`. `Clear` empties the conversation pane, but not the input area.
Thus `pilot02` started in the shared JVM with `HELLO` in the input area. The shared attempt (`pilot02.shared`) gave:

```
reset PASS
s001 FAIL  CHANGED [OSGi Chatbot]/#@InputPanel.textArea .text: expected="" actual="HELLO"
s002 FAIL
s003 FAIL
s004 PASS
s005 PASS
```

The suite then ran `pilot02` in a new JVM, and it passed. This is the retry rule of the suite. The tool operated correctly.
Recording 2 empties the input area. The full run used recording 2.

## R-38: stop stages

Each of the 20 runs has the same two stop lines in `app-output.log`:

```
[smoker] stop: closing the windows
[smoker] stop: stopping the OSGi framework
```

The stop did not end at stage 1 in any run. The suite run (`app-output-1.log`) has the same two lines.
After stage 1, the JVM of `p2.product` stays alive: the application does not stop the Equinox framework when its windows close.
Stage 2 starts after `stopTimeoutMs` (default 10000 ms). In `pilot01`, the scenario ended at 22:31:43 and stage 2 started at 22:31:53.
Then the bundles stop in the thread `Framework stop - Equinox Container` (`App.stop()`, `MCP tool unregistered: ...`), and the JVM ends.
No run has `stop: System.exit(` or `stop: halt(`.

Each stop of `p2.product` thus takes approximately 10 s more than necessary. This is a property of the application, not a defect of the tool.
A smaller `stopTimeoutMs` in the agent configuration file can make the stop shorter.

## Files in `configuration\`

`C:\smoke\out\wp05-configuration-before.txt` and `wp05-configuration-after.txt` are equal.
The 20 runs added no file to `configuration\`, removed no file and changed no file length.

## CI run (R-37)

https://github.com/taetrus/s.smoker/actions/runs/45058894259 (PR #15, commit `8799100`).

## Not tested

- A product with the hardware simulator. R-37 ran with the demo simulator (`DemoSimulator`) only.
- JRE 11 at run time on a target application.
