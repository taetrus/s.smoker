# s.smoker — project memory for Claude Code

Swing smoke tester. A `-javaagent` records and replays short UI scenarios in the JVM of the application,
compares semantic component snapshots with golden snapshots, and reads the application log for failures.

## Read first

1. `docs/development-plan.md` — audit findings F-01..F-24, changed requirements, work packages WP-01..WP-09. **This is the backlog.**
2. `docs/wp-01-handoff.md` — WP-01 state and the commands that operated on Windows.
3. `docs/wp-01-pilot-report.md` — WP-01 test report (pilot on Windows). Reports stay in `docs/` (decision 6).
   `docs/wp-03-windows-report.md` — WP-03 test report (Windows).
   `docs/wp-04-windows-report.md` — WP-04 test report (Windows, pilot applications).
   `docs/wp-05-windows-report.md` — WP-05 test report (Windows, pilot application).
   `docs/wp-09-windows-report.md` — WP-09 follow-up test report (Windows, pilot application).
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
It checks the WP-05 criterion with `run --suite` (suite files written at run time from `smoker-demo/suite/`, `screenshots` off):
20 copies of a short scenario and a reset scenario give NO_GOLDEN, then PASS, `applicationStarts` 1 and `application starts: 1`.
Scenario `a` (leaves a name) before `b` (golden of an empty name) gives `b` PASS with attempts 2, the note
`failed in a shared JVM, passed in a new JVM`, `applicationStarts` 2 and the folder `b.shared`.
With `DemoSimulator` (`-Ddemo.simPort`), suite `[sim-a, bad, sim-b]`: `before` starts it, the `reset` command resets it,
`sim-a` PASS, `bad` FAIL with attempts 2 and `failed in a shared JVM and in a new JVM`, `sim-b` PASS with attempts 1,
`applicationStarts` 3, exit code 1, the `after` marker exists and the port is closed.
A `before` command with exit code 1 gives exit code 2, no `agent-1.cfg`, and the `after` marker exists.
It checks the WP-06 criterion (R-20a) with `-Ddemo.recorderPanel=true` (`scenarios-recorder/recorder.json`, 9 steps in the groups
Tree, Table and Spinner): run1 NO_GOLDEN for s001 to s009, `approve`, run2 PASS with three `<tr class='group'>` rows in `report.html`.
The golden has `demo.picked` `opened Main.java` (s002) and `menu docs` (s004), `demo.total` `total 10` (s005) and `total 0` (s008),
and `selectedIndex` 2 for `demo.tree` (s001). A copy with the value `9` in s005 gives FAIL with a `demo.total` difference.
`scenarios-failing-recorder/bad-cell.json` gives ERROR `the cell editor refused the value "abc"`.
Run `mvn -B verify` and `mvn -B verify -Plocale-tr`
after every change to `smoker-agent` or `smoker-core`.

## State on 2026-11-25

- WP-06 tasks 2 to 7 (recorder: tree `SELECT`, double-click and right-click, `SET_CELL`, spinner `SET_TEXT`, golden preview,
  move and group) are code complete on branch `wp-06-recorder` (not merged yet). WP-06 is not complete: the usability test
  and the corrections (tasks 1 and 8) come in the field phase (decision 8). F-13 and F-21 stay open until then.
  R-20a passes on the demo (SelfTestIT, `recorder.json`).
- Tests: 92 in `smoker-core`, 385 in `smoker-agent`, 87 in `smoker-cli`, 17 IT. They pass on macOS (default locale and
  `-Plocale-tr`). Earlier results: Linux with Xvfb and the Windows CI (PR #18) passed with the earlier counts.

## State on 2026-11-23

- `master` holds WP-01, WP-02, WP-09, WP-03, WP-04 and WP-05. All six work packages are complete; their completion criteria passed.
- WP-05 (suite mode and lifecycle) is complete; merged in PR #15 (2026-11-22). F-03, F-04, F-10 and F-11 are closed.
  `run --suite`, the reset step, the three-stage stop and the agent configuration file (`config=<file>`).
  The Windows check on `p2.product` (2026-11-22, commit 8799100): R-36 (20 scenarios, 1 start), R-38 (20 stops),
  R-39 (`out` path with `;` and a space) pass; R-37 passes in the Windows CI with `DemoSimulator`. See `docs/wp-05-windows-report.md`.
  `p2.product` does not stop Equinox when its windows close: each stop ends at stage 2. Stage 1 now ends when no main
  frame shows, so stage 2 starts at once, not after `stopTimeoutMs` (PR #16, merged 2026-11-23; checked on `p2.product` on 2026-11-24: the stop takes 2 s with a 30 s limit, see the report).
- WP-03 (`WAIT_FOR`, F-02, R-23a) was merged on 2026-11-17 (PR #5). R-23a passes on the demo (`wait.json`, SelfTestIT).
  Windows run and manual walkthrough of `Wait for…`: pass (`docs/wp-03-windows-report.md`). Follow-ups from the
  walkthrough: the toolbar writes what it shows to the record log (PR #6), the picker gives no border to a `JViewport` (PR #7).
- WP-04 (log analysis) is complete; F-08, F-09, F-22 and F-24 are closed. Merged in PR #11; PR #13 keeps the console
  lines of each thread apart. Demo checks pass (SelfTestIT). The Windows check on the pilot (2026-11-22, after PR #13):
  5 of 5 runs pass, and 3 of 3 `jre8` runs give 28 hits. See `docs/wp-04-windows-report.md`.
- WP-09 follow-up, PR #17 (merged 2026-11-23): `Save` in the recorder writes `<scenario>.lint.json`, so the
  naming assistant operates without a run; `record` prints the lint summary. `JSpinner` and `JSlider` are interactive
  (lint entries). `record` creates the scenario folder. Windows CI passes. The Windows check on `p2.product`
  (2026-11-23, commit 5daf8df): N-1 to N-6 pass; the record session gives 14 components, 14 proposals (100 %), the
  same table as on macOS. See `docs/wp-09-windows-report.md`. Through Chrome Remote Desktop the application windows
  looked empty; the record sessions with the environment variable `J2D_D3D=false` operated (now in `README.md`,
  "Remote desktop"). Follow-ups of that check, plan item 13 (PR #21, merged 2026-11-23): the password
  warning comes at the first editing key, the toolbar shows `paused` or `RECORDING`, a save with no steps while
  paused gives a hint.
- WP-09 follow-up, PR #18 (merged 2026-11-23), items 10 and 11 of the plan: a generated field name
  (`jButton1`) gives way to the text and the label; a labeled component without a field gets the label text
  (source `label`). The lint table and the report have a `label` column; the summary counts `from labels`.
  Procedure for the Windows check of PR #17 and PR #18 on `p2.product`: `docs/wp-09-windows-check.md` (needs the
  owner for the record session).
- The record log and `app-output.log` are in the console charset of the application (Cp1252 on Windows), not UTF-8.
  PR #8 (merged 2026-11-19) makes the `[smoker]` texts ASCII and documents this in `README.md`.
  Windows record session: the record log has no non-ASCII byte (PR #8 comment).
- Windows CI (PR #9, merged 2026-11-19): `.github/workflows/windows.yml` runs the three Windows builds on each pull
  request and each push to `master`, except for a change to `*.md` files only. Self-hosted runner `CARLOSCARMEN` in `D:\Dev\actions-runner` on the Windows PC
  (outside the repository; not a service). Start it with `run.cmd` in the desktop session. When it is offline, the
  checks wait in the queue. Do not type on the PC while a job runs. Follow-up: move `actions/checkout@v4` to v5 (Node.js 20 warning).
  `.github/workflows/pilot-check.yml` runs checks on `p2.product` through the same runner: push a branch `pilot-check/**`,
  then read the summary in the log or the artifact `stop-check` (`gh run download`).
- Pilot goldens approved again on Windows on 2026-11-16 (`docs/wp-01-handoff.md` section 5).
- Decision 6 (2026-11-15): test reports go into `docs/` on GitHub, not Confluence.
- Open findings: F-02 fixed in the tool, untested on a product with the simulator. F-08, F-09, F-22, F-24 are closed (WP-04).
  F-03, F-04, F-10, F-11 are closed (WP-05); F-04 is tested with `DemoSimulator` only, not with the hardware simulator.
- SelfTestIT startup failure of 2026-11-15: root cause fixed in 7c7458c (the startup check read `Window.isShowing()`
  off the EDT and could see a frame inside its constructor). 2 of 100 demo starts failed before, 0 of 100 after.
  If a failure recurs, keep `smoker-it/target/failsafe-reports` and `smoker-it/target/it-work`.
- Do not type into the demo windows while SelfTestIT runs on a desktop: they take the focus, and a key press changes a text field.
- Test isolation: a test must not leave a window that reports "showing". `Window.getWindows()` keeps it for later test classes.
- Next in plan order (decision 8): the code tasks of WP-07 (1 to 3, 5 to 7).
  Then the field phase: Milestone 1, WP-06 tasks 1 and 8, WP-07 task 4.
- Not verified: JRE 21 at run time on a target application, a product with the hardware simulator.
  JRE 17 (Temurin 17.0.5) operates on spike.master (WP-04 Windows check).

## Decisions already made

- Smoke tests run on Windows only: the target applications load Windows DLLs (2026-11-15).
- Pilot application for WP-01 (2026-11-14): p.osgi.ds. `p2.product` for the five-step scenario, `spike.master` for HANG. No simulator: F-02 and F-04 stay untested until WP-03 and WP-05.
- Naming rule `<view>.<widget>` applies to all teams.
- Lint results fail the pipeline from version 1.1, against a baseline file (only new results fail).
- WP-09 (naming assistant) follows WP-01.
- Development continues before Milestone 1 (decision 8, 2026-11-24). A work package without its field tasks is "code complete", not complete.
- The JRE matrix is JRE 8 and JRE 21 (decision 9, 2026-11-24). No JRE 11 or JRE 17 jobs or runners.
- Open: number of developers.

## Working rules

- Work one work package at a time, in the order of the plan. State the WP and the finding IDs in each commit message.
- Each work package has a completion criterion in the plan. Do not report a work package as complete before the criterion passes.
- When a change alters the scenario or golden file format, update `README.md` and the demo scenarios in the same commit.
- Write documentation in Simplified Technical English: short sentences, active voice, one term for one thing.
- Commit with the GitHub noreply address `6750412+taetrus@users.noreply.github.com`. GitHub rejects a push that contains
  the private address (GH007). The repository config (`git config --local user.email`) sets the noreply address.
