# s.smoker — project memory for Claude Code

Swing smoke tester. A `-javaagent` records and replays short UI scenarios in the JVM of the application,
compares semantic component snapshots with golden snapshots, and reads the application log for failures.

## Read first

1. `docs/development-plan.md` — audit findings F-01..F-18, changed requirements, work packages WP-01..WP-09. **This is the backlog.**
2. `docs/architecture.md` — design decisions and class map of the current skeleton.
3. `docs/requirements.md` — original requirements. The development plan wins where they differ.
4. `README.md` — build and CLI usage.

## Hard constraints

- Java 8 source level. No `var`, no `List.of`, no `String.isBlank`, no `Files.readString`. Compile with `--release 8`.
- The agent jar and the CLI jar have no runtime dependency except relocated Gson (`smoker.shaded.gson`).
- The agent does no bytecode instrumentation.
- Every Swing access goes through the EDT. Never wait on an action `Runnable` directly: a modal dialog blocks it. Wait for a later ping (`Quiescence.edtResponds`).
- All case conversion uses `Locale.ROOT` (Turkish locale defect F-16).
- Windows named `smoker.*` are the tool's own UI. Exclude them from recording and snapshots.
- Target applications: Swing in Equinox/OSGi, SLF4J + Logback in bundles, hardware simulator behind the UI.
- Corporate build is air-gapped behind Nexus: add no dependency without a clear need.

## Commands

```bash
mvn -B package                         # build all modules, run the unit tests
mvn -B verify                          # also the integration test smoker-it (macOS / Windows / Linux with a display)
xvfb-run -a mvn -B verify              # Linux, no display
mvn -B verify -Plocale-tr              # all tests in tr_TR (F-16)
```

`SelfTestIT` replaces `selftest.sh`. It expects run1 NO_GOLDEN, run2 PASS, run3 FAIL with `CHANGED #demo.lang`,
run4 FAIL (log hit) and HANG. Run `mvn -B verify` and `mvn -B verify -Plocale-tr` after every change to `smoker-agent` or `smoker-core`.

## State on 2026-11-12

- Skeleton 0.1.0-SNAPSHOT builds and passes the self-test on JDK 21 + Xvfb only.
- Not verified: JRE 8/11/17 at run time, Windows, a real Equinox application, the recorder toolbar with a person.
- WP-01 tasks 7–10 done: unit tests in smoker-core and smoker-agent, `SelfTestIT` in smoker-it, `Locale.ROOT`, profile `locale-tr`.
  Verified on macOS, JDK 21, default locale and tr_TR. WP-01 tasks 1–6 and the completion criterion are open.

## Decisions already made

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
