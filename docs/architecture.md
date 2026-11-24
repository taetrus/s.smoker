# s.smoker — Architecture v0.1

Status: skeleton implemented and self-tested (Linux/Xvfb, JDK 21 compiling `--release 8`) · 2026-10-19

## 1. Shape

```mermaid
flowchart LR
  subgraph CI["GitLab job / dev machine"]
    CLI["smoker-cli.jar<br/>run · record · approve"]
    REP["junit.xml<br/>report.html"]
  end
  subgraph JVM["Application JVM (Equinox product)"]
    AG["smoker-agent.jar (-javaagent)<br/>system classloader"]
    SW["Swing / AWT<br/>bootstrap classloader"]
    B1["bundle: app UI"]
    B2["bundle: logback-classic"]
    LOG[("app.log")]
    AG -- "Window.getWindows()<br/>AWTEventListener<br/>invokeLater pings" --> SW
    B1 --> SW
    B2 --> LOG
    AG -- "tail" --> LOG
  end
  CLI -- "spawn with -javaagent=mode;scenario;out;log" --> JVM
  JVM -- "run-result.json + PNGs" --> CLI
  CLI --> REP
  SC[("scenarios/*.json<br/>in Git")] --> CLI
  CLI -- "approve" --> SC
```

Three decisions carry the design:

1. **In-process agent, no instrumentation.** `premain` only installs hooks; nothing is rewritten. Swing is on the
   bootstrap classpath, so `Window.getWindows()` and `Toolkit.addAWTEventListener` see every window no matter which
   OSGi bundle created it. Works on JRE 8 (`-javaagent` since Java 5) and is Equinox-transparent.
2. **Semantic oracle, semantic replay.** State = component tree (type, name, text, enabled, selection, row counts),
   not pixels. Replay = `doClick()`, `setText()`, `setSelectedIndex()` and synthesized AWT events on the EDT.
   No OS focus, no mouse, no DPI/LAF dependency → a Windows-recorded scenario replays on a Linux Xvfb runner.
   Screenshots are evidence only.
3. **The agent is autonomous; the CLI is orchestration.** One JVM per scenario; the agent writes
   `run-result.json` and exits. The CLI aggregates into JUnit XML + HTML. No socket protocol, no shared state.

## 2. Module map

| Module | Package | Key classes |
|---|---|---|
| smoker-core | `smoker.core.model` | `Scenario`, `Step`, `Action`, `Locator`, `Snapshot`, `NodeSnapshot`, `IgnoreRules`, `RunResult` |
| | `smoker.core.model` | `Golden` — the golden file: the first step in full, later steps as change lists |
| | `smoker.core.model` | `WindowKey` — a title rule (pattern → fixed window key); `compile` checks the rules |
| | `smoker.core.golden` | `GoldenCodec` — converts golden snapshots per step to a `Golden` and back |
| | `smoker.core.io` | `ScenarioFiles` — reads and checks the scenario and golden files (`schemaVersion` 2) |
| | `smoker.core.diff` | `SnapshotDiff` — path-keyed attribute diff with ignore rules |
| | `smoker.core.io` / `.report` | `Json` (Gson, pretty, stable), `JUnitXmlWriter`, `HtmlReportWriter` |
| smoker-agent | `smoker.agent` | `SmokerAgent` (premain), `AgentConfig`, `Observations`, `LogTailer`, `LogPattern`, `LogLevel`, `LogEvent`, `EventJoiner`, `FailRule`, `ConsoleTee`, `EdtWatchdog`, `Quiescence`, `Locators`, `SnapshotTaker`, `ActionExecutor`, `Places`, `Player`, `Recorder`, `CellEditTracker`, `RecorderToolbar`, `Screenshots` |
| | `smoker.agent` | `WindowKeys` — applies the `WindowKey` rules to window titles |
| | `smoker.agent` | `LogPattern` — converts the Logback `logPattern` into one regex (time, level, logger, thread, message).<br>`LogLevel` — the log levels in Logback order, for `failLevel`.<br>`LogEvent` — one log event: the lines of one entry, joined.<br>`EventJoiner` — joins the lines of one log source into events. A stack trace gives one hit.<br>`FailRule` — decides once for each closed event whether it is a hit (level, `failRegex`, allow rules).<br>`ConsoleTee` — wraps `System.out` and `System.err`. It passes the bytes unchanged. It keeps one line and one joiner for each writing thread, and gives each decoded line to the joiner of its thread. |
| | `smoker.core.io` | `ConfigFile` — reads and writes the agent configuration file (`key=value`, numbered keys) |
| | `smoker.core.proc` | `Processes` — runs commands and background processes; `stopTree` stops the descendants on Java 9+ (reflection on `ProcessHandle`) |
| smoker-agent | `smoker.agent` | `Session` — runs the scenarios of one launch list in one JVM: result file per scenario, `reset` step between two scenarios, stop after FAIL/HANG/ERROR |
| | `smoker.agent` | `AppStopper` — the stop in three stages (`WINDOW_CLOSING`, OSGi system bundle, `System.exit` with the `smoker-halt` thread as safety) |
| | `smoker.agent` | `DialogCloser` — closes the dialogs that do not belong to the tool, for the reset step |
| smoker-cli | `smoker.cli` | `Main` — `run` / `run --suite` / `record` / `approve` |
| | `smoker.cli` | `SuiteFile` — reads and checks the suite file (fields, commands, scenarios, golden files) |
| | `smoker.cli` | `SuitePlan` — pure logic: launch lists, verdicts, the retry rule and its notes, the count of failed starts |
| | `smoker.cli` | `SuiteRunner` — `before` / `reset` / `after` commands, application starts, the watch of the result files, time limits, `cli-alive`, the one-time cleanup, `suite-result.json` |
| smoker-demo | `smoker.demo` | `DemoApp` + `scenarios/` (`greet.json`, `wait.json`, ...) + `scenarios-failing/` |
| | `smoker.demo` | `DemoSimulator` — TCP simulator for the suite checks (`PING`, `INC`, `GET`, `RESET`, `QUIT`); `sim/start-sim.*` start it; `suite/` has the suite example and its scenarios |

Gson is relocated to `smoker.shaded.gson` in both shaded jars (R-03). The agent jar has no other dependency.

## 3. Play-mode step lifecycle

```mermaid
sequenceDiagram
  participant P as Player (smoker-main thread)
  participant E as EDT
  participant O as Observations
  P->>E: invokeLater(ActionExecutor.execute(step.action))
  P->>E: invokeLater(ping)
  Note over P,E: ping serviced ⇒ action done OR a modal dialog's nested loop is pumping.<br/>No ping within stepTimeoutMs ⇒ HANG + thread dump (EdtWatchdog)
  P->>P: Quiescence.await(quietMs): queue empty + EDT responsive for quietMs
  P->>E: invokeAndWait(SnapshotTaker.take())
  P->>P: Screenshots.capture() (evidence)
  P->>P: SnapshotDiff.diff(expected, actual, ignore)
  P->>O: drain(), then takeHits(lastEnd, now) → log/console/JUL/uncaught hits
  Note over P: PASS · FAIL (diff or hits) · ERROR (not found / exception) · NO_GOLDEN
```

**Modal dialogs** are the classic trap: `doClick()` on a menu item that opens `JOptionPane` never returns until the
dialog closes. The player therefore never waits for the action `Runnable` itself; it waits for a *subsequent ping*,
which a modal dialog's secondary loop services. Only true silence is a hang.

**Wait for a condition.** A `WAIT_FOR` step uses `WaitCondition`. The player polls one attribute of one component each
100 ms, with every Swing read on the EDT, until the value matches or `timeoutMs` ends. A timeout is FAIL.

**Progress.** `Player` writes `run-result.json` with `currentStep` before startup and before each action. After `System.exit`, the exit hook adds the hits and the step name (F-22).

**Stop.** `AppStopper` ends the application after the final result (F-10, R-38): `WINDOW_CLOSING` to the main frames on the EDT (skipped on HANG), `stop()` of the OSGi system bundle by reflection (no OSGi type at compile time), then `System.exit(code)`. Stage 1 waits until no main frame shows, at most `stopTimeoutMs`; stage 2 waits `stopTimeoutMs`. The system bundle is found before stage 1, while the windows still show. The daemon thread `smoker-halt` calls `Runtime.halt` when `System.exit` does not return. The class does not depend on `Player`. When the application exits itself in stage 1 (for example `EXIT_ON_CLOSE`), the exit code is the code of the application, and the shutdown hook of `Player` does nothing because `finished` is set.

**Startup** is a pseudo-step: first `Frame` showing within `startupTimeoutMs`, quiet, no log hits (R-14).

## 3a. Suite mode (WP-05)

`run --suite` is the CLI side, `Session` is the agent side. The CLI writes one configuration file for each application
start (`<out>/agent-<k>.cfg`) with the launch list (`scenario.1`, `scenario.2`, ...), the reset commands and the reset scenario.

- **Session.** The agent runs the launch list in one JVM. The first scenario has the `startup` step. A scenario that
  follows gets the `reset` step as its first step: close the dialogs (`DialogCloser`), run the `reset` commands
  (`Processes.startForeground`, with its own time limit and with stdin closed), replay the reset scenario without a snapshot, read the logs. A `reset` step that is not PASS gives
  its status to the next scenario. After FAIL, HANG or ERROR, the session stops: the scenarios that remain have no result file.
- **Stop.** After the last result, `AppStopper` stops the application in three stages. The exit code is then not
  a source of the verdict: the CLI reads the result files.
- **Verdicts and retry.** `SuitePlan` takes the results of a launch. The first scenario of a launch ran in a new JVM:
  its result is the verdict. A later scenario with FAIL, HANG or ERROR ran in a shared JVM: the CLI moves its folder to
  `<name>.shared/` and puts it first in the next launch (`SuiteRunner`). The retry gives the note on the verdict.
- **Time limits.** `SuiteRunner` polls the result files, computes the limit of the scenario in work, and stops the JVM
  with force when the limit passes.
- **Cleanup.** The `after` commands and the stop of the background processes run one time (`cleanup`), also after an
  exception and on Ctrl+C. For a hard stop of the CLI, the agent reads `aliveFile` before each scenario and each reset step.

## 4. Observing logs inside OSGi (R-10, R-15)

| Source | Mechanism | Reaches Logback-in-a-bundle? |
|---|---|---|
| Log files | `LogTailer`: tail from the current end (a new file from 0), rotation-aware, `drain()` reads to the end before each step closes; `EventJoiner` and `FailRule` make the hits | **Yes** — primary mechanism |
| Uncaught exceptions | `Thread.setDefaultUncaughtExceptionHandler` + `sun.awt.exception.handler` (Java 8 EDT) | n/a |
| stdout, stderr | `ConsoleTee` on `System.out` and `System.err` | catches `printStackTrace()` and Equinox console errors (F-22) |
| java.util.logging | root `Handler`: level ≥ `failLevel` or a thrown exception | only JUL users |
| Phase 2: in-JVM Logback events | fragment bundle `Fragment-Host: ch.qos.logback.classic` with an `AppenderBase` that pushes `(level, logger, message)` into a `java.util.function.Consumer<String>` stored in `System.getProperties()` (bootstrap-visible type, no class sharing needed); configured in `logback.xml` | Yes, with the fragment deployed in the test product |

Tailing is enough for a smoke tester and keeps the agent free of any classloader coupling; phase 2 adds
sub-second attribution and MDC fields if needed.

## 5. Locators and snapshots

- `Locators.resolve(Component)` → `#name` when `setName()` was called (auto names like `null.contentPane`,
  `OptionPane.button`, `frame0` are rejected) else `[window] Class('text')[index]` + window-relative x/y.
- Menu items are reached through `JMenu.getMenuComponents()` even while the popup is closed; popup items
  (combo lists, context menus) resolve their window through `JPopupMenu.getInvoker()`.
- `SnapshotTaker` walks only visible components and skips LAF internals (`javax.swing.plaf.*`, combo arrow buttons,
  scroll-bar buttons, table-header internals) so Metal-on-Linux and Windows-LAF goldens agree.
- Paths: `[Title]/#orders.table`, `[#orders.frame]/#orders.panel/JButton[0]` (WP-02 design, section 4.2); `IgnoreRules.paths` are regexes on this string.
- Locator lint: every visible interactive component without a name is listed in `RunResult.unnamedComponents`.
- Lint with proposals (WP-09): `SnapshotTaker` collects a `LintEntry` per component without a real name. The player
  writes them into `RunResult.lint`. The recorder writes them into `<scenario>.lint.json` (`LintFile`) at each save,
  with one more snapshot at the save. `lint --run` reads both files.

## 6. Recorder (R-20, R-26)

`Recorder` is an `AWTEventListener` (mouse, key, focus). Mapping:

| User does | Recorded |
|---|---|
| clicks button / menu / menu item | `CLICK #name` |
| types in a text component, leaves it (or presses ENTER) | `SET_TEXT #name = "…"` (diffed on focus loss) |
| picks a combo item, list row, table row, tab | `SELECT #name = value/index` (read back after Swing processed the click) |
| selects a node of a `JTree` | `SELECT #name` with `path` (labels from the root) |
| double-click, or right-click, with a place (tree node, table cell, list row) | `CLICK #name` with `count` 2 or `button` 3 and `path`, or `row` and `column` |
| ends a table cell edit with a new value | `SET_CELL #name` with `row`, `column` and `value` (`CellEditTracker`) |
| changes a `JSpinner` value | `SET_TEXT #name = "…"` |
| ENTER / ESC / TAB / DELETE / F-keys / Ctrl+… | `KEY value` (VK name, modifiers) |

The recorder takes the golden snapshot of a step at the first input of the next step (F-21), before that input changes the UI.
If no input comes, a background thread takes it when the UI is quiet.
Save writes the scenario and the golden file `<scenario>.golden.json` (the recording run defines v1 of the golden). `RecorderToolbar` (named `smoker.toolbar`, always-on-top) offers
Record/Pause, Pick (hover → red border + locator + "unnamed" warning), Rename, Delete, Save, `Group...`, `Up` and `Down`.
A preview pane shows the changes of the golden of the selected step. `Places` finds the place of a click (tree path, table cell,
list row) for the recorder and for replay. `CellEditTracker` watches the cell edits of a `JTable` and gives `SET_CELL`. Everything named
`smoker.*` is excluded from recording and snapshots.

## 7. Run matrix and environment facts

| Dimension | Note |
|---|---|
| JRE 8 vs 9+ | `--release 8`; no `sun.*` except the legacy EDT handler property; `Process.waitFor(timeout)` is Java 8 |
| Windows runner | runner process must be in an interactive session: `Robot` (screenshots) fails in Session 0; replay/snapshots do not need it |
| Linux runner | `xvfb-run -a`; Metal LAF; no window manager → OS focus is absent, `KEY` actions target the front window's last focus owner or root pane (default button / ESC bindings still fire) |
| macOS | works; `com.apple.laf.*` internals excluded from snapshots |
| HiDPI | irrelevant to the oracle; screenshots differ, by design not compared |

## 8. Roadmap

| Phase | Item | Req |
|---|---|---|
| 1 | Robot fallback for native `FileDialog`/OS prompts (`type: "ROBOT_CLICK"` with screen-relative coords) | R-31 |
| 1 | `approve --diff` interactive promotion in the HTML report (checkbox per difference → patch file) | R-33 |
| 1 | Matrix labels (`--label jre8-win`) merged into one report; run history JSON for flakiness | R-41, R-42 |
| 2 | Logback fragment bridge | R-15 |
| 2 | Hints API jar: `Smoker.markDynamic(component)`, `Smoker.busy()/idle()` for long async work | R-05 |
| 2 | Screenshot oracle with tolerance (opt-in per step) | R-24 |
| 3 | Recorder: inline rename, snapshot preview tree (grouping and the golden preview are done in WP-06) | R-26 |
