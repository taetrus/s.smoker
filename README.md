# s.smoker — Swing smoke tester

Record a short scenario on a Swing app, replay it on every build, and fail when the UI state, the
application log, or the EDT misbehaves. Attaches with `-javaagent`, no app code changes; JRE 8+.

```
smoker-core   model (Scenario/Step/Action/Locator/Snapshot), diff, JSON, JUnit-XML + HTML reports — no Swing
smoker-agent  -javaagent: recorder toolbar, semantic player, snapshot taker, log tailer, EDT watchdog   → smoker-agent.jar (shaded)
smoker-cli    launches the app with the agent, approves goldens, writes reports                           → smoker-cli.jar   (shaded)
smoker-demo   tiny Swing app + scenarios for the self-test
smoker-it     integration test: CLI + agent + demo app, full record-free cycle
```

## Build

```bash
mvn -B package                 # JDK 8–21; on JDK 9+ compiles with --release 8; runs the unit tests
mvn -B verify                  # also runs the integration test in smoker-it (needs a display)
mvn -B verify -Plocale-tr      # all tests in the Turkish locale (tr_TR)
xvfb-run -a mvn -B verify      # Linux without a display
```

The integration test runs `run`, `approve`, `run`, `run` on `smoker-demo/scenarios` and `run` on
`smoker-demo/scenarios-failing`. It expects NO_GOLDEN, PASS, FAIL with `CHANGED …#demo.lang`, ERROR for `empty-name` and HANG.
`empty-name` gives FAIL for s001 (a log hit) and ERROR for s002 (the action throws an exception).
It also adds a step, removes a step and approves one step, and checks the golden file after each change.
It copies the scenarios to `smoker-it/target/it-work` and does not change the files in the repository.
The CLI and the demo application use the JVM and the locale of the test.
The demo application has two system properties for the log analysis (WP-04).
`-Ddemo.logPanel=true` shows a "Log" panel with the buttons `demo.logTrace`, `demo.logInfoError`, `demo.printTrace` and `demo.exit`.
The panel is off by default, so the goldens of the other scenarios do not change.
`-Ddemo.equinoxError=true` prints an Equinox start error to `System.out` before the UI is built.
`smoker-demo/scenarios-log/` holds `log.json` and `exit.json`. Both need `-Ddemo.logPanel=true`.
The integration test checks these results:
a JUL error with a 40-line stack trace gives one hit in s001 (`log.json`);
`Error count 0 of 3` and `Exception handler ready` give no hit;
`printStackTrace` gives one `stderr` hit;
`System.exit(7)` in a step gives ERROR with `during step s001` and exit code 1 of the CLI (`exit.json`);
the Equinox error gives FAIL of the step `startup` with 4 `stdout` hits and no run of s001;
`approve` without `run-result.json` gives exit code 2.
Without a display the integration test fails, and `LocatorsWindowTest` is skipped.

Windows (PowerShell): `mvn -B verify` then run the commands below; no Xvfb needed on a logged-in desktop.

## Use

```bash
# 1. record (floating toolbar appears over the app: Record / Pick / Wait for… / Rename / Delete / Save)
java -jar smoker-cli.jar record --agent smoker-agent.jar --app "java -jar myapp.jar" --scenario scenarios/orders-create.json

# 2. run — first run has no goldens → NO_GOLDEN (exit 0 unless --strict)
java -jar smoker-cli.jar run --agent smoker-agent.jar --app "java -jar myapp.jar" --scenarios scenarios/ --out out/ --log logs/app.log

# 3. approve the observed state as golden: writes scenarios/orders-create.golden.json (all steps, or --steps s003,s004)
java -jar smoker-cli.jar approve --run out/orders-create --scenario scenarios/orders-create.json

# 4. run again → PASS / FAIL; out/junit.xml + out/report.html
```

`--app` is the full command line. The agent option is inserted after the first word, or where you put the
token `{agent}`. Equinox product: `--app "myapp.exe -vmargs {agent}"` — or add
`-javaagent:C:\tools\smoker-agent.jar=config=C:\tools\agent.cfg` to `eclipse.ini` under `-vmargs`
(see "Agent configuration file").

Windows: if a path in `--app` contains spaces, put the path in double quotes and write each inner quote as `\"`.
If you do not, Windows splits the `--app` value into many arguments.

```powershell
# Windows PowerShell 5.1: enclose the --app value in single quotes
java -jar smoker-cli.jar run --agent smoker-agent.jar --app '\"C:\Program Files\Java\bin\java.exe\" -jar myapp.jar' --scenarios scenarios --out out
```

```bat
rem cmd.exe: enclose the --app value in double quotes
java -jar smoker-cli.jar run --agent smoker-agent.jar --app "\"C:\Program Files\Java\bin\java.exe\" -jar myapp.jar" --scenarios scenarios --out out
```

A path without spaces needs no quotes. This is the easier procedure.

The CLI writes the console output of the application to `<scenario>.record.log` (record) and to `app-output.log`
(run). The application writes this output in its console charset, not in UTF-8: Cp1252 on a Windows with English
or German settings, Cp1254 on a Turkish Windows. The CLI copies the bytes and does not convert them.
The `[smoker]` lines of the agent are ASCII, except for texts of the application, for example a label text.
Scenario, golden and `run-result.json` files are always UTF-8.

The agent writes `run-result.json` before startup, before each action and at the end. Until the end, `finishedAt`
is 0 and `currentStep` names the step that runs (`startup` included). When the application calls `System.exit`
before the end, a shutdown hook writes the file with status ERROR, the error
`the application exited during step <id>` and the log hits of that step.

The CLI deletes an old `run-result.json` before it starts the application. After the run it reads the file:

- The file has `finishedAt` 0: the application stopped before the end. The CLI sets the status ERROR and the error
  `the application stopped during step <id>; exit code <n>`. If the CLI killed the application at the time limit,
  the status is HANG and the error ends with `killed after <ms> ms`.
- The file comes from the exit hook: the CLI adds `; exit code <n>` to the error.
- There is no file: ERROR `no run-result.json produced; app exit code <n>`, or HANG after a kill.

`run` passes `--log`, `--screenshots`, `--failRegex`, `--allowRegex`, `--logPattern`, `--failLevel` and `--logCharset`
to the agent. `record` passes `--log`, `--logPattern`, `--failLevel` and `--logCharset`. `--log a.log,b.log` becomes `log.1` and `log.2`.
`run` writes the agent configuration file `<out>/agent-<k>.cfg` for scenario *k*. `record` writes a temporary file.

`approve` without a `run-result.json` in the `--run` folder prints `approve: no run-result.json in <dir>` and exits with 2.

### Agent configuration file (`-javaagent:smoker-agent.jar=config=<file>`)

The only agent argument is `config=<file>`. Everything after `config=` is the path, so the path can contain `;`,
spaces and `=`. The old form `mode=play;scenario=...` stops the application with exit code 2 and the line
`[smoker] fatal: use config=<file> (see README)`. A hand-made `-javaagent` option needs `config=<file>`.
The CLI writes this file for you.

Format rules:

- One `key=value` per line. UTF-8. A BOM at the start is allowed.
- The first `=` ends the key. The agent trims key and value.
- An empty line and a line that starts with `#` have no meaning.
- A value has no escape rules. A backslash, `;` and `=` stay as they are.
- A line without `=` and a key that occurs two times are errors.
- An unknown key is an error: `unknown key '<key>' in <file>`.
- Numbered keys (`scenario.N`, `log.N`, `resetCommand.N`) start at 1 and have no gap:
  `missing key 'scenario.2' in <file>`. `resetTimeoutS.N` needs `resetCommand.N`.

| key | values | default |
|---|---|---|
| `mode` | `play` / `record` | play |
| `scenario.1`, `scenario.2`, ... | scenario JSON (play: input, record: output); `scenario.1` is required | required |
| `out` | root folder of the results; the agent writes the result of a scenario to `<out>/<scenario file name without extension>/` (run-result.json, screenshots) | smoker-out |
| `log.1`, `log.2`, ... | one log file to tail for each key (Logback/Log4j output) | — |
| `logPattern` | Logback pattern of the log lines (the text of `<pattern>` in `logback.xml`) | `%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n` |
| `failLevel` | `TRACE` / `DEBUG` / `INFO` / `WARN` / `ERROR`; a Logback event at this level or higher fails the step | ERROR |
| `failRegex` | regex for plain events (not Logback, not Equinox); a match on the first line fails the step | `\b(ERROR\|SEVERE\|FATAL)\b` |
| `allowRegex` | regex on the full text of an event; a matching event is never a hit | — |
| `logCharset` | charset of the log files | the platform charset (Cp1252 on a Windows with English settings) |
| `screenshots` | `evidence` / `off` | evidence |
| `edtHangMs` | EDT blocked longer than this → HANG + thread dump | 5000 |
| `exitWhenDone` | play: stop the application after writing results | true |
| `resetCommand.1`, `resetCommand.2`, ... | command to reset the application state (read now, used by suite mode later) | — |
| `resetTimeoutS.N` | time limit of `resetCommand.N` in seconds | 60 |
| `resetScenario` | scenario that resets the application (read now, used by suite mode later) | — |
| `stopTimeoutMs` | time to wait after each stage of the stop of the application, in ms (see "Stop of the application") | 10000 |
| `aliveFile` | file of the alive signal (read now, used by suite mode later) | — |

An unknown `failLevel`, an unknown `logCharset`, an unsupported part of `logPattern` or a wrong key stops the
application at start with exit code 2. System.err then has one line `[smoker] fatal: <message>`.

### Stop of the application

When `exitWhenDone` is true, the agent stops the application after it writes the result. The stop has three stages.
After stages 1 and 2, the agent waits `stopTimeoutMs`.

1. The agent sends `WINDOW_CLOSING` to each main frame. The application can close itself and release its resources.
   The agent skips this stage when the status is HANG: the EDT is blocked.
2. The agent stops the OSGi system bundle by reflection. It finds the bundle through the class loaders of the windows
   and through `EclipseStarter`. Without an OSGi framework, it goes to stage 3 at once.
3. The agent calls `System.exit(code)`. The code is 0 for PASS and NO_GOLDEN, and 1 for all other statuses.
   A thread `smoker-halt` calls `Runtime.halt(code)` after `stopTimeoutMs`, when `System.exit` does not return.

The agent writes one line `[smoker] stop: ...` for each stage to the console. The stop does not change the result file:
the agent writes it before stage 1. When the application exits itself in stage 1, the exit code is the code of the application.

### Log rules

The agent reads these sources: the files of `log`, `System.out`, `System.err`, `java.util.logging` and uncaught
exceptions. It decodes `System.out` and `System.err` with the charset of the stream, and the files with `logCharset`.
Lines that start with `[smoker] ` are the output of the agent. They are not events.
The agent assembles the console lines of each writing thread apart, so the output of two threads does not mix in
the hits. The console itself can show mixed lines.

The agent removes ANSI color codes (`ESC[...m`, from Logback `%highlight`, `%red` and others) from each line first.
Then it joins the lines of one source into events:

1. A line that agrees with `logPattern` starts a Logback event.
2. An `!ENTRY` line or a `!SESSION` line starts an Equinox event. The header of a `!SESSION` event ends at its
   first empty line. `!MESSAGE`, `!SUBENTRY` and `!STACK` lines join an open Equinox event.
3. A continuation line joins the open event: a line that starts with whitespace (`\tat …`), `Caused by:`,
   `Suppressed:` or `... N more`. With no open event, a continuation line starts a plain event.
4. An exception name line (for example `java.lang.IllegalStateException: x`) joins the open event. On the console
   it joins only when the same write call gave it and the line before it. Logback writes an event in one call.
   `printStackTrace` writes each line in a separate call, so its trace after a Logback line is a new plain event.
   The exception name line after the `!STACK` line of an Equinox event always joins that event.
5. An empty line joins the open event only when a continuation line follows it.
6. Each other line starts a plain event.

The fail rule runs once for each event:

| Event | Hit when |
|---|---|
| Logback | level ≥ `failLevel` |
| Equinox | severity 4 or 8, or severity 2 when `failLevel` ≤ `WARN` |
| plain | the event has a stack trace, or the first line matches `failRegex` |
| `java.util.logging` | the record has an exception, or level ≥ `failLevel` (SEVERE is ERROR, WARNING is WARN) |
| uncaught exception | always |

`allowRegex` applies to the full text of the event and wins over all other rules.
A hit keeps the first line in `line` and the full text in `text` (at most 50 lines and 8000 characters).
At most 200 hits wait for a step. The agent counts the hits after that and does not keep them. The step then
gets one more hit with source `smoker` and the line `<N> more hits not shown`.

Before a step closes, the agent reads each log file to its end. Each step gets the hits in (end of the last step,
now]. A hit between two steps goes to the next step. A file event has the time of its log line. A file event with
a time before the window of the step goes to the current step, with the prefix `late:` in `source`.

An event that the application still writes at the end of a step can go into two parts in two steps. This is rare:
it occurs only for Equinox output and for output in more than one write call.

A file that exists when the agent starts is read from its end. A file that appears later is read from its start.

Duplicate hits: when the application writes the same Logback lines to the console and to a file, each ERROR gives
two hits. Do not use `log` for a product that logs to the console.
The same is true for `java.util.logging` and uncaught exceptions: the default JUL `ConsoleHandler` writes each
record to `System.err`, and the uncaught hook writes the stack trace to `System.err`. Such an event then gives a
second hit on `stderr`.

### Scenario file

```json
{
  "schemaVersion": 2,
  "name": "orders-create",
  "startupTimeoutMs": 60000, "quietMs": 400, "stepTimeoutMs": 15000, "compare": "all", "maxRows": 20, "autoNames": true,
  "windowKeys": [ { "pattern": "Editor - .*", "key": "Editor" } ],
  "ignore": { "paths": ["#statusbar\\.clock"], "attributes": [], "valuePatterns": ["\\d{2}:\\d{2}:\\d{2}"] },
  "steps": [
    { "id": "s001", "name": "open orders", "action": { "type": "CLICK",    "locator": { "name": "nav.orders" } } },
    { "id": "s002", "name": "customer",    "action": { "type": "SET_TEXT", "locator": { "name": "orders.customer" }, "value": "ACME" } },
    { "id": "s003", "name": "priority",    "action": { "type": "SELECT",   "locator": { "name": "orders.priority" }, "value": "High" } },
    { "id": "s004", "name": "save",        "action": { "type": "KEY",      "value": "ENTER", "modifiers": 128 } },
    { "id": "s005", "name": "close",       "action": { "type": "CLOSE_WINDOW", "locator": { "windowTitle": "New order" } } }
  ]
}
```

Actions: `CLICK`, `SET_TEXT`, `SELECT` (combo/list/tab by text or index, table by row), `KEY` (VK name, `modifiers` =
`InputEvent.*_DOWN_MASK`), `WAIT` (ms), `CLOSE_WINDOW`, `WAIT_FOR` (see "Wait for a condition").

`schemaVersion` is 2. The scenario file has no golden snapshots. s.smoker refuses a file without `schemaVersion`,
with `schemaVersion` 1, or with an `expected` block in a step: that is the old format. To convert an old file,
add `"schemaVersion": 2` as the first field, remove each `expected` block, then run and `approve` again.
`compare` selects the compared components. Use `all` (the default): the snapshot diff compares all components.
Use `named`: the diff compares only windows and components with a name from `setName()`. Any other value is refused.
The report lists names that more than one component has in one window ("Names that are not unique").

Golden file: `approve` and the recorder write the golden snapshots to `<scenario>.golden.json` in the folder of the
scenario (`orders-create.json` gives `orders-create.golden.json`). It has `schemaVersion` 2, the first step in full
(`full`), and for each later step the changes against the step before it (`changes`). A step without a golden
gives `NO_GOLDEN`. `approve --steps` replaces only those steps. The other steps keep their golden. A step that is
not in the scenario leaves the golden file. `approve` prints the number of approved steps, the number of changed
steps and, for each changed step, the number of changed components. `approve` warns about a `--steps` id that is not
in the run. If the run used another `compare` mode than the scenario, `approve` writes nothing and exits with 2.
`run` does not read `*.golden.json` as a scenario. Commit the golden file with the scenario.

Each change entry has the `path` of one component and an `op`. `add` gives the new component in full in `node`.
`set` gives in `node` only the attributes with a new value. `set` lists in `unset` the attributes that have no value now.
`remove` has no `node`. The entries are sorted by path. s.smoker refuses an entry with an unknown `op`.
s.smoker writes all JSON files with one layout: an object or array is on one line when its one-line form has at most
100 characters (indent and key not counted). A longer one has one member per line. A short change entry is one line with the path and the value.

Title rules: a window title can change while the application runs. `windowKeys` maps a title to a fixed key.
The first `pattern` (Java regex, matches the whole title) gives the `key`. The key replaces the title in paths and in
`windowTitle` locators. A named window keeps its name. The `title` attribute stays the real title.

Tables, lists and trees: the snapshot holds the text of the first `maxRows` rows (default 20). A table gives `cells`.
A list or a tree gives `items`. A tree row has two spaces of indent per level. The diff reports `.items[i]` and `.cells[row][col]`.

Password fields (`JPasswordField`): the snapshot gives the text `***` when the field has a value, and `""` when it is empty.
The recorder does not record typing in a password field. If a scenario must fill one, add the `SET_TEXT` step by hand.
Do not put a real password in a scenario file.

### Wait for a condition

Use `WAIT_FOR` when the application needs time after a step. The player polls each 100 ms until the condition is true.

```json
{ "type": "WAIT_FOR", "locator": { "name": "demo.answer" },
  "attribute": "text", "matches": "ready .*", "timeoutMs": 10000 }
```

- `attribute` is one of `text`, `title`, `visible`, `enabled`, `selected`, `rowCount`, `itemCount`, `selectedIndex`.
- `matches` is a Java regex. The whole value must match.
- `timeoutMs` is optional. The default is 10000. It is independent of `stepTimeoutMs`.
- On timeout the step is FAIL. The message shows the last value, for example `last value: "waiting"`.
  If the player never finds the component, the last value is `<component not found>`.
  The player keeps polling while the component is missing: it can appear later.
- A component in a closed or hidden window gives `<component not found>`, not `visible = false`.
- After a timeout the step still has a snapshot. `approve` after a timed-out run approves the state before the answer.
- The step before a `WAIT_FOR` has no snapshot and no golden. Its status is PASS, unless its action fails or the log has a hit.
- Prefer `WAIT_FOR` to `WAIT`. `WAIT` always sleeps the full time. `WAIT_FOR` continues as soon as the condition is true.

To record a `WAIT_FOR` step:

1. Wait until the application shows the value.
2. Click `Wait for…` in the toolbar. The recorder pauses. Clicks do not go to the application.
3. Click the component. It must have a name, real or automatic. For a component without a name, the status line
   shows `needs a name` and a proposed name. ESC or a second click on `Wait for…` cancels.
4. In the dialog, set the attribute, the regex and the timeout. The default regex matches the current value.
5. Click OK. The recorder adds the step and removes the snapshot of the step before it.

Wait until the condition holds before the next input.

### Locators

`{"name": "orders.save"}` — from `component.setName("orders.save")`, the only stable key. Fallback
`{"windowTitle": "Orders", "className": "JButton", "text": "Save", "index": 0}`. Unnamed interactive components are
listed in the report ("locator lint"). Convention: `<view>.<widget>`.

### Automatic names

An unnamed component can have an automatic name. The name is `@<Class>.<field>`. Example: `@OrdersPanel.saveButton`.
The agent finds the field with reflection. The component must be in a field of an application class.
Classes in `java.`, `javax.`, `sun.`, `com.sun.`, `jdk.`, `com.apple.`, `apple.` and `smoker.` are not application classes.
The source code does not change.

- Automatic names are on by default. Set `"autoNames": false` in the scenario to turn them off.
- A real name from `setName()` always wins. A component with a real name has no automatic name.
- A locator can use an automatic name: `{"name": "@OrdersPanel.saveButton"}`. The agent uses it only when it is unique in the window.
- A locator with an automatic name still finds the component after the application adds the real name.
- Paths in the golden file use automatic names. After the application adds a real name, the paths change. Run `approve` again.
- The report marks the automatic names that the run used.
- An automatic name changes when a developer renames the field or the class.
- A component in a local variable has no field. It keeps the path rule and the locator fallback.
- Layout containers (`JPanel`, `JScrollPane`, `JSplitPane`, `JToolBar`, ...) never get an automatic name, also when a field refers to them.
- A menu item gets its automatic name from the class of its menu bar or of the invoker of its popup. The name is the same when the menu is closed.

### Lint

```bash
java -jar smoker-cli.jar lint --run out/ [--emit-patch names.patch]
```

`lint` reads the `run-result.json` files below the `--run` folder. It prints a table with these columns: window,
class, field, type, text, automatic name, proposed name and source. The proposed name is `<view>.<type>.<field>`.
Example: field `saveButton` in `OrdersPanel` gives `orders.btn.save`.
The last line is a summary: the number of unnamed interactive components, the number with a proposed name,
and the number with an automatic name. `lint` exits with 0. It exits with 2 if it finds no `run-result.json` or cannot write the file.
`--emit-patch` writes one Java line for each proposed name, for example `saveButton.setName("orders.btn.save");`,
with the class where the developer must add it.
One field gives one line, also when the component shows in more than one window.
In one window, the second equal proposal gets the suffix `2`, the third `3` (`orders.btn.ok2`).

### Statuses

`PASS` · `FAIL` (snapshot diff or log hit) · `HANG` (EDT blocked / action timed out, thread dump attached) ·
`ERROR` (component not found, exception thrown by the action) · `NO_GOLDEN` (nothing to compare yet).
The player continues after a `FAIL` step and stops after a `HANG` or `ERROR` step. The scenario status is the
worst step status: `ERROR` > `HANG` > `FAIL` > `NO_GOLDEN` > `PASS`. One `approve` then covers all steps of the run.
The next scenario starts a fresh JVM.

## CI

See `.gitlab-ci.yml`. Linux runners: `xvfb-run -a`. Windows runners: the GitLab runner must run as a
logged-in-user process (not a service in Session 0) for screenshots; semantic replay itself works without focus.

For development, `.github/workflows/windows.yml` runs the three Windows builds (default, `-Plocale-tr`, tr_TR with
JRE 8) on each pull request and each push to `master`, except when the change has only Markdown files (`*.md`). It uses a self-hosted runner on a Windows PC:

1. On GitHub, open Settings > Actions > Runners > New self-hosted runner. Select Windows and do the steps that GitHub shows.
2. When `config.cmd` asks to run the runner as a service, answer no. Start the runner with `run.cmd` in your desktop session.
3. If JRE 8 is not at `C:\Program Files\Java\jdk1.8.0_341`, set the repository variable `JRE8_JAVA` to its `java.exe`.

Do not type on the runner PC while a job runs. The jobs run one at a time.
