# WP-04 Log analysis — design

Date: 2026-11-20. Work package: WP-04 (`docs/development-plan.md`, WP-04). Findings: F-08, F-09, F-22, F-24.
Requirements: R-10a, R-12a. Branch: `wp-04-log-analysis`, from `master` (WP-01, WP-02, WP-09 and WP-03 merged).

## 1. Intent

The log is the second judge of a smoke test, after the snapshot. Now it is not reliable:

- A log line can go to the incorrect step (F-08). `LogTailer` reads each file every 150 ms and gives each line the
  time at which it reads it. `Player.finish()` takes the hits up to "now". A line that is in the file but not yet read
  is lost or goes to the next step.
- One exception gives many hits, and normal text gives hits (F-09). `failRegex` runs on each line. The default
  `\bERROR\b|\bSEVERE\b|Exception|\bError\b` finds `Error` in normal text and finds each `Caused by: …Exception` line.
- Equinox framework errors give no FAIL (F-22). The agent reads `System.err` and the log files, not `System.out`.
  Equinox `-consoleLog` writes to the console. On 2026-11-14, 14 unresolved bundles of spike.master gave startup PASS.
- A `System.exit` of the application leaves no result (F-22). `run-result.json` is written once, at the end.
  The report gives only the exit code. The step and the last ERROR line are only in `app-output.log`.
- `approve` on a run without `run-result.json` stops with a `NoSuchFileException` stack trace and exit code 1 (F-24).

The products log through Logback in the bundles. Output with `System.out.println` (and `e.printStackTrace()`) is also
common (owner, 2026-11-20). Both kinds of output must give correct hits.

## 2. Goals

1. Configuration parameters `logPattern` and `failLevel` (plan task 1).
2. Parse time, level, logger and message from each line that agrees with `logPattern` (task 2).
3. Join each continuation line (a stack trace line, or a line that does not start a new event) to the event before
   it. One event gives at most one hit (task 3, R-12a).
4. Read each log file to its end before the step closes (task 4, R-10a).
5. Assign a file event by the time in its first line when the time is available (task 5).
6. A test with 100 repetitions (task 6).
7. Read `System.out`. Recognize Equinox log entries and fail on severity ERROR (task 7, F-22).
8. Write `run-result.json` before each action, and again on `System.exit` (task 7, F-22).
9. `approve` without `run-result.json`: a message and exit code 2 (task 8, F-24).

Completion criterion (plan): R-10a and R-12a pass. An unresolved bundle at startup gives FAIL. After `System.exit`
in a step, `run-result.json` names that step. `approve` without `run-result.json` gives exit code 2.

## 3. Non-goals

- No Logback fragment bundle (R-15). It stays in phase 2.
- No glob for log files (`configuration/*.log`). The products run with `-consoleLog`, so the Equinox entries are in
  the console output (section 6.5).
- No `logPattern` for each source. Owner, 2026-11-22: the products usually log with Logback. In OSGi, Logback can
  start late, and before it starts, similar log lines go to `System.out`. One pattern covers the Logback output. The
  early lines that do not agree with it are plain events: `failRegex` (`\bERROR\b`) and the stack trace rule cover them.
- No removal of duplicate hits. A product that writes the same Logback output to the console and to a file gives two
  hits for each error. The status stays correct. `README.md` states this.
- No new configuration file. WP-05 replaces the agent arguments with `config=<properties file>`. Until then, a `;` in
  `logPattern` is not possible (section 6.1).
- No change to the JUL handler and the uncaught-exception hooks, except that their hits use the event model.

## 4. Anti-goals

- A hit for normal text. The default rules must not fail on `Error` or `Exception` in a message at level INFO.
- A stack trace that gives more than one hit, or a hit that goes to the incorrect step, in 100 repetitions.
- A silent change in the result of the existing demo scenarios. `empty-name` stays s001 FAIL on its log hit.
- An agent that starts with a `logPattern` it cannot parse. It must stop with a clear message.

## 5. Approaches (owner decision: 1, 2026-11-20)

1. **Join lines into events in the agent (chosen).** One joiner per source (each log file, `System.out`,
   `System.err`). The first line of an event decides its type: a Logback line, an Equinox entry or a plain line.
   The joiner joins the continuation lines. The fail rule runs once on each event.
2. **One hit per line, grouped at the end of the step.** Fewer changes. But the level is only on the first line of a
   Logback event, so the agent must keep all lines, not only the hits. Weak for R-12a.
3. **Logback fragment bundle (R-15).** Real levels and real throwables. Each product must install the bundle, it is
   OSGi work, and it does not cover `println`. The plan puts it in phase 2.

Owner decision on the format of `logPattern` (2026-11-20): Logback syntax, with a mixed approach. A line that agrees
with `logPattern` uses its level. A line that does not agree (a `println`) uses `failRegex` and the stack trace rule.

## 6. Design

### 6.1 Configuration (`AgentConfig`, CLI `run`)

| Key | Value | Default |
|---|---|---|
| `logPattern` | a Logback pattern (the text of `<pattern>` in `logback.xml`) | `%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n` (the pilot pattern, `docs/wp-01-handoff.md` 4.3) |
| `failLevel` | `TRACE`, `DEBUG`, `INFO`, `WARN` or `ERROR` | `ERROR` |
| `failRegex` | regex for plain events (not Logback, not Equinox) | `\b(ERROR\|SEVERE\|FATAL)\b` (was `\bERROR\b\|\bSEVERE\b\|Exception\|\bError\b`) |
| `allowRegex` | regex on the full text of an event; a match is never a hit | none |
| `logCharset` | charset of the log files | the platform charset (`Charset.defaultCharset()`) |

- `failLevel` is parsed with `Locale.ROOT`. An unknown value stops the agent at start.
- `logCharset`: Logback writes with the platform charset when `<charset>` is not set. That is Cp1252 on Windows. Now
  `LogTailer` reads UTF-8. A Turkish character gives incorrect text.
- The CLI `run` and `record` pass `--logPattern`, `--failLevel` and `--logCharset` to the agent, as they pass
  `--failRegex` now. The agent arguments use `;` as the separator: a pattern with `;` stops the CLI with a message
  that names WP-05.

### 6.2 Logback pattern to regex (`LogPattern`, new, `smoker-agent`)

`LogPattern.compile(String)` converts the pattern into one regex with groups `time`, `level`, `logger`, `thread` and
`msg`. Supported:

| Pattern part | Regex |
|---|---|
| literal text | quoted |
| `%d`, `%date` with `{format}`; no format = ISO8601 `yyyy-MM-dd HH:mm:ss,SSS`; `{ISO8601}` | a regex from the date format letters `yyyy MM dd HH mm ss SSS` and literals (group `time`) |
| `%level`, `%le`, `%p` | `TRACE\|DEBUG\|INFO\|WARN\|ERROR` with padding spaces (group `level`) |
| `%logger`, `%lo`, `%c` with `{n}` | non-greedy text without spaces (group `logger`) |
| `%thread`, `%t` | non-greedy text (group `thread`) |
| `%msg`, `%m`, `%message` | the rest of the line (group `msg`) |
| `%n` | end of the line |
| `%X{…}`, `%mdc`, `%class`, `%C`, `%method`, `%M`, `%line`, `%L`, `%file`, `%F`, `%relative`, `%r`, `%kvp` | non-greedy text, no group |
| format modifiers (`%-5level`, `%.30logger`, `%20.-30thread`) | ignored; padding spaces are allowed |
| `%highlight(…)`, `%cyan(…)` and other color words with `( … )` | the inner pattern |

An unknown conversion word, or a date format letter that is not in the table, stops the agent at start:
`smoker: logPattern: conversion word %xyz is not supported`.

A line agrees with the pattern only when the regex matches from the start of the line and the `time` group parses.
A `time` without a date takes the date of the agent clock. When that time is more than 60 s after "now", it is from
the day before (a run over midnight).

### 6.3 Events and the joiner (`LogEvent`, `EventJoiner`, new)

A `LogEvent` has: source, first line, full text (all lines), time (millis), level or severity (can be null), and
a flag "has stack trace".

`EventJoiner` takes lines of one source in order and gives closed events:

- **Logback line** (agrees with `logPattern`): starts a new event. Time from the line, level from the line.
- **Equinox entry** (line starts with `!ENTRY <bundle> <severity> <code> <date> <time>`): starts a new event. Time
  from the `!ENTRY` line (`yyyy-MM-dd HH:mm:ss.SSS`), severity 1 INFO, 2 WARNING, 4 ERROR, 8 CANCEL. While an Equinox
  entry is open, the lines `!MESSAGE`, `!SUBENTRY` and `!STACK` join it. `!SESSION` starts an Equinox event with no
  severity (never a hit); the header lines after it (`eclipse.buildId=…`, `java.version=…`) join it.
- **Continuation line** (`\tat `, `at ` with leading spaces, `Caused by:`, `Suppressed:`, `\t... N more`, `... N more`,
  or a line that starts with a space or a tab): joins the open event and sets "has stack trace" for `at` lines.
  Lines between the first line of a Logback event and its first `at` line also join it (the first line of the
  exception after the message: `java.lang.IllegalStateException: …`). Rule: while an event is open, a line that does
  not start a new event joins it when it agrees with `^[\w.$]+(Exception|Error|Throwable)\b`.
- **Plain line**: any other line starts a new plain event. Time is the time at which the agent gets the line.
- **Empty line:** does not close the open event. The joiner keeps it. When the next line is a continuation line,
  the empty line and that line join the event. Else the joiner drops the empty line. (In the real Equinox output, an
  empty line separates `Unresolved requirement:` from the first `\tat` line of one entry.)
- **`[smoker] ` line:** never an event, and it closes the open event of its source.
- A continuation line with no open event starts a plain event.

An event closes when the next event starts, or when the step closes (`drain`, section 6.4).

The fail rule runs once on each closed event:

| Event | Hit when |
|---|---|
| Logback | level ≥ `failLevel` |
| Equinox | severity 4 or 8, or severity 2 when `failLevel` ≤ `WARN` |
| plain | "has stack trace", or the first line matches `failRegex` |

`allowRegex` applies to the full text of the event and wins over all other rules.

A hit (`RunResult.LogHit`) keeps the first line in `line`, as now, and gets a new field `text` with the full text of
the event, limited to 50 lines and 8000 characters. `timestamp` is the time of the event.

### 6.4 Sources and step assignment (`Observations`, `LogTailer`, `Player`)

- **Log files:** `LogTailer` decodes with `logCharset` and gives lines to its own `EventJoiner`. A new method
  `drain()` reads the file to its end on the calling thread (under the lock of the tailer) and closes the open event.
  The polling thread stays for the time between steps.
- **A file that does not exist at agent start** is read from position 0 when it appears. (Now the tailer starts at the
  end at first sight. Lines written before the first poll are lost.) A file that exists at start is read from its end,
  as now.
- **`System.out` and `System.err`:** the agent wraps both streams (`System.out` is new). It decodes the bytes with the
  charset of the stream (now each byte is a char). Lines that start with `[smoker] ` are the agent's own output and
  are not events. Each stream has its own joiner. These lines arrive on the thread that writes them, so their time is
  exact.
- **JUL handler and uncaught exceptions:** each record is one event, as now. An uncaught exception is always a hit.
- **`Observations.drain()`:** drains each tailer and closes the open event of each joiner. `Player` calls it before it
  collects the hits of a step.
- **Contiguous windows:** `Player` keeps `lastEnd`. The startup window is [agent start, end of startup]. Each step
  takes the hits in (`lastEnd`, now]. A hit between two steps goes to the next step. Now such a hit is lost.
- **Time precision (builder check, my call):** when the date format of `logPattern` has no milliseconds (`SSS`), the
  agent compares at the precision of the format. A window then starts at the start of the second of `t0`. A line of a
  short step is not "late" because the format has no milliseconds.
- **Assignment by log time:** a file event goes to the window that contains its time. A file event with a time
  before the window of the current step (a late line of an earlier step) goes to the current step, with the prefix
  `late:` in `source`. The step that it belongs to is already written. A file event without a time (plain line) uses
  the time of reading.

### 6.5 Equinox framework errors (F-22)

The products start with `-consoleLog` (`docs/wp-01-handoff.md`). Equinox writes its log entries to the console in the
format of section 6.3. With `System.out` read, a severity-4 entry in startup gives startup FAIL.

**Samples (owner, 2026-11-22, branch `wp-04-samples`, `docs/wp-04-samples/`):** the risk is closed. On JRE 8,
spike.master writes 28 `!ENTRY` entries with severity 4 to the console in startup: 14 `FrameworkEvent ERROR` entries
with `!STACK 0`, `BundleException: Could not resolve module: …` and a trace, and 14 short entries
`Bundle initial@reference:file:… was not resolved.` The console entries are the same as `configuration\<n>.log`.
The spike.master startup therefore gives FAIL with 28 hits (one for each entry). Other facts from the samples:

- The console also has the Logback lines in the pilot pattern. A thread name can have spaces and colons
  (`[Start Level: Equinox Container: d59c8d93-…]`).
- The console text is Cp1252 (an em dash is one byte). The charset decode of section 6.4 is necessary.
- `crash.json`: the last line before `System.exit(7)` is `22:51:29.827 [AWT-EventQueue-0] ERROR … crashing on
  purpose`. After WP-04 it is the hit of s001.
- `p2-app-output.log` (pilot, JRE 8) has no Equinox entry and no ERROR line, only INFO and one WARN. `pilot.json`
  stays PASS.

The samples move to `smoker-agent/src/test/resources/wp04/` as test fixtures. The demo fixture of section 6.8 uses a
part of `spike-crash-app-output.log` (two entries of each kind).

**Pilot (owner, 2026-11-22):** unresolved bundles at startup give FAIL. No allow list for them. The Windows check
expects startup FAIL for spike.master (`hang.json`, `crash.json`) and PASS for `pilot.json`. WP-04 updates
`docs/wp-01-handoff.md` and `docs/wp-01-pilot-report.md`.

**Duplicate hits on the pilot:** the pilot runs with `--log logs\smoker.log`, and the same Logback lines are on the
console. Each ERROR then gives two hits. `README.md` recommends no `--log` for a product that logs to the console.

### 6.6 Result on `System.exit` (F-22)

- `RunResult` gets the field `currentStep` (step id, null when no step runs) and keeps `finishedAt` 0 until the end.
- Before each action (and before startup), `Player` writes `run-result.json` with `currentStep` and the results of the
  steps that are closed. Writes go to a temp file and then a move (`ATOMIC_MOVE` when the file system supports it),
  so a crash cannot leave half a file.
- The agent adds a shutdown hook. When the application calls `System.exit` before the player has finished, the hook:
  drains the observations, adds a step result for `currentStep` with status ERROR, the error
  `the application exited during step s003`, and the hits of its window, sets `status` ERROR and `finishedAt`, and
  writes the file. When the player has finished (its own `System.exit`), the hook does nothing.
- The hook has 2 s. It does not touch Swing.
- A crash in native code skips the hook. The file from before the action still names the step.
- CLI `run`: when `run-result.json` exists and `finishedAt` is 0, the status is ERROR (HANG when the CLI killed the
  application) and the error is `the application stopped during step s003; exit code 7`. When the hook wrote the file,
  the CLI adds `; exit code 7` to its error. The report and `junit.xml` show the step and its hits.
- Locks: `Player` and the hook write under one lock. The hook waits for the lock at most 1 s.

### 6.7 `approve` without `run-result.json` (F-24)

`approve: no run-result.json in <dir>` on `System.err`, exit code 2, as `lint`.

### 6.8 Demo application and scenarios

- The demo writes `demo.log` in the pilot pattern (a JUL formatter that writes `HH:mm:ss.SSS [thread] LEVEL logger -
  msg`, SEVERE → ERROR, WARNING → WARN). The existing `empty-name` scenario keeps its result.
- New demo buttons (named, in a "Log" panel):
  - `demo.logTrace`: logs one ERROR with an exception of 40 trace lines, and writes it at the end of the action,
    just before the step closes (R-10a, R-12a).
  - `demo.logInfoError`: logs `INFO` with the text `Error count 0 of 3`, and prints `Exception handler ready` with
    `println` (no hit: anti-goal).
  - `demo.printTrace`: `e.printStackTrace()` of an exception with a cause (one hit on `stderr`).
  - `demo.exit`: logs an ERROR, then `System.exit(7)`.
- Startup: with `-Ddemo.equinoxError=true`, the demo prints the real Equinox error text from the owner's sample to
  `System.out` at startup (fixture from section 6.5).
- Scenarios: `log.json` (`logTrace`, `logInfoError`, `printTrace`) and `scenarios-failing/exit.json` (`demo.exit`).

### 6.9 Documentation

`README.md`: the new keys, the new `failRegex` default, the event rules, the duplicate-hit note, the `;` limit, the
`-consoleLog` need, and the CLI result after `System.exit`. `CLAUDE.md` and the plan: state after WP-04.

## 7. Tests

- Unit (`smoker-agent`): `LogPattern` (the pilot pattern, ISO8601, `%-5level`, color words, unknown word),
  `EventJoiner` (Logback with 40 trace lines = 1 event; `Caused by` and `... 12 more`; Equinox entry with `!STACK`;
  plain `println` lines; continuation without open event; drain closes the open event), fail rules (anti-goal texts),
  midnight rule, `[smoker]` lines ignored, charset decode (Cp1252 `ş`).
- Sample fixtures (`wp04/`, real text): `spike-crash-app-output.log` gives exactly 28 Equinox hits, one Logback hit
  (`crashing on purpose`) and no other hit; `p2-app-output.log` gives no hit; `spike-configuration.log` gives 28 hits.
- **R-10a, 100 repetitions** (unit test with a real file and a real `LogTailer`): 100 times, write an ERROR line to
  the file and then close the step at once. Each time the hit goes to that step and not to the next. Runs in a few
  seconds. The IT also checks the late line once (`demo.logTrace`).
- **R-12a:** `log.json` in `SelfTestIT`: `logTrace` gives exactly one hit with 40 trace lines in `text`;
  `logInfoError` gives no hit; `printTrace` gives one `stderr` hit.
- **F-22:** `exit.json`: `run-result.json` has `currentStep` s001, status ERROR, the error names s001 and exit code 7,
  and s001 has the ERROR hit. Startup with `demo.equinoxError`: startup FAIL with one hit.
- **F-24:** CLI unit test: `approve` on an empty directory gives exit code 2 and the message.
- `mvn -B verify` and `mvn -B verify -Plocale-tr` on macOS. On Windows: the three builds, and the pilot spike.master
  with JRE 8 (unresolved bundles give startup FAIL) and `crash.json` (names the step).

## 8. Left to the builder

Calls from the builder check (2026-11-20):

- The CLI deletes an old `run-result.json` in the run folder before it starts the application. Otherwise a file of an
  earlier run can look like the result of a run that crashed.
- A `late:` hit makes the current step FAIL, as each hit does now.
- JUL levels map to the Logback levels for `failLevel`: SEVERE → ERROR, WARNING → WARN, INFO → INFO, others → DEBUG.
- The charset of `System.out` and `System.err` on Java 8: the `sun.stdout.encoding` and `sun.stderr.encoding` property
  when set (a console on Windows), else the platform charset.

- The class split of `LogPattern`, `LogEvent` and `EventJoiner`, and the names of their methods.
- The exact regex for each conversion word, within section 6.2.
- How the hook gets the player state (a static field in `SmokerAgent` is fine).
- The layout of the new demo panel, and where the full text of a hit appears in `report.html`.
