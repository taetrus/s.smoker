# WP-09 Naming assistant — design

Date: 2026-11-15. Work package: WP-09 (`docs/development-plan.md`, section 4.3). Risk: components without a name.
Branch: `wp-09-naming-assistant`, from `master` (WP-01 and WP-02 merged).

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
| proposed name | A name that `lint` proposes for the developer to put into the code: `<view>.<type>.<field>`. |
| application class | A class whose fully qualified name does not start with `java.`, `javax.`, `sun.`, `com.sun.`, `jdk.`, `com.apple.`, `apple.`, or `smoker.`. |

## 3. Goals

1. **Automatic names, on by default.** For an unnamed component that a field of an application-class object in the
   component tree refers to, the agent uses the automatic name `@<Class>.<field>`. Snapshots, paths, locators,
   recordings and the lint use it like a real name. A real name always wins.
2. **Lint with proposals (tasks 1–4).** For each unnamed interactive component, the lint result has these columns:
   window, application class, field, component type, visible text, proposed name, source of the proposal.
3. **Patch lines (task 5).** `lint --emit-patch <file>` writes one Java line for each component that has a field,
   grouped by class: `saveButton.setName("orders.btn.save");`.
4. **Marked in the report (task 7).** The report marks each automatic name, because it changes when a developer
   renames the field.
5. **Old locators keep working.** When a developer later adds a real name, a scenario recorded with the automatic name
   still finds the component.

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
- Proposed name: `<view>.<type>.<field>`. Example: field `saveButton` in `OrdersPanel` gives `orders.btn.save`.
- **Declaring class and runtime class (designer's call):** the view comes from the runtime class (the panel the user
  sees). The lint records both the runtime class and the class that declares the field. The patch line goes into the
  declaring class when it is the runtime class. When the field is declared in a superclass, the patch writes a comment
  line in the runtime class group instead: `// <field> is declared in <DeclaringClass>: set "<proposed name>" in
  <RuntimeClass>` (a line in the superclass would give the same name to every subclass).
- **Duplicate proposals (designer's call):** when two components of one window get the same proposed name, the second
  gets the suffix `2`, the third `3` (`orders.btn.ok2`), in tree order. One field is one line of code: all components
  of one field (same runtime class, declaring class and field, also in other windows or other instances) get the
  proposal of the first one.
- **Components without a field** (local variables) get a proposal from the visible text instead of the field:
  `<view>.<type>.<text>`, where the text is lower camel case of its letters and digits (`"Base URL:"` gives
  `baseUrl`). The view comes from the nearest application-class ancestor. Source of the proposal = `text`.
  Without text, there is no proposal.
- Proposals are never used as automatic names: a text changes with the language.

### 5.4 The switch

- A scenario field `autoNames` (boolean, default `true`). It is a scenario field and not an agent option, because it
  changes paths and goldens like `compare` does. `false` gives the WP-02 behavior.
- `schemaVersion` stays 2: a missing field means `true`. The recorder writes the field explicitly (`Json.write` writes
  all fields). The existing demo scenarios need no change: their components have real names, and no committed demo
  golden exists.

### 5.5 Lint command and patch

- `smoker-cli lint --run <out dir> [--emit-patch <file>]`. It reads every `run-result.json` below the out directory
  and joins the lint results: one row per class and field; for a component without a field, one row per class,
  type, text and proposed name. The first row wins.
- The agent writes the lint data into `run-result.json`: for each unnamed interactive component, the window part, the
  application class (fully qualified), the field (or none), the component type, the visible text, the automatic name
  (or none), the proposed name (or none), and the source of the proposal (`field` or `text`).
- `lint` prints an aligned text table to standard output and a summary line:
  `<n> unnamed interactive component(s), <p> with a proposed name (<percent> %), <a> with an automatic name`.
- `--emit-patch` writes, grouped by application class (fully qualified name as a comment line), one line per
  field: `<field>.setName("<proposed name>");`. A component with a text proposal gets a comment
  line instead: `// <type> "<text>" has no field: setName("<proposed name>") where it is created`.
- The report (`report.html`) shows the same lint table, and a list of the automatic names used in the run.

## 6. Non-goals

- No change of the application source code by the tool. `--emit-patch` writes a file; a developer applies it.
- No lint baseline and no pipeline failure on lint results (WP-07, version 1.1).
- No automatic names from text, position or tree order.
- No name proposals for components that are not interactive (labels, panels).

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

## 10. Left to the builder

- The exact JSON shape of the lint data in `run-result.json` (a new structured list; keep the WP-02 string lists for
  the existing report sections).
- The column widths of the text table.
- How the demo application shows the "field without a name" and "real name added later" cases (for example system
  properties, as in WP-02).
- Splitting the work into tasks and commits. Each commit builds and passes the tests.
