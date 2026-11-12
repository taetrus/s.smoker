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
mvn -B package                         # build all modules
xvfb-run -a ./selftest.sh              # Linux, no display: full record-free cycle on the demo application
./selftest.sh                          # macOS / Linux with a display
```

`selftest.sh` must show: run1 NO_GOLDEN, run2 PASS, run3 FAIL with `CHANGED #demo.lang`, run4 FAIL (log hit) and HANG.
Run it after every change to `smoker-agent` or `smoker-core`.

## State on 2026-11-12

- Skeleton 0.1.0-SNAPSHOT builds and passes the self-test on JDK 21 + Xvfb only.
- Not verified: JRE 8/11/17 at run time, Windows, a real Equinox application, the recorder toolbar with a person.
- No unit tests exist.

## Decisions already made

- Pilot application for WP-01: a typical product that uses the simulator.
- Naming rule `<view>.<widget>` applies to all teams.
- Lint results fail the pipeline from version 1.1, against a baseline file (only new results fail).
- WP-09 (naming assistant) follows WP-01.
- Open: number of developers.

## Working rules

- Work one work package at a time, in the order of the plan. State the WP and the finding IDs in each commit message.
- Each work package has a completion criterion in the plan. Do not report a work package as complete before the criterion passes.
- When a change alters the scenario or golden file format, update `README.md` and the demo scenarios in the same commit.
- Write documentation in Simplified Technical English: short sentences, active voice, one term for one thing.
