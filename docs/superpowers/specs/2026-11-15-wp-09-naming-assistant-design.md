# WP-09 Naming assistant — design

Date: 2026-11-15. Work package: WP-09 (`docs/development-plan.md`, section 4.3). Risk: components without a name.
Branch: `wp-09-naming-assistant`, from `master` (WP-01 and WP-02 merged).

Updated 2026-11-23 for the follow-up: plan items 8 to 13 (PR #17, PR #18, PR #21). This document describes the
behavior on `master` after these pull requests. Section 11 lists the changes to the first version.

## 1. Intent

Most components of the target products have no name. In the WP-01 pilot, 14 of 14 interactive components had no
name. WP-02 compares unnamed components by an anchored path (`[Settings]/JTextField[1]`), and such a path changes
when a component of the same class appears in front of it. Names make paths and locators stable.

The owner wants two paths that work together (2026-11-15):

- **Developers add real names where they can.** The tool tells them which components need a name, proposes the name,
  and writes the Java line for them.
- **The tool handles the components that still have no name.** At run time it gives such a component an automatic
  name, with no change of the source code. This is on by default.

## 2. Terms

| Term | Meaning |
|---|---|
| real name | A name that the application sets with `setName(...)` and that `Locators.isAutoName` does not reject. |
| generated name | A name that Swing makes itself, for example `frame0` or `null.contentPane`. It counts as no name (unchanged). |
| automatic name | A name that s.smoker gives at run time: `@<Class>.<field>`. It is never written into the application. |
| proposed name | A name that `lint` proposes for the developer to put into the code: `<view>.<type>.<stem>`. |
| stem | The last part of the proposed name. It comes from the field, the visible text or the label (section 5.3). |
| generated field name | A field name that a GUI builder made (`jButton1`, `btnNewButton_1`), or that only names the type (`textField_1`, `comboBox`, `tabs`). |
| interactive component | A component that the user changes or acts on (section 5.7). Only interactive components get a lint entry. |
| lint file | `<scenario>.lint.json` beside the scenario. The recorder writes it at each save (section 5.6). |
| application class | A class whose fully qualified name does not start with `java.`, `javax.`, `sun.`, `com.sun.`, `jdk.`, `com.apple.`, `apple.`, or `smoker.`. |

## 3. Goals

1. **Automatic names, on by default.** For an unnamed component that a field of an application-class object in the
   component tree refers to, the agent uses the automatic name `@<Class>.<field>`. Snapshots, paths, locators,
   recordings and the lint use it like a real name. A real name always wins.
2. **Lint with proposals (tasks 1–4).** For each unnamed interactive component, the lint result has these columns:
   window, application class, field, component type, visible text, label, automatic name, proposed name, source of
   the proposal.
3. **Patch lines (task 5).** `lint --emit-patch <file>` writes one Java line for each component that has a field,
   grouped by class: `saveButton.setName("orders.btn.save");`.
4. **Marked in the report (task 7).** The report marks each automatic name, because it changes when a developer
   renames the field.
5. **Old locators keep working.** When a developer later adds a real name, a scenario recorded with the automatic name
   still finds the component.
6. **Lint without a run (follow-up).** A record session gives the lint. A team can find the components that need a
   name with `record`, `Save` and `lint`, without a scenario, a golden or a run (section 5.6).

## 4. Decisions of the owner (2026-11-15)

| Decision | Effect |
|---|---|
| Developers add real names where they can; the tool handles the rest. | Lint, proposals and patch lines for developers; automatic names at run time for the rest. |
| Automatic names are on by default. | The default of the switch is `true` (section 5.4). |
| Proposed names look like `orders.btn.save`. | Format `<view>.<type>.<field>` (section 5.3). This refines decision 3 (`<view>.<widget>`): the widget part is `<type>.<field>`. Record this in `docs/development-plan.md`. |

## 5. Design

### 5.1 Automatic names

- For an unnamed component, the agent goes up its ancestors (the window included). The first ancestor that is an
  instance of an application class AND has a field that refers to the component gives the name (designer's call:
  an application-class ancestor without such a field is passed over, so a helper panel class does not hide the field
  of the window or view that holds the component). It reads the fields of the runtime class and of its superclasses
  up to the first non-application class. Only instance fields whose value is the component itself count: no static
  fields, no arrays or collections (designer's call). The name is
  `@<SimpleName of the runtime class of that ancestor>.<field name>`.
- Inside one class hierarchy, the first matching field in declaration order wins, subclass before superclass.
- Layout containers (WP-02 `isLayoutContainer`, for example an unnamed `JPanel` held in a field) never get an
  automatic name (designer's call): they show no state, and as named nodes they would only add golden lines. They
  stay transparent as in WP-02.
- The window itself counts as a container (a `JFrame` or `JDialog` subclass of the application often holds the fields).
- From a `JPopupMenu` with an invoker, the walk goes on from the invoker (as `Locators.logicalWindowOf`). So a menu
  item gets the same name in a closed menu, a lightweight popup and a heavyweight popup.
- For an anonymous or local class, use the simple name of the nearest enclosing named class.
- Use reflection with `setAccessible(true)`. If access fails (security manager, module rules), skip that field
  without an error.
- Cache the field list per class. Compute the map once for each snapshot and for each locator search, on the EDT.
- A component without such a field has no automatic name. It keeps the WP-02 path rule.
- A component with a real name never gets an automatic name.
- Two components with the same automatic name in one window (two instances of one panel class) get the WP-02 repeat
  index (`#@OrdersPanel.okButton[1]`), as for real names.

### 5.2 Where automatic names apply

- **Mode `named`:** an automatically named component counts as named, so it is compared in mode `named` too
  (designer's call: the tool "handles the names", so a team in mode `named` gets these components without code
  changes).
- **Snapshot paths and nodes:** an automatically named component counts as named. Its path is
  `<window part>/#@<Class>.<field>`, and `NodeSnapshot.name` holds the automatic name. It is an anchor like a named
  component.
- **Locators:** `Locators.resolve` writes the automatic name into `Locator.name` when the component has no real name.
  `Locators.find` matches a named locator against the real name, and also against the automatic name of each
  component, also when the component has a real name now (goal 5).
- **Recorder:** records with the same rules, through `Locators.resolve`. The recorder toolbar still shows an
  automatically named component as "needs a real name".
- **When a developer later adds the real name:** the locators still find the component (goal 5), so the replay runs.
  The golden paths of that component change once, from `#@OrdersPanel.saveButton` to `#orders.btn.save` (and the paths
  anchored below it). The run gives FAIL with these path differences only; one `approve` fixes it, and the reviewer
  sees one rename in the golden diff (conversation of 2026-11-15). No alias mechanism in the diff.
- **Static state:** the switch reaches `Locators` and `SnapshotTaker` the same way as `windowKeys` (a static setting set
  by `SmokerAgent` from the scenario; tests reset it).
- **Duplicate-name lint:** counts automatic names like real names.
- **Unnamed-interactive lint:** a component with an automatic name is not "unnamed" for the path rules, but it still
  needs a real name. It stays in the lint result, with its automatic name shown.

### 5.3 Proposed names

- View: the simple name of the application class, minus one suffix of `Panel`, `View`, `Dialog`, `Frame`, `Window`,
  `Pane`, `Form`, `Page`, `Tab`; the first letter in lower case (`Locale.ROOT`). `OrdersPanel` gives `orders`.
- Type, by component class (first match, `instanceof`):

  | Class | Type |
  |---|---|
  | `JPasswordField` | `pwd` |
  | `JTextField`, `JFormattedTextField` | `txt` |
  | `JTextArea`, `JTextPane`, `JEditorPane` | `txt` |
  | `JCheckBox`, `JCheckBoxMenuItem` | `chk` |
  | `JRadioButton`, `JRadioButtonMenuItem` | `rad` |
  | `JToggleButton` | `tgl` |
  | `JMenuItem`, `JMenu` | `menu` |
  | `AbstractButton` (other) | `btn` |
  | `JComboBox` | `cmb` |
  | `JList` | `lst` |
  | `JTable` | `tbl` |
  | `JTree` | `tree` |
  | `JTabbedPane` | `tabs` |
  | `JSpinner` | `spn` |
  | `JSlider` | `sld` |
  | other | the simple class name in lower case, without a leading `J` |

- Field: the field name, minus one type suffix of `Button`, `Btn`, `Field`, `Fld`, `Area`, `Pane`, `Box`, `Combo`,
  `ComboBox`, `List`, `Table`, `Tree`, `CheckBox`, `Check`, `Radio`, `Item`, `Menu`, `Label`, `Spinner`, `Slider`
  (longest match first, only when something remains); the first letter in lower case. `saveButton` gives `save`.
- Proposed name: `<view>.<type>.<stem>`. Example: field `saveButton` in `OrdersPanel` gives `orders.btn.save`.
- **Stem (follow-up, PR #18).** The stem comes from the first source that gives one. The lint records the source:

  | Order | Source | Stem | Example |
  |---|---|---|---|
  | 1 | `field` | The field name without its type suffix, when the field name is not generated | `saveButton` gives `save` |
  | 2 | `text` | The visible text of the component, in lower camel case | `"Base URL:"` gives `baseUrl` |
  | 3 | `label` | The text of the label of the component, in lower camel case | label `"Customer:"` gives `customer` |
  | 4 | `field` | A generated field name without its type suffix | `jTextField2` gives `jTextField2` |

  Without a stem from these sources, there is no proposal.
- **Generated field names.** `NameProposer.isGeneratedField` gives true for: the NetBeans pattern `j<Type><digits>`
  (`jButton1`), the WindowBuilder pattern `<prefix>New<Type>` with an optional `_<digits>` (`btnNewButton_1`,
  `chckbxNewCheckBox`, `mntmNewMenuItem`), and a field whose stem, without a trailing `_<digits>` or digits, only names
  the type (`textField_1`, `comboBox`, `list`, `tabs`, `textPane`). Such a name gives a poor proposal
  (`orders.btn.jButton1`), so the text and the label come first. It stays the last source, so no proposal is worse
  than in the first version. The pilot fields `textPane` and `textArea` have no text and no label: their proposals stay
  `chat.txt.text` and `input.txt.text`.
- **Label.** `JLabel.setLabelFor(c)` puts the label into the client property `labeledBy` of `c`.
  `Locators.labelOf` reads it. The lint needs no search of the window. A label beside a component without
  `setLabelFor` gives no stem (section 6).
- **Visible text.** `Locators.textOf`: the text of a button or a menu item. A text component has no visible text for
  the stem: its content is data, not a name. HTML tags and entities of a text do not count
  (`"<html><b>Save</b>&nbsp;all"` gives `saveAll`).
- **Declaring class and runtime class (designer's call):** the view comes from the runtime class (the panel the user
  sees). The lint records both the runtime class and the class that declares the field. The patch line goes into the
  declaring class when it is the runtime class. When the field is declared in a superclass, the patch writes a comment
  line in the runtime class group instead: `// <field> is declared in <DeclaringClass>: set "<proposed name>" in
  <RuntimeClass>` (a line in the superclass would give the same name to every subclass).
- **Duplicate proposals (designer's call):** when two components of one window get the same proposed name, the second
  gets the suffix `2`, the third `3` (`orders.btn.ok2`), in tree order. One field is one line of code: all components
  of one field (same runtime class, declaring class and field, also in other windows or other instances) get the
  proposal of the first one.
- **Components without a field** (local variables) get the stem from the text or the label. The view comes from the
  nearest application-class ancestor.
- The `Wait for…` picker of the recorder proposes a name for an unnamed component with the same rules.
- Proposals are never used as automatic names: a text changes with the language.

### 5.4 The switch

- A scenario field `autoNames` (boolean, default `true`). It is a scenario field and not an agent option, because it
  changes paths and goldens like `compare` does. `false` gives the WP-02 behavior.
- `schemaVersion` stays 2: a missing field means `true`. The recorder writes the field explicitly (`Json.write` writes
  all fields). The existing demo scenarios need no change: their components have real names, and no committed demo
  golden exists.

### 5.5 Lint command and patch

- `smoker-cli lint --run <dir> [--emit-patch <file>]`. It reads every `run-result.json` and every lint file
  (`*.lint.json`) below the folder, so `--run` can be an out folder or a scenario folder. It joins the lint results:
  one row per class and field; for a component without a field, one row per class, type, text and proposed name.
  The first row wins, in the sorted order of the file paths.
- The agent writes the lint data (`LintEntry`) into `run-result.json`, and the recorder into the lint file: for each
  unnamed interactive component, the window part, the application class (fully qualified), the class that declares
  the field, the field (or none), the component type, the visible text, the label text (or none), the automatic name
  (or none), the proposed name (or none), and the source of the proposal (`field`, `text` or `label`).
- `lint` prints an aligned text table to standard output and a summary line:
  `<n> unnamed interactive component(s), <p> with a proposed name (<percent> %: <f> from fields, <t> from text,
  <l> from labels), <a> with an automatic name`.
- `lint` exits with 0. It exits with 2 when it finds no `run-result.json` and no lint file, or cannot write the patch.
- `--emit-patch` writes, grouped by application class (fully qualified name as a comment line), one line per
  field: `<field>.setName("<proposed name>");`. A component with a generated field name also gets this line, with the
  proposal from its text or label (`jButton1.setName("orders.btn.save");`). A component without a field gets a comment
  line instead: `// <type> "<text>" has no field: setName("<proposed name>") where it is created`, or for a label
  proposal `// <type> with the label "<label>" has no field: ...`.
- The report (`report.html`) shows the same lint table, and a list of the automatic names used in the run.

### 5.6 Lint of a record session (follow-up, PR #17 and PR #21)

- At each `Save`, the recorder writes the scenario, the golden file and the lint file `<scenario>.lint.json`
  (`LintFile`: the scenario name and the lint entries). The lint holds the components of all snapshots of the record
  session, and of one more snapshot at the save. That snapshot is for the lint only: no step gets it, and the golden
  does not change. So a record session with no steps also gives a lint.
- A lint failure does not stop the save: the scenario and the golden are written first.
- Each save replaces the lint file. The file holds the last record session only.
- `ScenarioFiles.isScenarioFile` skips `*.golden.json` and `*.lint.json`, so a scenario folder with lint files runs
  as before.
- After the application stops, `record` prints the path of the lint file and the summary line, if the session saved.
  A lint file older than the session gives no output.
- `record` creates the scenario folder before the application starts (plan item 12). Before, a folder that did not
  exist gave a stack trace: the CLI could not create `<scenario>.record.log`.
- The walkthrough: start `record`, click `Record`, open each window, dialog and tab, close the dialogs, click `Save`.
  A modal dialog of the application can block the toolbar; the snapshot of the step that opened the dialog keeps the
  dialog in the lint. `Save` without `Record` gives the lint of the windows that show at the save.
- The toolbar shows the state of the recorder (plan item 13): its title ends with `paused` or `RECORDING`, the toggle
  follows the state, and the status line shows `paused: click Record to record the clicks` at the start. A save with
  no steps while paused adds `no steps: the recorder is paused, click Record to record the clicks`. In the Windows check,
  a click on `Record` did not arrive, and one session had no steps.

### 5.7 Interactive components

- `Locators.isInteractive`: `AbstractButton` (buttons, toggle buttons, check boxes, radio buttons, menu items, menus),
  `JTextComponent`, `JComboBox`, `JList`, `JTable`, `JTree`, `JTabbedPane`, `JSpinner` and `JSlider`.
  `JSpinner` and `JSlider` came with plan item 9 (PR #17).
- The editor text field of a spinner is part of the spinner: the snapshot walk skips the children of a `JSpinner`, so
  the text field gives no entry of its own.
- Buttons that a `JOptionPane` makes give no entry: the application cannot name them.

## 6. Non-goals

- No change of the application source code by the tool. `--emit-patch` writes a file; a developer applies it.
- No lint baseline and no pipeline failure on lint results (WP-07, version 1.1).
- No automatic names from text, position or tree order.
- No name proposals for components that are not interactive (labels, panels).
- No label from the position: a label beside a field without `setLabelFor` gives no stem. A guess by position can
  pick the wrong label (follow-up decision, PR #18).
- No proposal from an array or a collection index: a component in `JButton[]` or `List<JButton>` has no field. It gets
  the stem from its text or label, and the patch writes a comment line.

## 7. Anti-goals

- An automatic name that changes between two runs of the same build.
- A scenario recorded with an automatic name that fails after the developer adds the proposed real name.
- A reflection error that stops a snapshot, a recording or a replay.
- A password or other field value read through reflection and written anywhere. The tool reads only which component a
  field refers to.

## 8. Constraints

- Java 8 source level, `--release 8`. No new runtime dependency.
- Every Swing access on the EDT. Reflection on application objects also on the EDT, during the walk.
- `Locale.ROOT` for all case conversion and `String.format`.
- Windows named `smoker.*` are excluded.
- `mvn -B verify` and `mvn -B verify -Plocale-tr` pass after each change to `smoker-agent` or `smoker-core`.
- A change of the scenario or golden format updates `README.md` and the demo scenarios in the same commit.

## 9. Completion criterion (plan)

- On the pilot application, the tool proposes a name for at least 80 % of the unnamed interactive components.
  Proposals from fields and from text both count (designer's call, said to the owner on 2026-11-15); the summary line
  shows both numbers. Components with an automatic name stay in the denominator: they still need a real name.
  Measure it on p.osgi.ds (`com.kk.pde.ds.product`): the macOS build on the development Mac is acceptable for the
  percentage; write the `lint` summary line into the test report.
- A scenario that uses automatic names passes on Windows. Add this to `docs/wp-01-handoff.md` for the next Windows
  session, together with the re-approval of the pilot goldens (the goldens change once more, because automatic names
  change the paths).
- On the demo application (SelfTestIT): a component held in a field without a name gets an automatic name, a scenario
  recorded with it passes, and it still passes after the demo sets the proposed real name (goal 5).

Result: met on 2026-11-16. The pilot gave 14 proposals for 14 components (100 %, `docs/wp-09-pilot-report.md`).
`docs/wp-01-pilot/pilot-autonames.json` passes on Windows with JRE 8.

Follow-up check on Windows (2026-11-23, `docs/wp-09-windows-report.md`): checks N-1 to N-6 pass on `p2.product`.
A record session without a run gives 14 components and 14 proposals, the same table as on macOS. Not tested on a
product: plan items 9 (spinner, slider), 10 (generated field names), 11 (label source) and the toolbar state of
item 13. Unit tests cover them.

## 10. Left to the builder

- The exact JSON shape of the lint data in `run-result.json` (a new structured list; keep the WP-02 string lists for
  the existing report sections).
- The column widths of the text table.
- How the demo application shows the "field without a name" and "real name added later" cases (for example system
  properties, as in WP-02).
- Splitting the work into tasks and commits. Each commit builds and passes the tests.

## 11. Changes after the first version

| Date | Pull request | Plan item | Change | Sections |
|---|---|---|---|---|
| 2026-11-23 | #17 | 8 | The recorder writes the lint file at each save; `lint --run` reads lint files; `record` prints the summary | 3, 5.5, 5.6 |
| 2026-11-23 | #17 | 9 | `JSpinner` and `JSlider` are interactive | 5.7 |
| 2026-11-23 | #17 | 12 | `record` creates the scenario folder | 5.6 |
| 2026-11-23 | #18 | 10 | A generated field name gives way to the text and the label | 2, 5.3 |
| 2026-11-23 | #18 | 11 | Source `label` from `JLabel.setLabelFor`; label column; summary counts labels; HTML text | 2, 5.3, 5.5 |
| 2026-11-23 | #21 | 13 | Toolbar state, hint for a save with no steps while paused | 5.6 |
