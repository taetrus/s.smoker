# WP-04 Log analysis Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the log a reliable judge: one hit for each event, the hit in the correct step, Equinox errors and `System.out` read, a result after `System.exit`, and `approve` without a result gives exit code 2 (F-08, F-09, F-22, F-24, R-10a, R-12a).

**Architecture:** A new `LogPattern` converts a Logback pattern into a regex. A new `EventJoiner` (one for each source: each log file, `System.out`, `System.err`) joins lines into `LogEvent`s. A `FailRule` decides once for each event. `Observations` collects the hits and gives them to `Player` by window (`takeHits(from, to)`) after a `drain()`. `Player` writes `run-result.json` before each action and from a shutdown hook. The CLI reads an unfinished result and names the step.

**Tech Stack:** Java 8 source level (`--release 8`), Swing, JUnit 4, Gson (relocated), Maven.

**Spec:** `docs/superpowers/specs/2026-11-20-wp-04-log-analysis-design.md`

## Global Constraints

- Java 8 source: no `var`, no `List.of`, no `String.isBlank`, no `Files.readString`. Anonymous classes, as in the existing code. `java.util.function` is available.
- No new dependency. The agent stays free of runtime dependencies except relocated Gson.
- The agent does no bytecode instrumentation. Logback is observed through its output only.
- Case conversion only with `Locale.ROOT` (`failLevel`, level names).
- Never print (`System.out`, `System.err`, `SmokerAgent.log`) while you hold the lock of `Observations`. The console tee takes that lock inside the `PrintStream` lock: a print under the `Observations` lock can deadlock.
- `[smoker]` texts stay ASCII (PR #8).
- `schemaVersion` of the scenario stays 2. `RunResult` gets new fields only (`currentStep`, `LogHit.text`); old result files still read.
- Defaults (spec 6.1): `logPattern` = `%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n`; `failLevel` = `ERROR`; `failRegex` = `\b(ERROR|SEVERE|FATAL)\b`; `logCharset` = `Charset.defaultCharset()`.
- Error text (spec 6.2): `smoker: logPattern: conversion word %<word> is not supported`. Exit text (6.6): hook `the application exited during step <id>`; CLI `the application stopped during step <id>; exit code <n>`. F-24 (6.7): `approve: no run-result.json in <dir>`.
- `LogHit.text`: at most 50 lines and 8000 characters.
- Commit messages name the WP and the findings: `WP-04, F-09: …`. End each message with the attribution lines of the session. Commit with `6750412+taetrus@users.noreply.github.com`.
- Documentation in Simplified Technical English. Config or result format change → `README.md` in the same commit.
- After each change to `smoker-agent` or `smoker-core`: `mvn -B verify` and `mvn -B verify -Plocale-tr`.

## Review Focus

1. A Logback INFO line is open, and the application then prints an unrelated `println` text such as `java.util.Date loaded`: the exception-line rule (`^[\w.$]+(Exception|Error|Throwable)\b`) must not join plain text that is not an exception name. Test in Task 2 (`plainTextAfterLogbackLineStartsANewEvent`).
2. A stack trace whose lines arrive in two parts across a `drain()` (step end between `ERROR …` and its `\tat` lines): the result is two hits at most, never a lost hit, and the second part is a hit (it has a trace). Test in Task 2 (`continuationAfterCloseStartsAPlainHit`).
3. A deadlock between the console tee and a drain: a thread prints while the player thread drains. Test in Task 3 (`printWhileDrainingDoesNotDeadlock`, 2 threads, 1000 prints, finishes in 5 s).
4. The application calls `System.exit` while the player thread is in the middle of a step: the player must not overwrite the file of the hook with a later final write. Test in Task 4 (`hookResultIsNotOverwrittenByThePlayer`).
5. A log file that the application deletes and writes again (rotation) during a run: the tailer starts again at 0 and gives no duplicate of the lines it already read. Test in Task 3 (`rotatedFileIsReadFromTheStart`).

---

### Task 1: `LogPattern` — Logback pattern to regex

**Files:**
- Create: `smoker-agent/src/main/java/smoker/agent/LogLevel.java`, `smoker-agent/src/main/java/smoker/agent/LogPattern.java`
- Test: `smoker-agent/src/test/java/smoker/agent/LogPatternTest.java`

**Interfaces:**
- Produces: `enum LogLevel { TRACE, DEBUG, INFO, WARN, ERROR; static LogLevel parse(String) /* Locale.ROOT, IAE on unknown */; static LogLevel ofJul(java.util.logging.Level) /* SEVERE→ERROR, WARNING→WARN, INFO→INFO, else DEBUG */; static LogLevel ofEquinoxSeverity(int) /* 1 INFO, 2 WARN, 4 ERROR, 8 ERROR, else null */ }`
- Produces: `final class LogPattern { static final String DEFAULT = "%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n"; static LogPattern compile(String pattern) /* IAE with the spec text */; Match match(String line, long now) /* null when the line does not agree */; int precisionMs() /* 1 with SSS, else 1000 */ }` and `static final class Match { final long time; final LogLevel level; final String thread, logger, msg; }`

- [ ] **Step 1: Write the failing tests:**
  - `defaultPatternParsesAPilotLine`: line `22:51:29.827 [AWT-EventQueue-0] ERROR com.kk.pde.ds.spike.master.MasterApp - App-1 crashing on purpose`, `now` = local 2026-11-14 22:51:30.000 → level ERROR, thread `AWT-EventQueue-0`, logger `com.kk.pde.ds.spike.master.MasterApp`, msg `App-1 crashing on purpose`, time = local 2026-11-14 22:51:29.827.
  - `threadWithSpacesAndColons`: `22:48:04.573 [Start Level: Equinox Container: f41427cf-3b9c] INFO  com.kk.pde.ds.imp.Greet - Greet.start()` → thread `Start Level: Equinox Container: f41427cf-3b9c`, level INFO, msg `Greet.start()`.
  - `iso8601DefaultDate`: pattern `%d %-5level %logger - %msg%n`, line `2026-11-20 10:00:01,234 WARN  a.b - x` → time 2026-11-20 10:00:01.234, level WARN; `%date{ISO8601}` gives the same.
  - `colorWordsUseTheInnerPattern`: `%d{HH:mm:ss.SSS} %highlight(%-5level) %cyan(%logger{15}) - %msg%n` parses `10:00:00.000 ERROR a.B - boom`.
  - `exceptionWordsMatchNothing`: `%d{HH:mm:ss.SSS} %level %msg%n%ex` parses `10:00:00.000 INFO hi`.
  - `unknownWordIsRefused`: `compile("%d %xyz %msg")` throws IAE with message `smoker: logPattern: conversion word %xyz is not supported`; `compile("%d{dd MMM yyyy} %msg")` throws IAE (date letter `MMM`).
  - `lineThatDoesNotAgreeGivesNull`: `!ENTRY org.eclipse.osgi 4 0 2026-11-14 22:51:28.927`, `Exception handler ready`, `\tat a.B.c(B.java:1)` → null.
  - `timeAfterNowIsFromTheDayBefore`: `now` = local 2026-11-15 00:00:10.000, line time `23:59:59.000` → 2026-11-14 23:59:59.000; line time `00:00:05.000` → 2026-11-15.
  - `precision`: default pattern → 1; `%d{HH:mm:ss} %msg%n` → 1000.
  - `levelParseUsesLocaleRoot`: under `Locale.setDefault(new Locale("tr","TR"))`, `LogLevel.parse("info")` == INFO (restore the locale in `finally`).
- [ ] **Step 2: Run** `mvn -B -pl smoker-agent test -Dtest=LogPatternTest -Dsurefire.failIfNoSpecifiedTests=false` — expect compile failure.
- [ ] **Step 3: Implement** `LogLevel` and `LogPattern` per spec 6.2. Approach: a small scanner over the pattern (`%%` literal; `%` + optional modifier `-?\d*(\.-?\d+)?` + word + optional `{…}` options (quotes allowed) + optional `(`inner`)` for color words). Each conversion becomes ` *` + its regex + ` *`; literal text is `Pattern.quote`d; the first `%d`, `%level`, `%thread`, `%logger`, `%msg` get the named groups, later ones get none. Words with empty regex: `%ex %exception %throwable %xEx %xException %xThrowable %rEx %rootException %nopex %nopexception %wEx`. The date format becomes a regex from `yyyy yy MM dd HH mm ss SSS` and literals (`'…'` quoted); parse the captured text with `SimpleDateFormat(format, Locale.ROOT)` non-lenient; a format without `y`/`M`/`d` takes the local date of `now`, then the 60 s midnight rule.
- [ ] **Step 4: Run** the same command — PASS.
- [ ] **Step 5: Commit** `WP-04, F-09: LogPattern converts a Logback pattern into a regex`.

### Task 2: `LogEvent`, `EventJoiner`, `FailRule` — one event, one decision

**Files:**
- Create: `smoker-agent/src/main/java/smoker/agent/LogEvent.java`, `EventJoiner.java`, `FailRule.java` (same package)
- Move: `docs/wp-04-samples/*.log` → `smoker-agent/src/test/resources/wp04/` (`git mv`; keep the names)
- Test: `smoker-agent/src/test/java/smoker/agent/EventJoinerTest.java`, `FailRuleTest.java`

**Interfaces:**
- Consumes: `LogPattern`, `LogLevel` (Task 1).
- Produces: `final class LogEvent { enum Kind { LOGBACK, EQUINOX, PLAIN, JUL, UNCAUGHT } final String source; final Kind kind; final long time; final int precisionMs; final LogLevel level /* null: plain, or !SESSION */; boolean hasStackTrace; final List<String> lines; String firstLine(); String text() /* lines joined with "\n", at most 50 lines and 8000 chars */ }`
- Produces: `final class EventJoiner { EventJoiner(String source, LogPattern pattern, boolean useLineTime, java.util.function.Consumer<LogEvent> sink); void line(String line, long arrivedAt); void close(); }` — `useLineTime` true for files (time from the line), false for streams (time of arrival). `close()` closes the open event and drops a held empty line.
- Produces: `final class FailRule { FailRule(LogLevel failLevel, java.util.regex.Pattern failRegex, java.util.regex.Pattern allowRegex /* nullable */); boolean isHit(LogEvent e); }` — table of spec 6.3 plus: JUL hit when level ≥ failLevel or it has a throwable (`hasStackTrace`); UNCAUGHT always; `allowRegex.find(e.text())` wins over everything.

- [ ] **Step 1: Write the failing tests** (`EventJoinerTest` uses the pilot pattern and collects events in a list):
  - `logbackErrorWith40TraceLinesIsOneEvent`: one ERROR line, `java.lang.IllegalStateException: boom`, 40 `\tat a.B.m(B.java:N)` lines, then an INFO line → 2 events; the first has 42 lines, `hasStackTrace`, level ERROR.
  - `causedByAndMoreJoin`: trace with `Caused by: java.io.IOException: x`, `\t... 12 more`, `Suppressed: …` → one event.
  - `equinoxEntryWithEmptyLineInsideIsOneEvent`: the first 31 lines of `wp04/spike-crash-app-output.log` (`[smoker] agent started…`, `!SESSION`, header, the first `!ENTRY` with its empty line before `\tat`) then `close()` → 2 events: `!SESSION` (EQUINOX, level null) and the `!ENTRY` (EQUINOX, ERROR, 22 lines incl. `Unresolved requirement`, time 2026-11-14 22:51:28.788); the `[smoker]` line is no event.
  - `shortEquinoxEntries`: `!ENTRY org.eclipse.osgi 4 0 2026-11-14 22:51:28.927` / `!MESSAGE Bundle … was not resolved.` / empty / next `!ENTRY` → 2 events.
  - `plainPrintlnLines`: `Exception handler ready`, `hello` → 2 PLAIN events, no stack trace.
  - `printStackTraceIsOnePlainEventWithTrace`: `java.lang.IllegalStateException: print demo`, 3 `\tat`, `Caused by: java.io.IOException: cause`, `\t... 3 more` → 1 PLAIN event, `hasStackTrace`.
  - `plainTextAfterLogbackLineStartsANewEvent` (Review Focus 1): INFO line, then `java.util.Date loaded` → 2 events.
  - `continuationAfterCloseStartsAPlainHit` (Review Focus 2): ERROR line, `close()`, then 3 `\tat` lines → 2 events; the second is PLAIN with `hasStackTrace`.
  - `fileEventsUseLineTimeStreamsUseArrival`: same Logback line with `useLineTime` true → line time; false → `arrivedAt`.
  - `sampleFilesGiveTheExpectedHits` (`FailRuleTest`, default rule): read each `wp04/` file as Cp1252, feed all lines, `close()`, count hits: `spike-crash-app-output.log` 28 EQUINOX + 1 LOGBACK (`crashing on purpose`) and no other; `spike-hang-app-output.log` 28; `spike-configuration.log` 28; `spike-crash1-configuration.log` 28; `p2-app-output.log` 0.
  - `normalTextIsNoHit` (`FailRuleTest`, anti-goal): Logback INFO `Error count 0 of 3`; PLAIN `Exception handler ready`; PLAIN `No Error found` → no hit. PLAIN `FATAL disk` → hit. Logback WARN with trace, failLevel ERROR → no hit; failLevel WARN → hit.
  - `allowRegexWins`: ERROR event whose second line matches the allow regex → no hit.
  - `textIsLimited`: an event of 200 lines → `text()` has 50 lines; a line of 20000 chars → `text()` length ≤ 8000.
- [ ] **Step 2: Run** `mvn -B -pl smoker-agent test -Dtest='EventJoinerTest,FailRuleTest' -Dsurefire.failIfNoSpecifiedTests=false` — expect compile failure.
- [ ] **Step 3: Implement** per spec 6.3. The order of checks for each line: `[smoker] ` (close, drop) → `!ENTRY`/`!SESSION` (new EQUINOX) → `!MESSAGE`/`!SUBENTRY`/`!STACK` with an open EQUINOX event (join) → `LogPattern.match` (new LOGBACK) → empty line (hold) → continuation (join, or new PLAIN when none is open) → exception-name rule with an open event (join) → new PLAIN. `!ENTRY` time format `yyyy-MM-dd HH:mm:ss.SSS`, parsed with `Locale.ROOT`.
- [ ] **Step 4: Run** the same command — PASS.
- [ ] **Step 5: Commit** `WP-04, F-09, F-22: join log lines into events, one fail decision for each event` (with the `git mv` of the samples).

### Task 3: Sources, drain and step windows (`AgentConfig`, `Observations`, `LogTailer`, `ConsoleTee`, `Player`)

**Files:**
- Modify: `smoker-agent/src/main/java/smoker/agent/AgentConfig.java`, `Observations.java`, `LogTailer.java`, `Player.java` (`startup`, `finish`)
- Create: `smoker-agent/src/main/java/smoker/agent/ConsoleTee.java`
- Modify: `smoker-core/src/main/java/smoker/core/model/RunResult.java` (`LogHit.text`)
- Test: `AgentConfigTest.java`, new `ObservationsTest.java`, new `LogTailerTest.java`

**Interfaces:**
- Consumes: Tasks 1 and 2.
- Produces: `AgentConfig` fields `public String logPattern = LogPattern.DEFAULT; public LogLevel failLevel = LogLevel.ERROR; public Charset logCharset = Charset.defaultCharset();`, `failRegex` default `\b(ERROR|SEVERE|FATAL)\b`; keys `logPattern`, `failLevel`, `logCharset`. `AgentConfig.parse` compiles `logPattern` once to check it (IAE stops the agent).
- Produces: `RunResult.LogHit.text` (`public String text;` full text of the event).
- Produces: `Observations(AgentConfig cfg)`; `void install(AgentConfig cfg)` (as now, plus the `System.out` tee); `void accept(LogEvent e)` (package-private sink for the joiners and the tests); `void drain()`; `List<RunResult.LogHit> takeHits(long from, long to)`; `void stop()`. `hitsBetween` is removed.
- Produces: `final class ConsoleTee extends PrintStream { ConsoleTee(PrintStream real, Charset cs, EventJoiner joiner) }`; `static Charset streamCharset(String which /* "stdout" | "stderr" */)` — property `stdout.encoding`, then `sun.stdout.encoding`, then `Charset.defaultCharset()`.
- Produces: `LogTailer(Path file, Charset cs, EventJoiner joiner)`; `void drain()` (reads to the end on the calling thread under the tailer lock, then `joiner.close()`).

`takeHits(from, to)` semantics: call `drain()` first; return and remove every collected hit with `time ≤ to`. A hit with `time ≤ from` (ms precision) or `time < from − from % 1000` (s precision) gets `source = "late:" + source`. `from = Long.MIN_VALUE` means "no lower limit" (startup). Hits with `time > to` stay.

- [ ] **Step 1: Write the failing tests:**
  - `AgentConfigTest.newKeysAndDefaults`: defaults equal the Global Constraints; `logPattern=%d %msg%n;failLevel=warn;logCharset=windows-1252` parse; `failLevel=LOUD` and `logPattern=%xyz` throw IAE.
  - `ObservationsTest.takeHitsUsesContiguousWindows`: accept hits at times 100, 200, 300; `takeHits(MIN, 150)` → [100]; `takeHits(150, 250)` → [200]; a hit accepted later with time 120 → next `takeHits(250, 400)` → [`late:` 120, 300].
  - `ObservationsTest.secondPrecisionIsNotLate`: hit time 5000 (precision 1000), `takeHits(5300, 6000)` → one hit, not late.
  - `ObservationsTest.smokerLinesAreIgnored`: a `ConsoleTee` over a `ByteArrayOutputStream`; `println("[smoker] step s001 ERROR")` → no event; the bytes still reach the real stream.
  - `ObservationsTest.teeDecodesTheCharset`: write the Cp1252 bytes of `ERROR şık` + `\n` through a tee with `windows-1252` → one hit whose line is `ERROR şık`.
  - `ObservationsTest.printWhileDrainingDoesNotDeadlock` (Review Focus 3): thread A prints 1000 ERROR lines through the tee while thread B calls `takeHits` in a loop; both finish within 5 s; 1000 hits in total.
  - `LogTailerTest.r10aHundredRepetitions` (R-10a): real temp file and a started `LogTailer` + `Observations`; 100 times: `from = now`, append `HH:mm:ss.SSS [main] ERROR t - step i` with the current time, at once `takeHits(from, now)` → exactly one hit, text `step i`, not `late:`. Must finish in under 20 s.
  - `LogTailerTest.fileCreatedAfterStartIsReadFromZero`: tailer started on a missing file; create it with 2 ERROR lines; `drain()` → 2 hits.
  - `LogTailerTest.existingFileIsReadFromItsEnd`: file with 3 ERROR lines before the start → no hit; one more line → 1 hit.
  - `LogTailerTest.rotatedFileIsReadFromTheStart` (Review Focus 5): read 2 lines; truncate the file and write 1 new ERROR line → exactly 1 more hit.
  - `LogTailerTest.charset`: file in Cp1252 with `ERROR ğ`, `logCharset` windows-1252 → line `ERROR ğ`.
- [ ] **Step 2: Run** `mvn -B -pl smoker-core,smoker-agent install -DskipTests -q` then `mvn -B -pl smoker-agent test -Dtest='AgentConfigTest,ObservationsTest,LogTailerTest' -Dsurefire.failIfNoSpecifiedTests=false` — expect failures.
- [ ] **Step 3: Implement.** `Observations` keeps one lock for its hit list; `accept` runs the `FailRule` and stores the hit (`line` = first line, cut at 2000 chars as now; `text`; `timestamp` = event time). The JUL handler and the uncaught hooks build a `LogEvent` (JUL, UNCAUGHT) and call `accept`. `install` wraps `System.out` and `System.err` with `ConsoleTee` (one joiner each, `useLineTime` false, sources `stdout`, `stderr`). `LogTailer`: start at 0 when the file did not exist at construction; keep its rotation rule; decode with its charset; `drain()` and the poll loop share one lock. `Player.startup` uses `takeHits(Long.MIN_VALUE, now)`; `Player.finish` uses `takeHits(lastEnd, now)` and sets `lastEnd = now`; `startup` sets `lastEnd` too.
- [ ] **Step 4: Run** the same tests — PASS. Then `mvn -B verify` and `mvn -B verify -Plocale-tr` — all pass, SelfTestIT unchanged (`empty-name` s001 FAIL with `file:` hit `greet called with empty name`).
- [ ] **Step 5: README.** Agent keys table: `logPattern`, `failLevel`, `logCharset`, new `failRegex` default; a section "Log rules" with the table of spec 6.3, the `System.out` read, `[smoker]` lines ignored, the duplicate-hit note and "no `--log` for a product that logs to the console", and the `;` limit.
- [ ] **Step 6: Commit** `WP-04, F-08, F-09, F-22: read System.out, drain each source before a step closes, contiguous step windows`.

### Task 4: Result before each action and on `System.exit` (`Player`, `SmokerAgent`, `RunResult`)

**Files:**
- Modify: `smoker-core/src/main/java/smoker/core/model/RunResult.java` (`currentStep`)
- Modify: `smoker-core/src/main/java/smoker/core/io/Json.java` (atomic write, if `Json.write` is the shared writer; else a helper in `Player`)
- Modify: `smoker-agent/src/main/java/smoker/agent/Player.java`
- Test: `smoker-agent/src/test/java/smoker/agent/PlayerExitTest.java`, `smoker-core/src/test/java/smoker/core/io/JsonTest.java`

**Interfaces:**
- Consumes: `Observations.drain()`, `takeHits` (Task 3).
- Produces: `RunResult.currentStep` (`public String currentStep;`), `finishedAt` stays 0 until the end.
- Produces: in `Player`: `void writeProgress(String stepId)` (before startup with `"startup"`, and before each action); `void onExit()` (the shutdown-hook body, package-private for tests); `boolean finished` and `boolean exiting` under one lock `resultLock`. `run()` adds the hook (`Runtime.getRuntime().addShutdownHook`) at its start.
- Produces: `Json.writeAtomic(Path, Object)` — write to `<name>.tmp` in the same folder, then `Files.move` with `ATOMIC_MOVE`, and with `REPLACE_EXISTING` as the fallback when `ATOMIC_MOVE` is not supported.

- [ ] **Step 1: Write the failing tests** (`PlayerExitTest`, with a `Player` over a temp out dir and a fake scenario, as `PlayerTest` builds them):
  - `progressNamesTheCurrentStep`: after `writeProgress("s002")`, the file has `currentStep` `s002`, `finishedAt` 0, and the closed steps.
  - `onExitWritesAnErrorStepWithItsHits`: `writeProgress("s001")`, accept an ERROR event `crashing on purpose`, call `onExit()` → file status ERROR, `error` `the application exited during step s001`, last step `s001` ERROR with the hit, `finishedAt` > 0.
  - `onExitAfterTheEndDoesNothing`: after the normal final write, `onExit()` leaves the file unchanged (byte-equal).
  - `hookResultIsNotOverwrittenByThePlayer` (Review Focus 4): `onExit()`, then the player's final write path runs → the file still has the hook's error.
  - `JsonTest.writeAtomicReplacesTheFile`: write twice; the file has the second content and no `.tmp` file stays.
  - `JsonTest.oldResultWithoutNewFieldsReads`: a `run-result.json` without `currentStep` and `text` reads.
- [ ] **Step 2: Run** `mvn -B -pl smoker-core,smoker-agent install -DskipTests -q` then `mvn -B -pl smoker-agent test -Dtest=PlayerExitTest -Dsurefire.failIfNoSpecifiedTests=false` — expect failures.
- [ ] **Step 3: Implement** per spec 6.6. `onExit`: `tryLock` 1 s on `resultLock`; return when `finished` or when the lock is not got; set `exiting`; `obs.drain()`; add the step result for `currentStep` (`startup` included); status ERROR; `finishedAt`; `writeAtomic`. The player's final write in `finally` skips the write when `exiting`, and sets `finished` before its own `System.exit`. The hook does not touch Swing and does not print under the `Observations` lock.
- [ ] **Step 4: Run** the same tests — PASS; then `mvn -B verify` and `mvn -B verify -Plocale-tr`.
- [ ] **Step 5: Commit** `WP-04, F-22: run-result.json before each action and on System.exit`.

### Task 5: CLI — unfinished results, new options, `approve` (F-24), reports

**Files:**
- Modify: `smoker-cli/src/main/java/smoker/cli/Main.java` (`run`, `record`, `approve`, usage text)
- Modify: `smoker-core/src/main/java/smoker/core/report/HtmlReportWriter.java`, `JUnitXmlWriter.java`
- Test: `smoker-cli/src/test/java/smoker/cli/MainTest.java`, `smoker-core/src/test/java/smoker/core/report/HtmlReportWriterTest.java`, `JUnitXmlWriterTest.java`

**Interfaces:**
- Consumes: `RunResult.currentStep`, `LogHit.text` (Tasks 3, 4).
- Produces: `static RunResult resultOf(Path runDir, String name, Path scenario, int exit, long budgetMs) throws IOException` in `Main` — the logic that `run` now has inline after `launch`, extracted so that it can be tested.

- [ ] **Step 1: Write the failing tests:**
  - `approveWithoutRunResultGivesExitCode2` (F-24): empty temp dir → `approve` returns 2; `System.err` contains `approve: no run-result.json in `; no stack trace.
  - `unfinishedResultNamesTheStep`: a file with `currentStep` `s003`, `finishedAt` 0 → `resultOf(…, exit 7, …)` status ERROR, error `the application stopped during step s003; exit code 7`; with exit −999 → status HANG and the error names s003.
  - `hookResultGetsTheExitCode`: a file with `finishedAt` > 0 and error `the application exited during step s001` → error `the application exited during step s001; exit code 7`.
  - `missingResultKeepsTheOldMessage`: no file → as now (`no run-result.json produced; app exit code 7 — see app-output.log`).
  - `runDeletesAnOldResultBeforeTheLaunch`: covered by the IT (Task 6); here assert that `agentArgs` built by `run` contain `;logPattern=`, `;failLevel=`, `;logCharset=` when the options are given (extract `static String agentArgs(Map<String,String> o, Path scenario, Path runDir)` if needed).
  - `semicolonInLogPatternIsRefused`: `--logPattern "%d;%msg"` → `run` returns 2, message names WP-05, no application starts.
  - `HtmlReportWriterTest.hitShowsTheFullText`: a hit with `text` of 3 lines → the report contains all 3 lines (escaped); a hit without `text` shows `line`. `JUnitXmlWriterTest`: same for the failure body.
- [ ] **Step 2: Run** `mvn -B -pl smoker-core,smoker-cli test` — expect failures.
- [ ] **Step 3: Implement.** `run`: `Files.deleteIfExists(runDir.resolve("run-result.json"))` before `launch`; pass `--logPattern`, `--failLevel`, `--logCharset` (and `record` too); refuse a `;` in `logPattern` with `run: logPattern must not contain ';' (agent arguments use ';'; WP-05 adds a configuration file)`. `approve`: check the file first, as `lint` does. Usage text lists the new options.
- [ ] **Step 4: Run** the same command — PASS; then `mvn -B verify`.
- [ ] **Step 5: README.** CLI options, the result after `System.exit` (error text with the step), F-24 behaviour of `approve`.
- [ ] **Step 6: Commit** `WP-04, F-22, F-24: CLI names the step of an unfinished run; approve without result gives exit code 2`.

### Task 6: Demo, scenarios and SelfTestIT (completion criterion on the demo)

**Files:**
- Modify: `smoker-demo/src/main/java/smoker/demo/DemoApp.java`
- Create: `smoker-demo/src/main/java/smoker/demo/PilotFormatter.java`, `smoker-demo/src/main/resources/equinox-error.txt`
- Create: `smoker-demo/scenarios-log/log.json`, `smoker-demo/scenarios-log/exit.json`
- Modify: `smoker-it/src/test/java/smoker/it/SelfTestIT.java`

**Interfaces:**
- Consumes: everything above, through the CLI.
- Produces: demo system properties `demo.logPanel` (shows the "Log" panel; off by default so the goldens of the other scenarios do not change) and `demo.equinoxError` (prints `equinox-error.txt` to `System.out` in `main` before the UI); buttons `demo.logTrace`, `demo.logInfoError`, `demo.printTrace`, `demo.exit` (spec 6.8).

- [ ] **Step 1: Demo.** `PilotFormatter` writes `HH:mm:ss.SSS [<current thread name>] <LEVEL padded to 5> <logger> - <msg>` + `\n` + the stack trace of the throwable; SEVERE→ERROR, WARNING→WARN, INFO→INFO, CONFIG/FINE→DEBUG, else TRACE. `DemoApp` uses it for `demo.log`. `demo.logTrace` logs SEVERE `trace demo` with an `IllegalStateException` whose stack trace is set to exactly 40 synthetic elements, as the last statement of the action. `demo.logInfoError`: `LOG.info("Error count 0 of 3")` and `System.out.println("Exception handler ready")`. `demo.printTrace`: `new IllegalStateException("print demo", new java.io.IOException("cause")).printStackTrace()`. `demo.exit`: `LOG.severe("exit on purpose")`, then `System.exit(7)`. `equinox-error.txt`: lines 2–31 of `wp04/spike-crash-app-output.log` (`!SESSION`, header, one `FrameworkEvent ERROR` entry) plus the next `FrameworkEvent ERROR` entry and two `was not resolved.` entries (ASCII only).
- [ ] **Step 2: Scenarios.** `log.json`: s001 CLICK `demo.logTrace`, s002 CLICK `demo.logInfoError`, s003 CLICK `demo.printTrace`; `description` says it needs `-Ddemo.logPanel=true`. `exit.json`: s001 CLICK `demo.exit`.
- [ ] **Step 3: Write the failing IT tests** (each with `run(…, "-Ddemo.logPanel=true")`):
  - `logScenarioGivesOneHitForEachEvent` (R-12a, R-10a): `log.json` run1 → s001 FAIL with exactly 1 hit, source `file:demo.log`, `text` with 40 `\tat` lines, not `late:`; s002 no hit (status NO_GOLDEN); s003 FAIL with exactly 1 hit, source `stderr`.
  - `exitInAStepNamesTheStep` (F-22): `exit.json` → CLI exit 1; `run-result.json` status ERROR, `currentStep` s001, error contains `during step s001`; CLI output contains `exit code 7`; step s001 has a hit with `exit on purpose`.
  - `equinoxErrorAtStartupGivesFail` (F-22): `greet.json` with `-Ddemo.equinoxError=true` → step `startup` FAIL with exactly 4 hits, all `stdout`, 2 with `FrameworkEvent ERROR`; s001 not run.
  - `approveWithoutRunResultGivesExitCode2` (F-24): `cli("approve", "--run", <empty dir>, "--scenario", <greet.json>)` → exit 2.
  - Existing tests stay green: `empty-name` s001 FAIL with the `file:` hit `greet called with empty name`.
- [ ] **Step 4: Run** `mvn -B verify` and `mvn -B verify -Plocale-tr` — all pass. Run `SelfTestIT` 3 times in a row (`mvn -B verify -pl smoker-it` after an install) — 3 × green.
- [ ] **Step 5: README** (demo section): the two properties, `scenarios-log/`, what the IT checks for WP-04. **CLAUDE.md** (SelfTestIT paragraph): the WP-04 checks.
- [ ] **Step 6: Commit** `WP-04, F-08, F-09, F-22, F-24: demo log panel, log and exit scenarios, SelfTestIT checks`.

### Task 7: Close the work package

**Files:**
- Modify: `docs/development-plan.md` (WP-04 state), `CLAUDE.md` (state, test counts), `docs/wp-01-handoff.md` and `docs/wp-01-pilot-report.md` (F-22 note: spike.master on JRE 8 now gives startup FAIL; hang/crash run on JRE 17), `docs/architecture.md` (class map: `LogPattern`, `EventJoiner`, `FailRule`, `ConsoleTee`)
- Create: `docs/wp-04-windows-report.md` (after the Windows check)

- [ ] **Step 1:** Update the four documents. Remove `docs/wp-04-samples/` if Task 2 left it empty.
- [ ] **Step 2:** `mvn -B verify` and `mvn -B verify -Plocale-tr` — all pass. Record the test counts for `CLAUDE.md`.
- [ ] **Step 3:** Commit `WP-04: documents after the work package`, push the branch, open the PR. The Windows CI runs the three builds.
- [ ] **Step 4: Windows check by the owner** (procedure in the PR description):
  1. spike.master on JRE 8 with `crash.json`: `startup` FAIL with 28 `stdout` hits (F-22, unresolved bundles).
  2. spike.master on JRE 17 with `crash.json`: s001 ERROR, error `the application stopped`/`exited during step s001; exit code 7`, hit `crashing on purpose`.
  3. spike.master on JRE 17 with `hang.json`: HANG as before.
  4. p2.product on JRE 8 with `pilot.json` and without `--log`: PASS.
  5. `approve` on an empty folder: exit code 2.
- [ ] **Step 5:** Write `docs/wp-04-windows-report.md` from the results, commit, and report the completion criterion. Merge only on the owner's word.
