# s.smoker — project memory for Claude Code

Swing smoke tester. A `-javaagent` records and replays short UI scenarios in the JVM of the application,
compares semantic component snapshots with golden snapshots, and reads the application log for failures.

## Read first

1. `docs/development-plan.md` — audit findings F-01..F-24, changed requirements, work packages WP-01..WP-09. **This is the backlog.**
2. `docs/wp-01-handoff.md` — WP-01 state and the commands that operated on Windows.
3. `docs/wp-01-pilot-report.md` — WP-01 test report (pilot on Windows). Reports stay in `docs/` (decision 6).
   `docs/wp-03-windows-report.md` — WP-03 test report (Windows).
4. `docs/architecture.md` — design decisions and class map of the current skeleton.
5. `docs/requirements.md` — original requirements. The development plan wins where they differ.
6. `README.md` — build and CLI usage.

## Hard constraints

- Java 8 source level. No `var`, no `List.of`, no `String.isBlank`, no `Files.readString`. Compile with `--release 8`.
- The agent jar and the CLI jar have no runtime dependency except relocated Gson (`smoker.shaded.gson`).
- The agent does no bytecode instrumentation.
- Every Swing access goes through the EDT. Never wait on an action `Runnable` directly: a modal dialog blocks it. Wait for a later ping (`Quiescence.edtResponds`).
- All case conversion uses `Locale.ROOT` (Turkish locale defect F-16).
- Windows named `smoker.*` are the tool's own UI. Exclude them from recording and snapshots.
- Never write the text of a `JPasswordField` to a scenario, a snapshot or a report (F-19). The snapshot gives `***` or `""`.
- `Locators.resolve` and `Locators.find` must use the same class rule, `Locators.classNameOf` (F-23).
- Target applications: Swing in Equinox/OSGi, SLF4J + Logback in bundles, hardware simulator behind the UI.
- Corporate build is air-gapped behind Nexus: add no dependency without a clear need.

## Commands

```bash
mvn -B package                         # build all modules, run the unit tests
mvn -B verify                          # also the integration test smoker-it (macOS / Windows / Linux with a display)
xvfb-run -a mvn -B verify              # Linux, no display
mvn -B verify -Plocale-tr              # all tests in tr_TR (F-16)
```

Windows (PowerShell 5.1): put each `-D` argument in quotes, for example `"-Djvm=C:\Program Files\Java\jdk1.8.0_341\bin\java.exe"`.
For the CLI, write each inner quote of `--app` as `\"` and enclose the value in single quotes. `!` in Claude Code runs Git Bash, not PowerShell.

`SelfTestIT` expects run1 NO_GOLDEN, run2 PASS, run3 FAIL with `CHANGED [#demo.frame]/#demo.lang`,
run4 `empty-name` ERROR (s001 FAIL on the log hit, s002 ERROR on the exception thrown by the action) and `hang` HANG.
It also checks the WP-02 criterion on `table.json`: scenario under 80 lines, golden under 150 lines, an extra unnamed
panel gives PASS (modes `all` and `named`), a wrong cell gives FAIL.
It checks the WP-03 criterion on `wait.json`: run1 s001 PASS without a snapshot, s002 NO_GOLDEN; after `approve` PASS.
`wait-timeout`: s002 FAIL with the last value `waiting…`.
It checks the WP-04 criterion with `-Ddemo.logPanel=true` (`scenarios-log/`): `log.json` s001 FAIL with one `file:demo.log` hit
of 40 `\tat` lines, s002 no hit, s003 FAIL with one `stderr` hit; `exit.json` gives ERROR, `currentStep` s001, CLI exit code 1.
With `-Ddemo.equinoxError=true`, `greet.json` gives FAIL of `startup` with 4 `stdout` hits. `approve` without `run-result.json` gives exit code 2.
Run `mvn -B verify` and `mvn -B verify -Plocale-tr`
after every change to `smoker-agent` or `smoker-core`.

## State on 2026-11-19

- `master` holds WP-01, WP-02, WP-09 and WP-03. All four work packages are complete; their completion criteria passed.
- WP-03 (`WAIT_FOR`, F-02, R-23a) was merged on 2026-11-17 (PR #5). R-23a passes on the demo (`wait.json`, SelfTestIT).
  Windows run and manual walkthrough of `Wait for…`: pass (`docs/wp-03-windows-report.md`). Follow-ups from the
  walkthrough: the toolbar writes what it shows to the record log (PR #6), the picker gives no border to a `JViewport` (PR #7).
- Tests: 64 in `smoker-core`, 155 in `smoker-agent`, 22 in `smoker-cli`, 5 IT. They pass on macOS (JDK 27 default
  locale and `-Plocale-tr`) and on Windows 11 (JDK 25: default, `-Plocale-tr`, tr_TR with JRE 8u341 as test JVM),
  also in the Windows CI.
- The record log and `app-output.log` are in the console charset of the application (Cp1252 on Windows), not UTF-8.
  PR #8 (merged 2026-11-19) makes the `[smoker]` texts ASCII and documents this in `README.md`.
  Windows record session: the record log has no non-ASCII byte (PR #8 comment).
- Windows CI (PR #9, merged 2026-11-19): `.github/workflows/windows.yml` runs the three Windows builds on each pull
  request and each push to `master`, except for a change to `*.md` files only. Self-hosted runner `CARLOSCARMEN` in `D:\Dev\actions-runner` on the Windows PC
  (outside the repository; not a service). Start it with `run.cmd` in the desktop session. When it is offline, the
  checks wait in the queue. Do not type on the PC while a job runs. Follow-up: move `actions/checkout@v4` to v5 (Node.js 20 warning).
- Pilot goldens approved again on Windows on 2026-11-16 (`docs/wp-01-handoff.md` section 5).
- Decision 6 (2026-11-15): test reports go into `docs/` on GitHub, not Confluence.
- Open findings: F-02 fixed in the tool, untested on a product with the simulator; F-08, F-09, F-22, F-24 (WP-04).
- SelfTestIT startup failure of 2026-11-15: root cause fixed in 7c7458c (the startup check read `Window.isShowing()`
  off the EDT and could see a frame inside its constructor). 2 of 100 demo starts failed before, 0 of 100 after.
  If a failure recurs, keep `smoker-it/target/failsafe-reports` and `smoker-it/target/it-work`.
- Do not type into the demo windows while SelfTestIT runs on a desktop: they take the focus, and a key press changes a text field.
- Test isolation: a test must not leave a window that reports "showing". `Window.getWindows()` keeps it for later test classes.
- Next work package in plan order: WP-04.
- Not verified: JRE 11 and JRE 17 at run time on a target application, a product with the hardware simulator.

## Decisions already made

- Smoke tests run on Windows only: the target applications load Windows DLLs (2026-11-15).
- Pilot application for WP-01 (2026-11-14): p.osgi.ds. `p2.product` for the five-step scenario, `spike.master` for HANG. No simulator: F-02 and F-04 stay untested until WP-03 and WP-05.
- Naming rule `<view>.<widget>` applies to all teams.
- Lint results fail the pipeline from version 1.1, against a baseline file (only new results fail).
- WP-09 (naming assistant) follows WP-01.
- Open: number of developers.

## Working rules

- Work one work package at a time, in the order of the plan. State the WP and the finding IDs in each commit message.
- Each work package has a completion criterion in the plan. Do not report a work package as complete before the criterion passes.
- When a change alters the scenario or golden file format, update `README.md` and the demo scenarios in the same commit.
- Write documentation in Simplified Technical English: short sentences, active voice, one term for one thing.
- Commit with the GitHub noreply address `6750412+taetrus@users.noreply.github.com`. GitHub rejects a push that contains
  the private address (GH007). The repository config (`git config --local user.email`) sets the noreply address.
