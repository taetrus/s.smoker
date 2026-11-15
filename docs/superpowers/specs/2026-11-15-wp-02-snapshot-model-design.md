# WP-02 Snapshot model — design

Date: 2026-11-15. Work package: WP-02 (`docs/development-plan.md`, section 4.3). Findings: F-05, F-06, F-07, F-17, F-21.
Branch: start from `wp-01-test-base` (WP-01 is complete, pull request #1 is open).

## 1. Intent

A developer on a product team changes a screen. The smoke test in the merge request fails. The developer reads
`report.html`, decides that the change is intended, runs `approve` and commits the new golden. A reviewer in the merge
request looks at the golden change and agrees. Without this, the merge request cannot merge. (Owner, 2026-11-15.)

WP-02 makes this loop cheap and safe:

- The golden change in the GitLab diff is the evidence of the reviewer. It must be small and show only the real change.
- A layout change that has no effect on the user must not change the goldens.
- One `approve` must correct all steps.
- A golden must never show the state of a later step. Otherwise the developer and the reviewer approve a wrong state
  (the same trap as F-23 in the pilot).

Today the scenario and golden files are understandable to the owner. Readability is not a problem to solve. Size,
stability and correctness of the goldens are.

## 2. Goals

1. **Golden file apart from the scenario (F-06, R-25a).** `<scenario>.golden.json` beside `<scenario>.json`.
   The scenario file contains no snapshot. A scenario with 10 steps has fewer than 80 lines.
2. **Changes only after the first snapshot (F-06).** The golden file stores the first snapshot fully. It stores each
   later step as the list of changes from the step before it. A golden file for 10 steps on the demo application has
   fewer than 150 lines.
3. **Stable paths (F-05).** A path starts at the nearest named ancestor, or at the window when no ancestor has a name.
   Layout containers without a name are not in the path (section 4.2).
4. **Default comparison: all components that show state (owner decision, see section 3).** Option `named`: named
   components and window titles only.
5. **Content of tables, lists and trees (F-07, R-23b).** Cell text of the first `maxRows` rows of a table, the first
   `maxRows` items of a list, the first `maxRows` visible rows of a tree. `maxRows` is a scenario field, default 20.
   An incorrect cell value in row 1 causes FAIL.
6. **Schema version (F-17).** The scenario file and the golden file have `schemaVersion`. The tool refuses a file with
   a higher version, and a file in the old format, with a clear message.
7. **Lint for names that are not unique (F-17, R-22a).** Each name that occurs more than one time in a window gives
   one lint result. A window with two components named `x` gives one result.
8. **Correct goldens (F-21).** The golden of a step shows the state after that step and before any input of the next
   step. One `approve` writes the actual state of every step that has one.
9. **`approve` and the HTML report use the new format.** The report and the golden diff use the same words:
   path, attribute, old value, new value.

## 3. Decisions of the owner (2026-11-15)

| Decision | Effect on this design |
|---|---|
| Default comparison: compare all components until they have names. | Mode `all` is the default. Mode `named` is an option for each scenario. This changes R-30a: the plan text "named components and window titles only" as default is replaced. Record it as owner decision 7 in `docs/development-plan.md`. |
| The tool may refuse the old format. Convert the existing files in the same commit. | No reader for the old format. Convert `smoker-demo/scenarios/*.json`, `smoker-demo/scenarios-failing/*.json` and `docs/wp-01-pilot/*.json` in the commit that changes the format. Conversion = add `schemaVersion`, remove `expected`, rewrite `ignore.paths` to the new paths. The old goldens are not converted (my call, section 10): they have no table content and would fail anyway. No converter program. |
| A reviewer checks each golden change in the merge request. | Golden files must give small, stable Git diffs (goals 1 to 3). |
| Some windows of the products change their title while they run (for example a file name or a state). | The scenario gets title rules that map a changing title to a fixed window key (section 4.2). Paths and locators use the key. |

Earlier decisions that apply: Java 8 source level; Gson relocated, no new runtime dependency; Windows is the only
target platform (decision 5); naming rule `<view>.<widget>` (decision 3); reports go to `docs/` on GitHub (decision 6).

## 4. Design

### 4.1 Files

- `<scenario>.json`: `schemaVersion`, `name`, `description`, timeouts, `quietMs`, `compare` (`all` | `named`,
  default `all`), `maxRows` (default 20), `windowKeys` (section 4.2), `ignore`, `steps` (id, name, action). No `expected`.
- `<scenario>.golden.json`: `schemaVersion`, the snapshot of the first step that has a golden (full), and for each
  later step its list of changes. Written only by `approve` and by the recorder.
- A scenario without a golden file gives `NO_GOLDEN`, as today.
- `schemaVersion` starts at 2 for both files (the old format counts as version 1).

The builder selects the exact JSON layout. Requirements for it:

- One component per entry, sorted by path, so that a new component gives a block of added lines and nothing else moves.
- Each attribute on its own line (Gson pretty printing does this), so that one changed value gives one changed line.
- A change entry names the path and gives the new attribute values, a removed component, or an added component.
- A reviewer who reads only the changed lines in GitLab can see which component changed and how.

### 4.2 Paths and the components that are compared

- **Window part.** Each path starts with the window part:
  - `[#<name>]` when the window has an application name (`Locators.isAutoName` is false). The title does not matter.
  - else `[<key>]`. The key is the title, after the title rules of the scenario (below).
  - When two showing windows have the same window part, the second gets `[<...>][1]`, the third `[<...>][2]`, in the
    order of `Window.getWindows()`.
- **Title rules.** A scenario field `windowKeys`: a list of `{ "pattern": "<regex>", "key": "<text>" }`. The first
  pattern that matches the whole title gives the key. Without a match, the key is the title. Example:
  `{ "pattern": "Editor - .*", "key": "Editor" }`. The window `title` attribute is still compared; use `ignore.valuePatterns`
  or `ignore.attributes` if the changing title itself must not give a difference.
- **Locators** use the same key: `Locators.resolve` writes the key into `windowTitle`, and `Locators.find` compares the
  key of each window with it. The locator format does not change.
- **Named component:** `<window part>/#<name>`. A name that occurs again in the same window gets an index:
  `#<name>[1]`. These are the cases that the lint reports (goal 7). No two compared components have the same path.
- **Unnamed component:** `<anchor>/<Class>[<n>]`. The anchor is the path of the nearest named ancestor, else the window
  part. `<n>` is the index among the compared components of that class below that anchor, in tree order.
  `Locators.classNameOf` gives the class. Examples: `[Settings]/JTextField[1]`, `[#orders.frame]/#orders.panel/JButton[0]`.
- **Not compared, not in paths:** unnamed instances (including subclasses, for example an application `ChatPanel extends
  JPanel`) of layout containers without own state: `JPanel`, `JScrollPane`, `JViewport`, `JRootPane`, `JLayeredPane`,
  `Box`, `JSplitPane`, `JToolBar`, and the look-and-feel internals that `SnapshotTaker.lafInternal` excludes today.
  The builder can extend this list; the rule is "shows no state the user can see". A named container is compared
  and is an anchor.
- **Window:** compared with its title. Its path is the window part.
- **Mode `named`:** only windows and named components are compared. Their paths are the window part and
  `<window part>/#<name>`.
- **Locators** keep their format. The rule "class name plus index below the window" in `Locators.resolve` stays as it is
  (F-23 correction). Use one class rule in both places (`Locators.classNameOf`).

Consequence that the owner accepted: a new unnamed button or text field in front of existing ones shifts the indexes
of that class below the same anchor. Each name a team adds (WP-09) makes the paths shorter and more stable.

### 4.3 Content of tables, lists and trees

- Table: the text of each cell of the first `maxRows` rows, in view order, plus `rowCount`. Use the text that the
  user sees (the renderer component text when it is a `JLabel`, else `String.valueOf` of the value). The builder
  selects the exact representation (for example one string per row with a separator, or a list of rows).
- List: the text of the first `maxRows` items, plus `itemCount`.
- Tree: the labels of the first `maxRows` visible rows with their depth, plus `rowCount`.
- Combo box: keep the selected item and `itemCount`. Do not add the item list (the pilot had a combo box with 407 items
  from a network answer).
- Password fields stay masked (F-19).

### 4.4 Correct goldens (F-21)

Two causes, two corrections:

1. **Recorder.** Today a background thread takes the snapshot of a step when the UI is quiet for `quietMs`. If the
   next input comes before that time, the snapshot shows the later state. Correction: when the recorder sees the first
   input of the next step (focus change, mouse press, key press on the EDT) and the snapshot of the step before is still
   pending, it takes that snapshot at once, on the EDT, before the input is processed. The background snapshot stays for
   the last step and for steps that become quiet in time.
2. **Player and `approve`.** Today the player stops at the first step that fails, so `approve` sees only the steps up
   to that one. Correction: after a step that fails only on snapshot differences or log hits, the player continues
   with the next step. It stops after `HANG` and `ERROR` (the UI state is then not known). `approve` then has the actual
   state of each step that ran.
   This is a change of behavior. Update the "Statuses" section of `README.md`.

### 4.5 approve

- `approve --run <dir> --scenario <file> [--steps ...]` writes `<scenario>.golden.json` from the actual snapshots of
  the run. With `--steps`, only those steps change. The other steps keep their golden.
- It computes the change lists again for the whole file, so that the file is always the full first snapshot plus
  changes.
- It prints how many steps changed and, for each step, the number of changed components.

### 4.6 HTML report and JUnit XML

- The report shows, for each failing step, the differences in the same words as the golden diff: `CHANGED <path>
  .<attribute>: expected=… actual=…`, `MISSING <path>`, `UNEXPECTED <path>`.
- A table cell difference names the row and the column.
- The lint section shows the unnamed interactive components (as today) and the names that are not unique (new).
- The report shows the comparison mode of the scenario.

## 5. Non-goals

- No change of the locator format and no migration of locators (WP-09 does names).
- No `WAIT_FOR` (WP-03), no log analysis changes (WP-04), no suite mode (WP-05).
- No reader for the old format. The conversion is done once, in the commit.
- No image comparison (WP-08).

## 6. Anti-goals (a failure even if it works)

- An unrelated layout change makes many golden lines change. The reviewer stops reading the golden diff.
- `approve` needs more than one run to correct all steps.
- A golden file can contain the state of a later step.
- The report and the golden diff describe the same change with different words or paths.
- A password or API key appears in a golden file (F-19 stays valid).

## 7. Constraints

- Java 8 source level, `--release 8`. No new runtime dependency.
- Every Swing access on the EDT. Never wait on an action `Runnable` directly.
- `Locale.ROOT` for all case conversion and number formatting.
- Windows named `smoker.*` are excluded from snapshots and recording.
- `mvn -B verify` and `mvn -B verify -Plocale-tr` pass after each change to `smoker-agent` or `smoker-core`.
- When the format changes: update `README.md` and the demo scenarios in the same commit (project rule).

## 8. Completion criterion (from the plan, with decision 7)

- R-25a: a scenario file with 10 steps has fewer than 80 lines.
- R-30a (changed): a new unnamed panel in the layout causes no difference, in mode `all` and in mode `named`.
- R-23b: an incorrect cell value in row 1 causes FAIL.
- A golden file for 10 steps on the demo application has fewer than 150 lines.
- Lint: a window with two components named `x` gives one lint result.
- Title rules: a window title that changes and matches a `windowKeys` pattern gives no `MISSING` or `UNEXPECTED`
  line, and the locators of that window still find their components.
- F-21: a test shows that the golden of a step does not contain the state after the next step, and that one `approve`
  corrects all failing steps.

The demo application has one scenario with 5 steps. Add a demo scenario with 10 steps that uses the table, so that the
criterion can be measured. Add tests to `SelfTestIT` for R-30a and R-23b if the demo application can show them (for
example a system property that adds an unnamed panel, and one that changes a cell value).

## 9. Left to the builder

- The exact JSON layout of the golden file (within the requirements in 4.1).
- The representation of table rows, list items and tree rows (within 4.3).
- The list of layout container classes (within 4.2).
- How the demo application shows the R-30a and R-23b cases.
- The order of the work, and how to split it into commits. Each commit must build and pass the tests.

## 10. Open verification and calls made by the designer

- **Pilot files (designer's call).** The old goldens in `docs/wp-01-pilot/*.json` are removed, not converted. The two
  `ignore.paths` of `pilot.json` become `^\[OSGi Chatbot\]/JComboBox\[0\]$` and `^\[OSGi Chatbot\]/JTextPane\[0\]$`
  (layout containers and `ChatPanel` drop out of the path). Add a task for the next Windows session to
  `docs/wp-01-handoff.md`: run `pilot.json`, `hang.json`, `crash.json`, `approve`, run again, expect PASS, commit the goldens.
- **Repeated names and window identity (designer's call).** Window part `[#<name>]` or `[<key>]`, index for repeats
  (section 4.2). Reason: two compared components must never have the same path, or the diff hides one of them.
