# WP-01 handoff: continue on Windows

Date: 2026-11-14. Branch: `wp-01-test-base`. Read `CLAUDE.md` and `docs/development-plan.md` first.

## 1. State

| WP-01 task | State |
|---|---|
| 1–6 Pilot application on Windows and Linux | Open. Do these on the Windows PC. |
| 7 Unit tests | Done. 22 tests in `smoker-core`, 24 tests in `smoker-agent` (4 added for F-19). |
| 8 Maven integration test | Done. `smoker-it/.../SelfTestIT.java` replaces `selftest.sh`. |
| 9 `Locale.ROOT` | Done. `AgentConfig`, `JUnitXmlWriter`, `Recorder`. |
| 10 Turkish-locale run | Done. Profile `locale-tr`. |

Verified on macOS only, with `mvn -B clean verify`:

| Locale | Test JVM | Unit tests | IT |
|---|---|---|---|
| default (`en_TR`) | JDK 27 | 42/42 | 2/2 |
| `tr_TR` | JDK 27 | 42/42 | 2/2 |
| `tr_TR` | JRE 8 (`-Djvm`) | 42/42 | 2/2 |

Verified on Windows 11 on 2026-11-14, with `mvn -B verify` (Maven 3.9.6, JDK 25 in `E:\Program Files\...`):

| Locale | Test JVM | Unit tests | IT |
|---|---|---|---|
| default (`en_US`) | JDK 25 | 46/46 | 2/2 |
| `tr_TR` | JDK 25 | 46/46 | 2/2 |
| `tr_TR` | JRE 8u341 (`-Djvm`) | 46/46 | 2/2 |

The first Windows run failed. `SelfTestIT` gave the `--app` value with inner quotes to `ProcessBuilder`.
On Windows, `ProcessBuilder` does not escape inner quotes, and the CLI received only `E:\Program`.
`SelfTestIT.windowsArg` now escapes the quotes. The CLI did not change. `README.md` tells users how to quote `--app` on Windows.
The `java` path without `.exe` and the `;` separator of `--scenarios` caused no problem.

F-19 (new, done): the snapshot and the recorder read the text of a `JPasswordField`. The settings dialog of p.osgi.ds
shows the saved API key in one. The snapshot now gives `***`, and the recorder does not record typing in a password field.
The Windows results above are from the build with this fix.

The WP-01 completion criterion is not met. Do not start WP-02.

## 2. Prepare the Windows PC

1. Install Git for Windows. Keep `core.autocrlf=true`.
2. Install the GitHub CLI. Run `gh auth login`. The repository is private.
3. Install Temurin JDK 8 and JDK 17. Set `JAVA_HOME` to JDK 17.
4. Install Maven 3.9.x.
5. On the corporate network, copy the Nexus `settings.xml` to `%USERPROFILE%\.m2\`. Nexus must supply `maven-failsafe-plugin` 3.2.5.
6. Clone into a path without spaces, `;` or `=` (finding F-11):

```powershell
cd C:\dev
gh repo clone taetrus/s.smoker
cd s.smoker
git switch wp-01-test-base
```

## 3. Run the tests on Windows

Use a logged-in desktop session. Do not use a service or a minimized RDP window. In PowerShell, put each `-D` argument in quotes.

```powershell
mvn -B verify
mvn -B verify -Plocale-tr
mvn -B verify -Plocale-tr "-Djvm=<JDK 8 folder>\bin\java.exe"
```

Expected result of each run: unit tests 22 and 24, IT 2, `BUILD SUCCESS`.

If a run fails, keep these folders:

- `smoker-it\target\failsafe-reports\`
- `smoker-it\target\it-work\` (`cli-*.log`, `run-result.json`, `report.html`)

`SelfTestIT` did not run on Windows before. Examine these first:

- the quotes around the `java` path in `--app` (spaces in `C:\Program Files`),
- the `java` path without `.exe` (`SelfTestIT` uses `java.home\bin\java`),
- the `;` separator of `--scenarios` (`File.pathSeparator`).

## 4. Do WP-01 tasks 1–6 on the pilot application

Pilot application: p.osgi.ds (decision 1 in `docs/development-plan.md`, 2026-11-14).
Repository: `taetrus/p.osgi.ds`. Products: `com.kk.pde.ds.product` (five-step scenario) and `com.kk.pde.ds.spike.master.product` (HANG).

### 4.1 Build the pilot application

1. Clone into a path without spaces: `cd C:\dev` then `gh repo clone taetrus/p.osgi.ds`.
2. Build with the Maven Wrapper and JDK 17 or later: `cd p.osgi.ds` then `.\mvnw.cmd clean verify`. Tycho 4.0.13 does not start on Maven 3.10.
3. The product folders are below `distribution\target\products\`.
   Main product: `com.kk.pde.ds.product\win32\win32\x86_64`. Find the spike folder in the same way.
4. Write the startup time of each product (task 6).

### 4.2 Add a log file

The products log only to the console (stdout). The agent reads log files and `System.err`, not `System.out`.
Add a file appender to `configuration\logback.xml` in each product folder:

```xml
<appender name="FILE" class="ch.qos.logback.core.FileAppender">
    <file>logs/smoker.log</file>
    <append>false</append>
    <encoder>
        <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
    </encoder>
</appender>
```

Add `<appender-ref ref="FILE" />` to `<root>`. Write this change in the test report: the real products need it too (WP-04, R-15).

### 4.3 Record and replay

There is no `.exe` and no `eclipse.ini` (`includeLaunchers="false"`). `run.bat` starts `java -jar plugins\org.eclipse.osgi_*.jar`. Give the agent in the `java` command.
The CLI starts the application in its own working directory. Start the CLI in the product folder.

1. Copy `smoker-agent.jar` and `smoker-cli.jar` to `C:\tools\`.
2. `cd` to the main product folder.
3. Find the exact name of `plugins\org.eclipse.osgi_*.jar`. Use it in place of `<osgi>.jar` below.
4. Record:
   ```powershell
   java -jar C:\tools\smoker-cli.jar record --agent C:\tools\smoker-agent.jar --scenario C:\smoke\pilot.json --log logs\smoker.log `
     --app '"<JDK 8>\bin\java.exe" {agent} -jar plugins\<osgi>.jar -configuration configuration -consoleLog'
   ```
   The single quotes keep the inner double quotes. The CLI needs them when the JDK path contains spaces.
   Do not give `-console`: the OSGi console reads standard input.
5. Record five steps that need no LLM answer, for example: open the settings dialog, type a value, close it.
   The chatbot answers change in each run. Do not send a message to the LLM.
6. Replay with `run` in place of `record`, and `--scenarios C:\smoke --out C:\smoke\out` in place of `--scenario`.
   Then `approve`, and `run` again. The second run must give PASS.
7. Replay on a Linux runner with JRE 17 and `xvfb-run -a` (task 5). Use the `linux.gtk.x86_64` product.

### 4.4 HANG test on spike.master

1. `cd` to the `spike.master` product folder. Do the steps in 4.2 there too.
2. Record one step: click the button that freezes the EDT for 5 s. Set `stepTimeoutMs` to 3000 in the scenario file.
3. Replay. The expected result is HANG with a thread dump.
4. Optional: the crash button calls `System.exit(7)`. Write down what s.smoker reports.

### 4.5 What to write down (task 6)

- Each failure, with the CLI output and `report.html`.
- The lint result: the number of components without a name. `setName()` occurs 0 times in the Swing bundles of p.osgi.ds. This is the input for WP-09.
- The startup time of each product.
- Lock files or a damaged `configuration` folder after the agent calls `System.exit` (F-10).

Decision point: if the agent cannot attach, stop. Analyze the launcher before WP-02. Alternative: environment variable `JAVA_TOOL_OPTIONS`.

## 5. Open points from tasks 7–10

| Point | Effect | Work package |
|---|---|---|
| The player stops at the first step that does not pass. Step `s002` of `empty-name.json` ("throw on EDT") never runs. | No test examines the capture of an uncaught EDT exception. | WP-04 or a new scenario |
| The `build` job in `.gitlab-ci.yml` uses `-DskipTests`. | CI does not run the unit tests. | WP-07 |
| Without a display, `SelfTestIT` fails and `LocatorsWindowTest` is skipped. | Linux CI must use `xvfb-run -a mvn -B verify`. | WP-07 |
| `toUpperCase()` in `AgentConfig` could not fail with the values `play` and `record`. | F-16 was latent there. The real defect was `String.format("%.3f")` in `JUnitXmlWriter`. | Done |

## 6. Report back

Push the results to a new branch, or give the logs to Claude Code. Put the test report in Confluence (completion criterion).
