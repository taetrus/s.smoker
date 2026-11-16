# WP-03 Wait conditions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the action `WAIT_FOR` (player, recorder, demo) so that a scenario waits for a delayed answer instead of a fixed sleep (F-02, R-23a).

**Architecture:** `WAIT_FOR` is a new `Action.Type` with three new fields. A new class `smoker.agent.WaitCondition` reads one attribute of one component on the EDT and polls it from the player thread. `Player` runs it instead of `ActionExecutor`, and skips the snapshot of a step that a `WAIT_FOR` follows (owner decision B). The recorder gets a `Wait for…` button with a select mode that accepts only named components.

**Tech Stack:** Java 8 source level (`--release 8`), Swing, JUnit 4, Gson (relocated), Maven.

**Spec:** `docs/superpowers/specs/2026-11-16-wp-03-wait-conditions-design.md`

## Global Constraints

- Java 8 source: no `var`, no `List.of`, no `String.isBlank`, no `Files.readString`. Anonymous classes, as in the existing code.
- No new dependency.
- Every Swing access on the EDT. The wait never blocks the EDT. Never `invokeAndWait` without a time limit from the player thread.
- Case conversion only with `Locale.ROOT`.
- Windows named `smoker.*` are the tool's own UI: the new dialog is named `smoker.waitfor`.
- Never write the text of a `JPasswordField` anywhere (F-19), also not as the "last value".
- `schemaVersion` stays 2.
- Commit messages name the WP and the finding: `WP-03, F-02: …`. End each message with the attribution lines of the session.
- Documentation in Simplified Technical English. Format change → `README.md` in the same commit.
- After each change to `smoker-agent` or `smoker-core`: `mvn -B verify` and `mvn -B verify -Plocale-tr`.

## Review Focus

1. A `WAIT_FOR` whose component is in a modal dialog that the step before opened: the poll must still answer (the ping-style EDT task is served by the nested loop). Test in Task 3 (`waitsForAComponentInAModalDialog`).
2. The EDT is blocked during the wait: the player must give HANG, not FAIL or a stuck player. Test in Task 3 (`blockedEdtGivesHang`).
3. A scenario that ends with a step before nothing, or two `WAIT_FOR` steps in a row: only the step directly before a `WAIT_FOR` skips its snapshot; a `WAIT_FOR` before a `WAIT_FOR` skips too. Test in Task 3 (`skipsSnapshotOnlyBeforeWaitFor`).
4. A password field as the waited component: the last value in the FAIL message is `***` or empty, never the text. Test in Task 2 (`passwordFieldValueIsMasked`).
5. A regex with metacharacters in the current value (`ready (42)`, `1.5 V`): the recorder default regex must match the value literally and stay readable in JSON. Test in Task 5 (`literalRegexMatchesTheValueExactly`).

---

### Task 1: Format — `WAIT_FOR` in `Action`, README

**Files:**
- Modify: `smoker-core/src/main/java/smoker/core/model/Action.java`
- Test: `smoker-core/src/test/java/smoker/core/io/JsonTest.java`
- Modify: `README.md` (section "Scenario file", add subsection "Wait for a condition")

**Interfaces:**
- Produces: `Action.Type.WAIT_FOR`; fields `public String attribute; public String matches; public Long timeoutMs;`;
  `public static final long DEFAULT_WAIT_TIMEOUT_MS = 10_000;`;
  `public static Action waitFor(Locator l, String attribute, String matches, Long timeoutMs)`;
  `public long waitTimeoutMs()` → `timeoutMs` or the default.
  `toString()` for `WAIT_FOR`: `WAIT_FOR <locator> text ~ "ready .*"`.

- [ ] **Step 1: Write the failing tests** in `JsonTest`:
  - `waitForRoundTrips`: write `Action.waitFor(locatorNamed("demo.answer"), "text", "ready \\d+", 5000L)` inside a `Scenario`, read it back; assert type `WAIT_FOR`, attribute `text`, matches `ready \d+`, `timeoutMs` 5000.
  - `waitForWithoutTimeoutUsesTheDefault`: JSON `{"type":"WAIT_FOR","locator":{"name":"a"},"attribute":"text","matches":"x"}` → `waitTimeoutMs()` == 10000, `timeoutMs` null.
  - `oldActionHasNoWaitFields`: an existing `CLICK` JSON reads with `attribute`, `matches`, `timeoutMs` null, and writing it emits none of these keys.
- [ ] **Step 2: Run** `mvn -B -pl smoker-core test -Dtest=JsonTest` — expect compile failure (`WAIT_FOR` unknown).
- [ ] **Step 3: Implement** the Interfaces above in `Action.java`. Javadoc on `WAIT_FOR`: polls each 100 ms until `attribute` of the component matches `matches` (whole value), FAIL after `timeoutMs`.
- [ ] **Step 4: Run** the same command — PASS. Then `mvn -B -pl smoker-core test` — all pass.
- [ ] **Step 5: README.** Add "Wait for a condition": the JSON example of spec 6.1, the attribute list, whole-value match, default timeout, FAIL with last value, `<component not found>`, rule 6.4 (the step before a `WAIT_FOR` has no snapshot), "prefer `WAIT_FOR` to `WAIT`". Add `WAIT_FOR` to the action type list.
- [ ] **Step 6: Commit** `WP-03, F-02: action type WAIT_FOR in the scenario format`.

### Task 2: `WaitCondition` — read and poll one attribute

**Files:**
- Modify: `smoker-agent/src/main/java/smoker/agent/SnapshotTaker.java` (split `node`)
- Create: `smoker-agent/src/main/java/smoker/agent/WaitCondition.java`
- Test: `smoker-agent/src/test/java/smoker/agent/WaitConditionTest.java` (needs a display: `Assume.assumeFalse(GraphicsEnvironment.isHeadless())` as in `SnapshotTakerContentTest`; dispose all frames in `@After`)

**Interfaces:**
- Consumes: `Action` from Task 1; `Locators.find`, `Locators.titleOf`.
- Produces in `SnapshotTaker`: `static NodeSnapshot attributes(Component c, int maxRows)` — the attribute part of today's `node()` (type, visible, enabled, text, selected, counts, items, cells) with no lint, no path, no name side effects. `node()` calls it and keeps its lint code.
- Produces in `WaitCondition` (final class, package-private):
  - `static final List<String> ATTRIBUTES` = `text, title, visible, enabled, selected, rowCount, itemCount, selectedIndex`
  - `static final String NOT_FOUND = "<component not found>"`
  - `static final long POLL_MS = 100`
  - `static Pattern validate(Action a)` — throws `IllegalArgumentException` with a clear message for a missing locator, an attribute not in `ATTRIBUTES`, a null or invalid `matches`; returns the compiled pattern.
  - `static String valueOf(Component c, String attribute)` — EDT only. `title` → `Locators.titleOf(window)` for a `Window`, else null. `visible`/`enabled` null → `"true"`. Other values → `String.valueOf`, null stays null.
  - `static String read(Locator l, String attribute)` — EDT only; `NOT_FOUND` if `Locators.find` gives null.
  - `static boolean matches(Pattern p, String value)` — false for null and for `NOT_FOUND`; else `p.matcher(value).matches()`.
  - `static final class Outcome { final Kind kind; final String lastValue; }` with `enum Kind { MATCHED, TIMEOUT, HANG }`.
  - `static Outcome await(Action a, Pattern p, java.util.function.BooleanSupplier hangDetected)` — player thread only. Loop until `a.waitTimeoutMs()` passes: post a `FutureTask` that calls `read` with `invokeLater`, `get` it with a limit of 1000 ms (as `Player.mainWindowShown`); on match → MATCHED; if the task timed out and `hangDetected` is true → HANG; sleep `POLL_MS`. On timeout → TIMEOUT with the last value read (`null` text if never read).
  - `static String timeoutMessage(Action a, String lastValue)` → `WAIT_FOR <locator> <attribute> did not match "<matches>" within <ms> ms; last value: "<value>"` (`null` without quotes).

- [ ] **Step 1: Write the failing tests** in `WaitConditionTest`:
  - `validateRejectsUnknownAttribute` (`"items"` → IAE naming the allowed list), `validateRejectsInvalidRegex` (`"("`), `validateRejectsMissingLocator`. (No display needed: put these in `WaitConditionValidateTest` so they run headless.)
  - `matchesNeedsTheWholeValue`: `matches(compile("ready"), "ready 42")` false; `matches(compile("ready \\d+"), "ready 42")` true; null false; `NOT_FOUND` false with `compile(".*")`.
  - `valueOfGivesTrueForVisibleAndEnabled`: enabled visible `JLabel` → `"true"`, `"true"`; disabled → `"false"`.
  - `valueOfGivesCountsAsText`: `JComboBox` of 3 items, index 1 → `itemCount` `"3"`, `selectedIndex` `"1"`, `text` = item text.
  - `passwordFieldValueIsMasked`: `JPasswordField("secret")` → `valueOf(...,"text")` is not `"secret"` and equals the snapshot text of the same field.
  - `readGivesNotFoundForAMissingName`.
  - `awaitMatchesWhenTheLabelChanges`: frame with `JLabel` named `t.answer` text `waiting…`; a `javax.swing.Timer` sets `ready 42` after 500 ms; `await(waitFor(name t.answer, text, "ready \\d+", 3000L), …, () -> false)` (anonymous class) → MATCHED within 2000 ms.
  - `awaitTimesOutWithTheLastValue`: label stays `waiting…`, timeout 300 → TIMEOUT, `lastValue` `waiting…`; `timeoutMessage` contains `last value: "waiting…"` and `within 300 ms`.
- [ ] **Step 2: Run** `mvn -B -pl smoker-core,smoker-agent -am test -Dtest='WaitCondition*' -DfailIfNoTests=false` — expect compile failure.
- [ ] **Step 3: Split** `SnapshotTaker.node` into `attributes` + lint part. Run `mvn -B -pl smoker-agent -am test -Dtest='SnapshotTaker*' -DfailIfNoTests=false` — all existing snapshot tests still pass.
- [ ] **Step 4: Implement** `WaitCondition` per the Interfaces.
- [ ] **Step 5: Run** the Step 2 command — PASS.
- [ ] **Step 6: Commit** `WP-03, F-02: WaitCondition reads and polls one attribute`.

### Task 3: Player — run `WAIT_FOR`, skip the snapshot before it, CLI time limit

**Files:**
- Modify: `smoker-agent/src/main/java/smoker/agent/Player.java` (`runStep`, loop in `run`)
- Modify: `smoker-agent/src/main/java/smoker/agent/ActionExecutor.java` (`case WAIT_FOR`: throw `IllegalStateException("WAIT_FOR runs in the player")`)
- Modify: `smoker-cli/src/main/java/smoker/cli/Main.java:88`
- Test: `smoker-agent/src/test/java/smoker/agent/PlayerTest.java`, `smoker-agent/src/test/java/smoker/agent/PlayerWaitForTest.java` (display), `smoker-cli/src/test/java/smoker/cli/MainTest.java` (or the existing CLI test class that tests `Main`)

**Interfaces:**
- Consumes: `WaitCondition.validate/await/timeoutMessage`, `Outcome`.
- Produces: `static boolean skipsSnapshot(Scenario s, int index)` in `Player` — true when `index + 1 < steps.size()` and step `index + 1` is a `WAIT_FOR`.
  `runStep(Step step, boolean takeSnapshot)` replaces `runStep(Step)`.
  `static boolean runWaitFor(Action a, RunResult.StepResult sr, java.util.function.BooleanSupplier hang)` in `Player` — package-private, the test seam of `PlayerWaitForTest`. It sets `sr.status`, `sr.error` and `sr.differences`, and returns true when the step continues to settle and snapshot (MATCHED or TIMEOUT), false on HANG or ERROR. `runStep` calls it with `watchdog.hangDetected()`; on HANG, `runStep` adds the thread dump through `hang(...)`.
  `static long budgetMs(Scenario s)` in `Main` — today's formula plus the sum of `waitTimeoutMs()` of all `WAIT_FOR` steps.

Behaviour of `runStep` for `WAIT_FOR` (spec 6.3): `validate` (IAE → ERROR, `sr.error` = message); `await`; MATCHED → continue; TIMEOUT → `sr.status = FAIL`, `sr.differences.add(timeoutMessage(...))`, continue to settle/snapshot (a FAIL stays FAIL after the compare); HANG → `hang(sr, t0, "WAIT_FOR: EDT blocked")`. Then the normal settle, snapshot, compare, log hits. With `takeSnapshot == false`: run the action and settle, then `finish` — no `actual`, no compare, status PASS unless error or log hits; log `step sNNN: no snapshot, the next step is WAIT_FOR`.

- [ ] **Step 1: Write the failing tests:**
  - `PlayerTest.skipsSnapshotOnlyBeforeWaitFor`: steps `CLICK, WAIT_FOR, CLICK, WAIT_FOR, WAIT_FOR, CLICK` (indexes 0–5) → `skipsSnapshot` true at 0, 2 and 3; false at 1, 4 and 5.
  - `PlayerWaitForTest.matchedContinues`, `timeoutGivesFailWithLastValue` (`sr.status == FAIL`, `sr.differences` contains `last value: "waiting…"`), `invalidRegexGivesError`, `blockedEdtGivesHang` (block the EDT with `invokeLater(sleep 3000)`, timeout 2000, hang supplier true → returns false, status HANG), `waitsForAComponentInAModalDialog` (a non-blocking `invokeLater` opens a modal `JDialog` with label `t.dlg` that a `Timer` changes; MATCHED).
  - `MainTest.budgetAddsWaitForTimeouts`: scenario with defaults and one `WAIT_FOR` of 20000 ms → `budgetMs` = old formula + 20000.
- [ ] **Step 2: Run** `mvn -B test -Dtest='PlayerTest,PlayerWaitForTest,MainTest' -DfailIfNoTests=false` — expect compile failures.
- [ ] **Step 3: Implement** the Interfaces and behaviour above. In `run()` use an index loop and pass `!skipsSnapshot(scenario, i)`.
- [ ] **Step 4: Run** the Step 2 command — PASS. Then `mvn -B package` — all unit tests pass.
- [ ] **Step 5: Commit** `WP-03, F-02: player runs WAIT_FOR; no snapshot before a WAIT_FOR`.

### Task 4: Demo delayed answer, `wait.json`, IT (completion criterion)

**Files:**
- Modify: `smoker-demo/src/main/java/smoker/demo/DemoApp.java`
- Create: `smoker-demo/scenarios/wait.json`, `smoker-demo/scenarios-failing/wait-timeout.json`
- Modify: `smoker-it/src/test/java/smoker/it/SelfTestIT.java`
- Modify: `docs/architecture.md` (one line on `WaitCondition` in the player)

**Interfaces:**
- Consumes: the format from Task 1, the player from Task 3.
- Demo: a new `JPanel` row added **below** the notes row (so existing paths do not change), with `JButton` `demo.query` ("Query") and `JLabel` `demo.answer` (initial `-`). Click: label `waiting…`, then a daemon `Thread` sleeps 3000 ms and sets `ready 42` with `invokeLater` and logs `LOG.info("answer ready")`.
- `wait.json`: `quietMs` 300, `stepTimeoutMs` 10000; s001 `CLICK demo.query`; s002 `WAIT_FOR demo.answer text "ready \\d+"` `timeoutMs` 10000.
- `wait-timeout.json`: the same, `timeoutMs` 1000.

- [ ] **Step 1: Write the failing IT** `waitForPassesOnADelayedAnswer` in `SelfTestIT`: run1 `wait.json` → NO_GOLDEN for s002, s001 PASS with `actual == null`; `approve` → output contains `approved 1 step(s)`, golden keys `[s002]`; run2 → PASS; s002 `durationMs` ≥ 2500 (the wait is real); the golden of s002 contains `ready 42`. Extend `failingScenariosGive…`: copy `wait-timeout.json` too; result `wait-timeout` s002 FAIL, `differences` contains `last value: "waiting…"`.
- [ ] **Step 2: Run** `mvn -B verify -pl smoker-it -am -Dit.test=SelfTestIT` — expect FAIL (no `demo.query`).
- [ ] **Step 3: Implement** the demo change and the two scenarios.
- [ ] **Step 4: Run** the full `mvn -B verify`, then `mvn -B verify -Plocale-tr` — all pass, the existing goldens unchanged (`git status smoker-demo/scenarios/*.golden.json` clean), `table.golden.json` still under 150 lines.
- [ ] **Step 5: Check the criterion:** `grep -rl '"WAIT"' docs/wp-01-pilot` gives nothing.
- [ ] **Step 6: Commit** `WP-03, F-02: demo delayed answer and wait.json (R-23a)`.

### Task 5: Recorder — `Wait for…`

**Files:**
- Modify: `smoker-agent/src/main/java/smoker/agent/Recorder.java`
- Modify: `smoker-agent/src/main/java/smoker/agent/RecorderToolbar.java`
- Test: `smoker-agent/src/test/java/smoker/agent/RecorderToolbarTest.java` (headless helpers), `smoker-agent/src/test/java/smoker/agent/RecorderWindowTest.java` (display)

**Interfaces:**
- Consumes: `Action.waitFor`, `WaitCondition.validate/read/await`, `Locators.resolve`, `NameProposer`.
- Produces in `Recorder`:
  - `void addWaitFor(Action a)` — on the EDT: removes the snapshot and the pending entry of the current last step (spec 6.4), then adds `a` as a new step like `add`.
  - `snapshotLoop`: for a pending `WAIT_FOR` step, first `WaitCondition.await` (hang supplier: `false`), then the quiescence and snapshot as now. On TIMEOUT, say `WAIT_FOR did not match: <message>` and still store the snapshot.
- Produces in `RecorderToolbar`:
  - `static String refusal(Locator l)` — null when `l.isNamed()`; else `"needs a name: " + <proposal>`; the toolbar fills the proposal from `NameProposer` for the component.
  - `static String literalRegex(String value)` — backslash before each of `\ . [ ] { } ( ) * + ? ^ $ |`; null → `""`.
  - A `JButton` `Wait for…`. Select mode: a transparent glass pane (mouse listener, `setOpaque(false)`) on each showing application `RootPaneContainer` window (not `smoker.*`); remember and restore the old glass panes and their visibility. Pause the recorder during select mode and restore the earlier recording state after. ESC (key binding on the glass pane) or a second click on the button cancels. Hover highlight as in `onHover`. A click: deepest component under the point (`SwingUtilities.getDeepestComponentAt` on the content pane), `Locators.resolve`; refused → status line, stay in select mode; accepted → leave select mode, show the dialog.
  - Dialog `smoker.waitfor` (a `JDialog` named `smoker.waitfor`): locator text, attribute `JComboBox` (`WaitCondition.ATTRIBUTES`, default `text`), regex `JTextField` (default `literalRegex(WaitCondition.read(l, "text"))`, refreshed when the attribute changes), timeout `JTextField` (default `10000`). OK validates with `WaitCondition.validate`; an error shows in the dialog and keeps it open. OK → `recorder.addWaitFor(...)`.

- [ ] **Step 1: Write the failing tests:**
  - `RecorderToolbarTest.literalRegexMatchesTheValueExactly`: for `ready (42)`, `1.5 V`, `a|b`, `x\y`, `[*]`: `Pattern.compile(literalRegex(v)).matcher(v).matches()` true and `literalRegex("1.5 V")` equals `1\.5 V`; `literalRegex(null)` equals `""`.
  - `RecorderToolbarTest.refusesAComponentWithoutAName`: unnamed locator → message starts with `needs a name`; named locator and automatic `@Foo.bar` locator → null.
  - `RecorderWindowTest.waitForRemovesTheSnapshotOfTheStepBefore`: record a click (existing helpers), wait until `hasSnapshot`; `addWaitFor(waitFor(named label, text, current text quoted, 2000L))` on the EDT → previous step `hasSnapshot` false; the `WAIT_FOR` step gets a snapshot within 3000 ms; `save()` writes a golden without the click step.
  - `RecorderWindowTest.waitForSnapshotWaitsForTheCondition`: label `waiting…`, `addWaitFor` with `ready \d+`, a `Timer` sets `ready 1` after 800 ms → the stored snapshot of the `WAIT_FOR` step has the label text `ready 1`.
- [ ] **Step 2: Run** `mvn -B -pl smoker-agent -am test -Dtest='Recorder*' -DfailIfNoTests=false` — expect compile failures.
- [ ] **Step 3: Implement** `Recorder` changes, then the toolbar helpers, then the button, select mode and dialog.
- [ ] **Step 4: Run** the Step 2 command — PASS. Then `mvn -B verify` and `mvn -B verify -Plocale-tr` — all pass.
- [ ] **Step 5: Manual check** (macOS desktop, do not type into other demo windows): start the demo in record mode (`README.md`, "Use"), click Query, wait for `ready 42`, `Wait for…`, click the unnamed button "I have no name" → status `needs a name: …`; click `demo.answer` → dialog with regex `ready 42`; edit to `ready \d+`; OK; Save. Replay the saved scenario twice → NO_GOLDEN for nothing but new steps, then PASS after `approve`. Note the result in the commit message.
- [ ] **Step 6: Commit** `WP-03, F-02: recorder button Wait for… with a select mode for named components`.

### Task 6: Close the work package

**Files:** `docs/development-plan.md` (WP-03 state, F-02 → fixed on the demo, still untested on a simulator product), `CLAUDE.md` ("State" section: WP-03 on its branch, test counts; "Commands" section: the `wait` and `wait-timeout` expectations of `SelfTestIT`).

- [ ] **Step 1: Run** `mvn -B verify` and `mvn -B verify -Plocale-tr`; write the test counts from the Surefire/Failsafe summaries.
- [ ] **Step 2: Update** the two documents. Keep the owner's uncommitted edits in `CLAUDE.md`.
- [ ] **Step 3: Commit** `WP-03, F-02: state after WP-03`.
- [ ] **Step 4:** Ask the owner for the Windows pilot run (the plan requires Windows for smoke tests) before merge.
