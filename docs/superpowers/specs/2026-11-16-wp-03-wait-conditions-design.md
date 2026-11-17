# WP-03 Wait conditions — design

Date: 2026-11-16. Work package: WP-03 (`docs/development-plan.md`, section 4.3). Finding: F-02. Requirement: R-23a.
Branch: `wp-03-wait-conditions`, from `master` (WP-01, WP-02 and WP-09 merged).

## 1. Intent

The target applications get answers from a hardware simulator after a delay that is not known. The player waits only
for quiescence (empty event queue for `quietMs`), so it records the state too early (F-02). The only workaround now is
`WAIT` (a fixed sleep), which is slow when the delay is short and flaky when it is long.

`WAIT_FOR` waits until one attribute of one component agrees with a regular expression, or until a timeout occurs.

## 2. Goals

1. Action type `WAIT_FOR` with the fields `locator`, `attribute`, `matches`, `timeoutMs` (plan task 1).
2. The player examines the condition each 100 ms on the EDT (task 2).
3. On timeout: FAIL, with the last value in the step result (task 3).
4. Recorder: a `Wait for…` button. The developer selects the component with the picker (task 4).
5. Demo: a delayed answer and a scenario `wait.json` that uses `WAIT_FOR` (task 5).
6. No snapshot race on the step before a `WAIT_FOR` (owner decision B, 2026-11-16, section 5).

Completion criterion (plan): R-23a passes — a scenario waits for a label that changes 3 s after a click, and the step
passes. No scenario for the pilot application uses `WAIT`. On 2026-11-16, no file in `docs/wp-01-pilot/` uses `WAIT`.

## 3. Non-goals

- No change to `WAIT`. It stays for compatibility.
- No conditions on more than one component, no `AND`/`OR`, no negation. One regex covers most cases.
- No wait for a component to disappear. (A later work package can add it, if the pilot shows a need.)
- No test on a product with the hardware simulator in this work package: no such product is available
  (`CLAUDE.md`, "Not verified"). The demo delay stands in for it.

## 4. Anti-goals

- The wait must never block the EDT. `WAIT` does this now (`Thread.sleep` in `ActionExecutor`); `WAIT_FOR` must not.
- A `JPasswordField` value must never appear in a step result, a report or a scenario (F-19), also not as "last value".

## 5. Approaches (snapshot of the step before `WAIT_FOR`)

The step before a `WAIT_FOR` (for example "click Query") takes its snapshot after `quietMs` of quiet. With a real
simulator the answer can arrive in that window, so the golden of that step sometimes shows "waiting…" and sometimes the
answer.

| | Approach | Trade-off |
|---|---|---|
| A | Each step keeps its snapshot, as the plan says. Users add `ignore` rules. | Simplest. The flaky golden stays. |
| **B** | **A step directly followed by a `WAIT_FOR` step takes no snapshot.** | Format unchanged, race removed. The rule is implicit. |
| C | Optional field `waitFor` on any step: action, wait, snapshot. | Clean, but changes the format that the plan defines. |

**Owner picked B (2026-11-16).**

## 6. Design

### 6.1 Format (`smoker-core`, `Action`)

New enum value `WAIT_FOR` and three new fields on `Action`:

```json
{ "type": "WAIT_FOR", "locator": { "name": "demo.answer" },
  "attribute": "text", "matches": "ready .*", "timeoutMs": 10000 }
```

- `attribute`: one of `text`, `title`, `visible`, `enabled`, `selected`, `rowCount`, `itemCount`, `selectedIndex`.
  These are the scalar fields of `NodeSnapshot`. `items` and `cells` are not supported.
- `matches`: Java regex. **The whole value must match** (`Matcher.matches()`, not `find()`).
- `timeoutMs`: optional. Default 10000.
- `schemaVersion` stays 2: the change only adds fields. Old scenarios read as before.

### 6.2 Value of an attribute

The value comes from the same code that builds a snapshot node (`SnapshotTaker.node`), so a snapshot and a wait see the
same value and `JPasswordField` stays masked (F-19).

- Numbers and booleans become their `String.valueOf` text (`"true"`, `"3"`).
- `visible` and `enabled` are `null` in a node when they are `true`. For `WAIT_FOR`, `null` becomes `"true"`.
- Other `null` values never match. The last value shows as `null`.
- A component that `Locators.find` does not find never matches. The last value shows as `<component not found>`.
  The player continues to poll: the component can appear later (for example in a dialog).

### 6.3 Player

`WAIT_FOR` does not go through `ActionExecutor` on the EDT. `Player.runStep` handles it on the player thread:

1. Validate first: unknown `attribute`, missing `locator` or invalid `matches` give ERROR at once.
2. Each 100 ms: run one short task on the EDT (find the component, read the value), with a time limit, in the style of
   `Player.mainWindowShown`. Never `invokeAndWait` without a limit.
3. Match → continue with the normal step: settle (`Quiescence.await`), snapshot, compare, log hits.
4. Timeout → FAIL. The step result has the message
   `WAIT_FOR [locator] text did not match "ready .*" within 10000 ms; last value: "waiting…"`.
   The player continues with the next step (F-21 rule: continue after FAIL). The step still takes its snapshot, so
   `approve` and the report work as for other steps.
5. If the EDT does not answer and the watchdog reports a hang → HANG, as for other steps.

`timeoutMs` is independent of `stepTimeoutMs`.

### 6.4 Step before `WAIT_FOR` (decision B)

- Player: if the next step in `scenario.steps` is a `WAIT_FOR`, the current step runs its action and settles, but
  takes no snapshot and does no compare. Its status is PASS, unless the action fails or log hits occur.
  `actual` stays `null`; `approve` already skips such steps (`Main.approve`, `sr.actual == null`).
- Golden: the step has no entry. The delta encoding of `GoldenCodec` continues from the previous step that has one.
- The step is not NO_GOLDEN: the player knows that the step has no golden by design.
- Recorder: when the developer adds a `WAIT_FOR` step, the recorder removes the snapshot (taken or pending) of the step
  before it.

### 6.5 Recorder (`Wait for…`)

1. The toolbar gets a button `Wait for…`. A click turns on a select mode: the picker highlights the component under
   the mouse, as now.
2. The next click on an application component selects it. That click must not reach the application and must not be
   recorded. My call: put a transparent glass pane on the application windows while select mode is on; the recorder
   ignores input in that time. ESC or a second click on the button cancels.
   **Owner decision (2026-11-16): the select mode accepts only a component with a real name or a unique automatic
   name** (`Locators.resolve` gives a locator with `name`). For any other component, the status line shows
   "needs a name" and the proposed name (`NameProposer`), and select mode stays on. Reason: a locator without a name
   contains the text of the component, so a `WAIT_FOR` on `text` would find the component only when it shows the
   recorded value, and the regex would have no effect. A label that the application updates is normally a field, so
   an automatic name covers it. The player does not enforce this rule: it accepts any locator in a hand-written scenario.
3. A dialog (named `smoker.waitfor`) shows the locator and asks for: attribute (combo box, default `text`), regex
   (default: the current value, quoted with `Pattern.quote`, so it matches at once), timeout (default 10000).
4. OK adds the `WAIT_FOR` step after the last step, and removes the snapshot of the step before it (6.4).

The developer normally clicks `Wait for…` after the answer is visible. The current value then matches, and the
recorder snapshot of the `WAIT_FOR` step shows the answer.

### 6.6 Demo application and scenario

- `DemoApp`: a button `demo.query` and a label `demo.answer`. A click sets the label to `waiting…`, starts a background
  thread that sleeps 3 s (stands in for the simulator), then sets `ready 42` on the EDT and logs an info line.
- `smoker-demo/scenarios/wait.json`: click `demo.query`, then `WAIT_FOR demo.answer text "ready \\d+"`.
- `SelfTestIT`: `wait.json` passes (R-23a). A second run with `timeoutMs` 1000 gives FAIL with the last value
  `waiting…` in the step result.
- The new components must not change the paths in the existing demo goldens.

### 6.7 Documentation

Same commit as the format change (project rule): `README.md` (scenario format, `WAIT_FOR`, rule 6.4) and the demo
scenario. `docs/architecture.md` gets a line about the wait in the player.

### 6.8 Builder calls (from the builder check)

- **Lint side effects.** `SnapshotTaker.node` records lint entries. The 100 ms poll must read the value without them:
  split the attribute reading from the lint recording.
- **`title`.** `node()` does not set `title` (only `take()` does, for windows). The value reader reads it separately.
- **CLI time limit.** `Main.run` derives its time limit from `stepTimeoutMs`. Add the `timeoutMs` of each `WAIT_FOR`
  step to it.
- **Recorder snapshot of the `WAIT_FOR` step.** If the developer edits the regex so that it does not match yet, the
  recorder waits for the condition (with the timeout) before it takes the snapshot of the step.
- **IT.** `wait.json` follows `greet.json` (run, `approve`, run again → PASS). The timeout scenario goes into
  `smoker-demo/scenarios-failing`, which run4 of `SelfTestIT` already uses.

## 7. Tests

- Unit (`smoker-core`): JSON round trip of `WAIT_FOR`; old scenario without the new fields reads as before.
- Unit (`smoker-agent`): value conversion (6.2), whole-match rule, timeout message, ERROR on invalid regex or attribute,
  no snapshot for the step before `WAIT_FOR`, password field gives `***` as the last value.
- IT: section 6.6. Run `mvn -B verify` and `mvn -B verify -Plocale-tr`.

## 8. Left to the builder

- Class layout: where the poll loop and the value reader live (a small `WaitCondition` class is fine).
- How `SnapshotTaker.node` is made available for one component without a full snapshot.
- Layout of the `Wait for…` dialog.
