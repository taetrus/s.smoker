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
`-Ddemo.recorderPanel=true` shows a panel on the east side with a tree (`demo.tree`), an editable table (`demo.items`),
two popup menus and a spinner (`demo.count`) for the recorder scenarios (WP-06). The panel is off by default.
`smoker-demo/scenarios-recorder/recorder.json` (9 steps in three groups) and `smoker-demo/scenarios-failing-recorder/bad-cell.json`
need this option. The integration test checks R-20a with them: NO_GOLDEN, then PASS, a changed value gives FAIL,
and a value that the cell editor refuses gives ERROR.
`-Ddemo.localePanel=true` shows a label `demo.localeText` on the west side (WP-07, R-34). It shows a fixed instant and
the number 1234567 in the default locale and time zone. For `en-US` and `UTC` the text is `1 January 1970 14:00  1,234,567`.
The panel is off by default. `smoker-demo/scenarios-locale/locale.json` and its golden need this option and the run options
`--locale en-US --timeZone UTC`. The integration test checks that the run passes on a PC with the locale `tr_TR` and
the time zone `Europe/Istanbul`, and that the same run without the two options fails.
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
#    Save also writes scenarios/orders-create.lint.json: the components without a real name (see "Lint")
java -jar smoker-cli.jar record --agent smoker-agent.jar --app "java -jar myapp.jar" --scenario scenarios/orders-create.json

# 2. run — first run has no goldens → NO_GOLDEN (exit 0 unless --strict)
java -jar smoker-cli.jar run --agent smoker-agent.jar --app "java -jar myapp.jar" --scenarios scenarios/ --out out/ --log logs/app.log

# 3. approve the observed state as golden: writes scenarios/orders-create.golden.json (all steps, or --steps s003,s004)
java -jar smoker-cli.jar approve --run out/orders-create --scenario scenarios/orders-create.json

# 4. run again → PASS / FAIL; out/junit.xml + out/report.html

# 5. many scenarios in one application start, with a simulator and a reset (see "Suite file")
java -jar smoker-cli.jar run --agent smoker-agent.jar --suite suite.json --out out/

# 6. the same conditions on each PC: locale, time zone, look and feel, window size (see "Stable conditions")
java -jar smoker-cli.jar run --agent smoker-agent.jar --app "java -jar myapp.jar" --scenarios scenarios/ --out out/ --locale en-US --timeZone UTC --windowSize 1280x800
```

`--app` is the full command line. The agent option is inserted after the first word, or where you put the
token `{agent}`. Equinox product: `--app "myapp.exe -vmargs {agent}"`.
A fixed `-javaagent:...=config=<file>` line in `eclipse.ini` does not operate with `run`: each application start
has its own configuration file. Use that line only for a hand-made start (see "Agent configuration file").

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

`run` passes `--log`, `--screenshots`, `--failRegex`, `--allowRegex`, `--logPattern`, `--failLevel`, `--logCharset`,
`--stopTimeoutMs`, `--label`, `--locale`, `--timeZone`, `--lookAndFeel` and `--windowSize` to the agent. `record` passes
`--log`, `--logPattern`, `--failLevel`, `--logCharset`, `--locale`, `--timeZone`, `--lookAndFeel` and `--windowSize`.
`--log a.log,b.log` becomes `log.1` and `log.2`.
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
| `resetCommand.1`, `resetCommand.2`, ... | command that resets the application state between two scenarios (see "Suite file") | — |
| `resetTimeoutS.N` | time limit of `resetCommand.N` in seconds | 60 |
| `resetScenario` | scenario that the agent replays between two scenarios, without a snapshot (see "Suite file") | — |
| `stopTimeoutMs` | time to wait after each stage of the stop of the application, in ms (see "Stop of the application") | 10000 |
| `aliveFile` | file that the CLI touches each second; the agent stops the application when the file is older than 10 s (see "Suite file") | — |
| `label` | label of the job; the agent writes it to the field `label` of `run-result.json` (see "Matrix report") | — |
| `locale` | default locale of the application: a language tag, `en-US` or `en_US` (see "Stable conditions") | the locale of the PC |
| `timeZone` | default time zone of the application: a time zone ID, `UTC` or `Europe/Istanbul` | the time zone of the PC |
| `lookAndFeel` | look and feel: a class name, `system` or `crossplatform` | the default of the JRE |
| `windowSize` | size of the main window in pixels: `<width>x<height>`, `1280x800` | the size that the application sets |

An unknown `failLevel`, an unknown `logCharset`, an unsupported part of `logPattern`, a wrong value of `locale`,
`timeZone`, `lookAndFeel` or `windowSize`, or a wrong key stops the application at start with exit code 2.
System.err then has one line `[smoker] fatal: <message>`. `run` then gives ERROR for the scenario.

### Stable conditions

The keys `locale`, `timeZone`, `lookAndFeel` and `windowSize` make a run independent of the settings of the PC.
They apply in play mode and in record mode: a recording and its replays have the same conditions.
`run` and `record` have the options `--locale`, `--timeZone`, `--lookAndFeel` and `--windowSize`. The suite file
has the same keys in `agentConfig`.

```
java -jar smoker-cli.jar run --agent smoker-agent.jar --app "java -jar myapp.jar" --scenarios scenarios/ --out out/ --locale en-US --timeZone UTC --lookAndFeel crossplatform --windowSize 1280x800
```

| key | example | the agent does |
|---|---|---|
| `locale` | `en-US`, `en_US`, `tr-TR` | `Locale.setDefault`, and the system properties `user.language`, `user.country` (and `user.variant` when given) |
| `timeZone` | `UTC`, `Europe/Istanbul` | `TimeZone.setDefault`, and the system property `user.timezone` |
| `lookAndFeel` | `system`, `crossplatform`, `javax.swing.plaf.nimbus.NimbusLookAndFeel` | sets the system property `swing.defaultlaf` |
| `windowSize` | `1280x800` | sets the size of the main window |

- The agent sets `locale`, `timeZone` and `lookAndFeel` before the `main` method of the application starts.
  The agent log has one line for each key, for example `[smoker] locale=en-US`.
- `system` becomes the system look and feel of the operating system (Windows, Aqua on macOS, else Metal).
  `crossplatform` becomes `javax.swing.plaf.metal.MetalLookAndFeel`.
- `windowSize`: when the main window shows, the agent sets the first main window to the normal state if it is
  maximized, and sets its size. The location does not change. The agent does this one time for each application
  start, before the first step (play) or before the recorder starts (record).
- The application can replace a value: Equinox sets the locale from `-nl`, an application sets its own look and
  feel, a window has a minimum size. The agent does not force a value. At the start of each scenario the agent
  writes the actual values to the fields `locale`, `timeZone`, `lookAndFeel` and `windowSize` of `run-result.json`.
  For each configured key with a different actual value, the result gets a warning in the field `warnings`, for
  example `locale is tr-TR, not the configured en-US`. The status does not change. `run` prints each warning as
  `warning: <text>` below the status. `report.html` shows each warning below the heading of the scenario.
- The CLI checks `locale` and `windowSize` before the application starts: a wrong value gives
  `run: <message>` or `record: <message>` and exit code 2. The CLI does not check `timeZone` and `lookAndFeel`: the
  JRE of the application can know other time zones and classes than the JRE of the CLI. The agent checks them.
- Limit: JRE 8 and JRE 21 format some dates and numbers differently for the same locale. From Java 9 the JRE uses
  the CLDR locale data by default. The `locale` key does not remove this difference. There are two remedies:
  1. Add an ignore rule for the component that shows the date or the number (see "Scenario file").
  2. Add `-Djava.locale.providers=COMPAT` to the test start of the product (JRE 21 then uses the older locale data; not tested).
  The agent does not set this property: the JRE can read it before the agent starts.

### Stop of the application

When `exitWhenDone` is true, the agent stops the application after it writes the result. The stop has three stages.
After stages 1 and 2, the agent waits at most `stopTimeoutMs`.

1. The agent sends `WINDOW_CLOSING` to each main frame. The application can close itself and release its resources.
   When no main frame shows any more, the agent goes to stage 2 at once: an Equinox product can close its windows
   and keep the JVM alive. A frame that stays open (for example behind a "Save changes?" dialog) makes the agent wait
   `stopTimeoutMs`. The agent skips this stage when the status is HANG: the EDT is blocked.
2. The agent stops the OSGi system bundle by reflection. It finds the bundle before stage 1, through the class loaders
   of the windows, and else through `EclipseStarter`. Without an OSGi framework, it goes to stage 3 at once.
3. The agent calls `System.exit(code)`. The code is 0 for PASS and NO_GOLDEN, and 1 for all other statuses.
   A thread `smoker-halt` calls `Runtime.halt(code)` after `stopTimeoutMs`, when `System.exit` does not return.

The agent writes one line `[smoker] stop: ...` for each stage to the console. The stop does not change the result file:
the agent writes it before stage 1. When the application exits itself in stage 1, the exit code is the code of the application.

### Suite file

`run --suite <file>` runs many scenarios in one application start. It also runs commands around them: a simulator
before the scenarios, a reset between them, a clean-up after them. The demo has an example:
`smoker-demo/suite/suite.json`.

```json
{
  "app": "java -Ddemo.simPort=5555 -Ddemo.log=demo.log -jar ../target/smoker-demo.jar",
  "workDir": ".",
  "agentConfig": { "log": "demo.log", "failLevel": "ERROR", "stopTimeoutMs": 10000 },
  "before": [
    { "command": "sh ../sim/start-sim.sh ../target/smoker-demo.jar 5555", "background": true, "readyPort": 5555, "readyTimeoutS": 30 }
  ],
  "reset": [ { "command": "java -cp ../target/smoker-demo.jar smoker.demo.DemoSimulator --send 5555 RESET", "timeoutS": 20 } ],
  "resetScenario": "reset.json",
  "after": [ { "command": "java -cp ../target/smoker-demo.jar smoker.demo.DemoSimulator --send 5555 QUIT", "timeoutS": 20 } ],
  "scenarios": ["sim-a.json", "sim-b.json"]
}
```

| field | meaning | default |
|---|---|---|
| `app` | command line of the application, as `--app`, with the token `{agent}` | required |
| `workDir` | working directory of the application and of all commands | the current directory of the CLI |
| `agentConfig` | agent keys: `log` (a string or an array of strings), `logPattern`, `failLevel`, `failRegex`, `allowRegex`, `logCharset`, `screenshots`, `edtHangMs`, `stopTimeoutMs`, `locale`, `timeZone`, `lookAndFeel`, `windowSize` | none |
| `before` | commands that run before the first application start, in sequence | none |
| `reset` | commands that run between two scenarios and before each later application start | none |
| `resetScenario` | scenario that the agent replays between two scenarios, without a snapshot | none |
| `after` | commands that run at the end of the suite, always | none |
| `scenarios` | scenario files and folders in run sequence; a folder gives its `*.json` files in name sequence, without golden files | required |

A command is an object:

| field | meaning | default |
|---|---|---|
| `command` | command line; the CLI splits it as it splits `--app` | required |
| `timeoutS` | time limit of a command that the CLI waits for | 60 |
| `background` | `true`: the CLI keeps the process in operation and does not wait for its end; only in `before` | false |
| `readyPort` | the CLI waits until `127.0.0.1:<port>` accepts a TCP connection; only with `background` | none |
| `readyTimeoutS` | time limit of the wait for `readyPort` | 60 |

Rules:

- A relative path in `scenarios`, `resetScenario` and `workDir` is relative to the folder of the suite file.
- A relative path in a command or in `agentConfig.log` is relative to `workDir`.
  The CLI resolves the first word of a command against `workDir` only when it is a regular file. Write the file
  extension: `sim\stop-sim.bat`, not `sim\stop-sim`.
- `app` and each `command` must not be empty and must not contain a line break. `stopTimeoutMs` and `edtHangMs` are
  whole numbers, 0 or more. `screenshots` is `"evidence"` or `"off"`. `locale` is a language tag and
  `windowSize` is `<width>x<height>` (see "Stable conditions"); a wrong value gives `suite: <message>`.
- The names of the scenarios are compared without regard to case: `Login.json` and `login.json` are the same name.
- The file names of the scenarios, without the extension, must be different. Each name is the name of a result folder.
- The CLI checks the suite file, each scenario file and each golden file before it runs the first command.
  An unknown field, an unknown `agentConfig` key or a wrong file gives a message and exit code 2.
- `run --suite` permits only `--agent`, `--out`, `--strict`, `--timeout` and `--label`. Each other option gives
  `run: --<option> is not permitted with --suite; put it in the suite file` and exit code 2.
- The CLI puts the agent option at the token `{agent}` in `app`, or after the first word. A fixed `-javaagent` line
  in `eclipse.ini` does not operate with `run`: each application start has its own configuration file.
  For a launcher, write `myapp.exe -vmargs {agent}`.
- `--timeout <s>` replaces the time limit of each scenario (the time limit of the stop is added).

Sequence:

1. The CLI runs the `before` commands. The output of command *n* goes to `<out>/before-<n>.log`.
   A command that fails, or a `readyPort` that does not open, gives exit code 2 and no scenario runs.
2. The CLI starts the application with the scenarios that have no verdict. The agent runs them one after the other.
3. Between two scenarios the agent does the reset step. It closes the dialogs, runs the `reset` commands
   (output in `<out>/<next scenario>/reset-command.log`; on JRE 8 a command that times out gives the text
   `reset command <k> timed out; child processes can remain`) and replays `resetScenario`. The result of the step has the
   step id `reset`. A step of the reset that is not PASS gives that status to the next scenario.
4. The agent stops the application after a scenario with the status FAIL, HANG or ERROR. It does not run the scenarios that remain.
5. The CLI runs the `reset` commands, and starts the application again for the scenarios that have no verdict.
6. The CLI runs the `after` commands, also after an error and after Ctrl+C. A failed `after` command does not stop the others.
   Then it stops each background process. On Java 9 or later, this includes the child processes. On Java 8, only the
   process stops: the CLI prints `cannot stop child processes on Java 8: use an 'after' command` and the exit code is 2.
   Stop the simulator with an `after` command in this case.
   Before it stops a background process, the CLI waits up to 5 s for the end of all of them. An `after` command
   that stops the simulator can leave its wrapper script alive for a short time.
   Ctrl+C does not skip this step: the CLI waits for the time limits of the `after` commands before it ends.
   An error in the run itself (for example an application that cannot start) gives exit code 2. The CLI still writes
   the reports and `suite-result.json`; a scenario without a verdict has the status ERROR,
   except a scenario that waits for its retry: it keeps its result of the shared JVM (it can be FAIL) with the note
   `failed in a shared JVM; not run again: <reason>`. If the CLI cannot write the reports, it prints
   `suite: cannot write the reports: <message>` and the exit code is 2.
7. The CLI writes `junit.xml`, `report.html` and `suite-result.json` and prints `application starts: <n>`.

Retry rule. A scenario that is the first of a launch runs in a new JVM. Its result is its verdict.
A scenario that runs later in a shared JVM and gets FAIL, HANG or ERROR has no verdict yet.
The CLI moves its result folder to `<out>/<name>.shared/` and runs the scenario first in the next launch.
The result of that launch is the verdict. The verdict has a note in the reports and in `suite-result.json`:

- `failed in a shared JVM, passed in a new JVM`: the reset is not complete. The verdict is PASS.
- `failed in a shared JVM and in a new JVM`: the verdict is the result of the new JVM.
- `failed in a shared JVM; the application did not start for the retry`: the result of the shared JVM stays.

A failed `startup` step is not a verdict. After 3 failed starts in sequence, each scenario without a verdict gets
ERROR `not run: the application did not start 3 times`, and the reports have the entry `(application start)`.
`approve` on a result from a shared JVM prints a warning: the golden then holds the state after other scenarios.
Approve a golden from a result that ran first in its JVM, or from a run of the scenario alone.

`suite-result.json` has `applicationStarts`, `scenarios` (each with `name`, `status`, `attempts` and `note`) and
`commandErrors`. Exit code: 2 when a `before`, `reset` or `after` command failed or a background process did not
stop; else 1 when a verdict is not PASS (NO_GOLDEN counts as PASS without `--strict`); else 0.

Time limit. The CLI polls the result files two times each second. The limit of the scenario in work is its `budgetMs`
(without the startup part when it is not the first), the limits of the `reset` commands and of the reset scenario, and
3 times `stopTimeoutMs`. Past the limit, the CLI stops the application with force. The scenario in work gets HANG.

Hard stop of the CLI. The CLI writes the time to `<out>/cli-alive` each second while an application runs.
A hard stop (a cancelled pipeline job on Windows) runs no shutdown hook. The agent then sees that the file is older
than 10 s, prints `[smoker] the CLI stopped; stopping the application`, and stops the application after the scenario
in work. The tool cannot stop the simulator in this case. The `before` script must tolerate, or stop, a simulator
that an earlier job left.

Screenshots. Set `"screenshots": "off"` in `agentConfig` when a suite does not need them. On macOS, a screenshot takes
about 1 s while the display sleeps, and much longer in a JVM that made many windows.

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
`InputEvent.*_DOWN_MASK`), `WAIT` (ms), `CLOSE_WINDOW`, `WAIT_FOR` (see "Wait for a condition"), `SET_CELL` (see below).

Optional action fields (all are absent in an old scenario):

| Field | Type | Used by | Meaning |
|---|---|---|---|
| `count` | integer | `CLICK` | Click count. Absent means 1. The recorder writes only 2. |
| `button` | integer | `CLICK` | Mouse button: 1 or 3. Absent means 1. The recorder writes only 3. |
| `row` | integer | `CLICK`, `SET_CELL` | View row index of a `JTable` or a `JList`. |
| `column` | string | `CLICK`, `SET_CELL` | Column of a `JTable`. |
| `path` | array of strings | `SELECT`, `CLICK` | Node of a `JTree`: the labels from the root to the node. |

`SET_CELL` sets one cell of a `JTable`. It has `locator`, `row`, `column` and `value`.
`SET_TEXT` accepts a `JSpinner`. `SELECT` accepts a `JTree` with `path`.

```json
{ "type": "SELECT",   "locator": {"name": "files.tree"},   "path": ["Root", "src", "Main.java"] }
{ "type": "CLICK",    "locator": {"name": "orders.table"}, "row": 2, "column": "Status", "count": 2 }
{ "type": "CLICK",    "locator": {"name": "files.tree"},   "path": ["Root", "src"], "button": 3 }
{ "type": "SET_CELL", "locator": {"name": "orders.table"}, "row": 2, "column": "Qty", "value": "5" }
{ "type": "SET_TEXT", "locator": {"name": "orders.count"}, "value": "7" }
```

Tree path: each label is the text that the tree shows for the node (the same text as in the snapshot). The path
starts with the root label, also when the root is hidden. Replay starts at the model root. The root label must equal
the first label. For each next label, replay takes the first child with that label. A node that is not found gives
ERROR `tree node not found: Root/src/X`.

Tree limit: a tree that loads its children in a background thread is not supported. Replay looks for the node at once
and gives ERROR `tree node not found` when the children are not in the model yet.

Table column: the recorder writes the header text when it is not empty and no other column has the same text.
Otherwise it writes the view index as a decimal string. Replay takes the first view column whose header text equals
`column`. If there is none and `column` is an integer, replay takes the view column with that index. Otherwise the
step gives ERROR `column not found: <column>`.

Step `group` (string, optional): a step with `group` starts a group. The steps after it belong to the group until the
next step with `group`. The report shows a header row for each group. Replay ignores the field.

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
A tree also gives `selectedIndex`: the smallest selected row, only when a row is selected. A tree without a selected row
does not change. An existing golden with a tree that has a selected row fails one time with `CHANGED ... .selectedIndex: expected=null`.
Run `approve` to repair it.
A spinner node gives `text`: the text of its editor field. A spinner with a custom editor gives no text. An existing golden
with a spinner fails one time with `CHANGED ... .text: expected=null`. Run `approve` to repair it.

Password fields (`JPasswordField`): the snapshot gives the text `***` when the field has a value, and `""` when it is empty.
The recorder does not record typing in a password field. If a scenario must fill one, add the `SET_TEXT` step by hand.
Do not put a real password in a scenario file.

### Recorder toolbar

- The recorder starts paused. Click `Record` to record the clicks. The title of the toolbar ends with `paused` or
  `RECORDING`, and the toggle shows `● Record` or `❚❚ Pause`. At the start, the status line shows
  `paused: click Record to record the clicks`.
- `Save` with no steps while the recorder is paused adds `no steps: the recorder is paused, click Record to record the clicks`
  to the save message. A save with no steps still writes the lint file (see "Lint without a run").
- A modal dialog of the application can block the toolbar. Close the dialog, then click `Save`.
- The status line shows `password field: typing is not recorded` at the first key that changes a password field:
  a character, Backspace or Delete. It shows the warning once each time the field gets the focus.
- The right pane of the toolbar shows the changes of the selected step: the difference between its golden and the golden
  of the nearest earlier step. It shows `no changes`, `first golden: no earlier step to compare`, or the reason why the
  step has no golden (`snapshot pending...`, `no snapshot: WAIT_FOR follows`, `no golden: run and approve`).
- `Group...` sets the group of the selected step. An empty name removes the group. The list shows the group as a
  header line above the step. A header is not a row: you cannot select it.
- `Up` and `Down` move the selected step one row. The step keeps its id, its name and its group. A golden holds the
  state after all steps before it, so the goldens of the two steps that changed places are wrong. The recorder removes
  both and marks them `(no golden: run and approve)`. `Save` writes the golden file without them. The next run gives
  `NO_GOLDEN` for them, and `approve` repairs them.
- `Delete` keeps the goldens of the later steps.
- A recorded `SET_CELL` has no golden. The input that ends the cell edit acts before the recorder can take the
  snapshot: a check box toggles, Enter moves the selection, a button acts. The step shows
  `(no golden: run and approve)`. The next run gives `NO_GOLDEN` for it, and `approve` repairs it.
- A cell edit that a button action ends (for example a click on `Save` while the cell is open) gives `SET_CELL`
  directly before the `CLICK`.
- Enter or Tab that ends a cell edit and moves the selection of the table gives a `KEY` step after `SET_CELL`
  (`ENTER` or `TAB`, on the table when it has a name). The replay then moves the selection too. This `KEY` step gets
  its golden at record time. Escape cancels the edit and gives no step.
- The step ids can be out of order after a move or after such an insert. The ids stay as they are.
- The toolbar writes its status line, the Record toggle and the step list to the record log.

### Recorder notes

- Give each item of a context menu a name (`setName`). The replay does not find an item without a name in a
  heavyweight popup (a popup that does not fit in the window: Swing shows it in a window of its own).
- The recorder does not record the keys inside a cell edit. The edit gives one `SET_CELL` when it ends. Enter or Tab
  that moves the selection is the exception (see above).
- Enter or Tab that ends a cell edit with no change of the value gives no step, also when it moves the selection.
  Add a `SELECT` step for the new row if a later step needs it.
- A password field as cell editor gives no step. The status line shows `password field: a table cell edit is not
  recorded. Add a SET_CELL step by hand, or set the value outside the scenario.`
- A spinner with a custom editor (not a `JSpinner.DefaultEditor`) gives no step for an arrow click or a typed value.
  The status line shows `spinner with a custom editor: the value is not recorded` once for each spinner.
- The recorder records a right-click at the press, on each platform.
- The recorder does not record an expand or a collapse of a tree node through its handle. The snapshot shows the
  rows of the tree, so the golden of the next step has the change.
- A cell edit that stays open while you act outside the table is part of the goldens of these actions. Its `SET_CELL`
  comes where the edit ends. While the editor of one table stays open, the recorder does not record an edit in a
  second table.

### Remote desktop

Through a remote desktop, for example Chrome Remote Desktop, the windows of the application can show no content.
Turn off the Direct3D pipeline of Java 2D in the application JVM:

- Set the environment variable `J2D_D3D=false` before you start `record` or `run`. The application inherits it.
- Or add `-Dsun.java2d.d3d=false` to the JVM options of the application: in `--app` before `{agent}`, or in
  `eclipse.ini` after `-vmargs`.

The Windows check of the WP-09 follow-up (`docs/wp-09-windows-report.md`) used `J2D_D3D=false`. That check did not
test the cause separately.

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

`lint` reads the `run-result.json` files and the `*.lint.json` files below the `--run` folder. It prints a table with
these columns: window, class, field, type, text, label, automatic name, proposed name and source. The proposed name is
`<view>.<type>.<stem>`. Example: field `saveButton` in `OrdersPanel` gives `orders.btn.save`.

The stem comes from the first source that gives one. The source column shows it:

1. `field`: the field name without its type suffix (`saveButton` gives `save`).
2. `text`: the visible text of the component, in lower camel case (`Base URL:` gives `baseUrl`).
3. `label`: the text of the label of the component, from `JLabel.setLabelFor`.
4. `field`: a generated field name, when no other source gives a stem.

A generated field name comes from a GUI builder (`jButton1`, `btnNewButton_1`) or only names the type
(`textField_1`, `comboBox`, `tabs`). It gives a poor name, so the text and the label come first.
Example: field `jButton1` with the text `Save` gives `orders.btn.save`. HTML tags in a text do not count.

The last line is a summary: the number of unnamed interactive components, the number with a proposed name (by
source: fields, text, labels), and the number with an automatic name. `lint` exits with 0. It exits with 2 if it finds no `run-result.json` and no
`*.lint.json`, or if it cannot write the file.
`--emit-patch` writes one Java line for each proposed name, for example `saveButton.setName("orders.btn.save");`,
with the class where the developer must add it. A component with a generated field also gets this line
(`jButton1.setName("orders.btn.save");`). A component without a field gets a comment with its text or its label.
One field gives one line, also when the component shows in more than one window.
In one window, the second equal proposal gets the suffix `2`, the third `3` (`orders.btn.ok2`).

Interactive components are buttons, menu items, text components, combo boxes, lists, tables, trees, tabbed panes,
spinners and sliders. The text field inside a spinner is part of the spinner: it gives no entry of its own.

#### Lint without a run

The recorder also writes the lint. At each `Save`, it writes `<scenario>.lint.json` beside the scenario. The file
holds the components of all snapshots of the record session, and of one more snapshot at the save. That snapshot
is for the lint only: it gives no golden. A record session with no steps thus also gives a lint.

To find the components that need a name, without a scenario:

1. Start `record`.
2. Click `Record` in the toolbar. The title of the toolbar must end with `RECORDING`.
3. Open each window, dialog and tab of the application. Each click is a step, and each step takes a snapshot.
4. Close the dialogs, and click `Save`. The snapshot of the step that opened a dialog keeps the dialog in the lint.
   Then close the application.
5. `record` prints the summary line and the path of the lint file.
6. Run `lint --run scenarios/ --emit-patch names.patch` for the table and the patch.

`Save` without `Record` writes the lint of the windows that show at the save only.

The lint only sees the components that showed. A tab that you did not open is not in the lint.
Each save replaces the lint file: it holds the last record session only.

### Matrix report

`run` has the option `--label <text>`. The label names the job, for example `jre8` or `jre21`. It has 1 to 40
characters: letters, digits, `.`, `_` and `-`. A wrong label gives exit code 2 before the application starts.
`--label` is permitted with `--suite` too.

```
java -jar smoker-cli.jar run --agent smoker-agent.jar --app "java -jar myapp.jar" --scenarios scenarios/ --out out/ --label jre8
```

- The CLI writes the label to the agent configuration file (key `label`). The agent writes it to the field `label`
  of `run-result.json`. A result that the CLI makes itself has the label too. Without `--label` the field is absent.
- `run` writes `<out>/job-result.json` when it writes `report.html`. The file has the label, the times, the Java
  version, the operating system and, for each scenario, the key, the name, the status and the steps.
  The `key` of a scenario is its file name without the extension (in suite mode: the name in the suite).
  The matrix report reads this file.

`report --merge` joins the `job-result.json` files of several jobs into one page:

```
java -jar smoker-cli.jar report --merge out-jre8 out-jre21 --out merged
```

- `--merge` takes one or more job folders: the `--out` folders of the jobs. `--out` is the folder of the page.
  The command writes `<out>/report.html`. `--out` must not be a job folder.
- The column name of a job is its label. A job without a label gets the name of its folder.
- The page has the Java version, the operating system, the locale and the number of passed scenarios of each job.
  Below, it has one row for each scenario and one row for each step. The scenarios have the order of the first job.
  Scenarios and steps that only later jobs have come after them. A cell is `-` when the job did not run it.
- A status cell links to the scenario or the step in the `report.html` of the job. The link is relative to `--out`.
  When no relative path exists (another drive on Windows), the link is a `file:` URI.
  The page therefore works when the job folders stay beside the page.
- A note, an error or a warning of a scenario shows below its row, with the column name before it.
- Exit code 0: the page is written. The jobs give the verdict of the pipeline, not the merge.
  Exit code 2: a wrong argument, two jobs with one column name, a folder without a readable `job-result.json`,
  or a page that cannot be written. The error text names the folder.
- A `job-result.json` that has no `scenarios` list, or a scenario without `key`, gives exit code 2 and the text
  `report: <folder>/job-result.json is not a job result`.

#### History

`report --merge` has three more options:

```
java -jar smoker-cli.jar report --merge out-jre8 out-jre21 --out merged --history history.json --run-id $CI_PIPELINE_ID --ref $CI_COMMIT_REF_NAME
```

- `--history <file>`: the command reads the file, adds the current run and writes the file back. The page then
  shows the last 20 runs. Without this option the command reads and writes no history file.
- `--run-id` and `--ref` are free texts. The command stores them in the history and shows them in the header of the page.
  A run with a `--run-id` that the file already has replaces that run (a pipeline job that runs again).
  A run without `--run-id` is always added.
- The file keeps the last 20 runs. The page has the column `Last 20 runs: <job>` for each job, with one letter
  for each stored run, the oldest first and the current run last:
  `P` PASS, `F` FAIL, `H` HANG, `E` ERROR, `N` NO_GOLDEN, `-` not run.
  The title of a letter has the time, the id and the ref of the run.
- A step gets the text `unstable` when the stored runs of one job have a PASS and also a FAIL, HANG or ERROR.
- A history file that does not exist starts a new history (the page says `history: new file`).
  A file that is not a version 1 history file is moved to `<file>.damaged`. An existing `.damaged` file is replaced.
  A new history starts, and the command prints `history: the file was damaged, a new history starts` on `System.err`.
  The exit code stays 0.
- The file format:

```json
{
  "version": 1,
  "runs": [
    { "time": 1764050090000, "id": "4711", "ref": "master",
      "jobs": { "jre8": { "orders": { "status": "PASS", "steps": { "s001": "PASS", "s002": "PASS" } } } } }
  ]
}
```

Example with two pipeline jobs and a merge job (in `.gitlab-ci.yml` style):

```
test-jre8:   run ... --out out-jre8  --label jre8      # artifact: out-jre8
test-jre21:  run ... --out out-jre21 --label jre21     # artifact: out-jre21
report:      report --merge out-jre8 out-jre21 --out merged --history history.json --run-id $CI_PIPELINE_ID --ref $CI_COMMIT_REF_NAME
```

The merge job needs the artifacts of both jobs. It must keep `history.json` between pipelines (a cache or an artifact of the last run).

### Release guard

A product that ships must not start with the agent of the smoke tester. `check-release` finds the `-javaagent`
option in the `.ini` files of a product:

```
java -jar smoker-cli.jar check-release target/products [<path> ...]
```

- A path is a folder, an `.ini` file or a `.zip` file. The command reads each `.ini` file below a folder and each
  `.ini` entry of each `.zip` file below a folder. It does not read an archive in an archive.
- A hit is a line that starts with `-javaagent:` (leading blanks are allowed) and has the text `smoker` after it, in
  any case. The command prints `<file>:<line number>: <line>` for each hit (`<zip>!<entry>:<line number>: <line>` for an archive).
- Exit code 1: there is a hit. Exit code 0: no hit; the command prints `check-release: <n> file(s) checked, no agent option`.
  Exit code 2: a path does not exist, a file cannot be read, or a path has no `.ini` file. A guard that checked
  nothing for a path must not pass. Exit code 2 wins over exit code 1.
- The command reads the `.ini` files as ISO-8859-1.

Example for a Maven or Tycho build, after the product is assembled (`exec-maven-plugin`, phase `verify`):

```xml
<plugin>
  <groupId>org.codehaus.mojo</groupId>
  <artifactId>exec-maven-plugin</artifactId>
  <executions>
    <execution>
      <phase>verify</phase>
      <goals><goal>exec</goal></goals>
      <configuration>
        <executable>java</executable>
        <arguments>
          <argument>-jar</argument><argument>tools/smoker-cli.jar</argument>
          <argument>check-release</argument><argument>${project.build.directory}/products</argument>
        </arguments>
      </configuration>
    </execution>
  </executions>
</plugin>
```

Example for a pipeline job (in `.gitlab-ci.yml` style):

```
release-guard:
  script: java -jar smoker-cli.jar check-release target/products
```

The Maven example and the pipeline example are not tested.

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
