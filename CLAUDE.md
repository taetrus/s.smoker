# s.smoker — project memory for Claude Code

Swing smoke tester. A `-javaagent` records and replays short UI scenarios in the JVM of the application,
compares semantic component snapshots with golden snapshots, and reads the application log for failures.

## Read first

1. `docs/development-plan.md` — audit findings F-01..F-23, changed requirements, work packages WP-01..WP-09. **This is the backlog.**
2. `docs/wp-01-handoff.md` — WP-01 state and the commands that operated on Windows.
3. `docs/wp-01-pilot-report.md` — WP-01 test report (pilot on Windows). Reports stay in `docs/` (decision 6).
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
panel gives PASS (modes `all` and `named`), a wrong cell gives FAIL. Run `mvn -B verify` and `mvn -B verify -Plocale-tr`
after every change to `smoker-agent` or `smoker-core`.

## State on 2026-11-15

- WP-01 complete (branch `wp-01-test-base`, pushed). WP-02 complete on branch `wp-02-snapshot-model` (not pushed).
- Tests: 56 in `smoker-core`, 60 in `smoker-agent`, 3 IT. They pass on macOS (JDK 27 default locale and tr_TR, tr_TR with JRE 8 as test JVM).
- WP-02: golden file apart from the scenario (`schemaVersion: 2`), anchored paths, table cells, `compare` (`all` | `named`),
  `maxRows`, `windowKeys`, lint for names that are not unique, player continues after FAIL.
- Decision 6 (2026-11-15): test reports go into `docs/` on GitHub, not Confluence.
- Open findings: F-22 (WP-04), F-02 confirmed (WP-03).
- WP-09 implemented on branch `wp-09-naming-assistant` (not pushed): automatic names `@<Class>.<field>`, lint entries, `lint --emit-patch`, demo case `notes.json`.
  Tests on macOS: 61 in `smoker-core`, 120 in `smoker-agent`, 21 in `smoker-cli`, 4 IT.
  Windows (2026-11-16, `master` 5372284 + test fix 6fd6fa7): 61 + 120 + 21 unit tests and 4 IT pass in the three builds.
  The scenario with automatic names `docs/wp-01-pilot/pilot-autonames.json` passes on p.osgi.ds.
- SelfTestIT failure of 2026-11-15 (2 of 3 methods under load): root cause found and fixed in 7c7458c.
  The startup check read `Window.isShowing()` off the EDT. It could see a frame inside its constructor, where `visible` is still the default `true`.
  The fix scans the windows on the EDT with a bounded wait and requires `isDisplayable()`.
  Evidence: 2 of 100 demo starts failed before the fix, 0 of 100 after it.
  If a failure recurs, keep `smoker-it/target/failsafe-reports` and `smoker-it/target/it-work`.
- Pilot goldens approved again on Windows on 2026-11-16 (`docs/wp-01-handoff.md` section 5): `pilot.json` and
  `pilot-autonames.json` PASS 3 times, `hang.json` HANG. `crash.json` has no golden: the JVM exits before `run-result.json`.
- Open: `approve` on a run without `run-result.json` gives a stack trace (exit 1). It must give a message and exit 2, as `lint` does.
- Test isolation: a test must not leave a window that reports "showing". `Window.getWindows()` keeps it for later test classes.
- Next work package in plan order: WP-03 or WP-04.
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
