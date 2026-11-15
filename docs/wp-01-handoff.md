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

## 5. Open points

| Point | Effect | Work package |
|---|---|---|
| The player stops at the first step that does not pass. | Each `approve` corrects one step (F-21). Step `s002` of `empty-name.json` never runs. | WP-02, WP-04 |
| The `build` job in `.gitlab-ci.yml` uses `-DskipTests`. | CI does not run the unit tests. | WP-07 |
| Without a display, `SelfTestIT` fails and the window tests are skipped. | A developer on Linux must use `xvfb-run -a mvn -B verify`. The CI uses Windows runners (decision 5). | WP-07 |
| Equinox framework errors are not read (F-22). | A product with unresolved bundles gives startup PASS. | WP-04 |
