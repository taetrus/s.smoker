# WP-06 Recorder (code tasks) — design

Date: 2026-11-24. Findings: F-13, F-21. Requirement: R-20a.
Source: `docs/development-plan.md`, section "WP-06", tasks 2 to 7. Decision 8 moves tasks 1 and 8 (the usability
test and its corrections) to the field phase.

## 1. Intent

A developer records a scenario on a real product. Today the recorder ignores the double-click, the right mouse
button, table cell edits and spinner values, and it records a click on a tree as a `CLICK` on the tree without the
node (`Recorder.java`, the last `else` of `onMouse`) (F-13). The developer must then write these steps by hand.
WP-06 makes the recorder record them and the player replay them.

The toolbar gets three functions that help the person who records:

- a preview of what the selected step changed (the rest of F-21: the person sees a wrong golden before the first run);
- a function to move a step;
- a group label for steps.

The users are the developers of the target applications. They record on Windows.

## 2. Goals, non-goals, anti-goals

Goals:

1. The recorder records, and the player replays: tree selection, the double-click, the right-click, a table cell
   edit and a spinner value (R-20a).
2. The toolbar shows the changes of the golden snapshot of the selected step.
3. The toolbar can move a step up and down, and can give a step a group label.
4. A replay of each new type passes on the demo application (the criterion of R-20a).

Non-goals:

- Drag operations (F-13 names them; the tasks and R-20a do not).
- The usability test and its corrections (field phase).
- `WAIT_FOR` on a table cell or a tree node.
- Recording of keyboard navigation in a tree, table or list (arrow keys). This is not recorded today either.
- Collapse of a tree node as a step.
- A spinner with an editor that is not a `JSpinner.DefaultEditor`.
- A change to `Delete` in the toolbar. It keeps the goldens of the later steps, as today.

Anti-goals:

- A scenario file of today that fails to load or replays differently. Each new field is optional.
- A recorded step for the internal components of a cell editor or a spinner (an unnamed text field, an arrow button).
  Such a step breaks at the next look-and-feel change.
- A golden that the recorder knows is wrong and keeps without a mark (section 7.3).

## 3. Constraints

The hard constraints of `CLAUDE.md` apply. These are the ones that this work touches most:

- Java 8 source level, `--release 8`. No new dependency.
- Every Swing access goes through the EDT. Do not wait on an action `Runnable` directly.
- All case conversion uses `Locale.ROOT`.
- Windows named `smoker.*` are the tool's own UI. Each new dialog of the toolbar gets such a name.
- Never write the text of a `JPasswordField` to a scenario (F-19). This includes a cell editor that is a password field.
- `Locators.resolve` and `Locators.find` do not change (F-23).
- The `[smoker]` log texts are ASCII. A new button text or status text uses ASCII only.
- A change to the scenario or golden format updates `README.md` and the demo scenarios in the same commit.

## 4. Approaches

1. **More fields on the action (selected).** The locator names the component. Optional action fields give the place
   in the component (`row`, `column`, `path`) and the kind of click (`count`, `button`). Small change, old files stay
   valid, `Locators` does not change. Limit: only the actions with these fields can address a cell.
2. **The locator addresses a part of a component** (`#orders.table[2,Status]`). More general: `WAIT_FOR` on a cell
   comes free. It changes `Locator`, both `Locators` methods, the picker, the lint and the snapshot paths. No
   requirement asks for it. Not selected. The fields of approach 1 can move into the locator later.
3. **Screen coordinates.** Rejected: it breaks the semantic replay (R-31).

The owner selected approach 1 on 2026-11-24.

## 5. Scenario format

`schemaVersion` stays 2. All fields below are optional.

### 5.1 New fields of `Action`

| Field | Type | Used by | Meaning |
|---|---|---|---|
| `count` | integer | `CLICK` | Click count. Absent means 1. The recorder writes only 2. |
| `button` | integer | `CLICK` | Mouse button: 1 or 3. Absent means 1. The recorder writes only 3. |
| `row` | integer | `CLICK`, `SET_CELL` | View row index of a `JTable` or a `JList`. |
| `column` | string | `CLICK`, `SET_CELL` | Column of a `JTable` (rule in 5.3). |
| `path` | array of strings | `SELECT`, `CLICK` | Node of a `JTree`: the labels from the root to the node. |

New action type `SET_CELL`: `locator` (a `JTable`), `row`, `column`, `value`.

`SET_TEXT` accepts a `JSpinner` as its component. `SELECT` accepts a `JTree` with `path`.

Examples:

```json
{ "type": "SELECT",   "locator": {"name": "files.tree"},   "path": ["Root", "src", "Main.java"] }
{ "type": "CLICK",    "locator": {"name": "orders.table"}, "row": 2, "column": "Status", "count": 2 }
{ "type": "CLICK",    "locator": {"name": "files.tree"},   "path": ["Root", "src"], "button": 3 }
{ "type": "SET_CELL", "locator": {"name": "orders.table"}, "row": 2, "column": "Qty", "value": "5" }
{ "type": "SET_TEXT", "locator": {"name": "orders.count"}, "value": "7" }
```

`Action.toString()` shows the new fields, because it gives the default step name. Proposed texts (the builder can
adjust them): `CLICK #orders.table [row 2, Status] x2`, `CLICK #files.tree [Root/src] right`,
`SELECT #files.tree [Root/src/Main.java]`, `SET_CELL #orders.table [row 2, Qty] = "5"`.

### 5.2 Tree path

- Each label is `tree.convertValueToText(...)` of the node, the same text as in the snapshot.
- The path starts with the root label, also when the root is hidden.
- Replay: start at the model root. The root label must equal `path[0]`. For each next label, take the first child with
  that label. A node that is not found gives ERROR `tree node not found: Root/src/X`.

### 5.3 Table column

- The recorder writes the header text (`table.getColumnName(viewColumn)`) when it is not empty and no other column
  has the same text. Otherwise it writes the view index as a decimal string.
- Replay: the first view column whose header text equals `column`. If there is none and `column` is an integer, the
  view column with that index. Otherwise ERROR `column not found: <column>`.

### 5.4 New field of `Step`

`group` (string, optional). A step with `group` starts a group. The steps after it belong to the group until the next
step with `group`. The player ignores the field for the replay.

## 6. Recording and replay of each type

The "place" of a mouse event in a component is: for a `JTable` the row and column at the point, for a `JList` the row
at the point, for a `JTree` the path at the point (`getPathForLocation`). A point outside each row gives no place.

Events (designer's call):

- The recorder reads the second click of a double-click from `MOUSE_CLICKED` with `getClickCount() == 2`. It reads
  the state that the click caused (edit mode, selection) in a later EDT task, as the existing selection code does.
- The recorder records a right-click at `MOUSE_PRESSED` of button 3. The existing rule gives the snapshot of the
  step before it at this press. The right-click step gets its snapshot at the next input, as each step does.
- A step that the recorder changes in place (6.2, 6.5) is pending again. All step changes run on the EDT. The
  snapshot thread must wait for a quiet UI again before it takes the snapshot of such a step: a wait that started
  before the change does not count.

Replay with a place (`CLICK`, `SET_CELL`): the player first makes the place visible. For a tree it expands the parents
and calls `scrollPathToVisible`. For a table or a list it calls `scrollRectToVisible` with the cell bounds. A row
index outside the model gives ERROR `row not found: <row>`.

Old steps: a `CLICK` on a tree, table or list without a place replays as today, at the center of the component.

### 6.1 Tree selection

- Recording: a click (button 1, count 1) on a row of a `JTree` records `SELECT` with the lead selection path, read
  after Swing processed the click. This replaces the `CLICK` without a node that the recorder writes today. A click that is not on a row (for example on the expand handle) records nothing.
- Replay: find the node (5.2), expand its parents, `setSelectionPath`, `scrollPathToVisible`.
- Limit: a tree that loads its children in a background thread is not supported. Write this in `README.md`.

### 6.2 Double-click

- On a `JTable`, `JList` or `JTree`: the first click records its `SELECT` as today. The second click (count 2) adds
  one `CLICK` step with `count: 2` and the place.
  Exception: if the table is in cell edit mode after the double-click, the recorder adds no `CLICK` step. The
  `SET_CELL` step of that edit replaces it (6.4).
- On a component with no interactive ancestor: the first click records `CLICK` as today. The second click changes that
  step to `count: 2`, if it is still the last step. The step then loses its snapshot and is pending again, because the
  snapshot showed the state between the two clicks.
- On a button or a menu item: no change. Each release fires the action, so two clicks are two `CLICK` steps.
- On a text component: no change (no step).
- Replay of `count: 2`: dispatch press, release, click with click count 1, then press, release, click with click
  count 2, at the center of the place (or of the component when the action has no place).

### 6.3 Right-click

- Recording: a press of button 3 records `CLICK` with `button: 3` and the place, on the interactive ancestor, or on
  the source component when there is none. The press records the step on each platform; `isPopupTrigger` is not read.
- The click on an item of the context menu records `CLICK` on the menu item, with the existing code for menu items.
  The builder must check with a test that the locator of an item in a `JPopupMenu` resolves and replays.
- Replay: dispatch press, release, click with button 3 at the center of the place. `popupTrigger` is true on exactly
  one event: the release on Windows, the press on other platforms (`os.name`, `Locale.ROOT`). This opens a menu from a
  mouse listener and a menu from `setComponentPopupMenu`. No direct `JPopupMenu.show` call.

### 6.4 Table cell edit

- Recording: when a cell edit ends and `String.valueOf(table.getValueAt(row, column))` is different from the value
  before the edit, the recorder adds `SET_CELL` with the row, the column (5.3) and the new value. Null gives `""`.
  A cancelled edit and an edit without a change record nothing.
- This includes an editor that starts and stops in one click (a `Boolean` check box cell).
- While a cell edit is in progress, events in the table or in its editor record no other step: no `SET_TEXT` on the
  editor field, no `SELECT` on an editor combo box, no `KEY` for ENTER, TAB or ESC that ends the edit.
  The `SELECT` of the row click that comes before the edit stays.
- Order: `SET_CELL` comes before the step of the input that ended the edit (for example a click on a button).
- F-19: if the editor component is a `JPasswordField`, the recorder records no `SET_CELL` and gives the password
  warning in the status line.
- Replay: find the column, `editCellAt(row, column)`, set the value in the editor component, then
  `getCellEditor().stopCellEditing()`. Editor component: a text component gets `setText`, a check box gets
  `setSelected(Boolean.parseBoolean(value))`, a combo box gets the item whose `String.valueOf` equals the value (an
  editable combo box gets the text). ERROR when the cell is not editable, when the editor component has another
  type, or when `stopCellEditing()` gives false (then cancel the edit first): `the cell editor refused the value "<value>"`.

Known traps, for the builder: the AWT event listener sees an event before the table processes it, so the end of an
edit shows only after the event (use a later EDT task, not a wait). The existing text tracking (`editing`,
`flushText`) must not take a cell editor field.

### 6.5 Spinner

- Only `JSpinner.DefaultEditor`. For another editor the recorder records nothing and gives a status text one time.
- The value of the step is the text of the editor field after the change.
- Typed text: the existing text tracking records it at the flush, as `SET_TEXT` with the locator of the spinner, not
  of the field.
- Arrow buttons: a click on a button in a `JSpinner` records no `CLICK`. After Swing processed it, the recorder
  writes `SET_TEXT` on the spinner with the field text. If the last step is already a `SET_TEXT` on the same spinner,
  the recorder changes its value (the step loses its snapshot and is pending again). So ten arrow clicks give one step.
  The text tracking takes the new text as its start value, so the focus loss adds no second step.
- Replay: `getTextField().setText(value)`, then `spinner.commitEdit()`. A `ParseException` gives ERROR.
- Names: a spinner without `setName` gets its locator as each other component does (automatic name or position;
  WP-09 made `JSpinner` interactive). The lint and the snapshot of a spinner and of its field do not change.

## 7. Toolbar

```
[Pause] [Pick] [Wait for…] [Rename] [Group…] [Up] [Down] [Delete] [Save]
+- steps --------------------------+- changes of s003 ------------------+
| -- Create order --               | CHANGED [Orders]/#orders.status    |
| s001  CLICK #orders.new          |   .text: expected="" actual="Draft"|
| s002  SET_TEXT #orders.qty = "7" | UNEXPECTED [Order]/#order.save     |
| s003  CLICK #orders.save      <  |                                    |
| -- Check list --                 |                                    |
| s004  SELECT #orders.table = 0   |                                    |
+----------------------------------+------------------------------------+
```

The window gets wider for the second pane (the builder selects the size).

### 7.1 Preview

- The right pane shows `SnapshotDiff.diff(previous, current, scenario.ignore)`: `current` is the golden of the selected
  step, `previous` is the golden of the nearest earlier step that has one.
- No difference: `no changes`. No earlier golden: `first golden: no earlier step to compare`.
- A step without a golden shows the reason: pending, a `WAIT_FOR` follows, or removed by a move (7.3).
- The pane updates when the selection changes and when the selected step or an earlier step gets or loses a golden.

### 7.2 Group

- `Group…` opens an input dialog named `smoker.group` with the group name of the selected step. An empty text removes
  the group from the step.
- The step list shows a header line above a step that has `group`. The form of the header is the builder's call,
  with one limit: a header is not a row that a person can select, and it does not change the index of a step.
  `Rename`, `Group…`, `Up`, `Down` and `Delete` always act on a step.
- The HTML report shows a header row above the first step of a group: `RunResult.StepResult` gets `group`, the player
  copies it, `HtmlReportWriter` writes the row. The JUnit XML does not change.

### 7.3 Move

- `Up` and `Down` move the selected step one row. The step keeps its id, its name and its `group`. Ids are not renumbered.
  The `group` label goes with its step, so a move of a step with `group` moves the start of that group (5.4).
- A golden holds the state after all steps before it. After a move, the goldens of the two steps that changed places
  are wrong. The recorder removes both goldens (also a pending snapshot) and marks both rows
  `(no golden: run and approve)`. The owner selected this rule on 2026-11-24.
- If a step with a golden is now directly before a `WAIT_FOR`, the recorder removes that golden too (WP-03: the player
  takes no snapshot there).
- The mark stays in the session. `Save` writes the golden file without these steps, so the next run gives
  `NO_GOLDEN` for them and `approve` repairs them.
- The toolbar writes a move to the record log, as it does for a delete.

## 8. Snapshot

A `JTree` node of the snapshot gets `selectedIndex`: the smallest selected row (`getMinSelectionRow()`), only when a
row is selected. Without it, a tree `SELECT` does not show in the golden or in the preview.

Effect: an existing golden with a tree that has a selected row fails one time with
`CHANGED ... .selectedIndex: expected=null`. `approve` repairs it. Write this in `README.md`. A tree without a selected row does not change.
The goldens in the repository (`docs/wp-01-pilot/`, `smoker-demo/`) have no `JTree`, so they do not change.
(This is a call of the designer, not a decision of the owner.)

## 9. Demo and tests

- `DemoApp` gets a panel behind `-Ddemo.recorderPanel=true`. Without the property the demo does not change, so the
  existing goldens stay valid. The panel has:
  - a tree `demo.tree` (Root; src with Main.java and Util.java; docs with readme.md). A double-click on a leaf writes
    `opened <label>` to the label `demo.picked`. A mouse listener shows a context menu with the item `demo.tree.open`,
    which writes `menu <label of the node at the click>` to `demo.picked`;
  - an editable table `demo.items` with the columns Name (String), Qty (Integer) and Done (Boolean), and a label
    `demo.total` with the sum of Qty. The table has a context menu through `setComponentPopupMenu`, with one item;
  - a spinner `demo.count` (0 to 10).
- New scenario folder `smoker-demo/scenarios-recorder/` with `recorder.json`. It has one step or more for each new
  type: tree `SELECT`, `CLICK` with `count: 2`, `CLICK` with `button: 3` and the menu item, `SET_CELL` on Qty and on
  Done, `SET_TEXT` on the spinner, and one step with `group`.
- `SelfTestIT`: run gives NO_GOLDEN, `approve`, run gives PASS. The golden shows the effects (`opened Main.java`, the
  menu text, the new total, the spinner text). A copy of the scenario with a `SET_CELL` value that the Integer editor
  refuses gives ERROR in that step. The report has the group header row.
- Recording: unit tests in `smoker-agent` with real windows, in the style of `RecorderWindowTest`, for each row of
  section 6, and for the toolbar functions in the style of `RecorderToolbarTest`. A test must not leave a window that
  shows. The recording half has no check in `SelfTestIT`: `record` needs a person.
- Run `mvn -B verify` and `mvn -B verify -Plocale-tr` after each change to `smoker-agent` or `smoker-core`.
  The Windows CI runs the three builds on the pull request.

## 10. Documentation

- `README.md`: the new action fields, `SET_CELL`, `group`, the toolbar functions, the tree limit (6.1), the golden
  effect (8).
- `docs/architecture.md`, section 6: the mapping table and the toolbar text.
- `CLAUDE.md`: the `SelfTestIT` expectations and the state. WP-06 is "code complete" after this work, not complete
  (decision 8).
- `docs/development-plan.md`: the state of WP-06 tasks 2 to 7.

## 11. Left to the builder

- The mechanism that finds the start and the end of a cell edit (6.4), in the limits of the stated behaviour.
- The form of the group header in the step list, and the size and split of the toolbar window.
- The exact `toString` texts and status texts (ASCII).
- The split of `Recorder` and `ActionExecutor` into smaller classes if the new code makes them hard to read.
- Task order and commit split.

## 12. Changes after approval

The controller made these rulings during the build. Each line tells what changed against the text above.

- Ruling 3 (7.3): after a move, the recorder checks the swapped pair and the step before it. A checked step directly
  before a `WAIT_FOR` loses its golden and gets no mark. Another checked step without a golden that is not pending gets
  the mark `(no golden: run and approve)`.
- Ruling 4 (6.4): when the action of the next input ends the edit (a button that stops the edit at the release), its
  step is already recorded. `SET_CELL` goes directly before that step, with no golden and the mark.
- Ruling 6 (6.5, Names): the snapshot of a `JSpinner` gets `text`, the text of its `JSpinner.DefaultEditor` field.
  The field stays skipped. An existing golden with a spinner fails one time; `approve` repairs it.
- Ruling 7 (6.4): replay of `SET_CELL` first selects the cell (`changeSelection`), as a real edit does. A recorded
  `SET_CELL` never gets a golden at record time: it gets the mark, and the next run plus `approve` gives its golden.
  Enter or Tab of the edit that moves the selection of the table gives a `KEY` step after `SET_CELL`, with the
  normal golden. This replaces "no `KEY` for ENTER, TAB" in 6.4 for that case. Escape still gives no step.
- Ruling 8 (6.3): the replay does not find a context-menu item without a name in a heavyweight popup. `README.md`
  tells the user to name these items. `Locators` stay unchanged in this work package (F-23); a follow-up comes after
  the Windows check.
