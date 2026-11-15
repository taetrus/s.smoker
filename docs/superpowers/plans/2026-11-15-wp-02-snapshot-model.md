# WP-02 Snapshot Model Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Goldens in a separate file as first snapshot plus changes, with stable anchored paths, table/list/tree content, title rules, schema versions, duplicate-name lint, and correct goldens (F-05, F-06, F-07, F-17, F-21).

**Architecture:** A snapshot becomes a flat list of compared components, sorted by path. `smoker-core` owns the model, the golden file codec (full first snapshot plus change lists), file I/O with version checks, and the diff. `smoker-agent` builds the flat snapshot from Swing (anchored paths, title keys, content) and writes/reads goldens through core. `smoker-cli approve` rewrites the golden file from a run.

**Tech Stack:** Java 8 (`--release 8`), Swing, Gson (relocated), JUnit 4.13.2, Maven Surefire/Failsafe 3.2.5.

**Spec:** `docs/superpowers/specs/2026-11-15-wp-02-snapshot-model-design.md`

## Global Constraints

- Java 8 source level: no `var`, `List.of`, `String.isBlank`, `Files.readString`.
- No new runtime dependency. Gson only, relocated to `smoker.shaded.gson` in the agent and CLI jars.
- Every Swing access on the EDT. Never wait on an action `Runnable` directly (`Quiescence.edtResponds`).
- `Locale.ROOT` for all case conversion and `String.format`.
- Windows named `smoker.*` are excluded from snapshots and recording.
- `schemaVersion` of the new scenario and golden files: `2`. The old format counts as version 1 and is refused.
- `maxRows` default: `20`. `compare` values: `all` (default) and `named`.
- Golden file name: `<scenario name without .json>.golden.json`, in the folder of the scenario file.
- Password fields stay masked: `***` or `""` (F-19).
- After each task: `mvn -B verify` and `mvn -B verify -Plocale-tr` pass. Commit messages name WP-02 and the finding IDs.
- A commit that changes the scenario or golden format also updates `README.md` and the demo scenarios.

## Review Focus

1. A scenario gets a new step that the golden file does not have: that step gives `NO_GOLDEN`, the other steps are still compared. (Task 5)
2. The golden file has a step that the scenario no longer has: it is ignored by `run`, and `approve` removes it. (Task 5)
3. Two windows with the same title are open at the same time (two error dialogs): their paths get `[Error]` and `[Error][1]`, and nothing overwrites. (Task 2)
4. A table with more rows than `maxRows`: only the first 20 rows go into the snapshot. A changed value in row 25 gives no difference; a changed row count gives one `.rowCount` line. (Task 3)
5. A window title that changes and matches a `windowKeys` pattern: `CLOSE_WINDOW` and unnamed locators still find the window. (Task 4)

---

## File structure

| File | Responsibility | Task |
|---|---|---|
| `smoker-core/.../model/NodeSnapshot.java` | One compared component. Remove `children`. Add `items`, `cells`. | 2, 3 |
| `smoker-core/.../model/Snapshot.java` | `List<NodeSnapshot> nodes`, sorted by path. Replaces `windows`. | 2 |
| `smoker-core/.../model/Scenario.java` | Add `schemaVersion`, `compare`, `maxRows`, `windowKeys`. | 4, 5 |
| `smoker-core/.../model/WindowKey.java` (new) | `pattern`, `key`. | 4 |
| `smoker-core/.../model/Step.java` | Remove `expected`. | 5 |
| `smoker-core/.../model/Golden.java` (new) | Golden file model. | 1 |
| `smoker-core/.../golden/GoldenCodec.java` (new) | Snapshots by step ⇄ `Golden`. | 1 |
| `smoker-core/.../io/ScenarioFiles.java` (new) | Read scenario and golden with version checks, golden path. | 5 |
| `smoker-core/.../diff/SnapshotDiff.java` | Diff of flat snapshots, items, cells. | 2, 3 |
| `smoker-core/.../model/RunResult.java` | Add `compare`, `duplicateNames`. | 7 |
| `smoker-core/.../report/HtmlReportWriter.java` | Mode, duplicate names. | 7 |
| `smoker-agent/.../WindowKeys.java` (new) | Title → key. | 4 |
| `smoker-agent/.../SnapshotTaker.java` | Flat snapshot, anchored paths, content, duplicate names. | 2, 3, 4, 7 |
| `smoker-agent/.../Locators.java`, `ActionExecutor.java`, `RecorderToolbar.java` | Use window keys. | 4 |
| `smoker-agent/.../Player.java`, `SmokerAgent.java` | Golden from file; continue after FAIL. | 5, 6 |
| `smoker-agent/.../Recorder.java` | Snapshots in a map; synchronous snapshot on next input; save golden. | 5, 6 |
| `smoker-cli/.../Main.java` | `approve` writes the golden file. | 5 |
| `smoker-demo/...` | `demo.extraPanel`, `demo.badCell`, 10-step scenario. | 8 |
| `smoker-it/.../SelfTestIT.java` | Completion criterion. | 8 |

---

### Task 1: Golden file model and codec (core, additive)

**Files:**
- Create: `smoker-core/src/main/java/smoker/core/model/Golden.java`
- Create: `smoker-core/src/main/java/smoker/core/golden/GoldenCodec.java`
- Test: `smoker-core/src/test/java/smoker/core/golden/GoldenCodecTest.java`

**Interfaces:**
- Produces:
  - `class Golden { int schemaVersion = 2; String scenario; List<GoldenStep> steps; }`
  - `class Golden.GoldenStep { String id; List<NodeSnapshot> full; List<Change> changes; }` — exactly one of `full` (first step only) and `changes` is non-null.
  - `class Golden.Change { String path; String op; NodeSnapshot node; }` — `op` is `"set"` (node is the full new state, also for an added component) or `"remove"` (node is null).
  - `GoldenCodec.encode(String scenario, LinkedHashMap<String, Snapshot> byStep) -> Golden`
  - `GoldenCodec.decode(Golden g) -> LinkedHashMap<String, Snapshot>`
- This task works on the current `Snapshot`/`NodeSnapshot`. It reads nodes through `SnapshotDiff.flatten` so it does not depend on the tree; Task 2 replaces that with the flat list.

- [ ] **Step 1: Write the failing tests**

```java
@Test public void firstStepIsFullLaterStepsAreChanges()      // 3 steps: s001 full, s002 one text change, s003 equal to s002
    // assert g.steps.get(0).full.size() == 2, g.steps.get(1).changes.size() == 1 with op "set",
    // g.steps.get(2).changes.isEmpty()
@Test public void decodeGivesBackTheSameSnapshots()          // encode then decode, compare Json.toString per step
@Test public void removedAndAddedComponentsAreChanges()      // s002 removes "[W]/#a", adds "[W]/#b": ops "remove" and "set"
@Test public void changesAreSortedByPath()                   // paths in a change list are in ascending order
@Test public void emptyMapGivesNoSteps()
```

- [ ] **Step 2: Run** `mvn -B -q -pl smoker-core test -Dtest=GoldenCodecTest` — Expected: compile failure (classes missing).
- [ ] **Step 3: Implement** `Golden` and `GoldenCodec`. Node equality: compare `Json.toString(node)` of the two nodes (Gson writes fields in declaration order). Full snapshot and changes sorted by `path` (`String.compareTo`).
- [ ] **Step 4: Run** the tests — Expected: PASS. Then `mvn -B verify` — Expected: BUILD SUCCESS.
- [ ] **Step 5: Commit** `WP-02 (F-06): golden file model and codec`.

---

### Task 2: Flat snapshot with anchored paths

**Files:**
- Modify: `smoker-core/.../model/Snapshot.java`, `NodeSnapshot.java`, `diff/SnapshotDiff.java`, `golden/GoldenCodec.java`
- Modify: `smoker-agent/.../SnapshotTaker.java`
- Test: `smoker-core/.../diff/SnapshotDiffTest.java` (rewrite for flat nodes), `smoker-agent/.../SnapshotTakerPathTest.java` (new, needs a display)

**Interfaces:**
- Produces:
  - `Snapshot { List<NodeSnapshot> nodes; }` sorted by path. `NodeSnapshot.children` removed.
  - `SnapshotDiff.diff(Snapshot expected, Snapshot actual, IgnoreRules rules) -> List<String>` (same signature, flat input). `SnapshotDiff.byPath(Snapshot) -> LinkedHashMap<String, NodeSnapshot>` replaces `flatten`.
  - `SnapshotTaker.take() -> Snapshot` (same name). Path rules in the spec, section 4.2.
  - `static boolean SnapshotTaker.isLayoutContainer(Component c)` — unnamed instance of `JPanel`, `JScrollPane`, `JViewport`, `JRootPane`, `JLayeredPane`, `Box`, `JSplitPane`, `JToolBar`, or `lafInternal(c)`.
  - Window part, this task: `[#<name>]` if the window has an application name, else `[<title>]`; a repeated part gets `[1]`, `[2]` in `Window.getWindows()` order. Task 4 replaces the title with the key.
  - Repeated name in one window: `#<name>[1]`, `#<name>[2]` in tree order.

- [ ] **Step 1: Write the failing tests** (`SnapshotTakerPathTest`, EDT pattern of `LocatorsWindowTest`)

```java
@Test public void layoutContainersAreNotInPaths()            // frame "Orders" > JPanel > JPanel > JTextField: path "[Orders]/JTextField[0]"
@Test public void newUnnamedPanelGivesNoDifference()         // take, wrap the field in one more JPanel, take: SnapshotDiff.diff(...) is empty (R-30a)
@Test public void namedAncestorIsTheAnchor()                 // JPanel named "orders.form" > JButton: "[Orders]/#orders.form/JButton[0]"
@Test public void namedWindowUsesItsName()                   // frame name "orders.frame": "[#orders.frame]/..."; title change gives only "CHANGED [#orders.frame] .title"
@Test public void sameTitleTwiceGetsAnIndex()                // two dialogs "Error": "[Error]" and "[Error][1]"   (Review Focus 3)
@Test public void repeatedNameGetsAnIndex()                  // two buttons named "x": "[Orders]/#x" and "[Orders]/#x[1]"
@Test public void applicationSubclassOfJPanelIsALayoutContainer()   // class ChatPanel extends JPanel, unnamed: not in path
```
Core: rewrite `SnapshotDiffTest` for `Snapshot.nodes` with the same cases as today (equal, CHANGED, MISSING, UNEXPECTED, ignore rules, value patterns).

- [ ] **Step 2: Run** `mvn -B -q -pl smoker-core,smoker-agent -am test` — Expected: FAIL (compile errors on `children`, new tests fail).
- [ ] **Step 3: Implement.** `SnapshotTaker` walks the tree, keeps a per-anchor class counter `Map<String anchorPath, Map<String class, Integer>>`, adds only non-layout-container nodes, sorts by path at the end. Window node: path = window part, `title` set. Update `GoldenCodec` to use `nodes`. Update every other use of `windows`/`children` (`grep -rn "\.windows\|children" smoker-*/src`).
- [ ] **Step 4: Run** `mvn -B verify` and `mvn -B verify -Plocale-tr` — Expected: BUILD SUCCESS. `SelfTestIT` still passes: its run3 assertion becomes `"CHANGED [#demo.frame]/#demo.lang .text: expected=\"tr\" actual=\"de\""` (update the assertion; `demo.frame` is the frame name in `DemoApp`).
- [ ] **Step 5: Commit** `WP-02 (F-05): flat snapshot with anchored paths`.

---

### Task 3: Content of tables, lists and trees

**Files:**
- Modify: `smoker-core/.../model/NodeSnapshot.java`, `diff/SnapshotDiff.java`, `smoker-agent/.../SnapshotTaker.java`, `smoker-core/.../model/Scenario.java`
- Test: `SnapshotDiffTest`, `smoker-agent/.../SnapshotTakerContentTest.java` (new, display)

**Interfaces:**
- Produces:
  - `NodeSnapshot.items: List<String>` — list item texts; tree rows as `"  " × depth + label`.
  - `NodeSnapshot.cells: List<List<String>>` — table rows in view order, cell texts.
  - `Scenario.maxRows: int = 20`.
  - `SnapshotTaker(int maxRows)` constructor; the existing no-arg use passes `scenario.maxRows`.
  - Diff lines: `CHANGED <path> .items[<i>]: expected=… actual=…`, `CHANGED <path> .cells[<row>][<col>]: expected=… actual=…`; a different list length gives `CHANGED <path> .items: expected=<n> rows actual=<m> rows` once, then per-index lines for the common part.
- Cell text: renderer component text when it is a `JLabel`, else `String.valueOf(value)`. Must run on the EDT.
- Combo box: unchanged (selected item, `itemCount`).

- [ ] **Step 1: Write the failing tests**

```java
// SnapshotTakerContentTest
@Test public void tableGivesCellTextOfTheFirstRows()       // 2x2 table: cells == [["Kerem","tr"],["Ana","en"]], rowCount 2
@Test public void onlyMaxRowsRowsAreTaken()                // 25 rows, maxRows 20: cells.size()==20, rowCount 25   (Review Focus 4)
@Test public void listGivesItems()                         // JList ["a","b"]: items == ["a","b"]
@Test public void treeGivesVisibleRowsWithDepth()          // root "r" expanded with child "c": items == ["r","  c"]
// SnapshotDiffTest
@Test public void wrongCellInRowOneGivesOneLine()          // cells[0][1] "tr" -> "de": "CHANGED p .cells[0][1]: expected=\"tr\" actual=\"de\""  (R-23b)
@Test public void shorterListGivesALengthLine()
```

- [ ] **Step 2: Run** the agent and core tests — Expected: FAIL.
- [ ] **Step 3: Implement** in `SnapshotTaker.node(...)` and `SnapshotDiff`. Value patterns of `IgnoreRules` apply to items and cells as they do to `text`.
- [ ] **Step 4: Run** `mvn -B verify` and `-Plocale-tr` — Expected: BUILD SUCCESS.
- [ ] **Step 5: Commit** `WP-02 (F-07): cell text, list items and tree rows in snapshots`.

---

### Task 4: Title rules (window keys) in paths and locators

**Files:**
- Create: `smoker-core/.../model/WindowKey.java`, `smoker-agent/.../WindowKeys.java`
- Modify: `Scenario.java` (add `List<WindowKey> windowKeys`), `SnapshotTaker.java`, `Locators.java`, `ActionExecutor.java:156`, `RecorderToolbar.java:175`, `SmokerAgent.java`
- Test: `smoker-agent/.../WindowKeysTest.java` (headless), `LocatorsWindowTest` (add cases)

**Interfaces:**
- Produces:
  - `class WindowKey { String pattern; String key; }`
  - `final class WindowKeys { WindowKeys(List<WindowKey> rules); String keyOf(String title); String keyOf(Window w); }` — first pattern that matches the whole title (`Matcher.matches`) gives the key; no match gives the title; null title stays null.
  - `static void Locators.useWindowKeys(WindowKeys k)` (default: no rules). `Locators.resolve` writes `keyOf(window)` into `windowTitle`; `Locators.find` and `ActionExecutor` `CLOSE_WINDOW` compare `keyOf(window)`.
  - `SnapshotTaker` window part for an unnamed window: `[<keyOf(window)>]`.
  - `SmokerAgent` calls `Locators.useWindowKeys(new WindowKeys(scenario.windowKeys))` before play or record.

- [ ] **Step 1: Write the failing tests**

```java
// WindowKeysTest
@Test public void firstMatchingPatternGivesTheKey()        // [{"Editor - .*","Editor"}]: keyOf("Editor - a.txt") == "Editor"
@Test public void patternMustMatchTheWholeTitle()          // keyOf("My Editor - a") == "My Editor - a"
@Test public void noRulesGiveTheTitle()
// LocatorsWindowTest
@Test public void unnamedLocatorFindsTheWindowAfterATitleChange()   // rule "Editor - .*"->"Editor"; resolve in "Editor - a.txt", setTitle("Editor - b.txt"), find == same button  (Review Focus 5)
// SnapshotTakerPathTest
@Test public void changingTitleWithARuleKeepsThePaths()    // only "CHANGED [Editor] .title" in the diff, no MISSING/UNEXPECTED
```

- [ ] **Step 2: Run** — Expected: FAIL.
- [ ] **Step 3: Implement.** Reset `Locators.useWindowKeys(null)` in test `@After` methods.
- [ ] **Step 4: Run** `mvn -B verify` and `-Plocale-tr` — Expected: BUILD SUCCESS.
- [ ] **Step 5: Commit** `WP-02 (F-05): title rules for windows in paths and locators`.

---

### Task 5: Separate golden file, schema version, approve (format change)

**Files:**
- Create: `smoker-core/.../io/ScenarioFiles.java`
- Modify: `Scenario.java` (`schemaVersion = 2`, `compare = "all"`), `Step.java` (remove `expected`), `SmokerAgent.java`, `Player.java`, `Recorder.java`, `RecorderToolbar.java:119`, `smoker-cli/.../Main.java`
- Modify (conversion): `smoker-demo/scenarios/greet.json`, `smoker-demo/scenarios-failing/*.json`, `docs/wp-01-pilot/{pilot,hang,crash}.json`, `README.md`, `docs/wp-01-handoff.md`
- Test: `smoker-core/.../io/ScenarioFilesTest.java`, `smoker-it/.../SelfTestIT.java`

**Interfaces:**
- Produces:
  - `ScenarioFiles.readScenario(Path) -> Scenario` — throws `IllegalArgumentException` with the text `"<file>: old scenario format (schemaVersion 1). Convert it: see README, section Scenario format."` when `schemaVersion` is missing or 1, or when a step has `expected`; and `"<file>: schemaVersion <n> is newer than this s.smoker (2)."` when it is above 2. Detect `expected` on the JSON tree before mapping.
  - `ScenarioFiles.goldenPath(Path scenario) -> Path`
  - `ScenarioFiles.readGolden(Path scenario) -> LinkedHashMap<String, Snapshot>` — empty map when the file does not exist; same version checks.
  - `ScenarioFiles.writeGolden(Path scenario, String name, LinkedHashMap<String, Snapshot> byStep)`
  - `Player` compares step `s` with `golden.get(s.id)`; no entry gives `NO_GOLDEN`.
  - `Recorder` keeps `LinkedHashMap<String, Snapshot> snapshots`; `boolean hasSnapshot(Step)`; `save()` writes scenario and golden.
  - `approve`: reads run result, scenario and existing golden; replaces the steps of the run (all, or `--steps`); drops golden steps that the scenario does not have; writes the golden; prints `approved <n> step(s) into <golden file>` and one line per changed step `  <id>: <k> component(s) changed`.

- [ ] **Step 1: Write the failing tests**

```java
// ScenarioFilesTest
@Test public void oldFormatIsRefused()                     // file with "expected" in a step: message contains "old scenario format"
@Test public void missingSchemaVersionIsRefused()
@Test public void newerSchemaVersionIsRefused()            // 3: message contains "newer than this s.smoker (2)"
@Test public void missingGoldenFileGivesAnEmptyMap()
@Test public void goldenSurvivesWriteAndRead()
@Test public void goldenPathIsBesideTheScenario()          // dir/greet.json -> dir/greet.golden.json
// SelfTestIT (extend the greet cycle)
//   after approve: greet.golden.json exists, greet.json contains no "expected"
//   add a step "s006" (KEY ESCAPE) to the copied scenario, run: s006 NO_GOLDEN, others PASS        (Review Focus 1)
//   remove step "s005" from the copied scenario, approve: golden has no "s005"                      (Review Focus 2)
//   approve --steps s002 after run3: golden changes only s002
```

- [ ] **Step 2: Run** `mvn -B verify` — Expected: FAIL.
- [ ] **Step 3: Implement**, then convert the files: add `"schemaVersion": 2`, remove each `expected`. In `pilot.json` set the two `ignore.paths` to `^\[OSGi Chatbot\]/JComboBox\[0\]$` and `^\[OSGi Chatbot\]/JTextPane\[0\]$`. Update `README.md` (scenario format, golden file, `approve`, `compare`, `maxRows`, `windowKeys`). Add to `docs/wp-01-handoff.md` the Windows task: run `pilot.json`, `hang.json`, `crash.json`, `approve`, run again, expect PASS, commit the goldens.
- [ ] **Step 4: Run** `mvn -B verify` and `-Plocale-tr` — Expected: BUILD SUCCESS.
- [ ] **Step 5: Commit** `WP-02 (F-06, F-17): golden file apart from the scenario, schemaVersion 2`.

---

### Task 6: Correct goldens (F-21)

**Files:**
- Modify: `smoker-agent/.../Player.java`, `Recorder.java`, `README.md` (section Statuses)
- Test: `smoker-agent/.../RecorderWindowTest.java` (add), `smoker-it/.../SelfTestIT.java`

**Interfaces:**
- Consumes: `Recorder.snapshots`, `Recorder.hasSnapshot(Step)` from Task 5.
- Produces:
  - `Player`: after a step with status `FAIL` the loop continues; after `HANG` or `ERROR` it stops. Scenario status: the worst status of the steps, order `ERROR` > `HANG` > `FAIL` > `NO_GOLDEN` > `PASS`.
  - `Recorder`: on `MOUSE_PRESSED`, `KEY_PRESSED` and `FOCUS_GAINED` of a non-smoker component, when the newest step has no snapshot, take it at once on the EDT (the AWT listener already runs on the EDT) and remove it from `pendingSnapshots`. The background loop skips steps that already have a snapshot.

- [ ] **Step 1: Write the failing tests**

```java
// RecorderWindowTest
@Test public void goldenOfATextStepDoesNotShowTheNextClick()   // type into field, press button that sets label "done" at once:
                                                               // snapshot of the SET_TEXT step has label text != "done"
// SelfTestIT, run3 of the greet cycle:
//   status FAIL, and steps after s002 are also in the result (no stop at s002)
//   one approve of run3, then run: PASS                         (one approve corrects all steps)
// SelfTestIT, failing scenarios: empty-name s002 now runs (the player continues after the log hit of s001)
```

- [ ] **Step 2: Run** — Expected: FAIL.
- [ ] **Step 3: Implement.** Update the README sentence "A scenario stops at its first non-PASS step" to: continues after FAIL, stops after HANG and ERROR.
- [ ] **Step 4: Run** `mvn -B verify` and `-Plocale-tr` — Expected: BUILD SUCCESS.
- [ ] **Step 5: Commit** `WP-02 (F-21): golden before the next input; continue after FAIL`.

---

### Task 7: Duplicate-name lint, mode and report

**Files:**
- Modify: `SnapshotTaker.java`, `RunResult.java`, `Player.java`, `HtmlReportWriter.java`
- Test: `SnapshotTakerPathTest` (add), `smoker-core/.../report/HtmlReportWriterTest.java` (new)

**Interfaces:**
- Produces:
  - `SnapshotTaker.duplicateNames() -> Set<String>` — one entry per window and name that occurs more than one time: `"<window part>: #<name> (<count>x)"`.
  - `RunResult.duplicateNames: List<String>`, `RunResult.compare: String`.
  - `SnapshotTaker(int maxRows, boolean namedOnly)` replaces `SnapshotTaker(int maxRows)` from Task 3. `Player` and `Recorder` pass `"named".equals(scenario.compare)`.
  - Mode `named`: only window nodes and named components.
  - Report: shows `compare` in the scenario header; lint section "Names that are not unique" lists `duplicateNames`.

- [ ] **Step 1: Write the failing tests**

```java
@Test public void twoComponentsNamedXGiveOneLintResult()     // duplicateNames() == {"[Orders]: #x (2x)"}   (R-22a)
@Test public void namedModeComparesWindowsAndNamedOnly()     // frame with named "a" and unnamed button: nodes == ["[Orders]", "[Orders]/#a"]
@Test public void newUnnamedPanelGivesNoDifferenceInNamedMode()   // R-30a in mode named
// HtmlReportWriterTest
@Test public void reportShowsModeAndDuplicateNames()         // html contains "compare: all" and "#x (2x)"
```

- [ ] **Step 2: Run** — Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `mvn -B verify` and `-Plocale-tr` — Expected: BUILD SUCCESS.
- [ ] **Step 5: Commit** `WP-02 (F-17): lint for names that are not unique; compare mode in the report`.

---

### Task 8: Demo cases, completion criterion, documents

**Files:**
- Modify: `smoker-demo/src/main/java/smoker/demo/DemoApp.java`
- Create: `smoker-demo/scenarios/table.json` (10 steps: greet three names in different languages and read the table)
- Modify: `smoker-it/.../SelfTestIT.java`, `docs/development-plan.md`, `CLAUDE.md`
- Test: `SelfTestIT`

**Interfaces:**
- Produces:
  - `DemoApp`: `-Ddemo.extraPanel=true` wraps the top row in one more unnamed `JPanel`. `-Ddemo.badCell=true` writes the language of the first table row in upper case (`toUpperCase(Locale.ROOT)`).
  - `SelfTestIT.run(...)` gets an optional list of extra JVM options for the demo application.

- [ ] **Step 1: Write the failing tests** (new `SelfTestIT` method `tableScenarioMeetsTheCompletionCriterion`)

```java
// table.json has fewer than 80 lines                                                     (R-25a)
// run, approve: table.golden.json has fewer than 150 lines                               (plan criterion)
// run with -Ddemo.extraPanel=true: PASS                                                  (R-30a)
// run with -Ddemo.badCell=true: FAIL, a difference matches "CHANGED .*#demo.table .cells\[0\]\[1\]"   (R-23b)
```

- [ ] **Step 2: Run** `mvn -B verify` — Expected: FAIL.
- [ ] **Step 3: Implement** the demo options and `table.json`. In `docs/development-plan.md`: add decision 7 (default comparison `all`, option `named`; R-30a text changed) and mark F-05, F-06, F-07, F-17, F-21 as done in WP-02. Update the state in `CLAUDE.md`.
- [ ] **Step 4: Run** `mvn -B clean verify`, `-Plocale-tr`, and `-Plocale-tr "-Djvm=<JDK 8>/bin/java"` — Expected: BUILD SUCCESS in all three.
- [ ] **Step 5: Commit** `WP-02 (F-05, F-06, F-07, F-17, F-21): completion criterion on the demo application`.
