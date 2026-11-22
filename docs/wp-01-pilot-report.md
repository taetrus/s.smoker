# WP-01 test report: pilot application p.osgi.ds on Windows

Date: 2026-11-14. Branch: `wp-01-test-base`. Tester: one developer with Claude Code.
This report is the WP-01 test report. It stays in the GitHub repository (decision 6) and is part of the WP-01 completion criterion.

## 1. Result

| WP-01 task | Result |
|---|---|
| 1 Select the application | p.osgi.ds (decision 1). Main product `com.kk.pde.ds.product`, HANG product `com.kk.pde.ds.spike.master.product`. |
| 2 Add the agent option | No `eclipse.ini` and no launcher (`includeLaunchers="false"`). The agent option is in the `java` command. The agent attaches. |
| 3 Record on Windows with JRE 8 | Done. JRE 1.8.0_341, Windows 11. |
| 4 Record five steps | Done after the correction of F-20. The first recording had 3 of 5 steps. |
| 5 Replay on Windows | Windows: PASS 3 times in sequence after the correction of F-23 (run 13 to run 15). The earlier PASS (run 7 to run 11) was not valid: step s002 typed into the wrong field. **Linux: open.** |
| 6 Write down failures, lint, startup | Sections 3 to 6 of this report. |
| 7–10 Tests, IT, `Locale.ROOT`, tr_TR | Done. On Windows: 22 + 28 unit tests and 2 IT pass in 3 configurations (section 2). |

Completion criterion (changed by decision 5 on 2026-11-15): the five-step scenario passes on Windows with JRE 8. The test report is in the GitHub repository.
Decision 6 (2026-11-15) replaces Confluence with the GitHub repository. **The criterion passes. WP-01 is complete.**

## 2. Environment and s.smoker tests

- Windows 11 Pro 10.0.26300, PowerShell 5.1, Maven 3.9.6.
- JDK 25.0.1 in `E:\Program Files\...` (path with spaces). JRE 8u341 in `C:\Program Files\Java\jdk1.8.0_341`. No JDK 17 on this PC.
- p.osgi.ds: `mvnw.cmd clean verify` with JDK 25 (Maven 3.9.16 from the wrapper): `BUILD SUCCESS` in 65 s.

| Command | Unit tests | IT | Result |
|---|---|---|---|
| `mvn -B verify` | 22 + 28 | 2 | BUILD SUCCESS |
| `mvn -B verify -Plocale-tr` | 22 + 28 | 2 | BUILD SUCCESS |
| `mvn -B verify -Plocale-tr "-Djvm=...\jdk1.8.0_341\bin\java.exe"` | 22 + 28 | 2 | BUILD SUCCESS |

## 3. Pilot scenario

File: `C:\smoke\pilot.json`. Five steps, no LLM request:

| Step | Action | Time (run 10) |
|---|---|---|
| startup | — | 1175 ms |
| s001 | CLICK `[OSGi Chatbot] JButton('Settings')` (opens a modal dialog) | 643 ms |
| s002 | SET_TEXT `[Settings] JTextField` (Model). Before the correction of F-23, the replay typed into the API key field. | 513 ms |
| s003 | CLICK `[Settings] JButton('Cancel')` (closes the dialog) | 571 ms |
| s004 | SET_TEXT `[OSGi Chatbot] JTextArea` (chat input) | 494 ms |
| s005 | CLICK `[OSGi Chatbot] JButton('Clear')`. Clear removes the conversation history, not the input text. The chat text has an ignore rule, so the snapshot does not examine the result of this step. | 561 ms |

A full run takes 4 s to 5 s (JVM start, five steps, exit). Runs 13 to 15: 5.1 s, 5.0 s, 5.4 s.

Ignore rules in the scenario. The application connects to `openrouter.ai` at startup in a background thread (F-02):

- the model combo box `JComboBox[0]` (1 item or 407 items),
- the chat text `ChatPanel[0]/JViewport[0]/JTextPane[0]`.

Sequence until the first PASS: run 1 to run 6 FAIL, 6 `approve` operations, then the ignore rules. Causes: F-21 (two goldens
with the state of the next step), F-02 (network answer after the snapshot), and one text in the chat input before Record.

## 4. HANG and crash test on spike.master

| Scenario | Action | Result |
|---|---|---|
| `C:\smoke\hang\hang.json`, `stepTimeoutMs` 3000 | CLICK `Freeze 5s` | **HANG** after 3014 ms. The thread dump shows `AWT-EventQueue-0` in `Thread.sleep` at `MasterApp.java:261`. Expected result. |
| `C:\smoke\crash\crash.json` | CLICK `Crash` (`System.exit(7)`) | **ERROR**: "no run-result.json produced; app exit code 7". The report does not show the step or the ERROR line (F-22). |

F-22 note (WP-04): on JRE 8, spike.master now gives startup FAIL (28 Equinox entries, 4 platform bundles need JavaSE-17). Run `hang.json` and `crash.json` on JRE 17.
`pilot.json` on p2.product stays PASS. With `--log logs\smoker.log` and the console, each ERROR gives two hits. Do not pass `--log` for a product that logs to the console.

## 5. Failures and findings

| ID | Sev. | Where | Finding | State |
|---|---|---|---|---|
| — | — | `SelfTestIT` | On Windows, `ProcessBuilder` does not escape inner quotes. The CLI received only `E:\Program` as `--app`. | Corrected (`SelfTestIT.windowsArg`). README tells users how to quote `--app`. |
| — | — | Handoff | The `record` command of the handoff did not operate in PowerShell 5.1 (inner quotes without `\`). | Corrected in the handoff. |
| F-19 | A | Agent | The snapshot and the recorder read the text of a `JPasswordField`. The settings dialog shows the saved API key in one. | Corrected. The snapshot gives `***`. Typing in a password field is not recorded. In the replay the API key field contained a value (F-23), and the golden shows `***`. No API key is saved on the test PC. |
| F-20 | A | Recorder | A click that opens or closes a modal dialog was lost (no `MOUSE_CLICKED`). | Corrected. Buttons are recorded on `MOUSE_RELEASED`. |
| F-23 | A | Player | The locator `[Settings] JTextField` index 0 found the `JPasswordField` (subclass of `JTextField`) in front of the Model field. s002 typed "MODEL" into the API key field. `approve` accepted this state, so the run gave PASS. | Corrected. `find` and `resolve` use the same class rule (`Locators.classNameOf`). 2 window tests. After the correction: FAIL at s002 with the old golden, `approve`, then PASS 3 times. |
| F-21 | B | Recorder | The golden snapshot of a step can show the state after the next step. Each `approve` corrects only one step. | Open (WP-02, WP-06). |
| F-02 | A | Player | Confirmed without a simulator: the answer from `openrouter.ai` arrives after the snapshot. The result changes from run to run. | Open (WP-03). Workaround: ignore rules. |
| F-22 | B | Agent | Equinox framework errors are not read. 14 unresolved bundles gave startup PASS. After `System.exit` the report shows only the exit code. | Closed (WP-04, PR #11 and PR #13). Windows check passed: `docs/wp-04-windows-report.md`. |
| F-10 | B | Agent | The agent called `System.exit` 12 times, and the PC lost power one time. The `configuration` folder was not damaged, and each next start operated. Equinox adds one `configuration\<n>.log` file in each run. | Not seen as a problem. |

Findings for p.osgi.ds (not for s.smoker):

- spike.master contains 4 platform bundles that need JavaSE-17 (`org.eclipse.core.jobs`, `org.eclipse.equinox.common`,
  `org.eclipse.equinox.concurrent`, `org.eclipse.equinox.registry`). On JRE 8, 14 bundles do not resolve, and the ECF export does not operate.
  The main product has no such bundle.
- The products log only to the console. A Logback file appender was added in `distribution\target\products\...\configuration\logback.xml`.
  This change is in the build output: `mvnw clean` removes it. The real products need the same change (WP-04, R-15).
- The main product does not stop when the window closes (`DISPOSE_ON_CLOSE`, the OSGi framework continues).

## 6. Lint and startup time

- Lint: 14 interactive components without a name in the two windows of the scenario. 0 components have a name.
  All locators use window title, class and text. Input for WP-09.
- Startup time on JRE 8, without the agent, to the first window: main product 1.5 s, spike.master 1.0 s.
- Startup step with the agent (run 10): 1175 ms.

## 7. Evidence

In the repository: the three scenario files in `docs/wp-01-pilot/` (`pilot.json`, `hang.json`, `crash.json`).

On the test PC, not in the repository:

- `C:\smoke\evidence\`: both recordings as recorded, the recorder logs, the scenario before the ignore rules.
- `C:\smoke\out\run1` to `run11-after-reboot`, `hang1`, `crash1`: `report.html`, `junit.xml`, `run-result.json`, screenshots, `app-output.log`.

## 8. Open for WP-01

Nothing. Copy this report to Confluence when the space `s.smoker` exists (decision 6).

Decision 5 (2026-11-15) removes the Linux replay: the target applications load Windows DLLs and cannot start on Linux.
