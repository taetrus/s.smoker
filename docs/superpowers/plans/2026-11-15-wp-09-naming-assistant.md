# WP-09 Naming Assistant Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Automatic names `@<Class>.<field>` at run time (on by default), a lint with proposed real names `<view>.<type>.<field>`, a `lint --emit-patch` command, and locators that survive the later real name.

**Architecture:** `smoker-agent` finds, by reflection on the EDT, the application-class ancestor field that holds an unnamed component (`AutoNames`), and proposes real names (`NameProposer`). `SnapshotTaker` and `Locators` treat an automatic name like a real name; `SnapshotTaker` also writes structured lint entries into `run-result.json`. `smoker-cli lint` joins the entries of a run, prints a table and a summary, and writes the patch.

**Tech Stack:** Java 8 (`--release 8`), Swing, java.lang.reflect, Gson (relocated), JUnit 4.13.2, Maven Surefire/Failsafe 3.2.5.

**Spec:** `docs/superpowers/specs/2026-11-15-wp-09-naming-assistant-design.md`

## Global Constraints

- Java 8 source level: no `var`, `List.of`, `String.isBlank`, `Files.readString`. No new runtime dependency.
- Every Swing access and every reflective read of application objects on the EDT. Never wait on an action `Runnable` directly.
- `Locale.ROOT` for all case conversion and `String.format`.
- Windows named `smoker.*` are excluded.
- Automatic name format: `@<SimpleName of the runtime class>.<field name>`. A real name always wins.
- Application class: fully qualified name does not start with `java.`, `javax.`, `sun.`, `com.sun.`, `jdk.`, `com.apple.`, `apple.`, `smoker.`.
- Proposed name format: `<view>.<type>.<field>` (example: field `saveButton` in `OrdersPanel` → `orders.btn.save`).
- Scenario field `autoNames`: boolean, default `true`; `schemaVersion` stays 2.
- The tool reads only which component a field refers to; it never reads or writes another field value (no password reaches any file).
- After each task: `mvn -B verify` and `mvn -B verify -Plocale-tr` pass. Commit messages name WP-09.
- A change of the scenario format updates `README.md` and the demo scenarios in the same commit.

## Review Focus

1. A component referred to by two fields of one class: the first field in declaration order gives the name. (Task 2)
2. A field declared in a superclass: the automatic name uses the runtime class; the patch writes a comment, not a line in the superclass. (Tasks 2, 5)
3. Two instances of one panel class in a window: `#@P.f` and `#@P.f[1]`, nothing overwrites. (Task 3)
4. A component held only by a field of a JDK class (for example the viewport of a `JScrollPane`): no automatic name, and no exception. (Task 2)
5. A `JPasswordField` held in a field: it gets an automatic name, and no file contains its value. (Task 4)

---

## File structure

| File | Responsibility | Task |
|---|---|---|
| `smoker-agent/.../NameProposer.java` (new) | Pure functions: view, type, field stem, text stem, proposed name. | 1 |
| `smoker-core/.../model/LintEntry.java` (new) | One lint row. | 1 |
| `smoker-core/.../model/Scenario.java` | `autoNames`. | 1 |
| `smoker-core/.../model/RunResult.java` | `List<LintEntry> lint`, `List<String> autoNames`. | 1 |
| `smoker-agent/.../AutoNames.java` (new) | Reflection: component → automatic name, runtime and declaring class, field. Static switch. | 2 |
| `smoker-agent/.../SnapshotTaker.java`, `Locators.java`, `SmokerAgent.java` | Use automatic names in paths, nodes, locators. | 3 |
| `smoker-agent/.../SnapshotTaker.java`, `Player.java`, `smoker-core/.../report/HtmlReportWriter.java` | Lint entries, report table, list of automatic names. | 4 |
| `smoker-cli/.../Main.java`, `smoker-cli/.../Lint.java` (new) | `lint --run <dir> [--emit-patch <file>]`. | 5 |
| `smoker-demo/...`, `smoker-it/.../SelfTestIT.java`, docs | Demo case, criterion, documents. | 6 |
| `docs/wp-09-pilot-report.md` (new) | 80 % measurement on p.osgi.ds. | 7 |

---

### Task 1: Name proposals and lint model

**Files:**
- Create: `smoker-agent/src/main/java/smoker/agent/NameProposer.java`, `smoker-core/src/main/java/smoker/core/model/LintEntry.java`
- Modify: `smoker-core/.../model/Scenario.java`, `smoker-core/.../model/RunResult.java`
- Test: `smoker-agent/src/test/java/smoker/agent/NameProposerTest.java` (headless)

**Interfaces:**
- Produces:
  - `final class NameProposer { static String view(String classSimpleName); static String type(Component c); static String fieldStem(String field); static String textStem(String text); static String propose(String classSimpleName, Component c, String field, String text); static String source(String field, String text); }` — `propose` returns `<view>.<type>.<fieldStem>` when `field != null`, else `<view>.<type>.<textStem>` when the text gives a non-empty stem, else `null`. `source` returns `"field"`, `"text"` or `null` with the same rule. Suffix and type tables: spec section 5.3, verbatim.
  - `class LintEntry { String window; String runtimeClass; String declaringClass; String field; String type; String text; String autoName; String proposedName; String source; }` (fully qualified class names; `type` = simple component class name).
  - `Scenario.autoNames: boolean = true`. `RunResult.lint: List<LintEntry>`, `RunResult.autoNames: List<String>` (sorted automatic names used in the run).

- [ ] **Step 1: Write the failing tests**

```java
@Test public void viewDropsOneSuffixAndLowersTheFirstLetter()  // "OrdersPanel"->"orders", "CustomerDetailsDialog"->"customerDetails", "Panel"->"panel", "Settings"->"settings"
@Test public void typeFollowsTheTable()                        // JPasswordField->"pwd", JTextField->"txt", JTextArea->"txt", JCheckBox->"chk", JRadioButton->"rad", JToggleButton->"tgl", JMenuItem->"menu", JButton->"btn", JComboBox->"cmb", JList->"lst", JTable->"tbl", JTree->"tree", JTabbedPane->"tabs", JSpinner->"spn", JSlider->"sld", JProgressBar->"progressbar"
@Test public void fieldStemDropsTheLongestTypeSuffix()         // "saveButton"->"save", "customerNameField"->"customerName", "modelComboBox"->"model", "button"->"button", "SaveBtn"->"save"
@Test public void textStemIsLowerCamelCase()                   // "Base URL:"->"baseUrl", "Save"->"save", "Hang EDT 8s"->"hangEdt8s", "…"->"" 
@Test public void proposeUsesTheFieldFirst()                   // ("OrdersPanel", JButton, "saveButton", "Save") -> "orders.btn.save", source "field"
@Test public void proposeFallsBackToTheText()                  // ("SettingsDialog", JButton, null, "Cancel") -> "settings.btn.cancel", source "text"
@Test public void noFieldAndNoTextGivesNoProposal()            // ("X", JButton, null, null) -> null, source null
```

- [ ] **Step 2: Run** `mvn -B -q -pl smoker-core,smoker-agent -am test -Dtest=NameProposerTest -Dsurefire.failIfNoSpecifiedTests=false` — Expected: compile failure.
- [ ] **Step 3: Implement.** Case conversion with `Locale.ROOT`.
- [ ] **Step 4: Run** `mvn -B verify` and `-Plocale-tr` — Expected: BUILD SUCCESS.
- [ ] **Step 5: Commit** `WP-09: name proposals and lint model`.

---

### Task 2: Automatic names by reflection

**Files:**
- Create: `smoker-agent/src/main/java/smoker/agent/AutoNames.java`
- Test: `smoker-agent/src/test/java/smoker/agent/AutoNamesTest.java` (display; EDT pattern of `LocatorsWindowTest`)

**Interfaces:**
- Produces:
  - `final class AutoNames { static final class Hit { final String name; final String runtimeClass; final String declaringClass; final String field; } static void enable(boolean on); static boolean enabled(); static boolean isApplicationClass(Class<?> c); static Hit of(Component c); }`
  - `of(c)`: `null` when disabled, when `c` has a real name (`SnapshotTaker`/`Locators` rule: non-empty and not `Locators.isAutoName`), when `SnapshotTaker.isLayoutContainer(c)`, or when no ancestor field refers to it. Else the hit of spec section 5.1: go up `c.getParent()` (window included); the first ancestor `a` with `isApplicationClass(a.getClass())` whose instance fields (runtime class first, then superclasses while they are application classes; declaration order inside a class; no static fields) contain a field whose value `== c`.
  - Name: `"@" + simpleName(a.getClass()) + "." + field.getName()`; for an anonymous or local class use the simple name of the nearest enclosing named class.
  - Field lists cached per class (`ConcurrentHashMap<Class<?>, Field[]>`). `setAccessible(true)`; on any `RuntimeException` or `IllegalAccessException` skip the field.
  - Must run on the EDT. Default: enabled.

- [ ] **Step 1: Write the failing tests** (the prefix `smoker.` is excluded from application classes, so the test panels live in package `nametest` under `smoker-agent/src/test/java/nametest/`, e.g. `nametest.OrdersPanel`)

```java
@Test public void fieldOfTheNearestHolderGivesTheName()         // nametest.OrdersPanel with field saveButton: of(saveButton).name == "@OrdersPanel.saveButton", field "saveButton", runtimeClass "nametest.OrdersPanel"
@Test public void realNameWins()                                // saveButton.setName("orders.btn.save") -> of(...) == null
@Test public void helperPanelWithoutTheFieldIsPassedOver()       // OrdersView (field okButton) > unnamed nametest.HelperPanel (no field) > okButton -> "@OrdersView.okButton"
@Test public void firstFieldInDeclarationOrderWins()             // class with fields a and b both = same button -> "@Twice.a"   (Review Focus 1)
@Test public void superclassFieldUsesTheRuntimeClass()           // nametest.BasePanel declares okButton; nametest.OrdersPanel2 extends it -> name "@OrdersPanel2.okButton", declaringClass "nametest.BasePanel"   (Review Focus 2)
@Test public void componentHeldOnlyByAJdkFieldHasNoName()        // the JViewport of a JScrollPane inside an app panel -> null, no exception   (Review Focus 4)
@Test public void layoutContainerHeldInAFieldHasNoName()         // app panel with field "form" = unnamed JPanel -> of(form) == null
@Test public void staticFieldDoesNotCount()
@Test public void disabledGivesNoName()                          // enable(false) -> null; reset enable(true) in @After
```

- [ ] **Step 2: Run** the test — Expected: compile failure.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `mvn -B verify` and `-Plocale-tr` — Expected: BUILD SUCCESS.
- [ ] **Step 5: Commit** `WP-09: automatic names by reflection`.

---

### Task 3: Automatic names in snapshots and locators

**Files:**
- Modify: `smoker-agent/.../SnapshotTaker.java` (`applicationName` / `walk` / `node`), `smoker-agent/.../Locators.java` (`resolve`, `find`), `smoker-agent/.../AutoNames.java` (`nameIgnoringRealName`), `smoker-agent/.../RecorderToolbar.java` (picker text), `smoker-agent/.../SmokerAgent.java`
- Test: `SnapshotTakerPathTest`, `LocatorsWindowTest` (add)

**Interfaces:**
- Consumes: `AutoNames.of(Component)`, `AutoNames.enable(boolean)` (Task 2); `Scenario.autoNames` (Task 1).
- Produces:
  - `SnapshotTaker`: the name of a component for paths, nodes, anchors, `named` mode and the duplicate-name lint = real name, else `AutoNames.of(c).name`, else none. Path of an automatically named component: `<window part>/#@<Class>.<field>`; repeats get the WP-02 index.
  - `Locators.resolve(c)`: `Locator.name` = real name, else automatic name.
  - `Locators.find(l)` for a named locator: a component matches when its real name equals `l.name`, or when `AutoNames` (computed ignoring the real name) gives `l.name`. Add `static String AutoNames.nameIgnoringRealName(Component c)` for this (same rule as `of`, without the real-name check).
  - `SmokerAgent` calls `AutoNames.enable(scenario.autoNames)` next to `Locators.useWindowKeys(...)`, for play and record.
  - `RecorderToolbar` picker: when the resolved locator name starts with `@`, the shown text adds `(automatic name - needs a real name)` (spec 5.2).

- [ ] **Step 1: Write the failing tests**

```java
// SnapshotTakerPathTest (panels in package nametest)
@Test public void automaticallyNamedComponentHasANamedPath()       // "[Orders]/#@OrdersPanel.saveButton", node.name "@OrdersPanel.saveButton"
@Test public void twoPanelsOfOneClassGetAnIndex()                   // "[Orders]/#@OrdersPanel.saveButton" and "...[1]"   (Review Focus 3)
@Test public void namedModeComparesAutomaticallyNamedComponents()   // SnapshotTaker(20, true) keeps the auto-named node
@Test public void autoNamesOffGivesTheWp02Path()                    // AutoNames.enable(false): "[Orders]/JButton[0]"
// LocatorsWindowTest
@Test public void resolveUsesTheAutomaticName()                     // Locator.name == "@OrdersPanel.saveButton"
@Test public void oldAutomaticLocatorFindsTheComponentAfterARealName()   // resolve, then setName("orders.btn.save"), find(old locator) == same button   (spec goal 5)
```

- [ ] **Step 2: Run** — Expected: FAIL.
- [ ] **Step 3: Implement.** Reset `AutoNames.enable(true)` in every test `@After` that changes it.
- [ ] **Step 4: Run** `mvn -B verify` and `-Plocale-tr` — Expected: BUILD SUCCESS.
- [ ] **Step 5: Commit** `WP-09: automatic names in snapshots and locators`.

---

### Task 4: Lint entries and report

**Files:**
- Modify: `smoker-agent/.../SnapshotTaker.java`, `smoker-agent/.../Player.java`, `smoker-core/.../report/HtmlReportWriter.java`
- Test: `smoker-agent/.../SnapshotTakerLintTest.java` (new, display), `smoker-core/.../report/HtmlReportWriterTest.java` (add)

**Interfaces:**
- Consumes: `NameProposer` (Task 1), `AutoNames.of` (Task 2).
- Produces:
  - `SnapshotTaker.lintEntries() -> List<LintEntry>`: one entry per unnamed interactive component (no real name; `Locators.isInteractive`; not `lafInternal`; not generated by `JOptionPane`), keyed by window part + path; accumulated over all snapshots (last seen wins). `runtimeClass`/`declaringClass`/`field` from the `AutoNames` hit, or for a text proposal `runtimeClass` = nearest application-class ancestor (or null). `text` = `Locators.textOf(c)` (never the text of a text component: `Locators.textOf` already excludes editable text). `proposedName` from `NameProposer.propose`; duplicate proposals inside one view get suffix `2`, `3` in tree order (spec 5.3).
  - `SnapshotTaker.autoNamesUsed() -> Set<String>` (sorted).
  - `Player` copies both into `RunResult.lint` and `RunResult.autoNames`.
  - Report: a lint table (window, application class, field, type, text, automatic name, proposed name, source) and a list "Automatic names used in this run" with the note that an automatic name changes when the field is renamed. HTML-escaped.

- [ ] **Step 1: Write the failing tests**

```java
@Test public void unnamedButtonInAFieldGivesAFullEntry()       // OrdersPanel.saveButton "Save": field "saveButton", autoName "@OrdersPanel.saveButton", proposedName "orders.btn.save", source "field"
@Test public void localButtonGivesATextProposal()              // button "Cancel" not in a field, inside SettingsDialog-like app panel: field null, autoName null, proposedName "settings.btn.cancel", source "text"
@Test public void equalProposalsGetASuffix()                   // two local buttons "OK" in one view: "x.btn.ok", "x.btn.ok2"
@Test public void passwordFieldValueReachesNoFile()            // field-held JPasswordField with text "sk-secret": write RunResult with lint via Json.write, file does not contain "sk-secret"   (Review Focus 5)
@Test public void namedComponentIsNotInTheLint()
// HtmlReportWriterTest
@Test public void reportShowsTheLintTableAndAutomaticNames()   // contains "orders.btn.save" and "@OrdersPanel.saveButton"
```

- [ ] **Step 2: Run** — Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `mvn -B verify` and `-Plocale-tr` — Expected: BUILD SUCCESS.
- [ ] **Step 5: Commit** `WP-09: lint entries with proposed names; report`.

---

### Task 5: `lint` command and patch

**Files:**
- Create: `smoker-cli/src/main/java/smoker/cli/Lint.java`
- Modify: `smoker-cli/.../Main.java` (command `lint`, usage text)
- Test: `smoker-cli/src/test/java/smoker/cli/LintTest.java`

**Interfaces:**
- Consumes: `RunResult.lint` (Task 1, filled by Task 4).
- Produces:
  - `Main`: `lint --run <out dir> [--emit-patch <file>]` → exit 0 (exit 2 when `--run` is missing or no `run-result.json` exists below it).
  - `Lint.read(Path outDir) -> List<LintEntry>`: all `run-result.json` files below `outDir`, joined; one entry per (window, runtimeClass, field or text, type) — first seen wins.
  - `Lint.table(List<LintEntry>) -> String`: aligned columns `window | class | field | type | text | automatic name | proposed name | source` (class = simple name of `runtimeClass`).
  - `Lint.summary(List<LintEntry>) -> String`: exactly `"<n> unnamed interactive component(s), <p> with a proposed name (<percent> %: <f> from fields, <t> from text), <a> with an automatic name"`, percent rounded down, `Locale.ROOT`.
  - `Lint.patch(List<LintEntry>) -> String`: grouped by `runtimeClass` (a `// <fully qualified class>` line per group, groups sorted); per entry with `field` and `declaringClass == runtimeClass`: `<field>.setName("<proposed>");`; with a superclass field: `// <field> is declared in <DeclaringSimpleName>: set "<proposed>" in <RuntimeSimpleName>`; with source `text`: `// <type> "<text>" has no field: setName("<proposed>") where it is created`; entries without a proposal are left out.

- [ ] **Step 1: Write the failing tests** (build `run-result.json` files in a `TemporaryFolder` with `Json.write`)

```java
@Test public void readJoinsEntriesOfAllRuns()                  // two runs with one shared and one own entry -> 3 entries
@Test public void summaryCountsFieldAndTextProposals()         // 4 entries: 2 field, 1 text, 1 none, 2 auto -> "4 unnamed interactive component(s), 3 with a proposed name (75 %: 2 from fields, 1 from text), 2 with an automatic name"
@Test public void patchWritesSetNameLinesPerClass()            // "// nametest.OrdersPanel" then "saveButton.setName(\"orders.btn.save\");"
@Test public void superclassFieldGivesAComment()               // "// okButton is declared in BasePanel: set \"orders2.btn.ok\" in OrdersPanel2"
@Test public void textProposalGivesAComment()                  // "// JButton \"Cancel\" has no field: setName(\"settings.btn.cancel\") where it is created"
@Test public void lintWithoutRunResultsExitsTwo()
```

- [ ] **Step 2: Run** — Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `mvn -B verify` and `-Plocale-tr` — Expected: BUILD SUCCESS.
- [ ] **Step 5: Commit** `WP-09: lint command with patch output`.

---

### Task 6: Demo case, integration test, documents

**Files:**
- Create: `smoker-demo/src/main/java/demo/notes/NotesPanel.java`, `smoker-demo/scenarios/notes.json`
- Modify: `smoker-demo/.../DemoApp.java`, `smoker-it/.../SelfTestIT.java`, `README.md`, `docs/development-plan.md`, `CLAUDE.md`, `docs/wp-01-handoff.md`
- Test: `SelfTestIT`

**Interfaces:**
- Produces:
  - `NotesPanel extends JPanel` in package `demo.notes` (`smoker-demo/src/main/java/demo/notes/NotesPanel.java`): the prefix `smoker.` is excluded from application classes, so the demo panel must live outside it. Fields: `JTextField noteField`, `JButton saveButton` ("Save"), `JLabel savedLabel` (named `notes.saved`). Save copies the note into `savedLabel`. With `-Ddemo.realNames=true`, `saveButton.setName("notes.btn.save")` and `noteField.setName("notes.txt.note")`.
  - `DemoApp` adds `NotesPanel` to the frame (bottom area). The existing demo components keep their real names.
  - `notes.json`: 2 steps: SET_TEXT locator name `@NotesPanel.noteField` value `hello`; CLICK locator name `@NotesPanel.saveButton`.
  - Docs: README (automatic names, `autoNames`, `lint`, `--emit-patch`); development-plan: refine decision 3 (`<view>.<type>.<field>`), mark the WP-09 risk line and WP-09 tasks done; CLAUDE.md state; handoff: the pilot goldens are approved on Windows after WP-09, and a scenario with automatic names must pass on Windows.

- [ ] **Step 1: Write the failing tests** (new `SelfTestIT` method `automaticNamesSurviveARealName`)

```java
// run notes.json: NO_GOLDEN, s001/s002 not ERROR; approve; run: PASS
// golden contains "#@NotesPanel.saveButton"
// run with -Ddemo.realNames=true: status FAIL, no step ERROR (locators still find the components),
//   every difference starts with "MISSING " or "UNEXPECTED " and names an @NotesPanel or notes. path
// approve that run; run again with -Ddemo.realNames=true: PASS
// lint --run <first run dir>: exit 0; output contains "notes.btn.save" and a summary line; --emit-patch file contains "saveButton.setName(\"notes.btn.save\");"
```

- [ ] **Step 2: Run** `mvn -B verify` — Expected: FAIL.
- [ ] **Step 3: Implement** the demo panel, the scenario and the documents.
- [ ] **Step 4: Run** `mvn -B clean verify`, `-Plocale-tr`, and `-Plocale-tr "-Djvm=<JDK 8>/bin/java"` (one at a time) — Expected: BUILD SUCCESS in all three.
- [ ] **Step 5: Commit** `WP-09: demo case and completion criterion`.

---

### Task 7: Pilot measurement (completion criterion, 80 %)

**Files:**
- Create: `docs/wp-09-pilot-report.md`

**Interfaces:**
- Consumes: the built jars; p.osgi.ds at `~/dev/p.osgi.ds` (macOS product under `distribution/target/products/com.kk.pde.ds.product/`; build it with `./mvnw clean verify` if missing); `docs/wp-01-pilot/pilot.json`.

- [ ] **Step 1:** Copy `pilot.json` into a scratch folder. Run it with `smoker-cli run` against the macOS main product (agent in the `java` command, `-consoleLog`, no `-console`; start in the product folder; Logback file appender as in `docs/wp-01-handoff.md` section 4.2 if the log is needed). Expected: the run ends (any status) and writes `run-result.json` with `lint` entries.
- [ ] **Step 2:** Run `smoker-cli lint --run <out> --emit-patch <scratch>/pilot.patch`. Expected: a summary line with a percentage.
- [ ] **Step 3:** Write `docs/wp-09-pilot-report.md`: environment, the summary line, the lint table, the patch, and whether the percentage meets 80 %. If it does not, write the components without a proposal and why; do not change the criterion.
- [ ] **Step 4: Commit** `WP-09: pilot measurement of name proposals`.
