# s.smoker — Swing Smoke Tester: Requirements v0.1

Status: draft · Owner: Kerem · Date: 2026-10-19
Note: `development-plan.md` section 3 changes and adds requirements. Where the two differ, the development plan wins.

## Context (answers that shape the design)

| Question | Answer | Consequence |
|---|---|---|
| Target apps | Swing UIs running inside OSGi (Equinox-based products built with PDE/Tycho) | Agent-based attachment on the system classloader; no reliance on bundle classloaders |
| Logging | SLF4J + Logback inside bundles | Primary observation = tail Logback's file output; optional Logback fragment bundle for in-JVM forwarding (phase 2) |
| Source control of the app | Yes | Component naming hygiene (`setName`) can be enforced; a "hints" API is acceptable |
| Scenario authors | Developers, but a good UI is required | In-app floating recorder toolbar + component picker; HTML diff report |
| Where it runs | Dev machines = Windows; GitLab runners = Linux or Windows | Semantic replay must not need focus; Linux runners via `xvfb-run`; Windows runners must run in an interactive session for Robot/screenshots |

## 1. Platform & runtime

| ID | Requirement | Notes |
|---|---|---|
| R-01 | Runs on Windows; macOS and Linux supported | Linux CI runners need a display (`xvfb-run`); Windows CI runners need an interactive session — `java.awt.Robot` does not work in Session 0 (services) |
| R-02 | Compiled for Java 8 (`maven.compiler.release=8`), tested on JRE 8, 11, 17, 21 | No Java 9+ APIs. Avoid `sun.awt.*` internals; ship `--add-opens` notes if ever needed |
| R-03 | Ships as one shaded, dependency-free agent jar + one CLI jar | Air-gapped friendly; two artifacts in Nexus. Third-party classes relocated (e.g. `smoker.shaded.gson`) |
| R-04 | Attaches with **zero app code changes** via `-javaagent:smoker-agent.jar=<args>` | Works on JRE 8+. Agent lives on the system classloader; Swing is on bootstrap, so `Window.getWindows()` sees every window regardless of OSGi bundle classloaders |
| R-05 | Optional thin "hints" API the app may depend on | Marks dynamic regions, names components, exposes a "quiescent" signal from the app |
| R-06 | Launch recipes for Equinox products | `-javaagent` via `eclipse.ini` `-vmargs`, or CLI-launched `java -javaagent:… -jar org.eclipse.equinox.launcher_*.jar` |

## 2. Log & exception observation

| ID | Requirement |
|---|---|
| R-10 | Tail configured log file(s) during a scenario; attach matching lines to the step that was executing (time-window correlation) |
| R-11 | Install `Thread.setDefaultUncaughtExceptionHandler` + EDT exception handler; capture `System.err`; install a `java.util.logging` handler in-JVM |
| R-12 | Configurable fail rules: regex on message/level (`ERROR`, `Exception`, `OutOfMemoryError`), with allow-list for known noise |
| R-13 | **EDT watchdog**: EDT blocked > N s → thread dump → mark step failed as `HANG` |
| R-14 | Startup smoke: main window visible within T s and no fail-rule hits during startup |
| R-15 | Phase 2: Logback fragment bundle (`Fragment-Host: ch.qos.logback.classic`) providing an appender that forwards events to the agent through a bootstrap-visible channel (`System.getProperties()` + `java.util.function.Consumer`) |

## 3. Recording

| ID | Requirement |
|---|---|
| R-20 | Record user actions via `Toolkit.addAWTEventListener` (mouse, key, window, focus masks) with a floating toolbar: Record / Pause / Mark step / Snapshot / Save |
| R-21 | Resolve every event target to a **stable locator**, priority: `component.getName()` → window title + class + label/text + sibling index. Coordinates stored only as fallback |
| R-22 | Locator lint: report interactive components without a name (`setName`) so developers add names |
| R-23 | After each action wait for **quiescence** (EDT idle + no repaints for N ms, bounded by a timeout), then capture a **semantic snapshot**: visible windows/dialogs, component tree with enabled/visible/selected/text/row-count/title |
| R-24 | Screenshot per step as evidence; optional secondary oracle with tolerance. Not the primary oracle (fonts, DPI scaling on Java 8 vs 9+, LAF make pixels fragile) |
| R-25 | Scenario file is human-readable, diffable, VCS-friendly JSON; one file per functionality; hand-editable |
| R-26 | Component picker: hover highlights the component and shows its locator; click copies it |

## 4. Replay & comparison

| ID | Requirement |
|---|---|
| R-30 | Replay a scenario and compare each step's snapshot against the golden; produce a structured diff |
| R-31 | Replay strategy: **semantic by default** (on EDT: `doClick`, `setText`, `setSelectedIndex`, menu path, `JTable` row select), **Robot fallback** for native dialogs/popups. Semantic replay needs no focus and is CI-safe |
| R-32 | Ignore/mask rules: timestamps, counters, window positions/sizes; regex per locator or per attribute |
| R-33 | "Approve" workflow: promote a run's snapshots to golden per step or per scenario |
| R-34 | Determinism knobs: fixed window size, locale, timezone, LAF; test-data reset hook before each scenario |
| R-35 | Fail fast per scenario, continue with next; failure bundle: screenshot, component tree dump, log excerpt, thread dump |

## 5. Reporting & CI

| ID | Requirement |
|---|---|
| R-40 | JUnit XML + HTML report; non-zero exit code on failure → GitLab CI job status and test report widget |
| R-41 | Matrix dimension support: JRE × OS × LAF × DPI, reported separately |
| R-42 | Run history retained so flaky steps can be spotted |

## 6. Non-goals

- Not a full functional-test framework; scenarios stay short (5–20 steps).
- No OCR / pixel-perfect visual regression.
- Swing only — no SWT/JavaFX.
