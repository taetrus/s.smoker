# WP-01 handoff

Date: 2026-11-14. Branch: `wp-01-test-base`. Read `CLAUDE.md`, `docs/development-plan.md` and `docs/wp-01-pilot-report.md` first.

## 1. State

| WP-01 task | State |
|---|---|
| 1–4, 6 Pilot application on Windows | Done on 2026-11-14. See `docs/wp-01-pilot-report.md`. |
| 5 Replay on Windows | Done. F-23 corrected. PASS 3 times in sequence (run 13 to run 15). See section 2. |
| 5 Replay on Linux with JRE 17 | Not necessary (decision 5, 2026-11-15). |
| 7 Unit tests | Done. 22 tests in `smoker-core`, 28 tests in `smoker-agent`. |
| 8 Maven integration test | Done. `SelfTestIT` passes on macOS and Windows. |
| 9 `Locale.ROOT` | Done. |
| 10 Turkish-locale run | Done. Profile `locale-tr`. |

Verified on Windows 11 on 2026-11-14 (Maven 3.9.6, JDK 25 in a path with spaces, JRE 8u341):

| Command | Unit tests | IT |
|---|---|---|
| `mvn -B verify` | 50/50 | 2/2 |
| `mvn -B verify -Plocale-tr` | 50/50 | 2/2 |
| `mvn -B verify -Plocale-tr "-Djvm=<JDK 8>\bin\java.exe"` | 50/50 | 2/2 |

Completion criterion of WP-01 (changed by decisions 5 and 6): the five-step scenario passes on Windows with JRE 8. The test report is in the GitHub repository.
**Met on 2026-11-15.** WP-01 is complete.

## 2. Done on Windows: correction of F-23

F-23: `Locators.find` accepted a component whose superclass has the locator class. `JPasswordField` extends `JTextField`,
so the locator `[Settings] JTextField` found the API key field, not the Model field.
Now `find` and `resolve` use the same rule, `Locators.classNameOf`: the simple class name, or the superclass name only for an anonymous class.
Two window tests in `LocatorsWindowTest`. The F-23 test fails with the old `find`.

Replay after the correction: run 12 FAIL at s002 with the old golden (expected). The actual state had `MODEL` in the Model field and `''` in the API key field.
After `approve`: run 13, 14 and 15 PASS (5.1 s, 5.0 s, 5.4 s). `docs/wp-01-pilot/pilot.json` is this scenario. It contains no `***` and no API key.

## 3. Linux replay: not necessary

Decision 5 (2026-11-15) removes the Linux replay. The target applications load Windows DLLs and cannot start on Linux.
The procedure for Linux was in this section up to commit `4da86ff`. Use `git show 4da86ff:docs/wp-01-handoff.md` if a team needs it.

## 4. Reference: the Windows procedure that operated

### 4.1 Tests

Use a logged-in desktop session. In PowerShell, put each `-D` argument in quotes. Commands: section 1.
If a run fails, keep `smoker-it\target\failsafe-reports\` and `smoker-it\target\it-work\`.

### 4.2 Record and replay on the main product

- Product folder: `p.osgi.ds\distribution\target\products\com.kk.pde.ds.product\win32\win32\x86_64`. Start the CLI there.
- There is no `.exe` and no `eclipse.ini`. Give the agent in the `java` command.
- Windows PowerShell 5.1: write each inner quote of `--app` as `\"`, and enclose the value in single quotes.
  Without the backslashes, the CLI receives only `C:\Program`. Run the command in PowerShell, not in Git Bash (`!` in Claude Code is Git Bash).

```powershell
java -jar C:\tools\smoker-cli.jar run --agent C:\tools\smoker-agent.jar --scenarios C:\smoke --out C:\smoke\out\<run> --log logs\smoker.log `
  --app '\"C:\Program Files\Java\jdk1.8.0_341\bin\java.exe\" {agent} -jar plugins\org.eclipse.osgi_3.23.0.v20250228-0640.jar -configuration configuration -consoleLog'
java -jar C:\tools\smoker-cli.jar approve --run C:\smoke\out\<run>\pilot --scenario C:\smoke\pilot.json
```

- For `record`, use `record` and `--scenario C:\smoke\pilot.json` in place of `run`, `--scenarios` and `--out`.
  Save in the toolbar does not stop the application. The main product does not stop when its window closes. Stop the `java` process after Save.
- Do not click Send, Save (settings), Test Connection, Refresh Models or Set Default: they use the network or change the settings file.

### 4.3 Logback file appender

Add this appender to `configuration/logback.xml` of each product folder, and add `<appender-ref ref="FILE" />` to `<root>`:

```xml
<appender name="FILE" class="ch.qos.logback.core.FileAppender">
    <file>logs/smoker.log</file>
    <append>false</append>
    <encoder>
        <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
    </encoder>
</appender>
```

`mvnw clean` removes this change. The real products need the same change (WP-04, R-15).

### 4.4 HANG and crash on spike.master

Scenarios in `docs/wp-01-pilot/` (`hang.json` with `stepTimeoutMs` 3000, `crash.json`). Results: report section 4.
On JRE 8, 14 bundles of spike.master do not resolve (4 platform bundles need JavaSE-17). The Freeze and Crash buttons operate.

## 5. New goldens for the pilot: done on 2026-11-16

Done on Windows 11 with `master` at `5372284` (WP-02 and WP-09 merged), JRE 8u341. Branch `wp-01-pilot-goldens`.

Tests first. `SnapshotTakerContentTest.toolTipsGiveNoNode` failed in each of the three builds: a fake frame of
`MainWindowTest` stayed "showing" and went into later snapshots. The run order on Windows shows this, the order on macOS does not.
Corrected in the test (commit 6fd6fa7). Then all three builds pass: 61 + 120 + 21 unit tests, 4 IT.

| Scenario | Result |
|---|---|
| `pilot.json` | `NO_GOLDEN`, `approve`, then PASS 3 times (5.1 s to 5.4 s). `pilot.golden.json`: 148 lines. s002 sets `MODEL` in `@SettingsDialog.modelField`. The API key field stays empty. |
| `hang.json` | HANG 2 times. `hang.golden.json` has no steps (expected). |
| `crash.json` | ERROR (exit code 7) 2 times. No golden file: the JVM stops before the agent writes `run-result.json` (F-22), and `approve` stops with a `NoSuchFileException` stack trace. |
| `pilot-autonames.json` (WP-09 check) | `NO_GOLDEN`, `approve`, then PASS 3 times. The report marks the 3 automatic names. The golden is the same as `pilot.golden.json`. |

- The ignore rules of `pilot.json` did not match after WP-09: the components have automatic names now. The rules are now
  `^\[OSGi Chatbot\]/#@ChatFrame\.modelCombo$` and `^\[OSGi Chatbot\]/#@ChatPanel\.textPane$`. Each matches one node.
- `pilot-autonames.json` is `pilot.json` with automatic names in 3 locators: s002 `@SettingsDialog.modelField`,
  s004 `@InputPanel.textArea`, s005 `@InputPanel.clearButton`. s001 and s003 keep text locators: the Settings and Cancel
  buttons are local variables, so they have no automatic name.
- [x] WP-09 Windows check: a scenario with automatic names. `NO_GOLDEN`, approve, PASS.

Open:

- `approve` on a run without `run-result.json` gives a stack trace and exit code 1. `lint` gives a message and exit code 2 in the same case.

For the WP-06 recording session (a person must record): make a test for focus changes. The recorder takes the pending snapshot of a step at `FOCUS_GAINED`.
   - [ ] Record a step whose action opens a dialog. Make sure that the golden of the step shows the dialog.
   - [ ] Record a step after which the application moves the focus by code. Make sure that the golden of the step is not taken too early.

## 6. Open points

- The intermittent failure of `SelfTestIT` (2026-11-15, macOS, JDK 27, `-Plocale-tr`, under load: 2 of 3 methods failed) has a known root cause.
  The startup check read `Window.isShowing()` off the EDT. It could see a frame inside its constructor, where `visible` is still the default `true`.
  Commit 7c7458c fixes it: the check scans the windows on the EDT with a bounded wait and requires `isDisplayable()`.
  Evidence: 2 of 100 demo starts failed before the fix, 0 of 100 after it.
  If a failure recurs on Windows, keep `smoker-it/target/failsafe-reports` and `smoker-it/target/it-work`.

| Point | Effect | Work package |
|---|---|---|
| The `build` job in `.gitlab-ci.yml` uses `-DskipTests`. | CI does not run the unit tests. | WP-07 |
| Without a display, `SelfTestIT` fails and the window tests are skipped. | A developer on Linux must use `xvfb-run -a mvn -B verify`. The CI uses Windows runners (decision 5). | WP-07 |
| Equinox framework errors are not read (F-22). | A product with unresolved bundles gives startup PASS. | WP-04 |
