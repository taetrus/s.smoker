# s.smoker — project memory for Claude Code

Swing smoke tester. A `-javaagent` records and replays short UI scenarios in the JVM of the application,
compares semantic component snapshots with golden snapshots, and reads the application log for failures.

## Read first

1. `docs/development-plan.md` — audit findings F-01..F-23, changed requirements, work packages WP-01..WP-09. **This is the backlog.**
2. `docs/wp-01-handoff.md` — WP-01 state and the commands that operated on Windows.
3. `docs/wp-01-pilot-report.md` — results of the pilot on Windows. Input for the Confluence page.
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

`SelfTestIT` replaces `selftest.sh`. It expects run1 NO_GOLDEN, run2 PASS, run3 FAIL with `CHANGED #demo.lang`,
run4 FAIL (log hit) and HANG. Run `mvn -B verify` and `mvn -B verify -Plocale-tr` after every change to `smoker-agent` or `smoker-core`.

## State on 2026-11-14

- Work package: WP-01. Branch `wp-01-test-base`, pushed. Do not start WP-02.
- Tests: 22 in `smoker-core`, 28 in `smoker-agent`, 2 IT. They pass on macOS and on Windows 11
  (JDK 25, default locale and tr_TR, and tr_TR with JRE 8 as test JVM).
- WP-01 tasks 1–4, 6 and 7–10 done. The pilot p.osgi.ds operated on Windows with JRE 8u341:
  - five-step scenario `docs/wp-01-pilot/pilot.json`: PASS 3 times in sequence;
  - HANG test on spike.master: HANG with a thread dump; crash test: ERROR with exit code 7;
  - lint: 14 unnamed interactive components, 0 named (input for WP-09).
- Findings from the pilot: F-19, F-20, F-23 corrected. F-21 (WP-02, WP-06) and F-22 (WP-04) open. F-02 confirmed (WP-03).
- 2026-11-15: the code of 2026-11-14 also passes on macOS (JDK 27 default and tr_TR, JRE 8 tr_TR): 22 + 28 unit tests, 2 IT.
- Decision 5 (2026-11-15): smoke tests run on Windows only. No Linux replay in WP-01.
- Open for the WP-01 completion criterion: the test report in Confluence. Then WP-01 is complete.
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
