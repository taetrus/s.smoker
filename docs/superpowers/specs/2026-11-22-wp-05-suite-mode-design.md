# WP-05 Suite mode and lifecycle — design

Date: 2026-11-22. Findings: F-03, F-04, F-10, F-11. Requirements: R-36, R-37, R-38, R-39.
Source: `docs/development-plan.md`, section "WP-05".

## 1. Intent

An Equinox application uses 20 s to 60 s to start. Today the CLI starts one JVM for each scenario, so 50 scenarios
use up to 50 minutes for startup only (F-03). WP-05 lets one application start run many scenarios.

Three more problems are in the same area:

- No procedure starts, resets and stops the simulator and the test data (F-04).
- The agent calls `System.exit` directly. An OSGi framework then does not stop its bundles, and lock files can
  remain (F-10).
- The agent arguments use `;`, `,` and `=` as separators. A path with one of these characters breaks the
  configuration (F-11).

The users are the developers of the target applications. They run the suite in the merge request pipeline on Windows.

## 2. Goals, non-goals, anti-goals

Goals:

1. `run --suite suite.json` runs many scenarios in one JVM (R-36).
2. The suite file has `before`, `reset` and `after` commands (R-37). `after` runs also after a failure.
3. The agent stops the application in three stages: close the windows, stop the OSGi framework, `System.exit` (R-38).
4. The agent gets its configuration from one file (R-39).

Non-goals:

- Parallel runs of scenarios.
- A protocol between the tool and the simulator. The scripts of the owner own the simulator messages and the test data.
- Recording in suite mode. `record` continues to record one scenario in a new application.
- Automatic creation of the reset scenario.

Anti-goals:

- A suite that reports a failure which a new application does not show. Milestone 1 permits fewer than 2 % false
  failures. The retry rule (section 6) prevents this.
- A stop of the CLI that leaves the simulator or the application in operation.
- A change that breaks `run --app … --scenarios …`, SelfTestIT or the pilot runbooks.

## 3. Constraints

- Java 8 source level. No new dependency. No bytecode instrumentation.
- Each Swing access goes through the EDT. Do not wait on an action `Runnable`; wait for a later ping.
- `[smoker]` texts are ASCII. Case conversion uses `Locale.ROOT`.
- The result files of WP-04 stay valid: `currentStep`, `finishedAt` 0 before the end, the shutdown hook, atomic writes.
- The pilot for the Windows check is p.osgi.ds. It has no simulator (owner decision, 2026-11-22).

## 4. Approaches

Decision to make: how the CLI and the agent work together while one JVM runs many scenarios.

1. **The agent runs the list (chosen).** The configuration file gives the agent the scenario list, the reset command
   and the reset scenario. The agent writes one `run-result.json` for each scenario. At the first FAIL, HANG or ERROR
   it stops the application. The CLI reads the result files and starts a new JVM for the scenarios that remain.
   Trade-off: no new channel. The reset command is a child process of the application JVM.
2. **The CLI drives through files.** The agent writes "done" and waits for a "go" file. All commands run in the CLI.
   Trade-off: a two-sided wait on polled files. Each side needs a timeout for a stopped partner.
3. **The CLI drives through a local TCP socket.** Trade-off: the cleanest control, the most new code, and a network
   port in a tool that has none.

The owner chose approach 1. Reason: it uses the result-file contract that WP-04 made safe against an application
exit, and it adds no second protocol. If the CLI stops, the application finishes and exits alone.

Decision made before: what brings the application back to its start screen between scenarios.

- A: the scenario author makes each scenario end where it started.
- B: the suite names a reset scenario that the tool replays between scenarios.
- C: B, and a scenario that fails in a shared JVM runs one more time in a new JVM. **The owner chose C.**

## 5. The suite file

```json
{
  "app": "\"C:\\Program Files\\Java\\jdk1.8.0_341\\bin\\java.exe\" {agent} -jar plugins\\org.eclipse.osgi_3.23.0.jar -consoleLog",
  "workDir": ".",
  "agentConfig": { "log": "logs/app.log", "failLevel": "ERROR" },
  "before": [
    { "command": "sim\\start-sim.bat", "background": true, "readyPort": 5020, "readyTimeoutS": 60 }
  ],
  "reset": [ { "command": "sim\\load-testdata.bat", "timeoutS": 60 } ],
  "resetScenario": "scenarios/reset.json",
  "after": [ { "command": "sim\\stop-sim.bat" } ],
  "scenarios": [ "scenarios/login.json", "scenarios/orders" ]
}
```

| Field | Meaning | Default |
|---|---|---|
| `app` | Command line of the application, as `--app` today, with the token `{agent}`. | required |
| `workDir` | Working directory of the application and of all commands. | the current directory of the CLI |
| `agentConfig` | Agent keys (section 9): `log`, `logPattern`, `failLevel`, `failRegex`, `allowRegex`, `logCharset`, `screenshots`, `edtHangMs`, `stopTimeoutMs`. | none |
| `before` | Commands that run before the first application start, in sequence. | none |
| `reset` | Commands that run between two scenarios and before each later application start. | none |
| `resetScenario` | Scenario file that the agent replays between two scenarios, without a snapshot. | none |
| `after` | Commands that run at the end of the suite, always. | none |
| `scenarios` | Scenario files and folders, in run sequence. A folder gives its `*.json` files in name sequence, without golden files. | required |

Rules:

- A relative path in `scenarios`, `resetScenario` and `workDir` is relative to the folder of the suite file.
- A relative path in a command or in `agentConfig.log` is relative to `workDir`, because the application sees it.
- The file names of the scenarios, without the extension, must be different. Each name is the name of a result folder.
- The CLI checks the suite file, each scenario file and each golden file before it runs the first command. A wrong
  file gives a message and exit code 2.
- An unknown field in the suite file or in `agentConfig` gives a message and exit code 2. A typing error in `after`
  must not be ignored silently.

Options of `run --suite <file>`: `--agent` (required), `--out`, `--strict` and `--timeout`. Each other option of
`run` gives `run: --<option> is not permitted with --suite; put it in the suite file` and exit code 2.

`agentConfig.log` is a string (one file) or an array of strings. The suite file has no comma list: a path can
contain `,`.

The CLI always gives the agent option on the command line of `app`: at the token `{agent}`, or after the first
word when there is no token, as today. A fixed `-javaagent` line in `eclipse.ini` does not operate with `run`,
because each application start gets its own configuration file. For a launcher, use `myapp.exe -vmargs {agent}`.

A command object:

| Field | Meaning | Default |
|---|---|---|
| `command` | Command line. The CLI splits it as it splits `--app`. | required |
| `timeoutS` | Time limit of a command that the CLI waits for. | 60 |
| `background` | `true`: the CLI keeps the process in operation and does not wait for its end. Only in `before`. | false |
| `readyPort` | The CLI waits until `127.0.0.1:<port>` accepts a TCP connection. Only with `background`. | none |
| `readyTimeoutS` | Time limit of the wait for `readyPort`. | 60 |

## 6. Run sequence

### 6.1 The CLI

1. Check all files. Create the `out` folder. Delete old result folders of the scenarios of this suite.
2. Run the `before` commands. The output of command *n* goes to `out/before-<n>.log`.
   - A command without `background`: wait for its end. An exit code other than 0 or a timeout is a setup failure.
   - A command with `background`: start it. If it ends before `readyPort` accepts a connection, or the wait reaches
     `readyTimeoutS`, that is a setup failure.
   - A setup failure: run no scenario, do step 6, and exit with code 2.
3. Make the launch list: all scenarios that have no verdict, in suite sequence.
4. Start the application with the agent option `config=<out>/agent-<k>.cfg`, where *k* is the number of the
   application start. Watch the run (section 6.4).
5. After the JVM ends, read the result files (section 6.3). If scenarios without a verdict remain, run the `reset`
   commands (the simulator must not keep the state of the failed scenario), and go to step 3. A failed `reset`
   command here is a setup failure for the scenarios that remain: they get ERROR `not run: reset command failed`.
6. Run the `after` commands, each with its time limit; a failed `after` command gives a message and does not stop the
   other `after` commands. Then stop each background process with its child processes.
7. Write `junit.xml`, `report.html` and `suite-result.json`. Print the summary with the line
   `application starts: <n>`. Exit code: 2 when a `before`, `reset` or `after` command of the CLI failed or a
   background process did not stop; else 1 when a verdict is not PASS (NO_GOLDEN counts as PASS without `--strict`);
   else 0. A suite where the simulator did not stop must not give a green pipeline.

Step 6 runs also when the CLI gets Ctrl+C or a failure of its own: a JVM shutdown hook of the CLI stops the
application process, and then does step 6.

A hard stop of the CLI (a cancelled pipeline job on Windows) runs no shutdown hook. For this case the CLI writes the
time to the file `<out>/cli-alive` each second while an application runs, and the configuration file has the key
`aliveFile`. Before each scenario and each `reset` step, the agent reads the file time. When the file is older than
10 s, the agent prints `[smoker] the CLI stopped; stopping the application`, starts no more scenarios and stops the
application (section 8). The scenario in work first runs to its end. The tool cannot stop the simulator in this
case: the `before` script of the owner must tolerate or stop a simulator that an earlier job left. The README says so.

### 6.2 The agent, in one JVM

The agent has the launch list from its configuration file.

1. First scenario: the `startup` step, as today. It is the first step of the result of that scenario.
2. Run the scenario. Apply its `windowKeys`, `autoNames`, `compare` and `maxRows` first. Write
   `<out>/<scenario name>/run-result.json` with progress and final result, as today.
3. If the status is FAIL, HANG or ERROR: stop the application (section 8). Do not run the scenarios that remain.
4. If the status is PASS or NO_GOLDEN and a scenario remains: do the `reset` step (section 7). It is the first step
   of the result of the next scenario, with the step id `reset`. If the `reset` step is not PASS, the next scenario
   gets the status of the `reset` step and does not run its own steps; then step 3 applies.
5. After the last scenario: stop the application (section 8).

One shutdown hook for each JVM gives the exit result to the scenario that runs at that time (F-22). A scenario of
the launch list that did not start has no result file.

The log window of a scenario starts where the window of the step before it ended, also across scenarios. A log hit
during the `reset` step belongs to the `reset` step and makes it FAIL.

### 6.3 Verdicts and the retry rule

The CLI reads the result of each scenario of the launch list, in sequence:

- A scenario without a result file did not run. It stays without a verdict.
- The first scenario of a launch ran in a new JVM. Its result is its verdict, also FAIL, HANG or ERROR.
- A later scenario ran in a shared JVM. PASS and NO_GOLDEN are verdicts. FAIL, HANG or ERROR is not a verdict:
  the CLI moves the result folder to `out/<name>.shared/`, and the scenario is the first scenario of the next launch.
  The result of that launch is the verdict.

So the next launch list starts with the scenario to try again and continues with the scenarios that remain. A
retry that passes costs one more application start. A retry that fails costs two.

The verdict of a retried scenario gets a note, in the reports and in `suite-result.json`:

- `failed in a shared JVM, passed in a new JVM` — the reset is not complete. The verdict is PASS.
- `failed in a shared JVM and in a new JVM` — the verdict is the result of the new JVM.

A failed `startup` step is a failure of the application start, not of a scenario. It is not a verdict: the
scenario stays the first of the next launch list, and no folder is moved. The CLI counts these launches. A launch
whose `startup` step passes sets the count to 0. At the third failed startup in sequence, the CLI stops: each
scenario without a verdict gets ERROR `not run: the application did not start 3 times`. The reports then have one
more entry, `(application start)`, with the `startup` step of the last launch and its log hits. A scenario that
waits for its retry at that time keeps its result from the shared JVM as its verdict, with the note
`failed in a shared JVM; the application did not start for the retry`.

`RunResult` gets two fields: `sharedJvm` (the agent sets it to `true` for each scenario after the first of a JVM)
and `note` (the CLI sets it). `approve` on a result with `sharedJvm` prints a warning: the golden then holds the
state after other scenarios.

### 6.4 Time limits

The CLI polls the result files two times each second. The scenario "in work" is the first of the launch list without
a final result. Its limit starts when the scenario before it got its final result (or at the application start):

- `budgetMs(scenario)` of today, without the startup part for a scenario that is not the first;
- plus, for a scenario that is not the first: the `reset` command limits and `budgetMs` of the reset scenario;
- plus 3 × `stopTimeoutMs` for the stop of the application.

`--timeout <s>` replaces the first two parts for each scenario. Past the limit, the CLI stops the JVM with force.
The scenario in work gets HANG, as today, and the retry rule applies.

## 7. The reset step

Between two scenarios in one JVM, the agent does these actions. The result is one step with the id `reset`.

1. Close the dialogs: on the EDT, clear the menu selection and dispose each showing `java.awt.Dialog` whose name
   does not start with `smoker.`. Repeat until no dialog shows, at most 10 times. A dialog that still shows gives
   ERROR `reset: a dialog stays open: <title>`.
2. Run the `reset` commands, in sequence, as child processes with the working directory of the application. The
   output goes to `<out>/<next scenario name>/reset-command.log`. An exit code other than 0 or a timeout gives ERROR.
   After a timeout the agent stops the process, with its child processes when the application runs on Java 9 or
   later (decision 11). On Java 8 a child of a `.bat` can stay in operation; the error text says
   `reset command timed out; child processes can remain`. The next scenario does not run in this JVM in each case:
   the ERROR of the `reset` step stops the application, and the retry rule applies.
3. Replay the reset scenario with its own `windowKeys` and `autoNames`: each action, the settle wait, no snapshot,
   no compare. A step of it that is not PASS gives the `reset` step that status and the text
   `reset scenario: step <id>: <error>`.
4. Wait for a quiet EDT, read the logs to their end, and take the log hits. A hit gives FAIL.

A reset scenario is a usual scenario file. Its golden file, if there is one, is not read.

## 8. The stop of the application (F-10)

The agent stops the application after it wrote the final result of the last scenario that it ran. This applies to
`run --suite` and to `run --scenarios`. It does not apply to `record`. `exitWhenDone=false` keeps its meaning: no stop.

1. Post `WINDOW_CLOSING` to each showing frame of the application, on the EDT. Skip this stage when the EDT is
   blocked (the status was HANG). Wait `stopTimeoutMs` (default 10 000).
2. If the JVM still operates: find the OSGi framework and call `stop()` on the system bundle (bundle 0), through
   reflection. Wait `stopTimeoutMs`.
3. If the JVM still operates: call `System.exit` with code 0 (each scenario PASS or NO_GOLDEN) or 1.
4. Safety: before stage 3, start a daemon thread that calls `Runtime.halt` with the same code after `stopTimeoutMs`.
   A shutdown hook of the application that blocks must not keep the JVM in operation.

How stage 2 finds the framework (builder: keep this order, use method names only, no OSGi class on the class path):

- For the class of each window and of its components: when the class loader has a method `getBundle()`, call
  `getBundle().getBundleContext().getBundle(0L).stop()`.
- Else try `org.eclipse.core.runtime.adaptor.EclipseStarter.getSystemBundleContext()` through the system class
  loader and through the context class loader of each live thread.
- No framework found: go to stage 3 immediately.

Effect on the contract: when stage 1 or 2 ends the JVM, the exit code is that of the application, not of the agent.
The CLI therefore takes each verdict from the result files. It uses the exit code only for a result without
`finishedAt`, as today. A dialog that asks "Save changes?" at stage 1 blocks the close; stage 2 or 3 then ends the JVM.

## 9. The agent configuration file (F-11)

The agent accepts one argument form: `-javaagent:smoker-agent.jar=config=<file>`. All text after `config=` is the
path. The old form `k=v;k=v` gives `[smoker] fatal: use config=<file> (see README)` and exit code 2.

File format (owner: this is my decision, see section 12):

- UTF-8 text. One `key=value` in each line. The first `=` ends the key. Spaces around the key and the value are removed.
- Empty lines and lines that start with `#` are ignored.
- **No escape sequences.** A backslash is a backslash. `java.util.Properties` is not used: it changes
  `C:\tools` and the default `failRegex` `\b(ERROR|SEVERE|FATAL)\b`.
- An unknown key gives `[smoker] fatal: unknown key '<key>' in <file>` and exit code 2.

Keys: all keys of today (`mode`, `out`, `logPattern`, `failLevel`, `failRegex`, `allowRegex`, `logCharset`,
`screenshots`, `edtHangMs`, `exitWhenDone`) and:

| Key | Meaning |
|---|---|
| `scenario.1`, `scenario.2`, … | The launch list, as absolute paths. `record` uses `scenario.1` only. |
| `out` | Root folder. The result of a scenario goes to `<out>/<file name without extension>/`. |
| `log.1`, `log.2`, … | Log files. A path can contain `,` and `;`. |
| `resetCommand.1`, … | The `reset` command lines. |
| `resetTimeoutS.1`, … | Their time limits. |
| `resetScenario` | Path of the reset scenario. |
| `stopTimeoutMs` | Time limit of each stop stage. Default 10 000. |
| `aliveFile` | File that the CLI writes each second (section 6.1). Without the key, the agent does not check. |

`out` changes its meaning: today it is the folder of one scenario. The CLI writes the file for each launch. The
options `--log`, `--logPattern` and the others of `run` and `record` stay. The CLI check that refuses `;` in
`--logPattern` is removed. `--log a.log,b.log` stays a comma list on the command line; the CLI writes `log.1`, `log.2`.

`run --app … --scenarios …` keeps its behaviour: one JVM for each scenario, no reset, no retry. It uses the new
configuration file and the new stop.

## 10. Reports

- `report.html` and `junit.xml`: one entry for each scenario with its verdict, as today. A note shows below the
  scenario name. The `reset` step shows as a step, as `startup` does.
- `suite-result.json` in `out`: `applicationStarts`, and for each scenario `name`, `status`, `attempts`, `note`.
  SelfTestIT and the Windows check read it.
- Screenshot and result paths of the first attempt stay in `out/<name>.shared/`.

## 11. Tests and the completion criterion

Demo additions (`smoker-demo`):

- A small TCP simulator (`smoker.demo.DemoSimulator`): it opens a port, answers one text command, and keeps a
  counter that a `reset` command sets to 0. Start scripts for Windows (`.bat`) and for macOS and Linux (`.sh`).
- `-Ddemo.lockFile=<file>`: the demo creates the file at start and deletes it when its window closes.
- `-Ddemo.ignoreClose=true`: the demo window ignores `WINDOW_CLOSING`.
- A suite folder `smoker-demo/suite/` with a suite file, a reset scenario, and scenarios that do not end where they
  started.

SelfTestIT checks:

1. R-36: a suite of 20 passing scenarios gives `applicationStarts` 1.
2. Retry: a scenario that fails only after another scenario gets the note `failed in a shared JVM, passed in a new JVM`,
   verdict PASS, 2 application starts.
3. A real FAIL in the middle: the verdict FAIL with the second note; the scenarios after it run and pass.
4. R-37: `before` starts the simulator and waits for the port; after a failing suite the port is closed and the
   `after` marker file exists.
5. R-38, stage 1: no lock file after the run. Stage 3: with `ignoreClose` and a short `stopTimeoutMs` the JVM ends
   and the result is complete.
6. R-39: a run with the `out` folder `out;1 a=b` passes.
7. A failing `before` command: exit code 2, no application start, `after` runs.

Unit tests cover the configuration parser, the suite file check, the verdict and retry logic, the time limits, the
dialog close, and stage 2 of the stop with classes that have the method names of OSGi.

Windows check on p.osgi.ds (the owner runs it with a runbook, as for WP-04):

- R-36: 20 copies of the pilot scenario and a reset scenario on `p2.product`: one application start.
- R-38: after 20 suite runs, no lock file in `configuration/` and in the workspace. The report names the stop stage
  that ended the JVM in each run.
- R-39: an `out` path with `;` and spaces.
- R-37 is proved with the demo simulator in the Windows CI, not with a real simulator. The report says so. The item
  "not verified: a product with the hardware simulator" stays open.

Completion criterion of the plan: R-36, R-37, R-38 and R-39 pass; 20 scenarios on the pilot use one startup.

## 12. Decisions of the designer (the owner did not decide these)

1. The configuration file has no escape sequences and is not read with `java.util.Properties` (section 9).
2. The old agent argument form is removed, not kept as a second form.
3. `run --scenarios` keeps one JVM for each scenario.
4. A failed `reset` step uses the retry rule; no special case.
5. The retry launch continues with the scenarios that remain (section 6.3).
6. The limit of three failed startups in sequence.
7. `Runtime.halt` as the safety after `System.exit` (section 8, stage 4).
8. Dialogs are closed with `dispose()`, not with `WINDOW_CLOSING`: a dialog cannot refuse it.
9. An unknown field or key is an error, not a warning.
10. `approve` warns on a result from a shared JVM, and continues.
11. Stop of a background process tree: `ProcessHandle.descendants()` through reflection when the CLI runs on Java 9
    or later. On Java 8 the CLI stops only the process itself and prints
    `cannot stop child processes on Java 8: use an 'after' command`. The agent uses the same method for a `reset`
    command that timed out (section 7).
12. The `cli-alive` file against a hard stop of the CLI (section 6.1).
13. A failed startup is not a scenario verdict (section 6.3).
14. `run --suite` permits only `--agent`, `--out`, `--strict` and `--timeout` (section 5).
15. Exit code 2 when a command of the CLI failed (section 6.1).

## 13. Left to the builder

- Class names and the split of `Player` into a JVM session and a scenario run.
- The layout of `suite-result.json` beyond the fields in section 10.
- The exact texts of messages that this document does not give.
- The README sections ("Suite file", "Agent configuration file") and the updates of `docs/architecture.md` and
  `CLAUDE.md`, in the same commits as the format changes.
