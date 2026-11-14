# WP-01 handoff: continue on Windows

Date: 2026-11-14. Branch: `wp-01-test-base`. Read `CLAUDE.md` and `docs/development-plan.md` first.

## 1. State

| WP-01 task | State |
|---|---|
| 1–6 Pilot application on Windows and Linux | Open. Do these on the Windows PC. |
| 7 Unit tests | Done. 22 tests in `smoker-core`, 20 tests in `smoker-agent`. |
| 8 Maven integration test | Done. `smoker-it/.../SelfTestIT.java` replaces `selftest.sh`. |
| 9 `Locale.ROOT` | Done. `AgentConfig`, `JUnitXmlWriter`, `Recorder`. |
| 10 Turkish-locale run | Done. Profile `locale-tr`. |

Verified on macOS only, with `mvn -B clean verify`:

| Locale | Test JVM | Unit tests | IT |
|---|---|---|---|
| default (`en_TR`) | JDK 27 | 42/42 | 2/2 |
| `tr_TR` | JDK 27 | 42/42 | 2/2 |
| `tr_TR` | JRE 8 (`-Djvm`) | 42/42 | 2/2 |

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

Expected result of each run: unit tests 22 and 20, IT 2, `BUILD SUCCESS`.

If a run fails, keep these folders:

- `smoker-it\target\failsafe-reports\`
- `smoker-it\target\it-work\` (`cli-*.log`, `run-result.json`, `report.html`)

`SelfTestIT` did not run on Windows before. Examine these first:

- the quotes around the `java` path in `--app` (spaces in `C:\Program Files`),
- the `java` path without `.exe` (`SelfTestIT` uses `java.home\bin\java`),
- the `;` separator of `--scenarios` (`File.pathSeparator`).

## 4. Do WP-01 tasks 1–6 on the pilot application

1. Copy `smoker-agent\target\smoker-agent.jar` to a path without spaces, for example `C:\tools\`.
2. Add this line below `-vmargs` in the `eclipse.ini` of the product:
   `-javaagent:C:\tools\smoker-agent.jar=mode=record;scenario=C:\smoke\pilot.json`
3. Start the product with JRE 8. Record one scenario with five steps. Save it.
4. Remove the record line from `eclipse.ini`.
5. Replay with `smoker-cli.jar run --app "pilot.exe -vmargs {agent}"`.
6. Replay on a Linux runner with JRE 17 and `xvfb-run -a`.
7. Write down each failure, the lint result (unnamed components in `report.html`) and the startup time.

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
