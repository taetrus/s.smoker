# WP-05 Suite mode and lifecycle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** One application start runs many scenarios (`run --suite`), with `before`/`reset`/`after` commands, a clean three-stage stop, and one agent configuration file.

**Architecture:** The agent runs the launch list that its configuration file gives, writes one `run-result.json` for each scenario, and stops the application at the first FAIL, HANG or ERROR. The CLI reads the result files, applies the retry rule, and starts a new JVM for the scenarios without a verdict. The result files are the only contract between the CLI and the agent.

**Tech Stack:** Java 8 source level (`--release 8`), Swing, JUnit 4, Maven, relocated Gson only.

**Spec:** `docs/superpowers/specs/2026-11-22-wp-05-suite-mode-design.md` (the design). Read it before each task: section numbers below refer to it.

## Global Constraints

- Java 8 source level. No `var`, no `List.of`, no `String.isBlank`, no `Files.readString`. Java 9 API (`ProcessHandle`) only through reflection.
- No new dependency. No bytecode instrumentation.
- Every Swing access goes through the EDT. Never wait on an action `Runnable` directly; wait for a later ping (`Quiescence.edtResponds`).
- All case conversion uses `Locale.ROOT`. `[smoker]` texts are ASCII.
- Windows named `smoker.*` are the tool's own UI: the dialog close and the frame close skip them.
- The WP-04 result contract stays: progress write before each action (`currentStep`), `finishedAt` 0 until the end, the shutdown hook, `Json.writeAtomic`.
- `run --app … --scenarios …` keeps one JVM for each scenario, no reset, no retry (design decision 3).
- The configuration file is UTF-8 `key=value` lines, `#` comment lines, no escape sequences, not `java.util.Properties` (section 9).
- Default `stopTimeoutMs` 10 000. Default command `timeoutS` 60, `readyTimeoutS` 60. The CLI writes `cli-alive` each second; the agent treats a file older than 10 s as a stopped CLI.
- Exact texts from the design: `[smoker] fatal: use config=<file> (see README)`, `[smoker] fatal: unknown key '<key>' in <file>`, `failed in a shared JVM, passed in a new JVM`, `failed in a shared JVM and in a new JVM`, `failed in a shared JVM; the application did not start for the retry`, `not run: the application did not start 3 times`, `not run: reset command failed`, `reset: a dialog stays open: <title>`, `reset scenario: step <id>: <error>`, `reset command timed out; child processes can remain`, `[smoker] the CLI stopped; stopping the application`, `cannot stop child processes on Java 8: use an 'after' command`, `run: --<option> is not permitted with --suite; put it in the suite file`, `application starts: <n>`.
- Documentation in Simplified Technical English. Update `README.md` in the same commit as a format change (CLAUDE.md working rule).
- Commit messages name WP-05 and the finding IDs. Never `git add -A`: the tree has untracked `.DS_Store` files and `.superpowers/`.
- After each change to `smoker-agent` or `smoker-core`: `mvn -B verify` and `mvn -B verify -Plocale-tr` pass.

## Review Focus

1. A Windows path with backslashes, spaces, `;`, `=` and `#` in a configuration value (`C:\tools\new dir;a=b #1`) must reach the agent unchanged: `\t` and `\n` stay two characters. Test in Task 1.
2. A scenario that ends with a modal `JOptionPane` open (a nested event loop on the EDT) must not block the reset step: the dialog close runs on the EDT and the dialog goes away. Test in Task 4.
3. A relative command such as `sim\start-sim.bat` is relative to `workDir`, not to the current directory of the CLI. `ProcessBuilder` resolves the program against the current directory of the parent process, not `directory()`. Test in Task 4.
4. A background `before` process that ends at once (port in use, wrong path) must give a setup failure at once, not after `readyTimeoutS`. Test in Task 7.
5. Two scenario files with the same name in two folders (`a/x.json`, `b/x.json`) share a result folder: the suite check must refuse them with exit code 2. Test in Task 5.

---

### Task 1: Agent configuration file (F-11, R-39)

**Files:**
- Create: `smoker-core/src/main/java/smoker/core/io/ConfigFile.java`
- Create: `smoker-core/src/test/java/smoker/core/io/ConfigFileTest.java`
- Modify: `smoker-agent/src/main/java/smoker/agent/AgentConfig.java`, `SmokerAgent.java`, `Player.java` (constructor and out folder only), `Recorder.java` (use `cfg.scenarios.get(0)`)
- Modify: `smoker-cli/src/main/java/smoker/cli/Main.java` (`agentArgs` replaced, `launch`, the `;` check removed)
- Test: `smoker-agent/src/test/java/smoker/agent/AgentConfigTest.java` (rewrite on `fromMap`), every agent test that calls `AgentConfig.parse`, `smoker-cli/src/test/java/smoker/cli/MainTest.java`, `smoker-it/src/test/java/smoker/it/SelfTestIT.java`
- Docs: `README.md` section "Agent arguments" becomes "Agent configuration file"

**Interfaces:**
- Produces (core): `public final class ConfigFile { public static LinkedHashMap<String,String> read(Path file) throws IOException; public static void write(Path file, Map<String,String> values) throws IOException; }`. `read`: the first `=` ends the key, key and value are trimmed, empty and `#` lines are skipped, a line without `=` gives `IllegalArgumentException("<file>:<line>: no '=' in the line")`, a key that occurs two times gives `IllegalArgumentException`. `write`: a key or value with `\r` or `\n` gives `IllegalArgumentException("<key>: a value cannot contain a line break")`. UTF-8 both ways.
- Produces (agent): `AgentConfig.fromArgs(String args)` (accepts only `config=<path>`; everything after `config=` is the path), `AgentConfig.fromFile(Path)`, `AgentConfig.fromMap(Map<String,String> kv, String source)`. Fields: `List<Path> scenarios` (from `scenario.1..n`), `Path out` (root folder), `List<Path> logFiles` (from `log.1..n`), `List<String> resetCommands`, `List<Long> resetTimeoutS` (default 60 for each command), `Path resetScenario`, `long stopTimeoutMs = 10_000`, `Path aliveFile`, plus every field of today. Numbered keys run from 1 without a gap: `scenario.1, scenario.3` gives `missing key 'scenario.2' in <source>`; `resetTimeoutS.2` without `resetCommand.2` is an unknown key. At least `scenario.1` is required.
- Produces (agent): `Player(AgentConfig cfg, Path scenarioFile, Scenario sc, Map<String,Snapshot> golden, Observations obs, EdtWatchdog watchdog, Path outDir)`. The result file and the screenshots go to `outDir`. The 5-argument constructor stays for the tests and delegates with `cfg.scenarios.isEmpty() ? null : cfg.scenarios.get(0)` and `cfg.out`.
- Produces (cli): `static LinkedHashMap<String,String> agentConfig(Map<String,String> options, List<Path> scenarios, Path out)` and `static int launch(String appCmd, Path agentJar, Path configFile, Path workDir, Path outputLog, long budgetMs)`; the agent option is `-javaagent:<jar>=config=<absolute cfg path>`; `workDir` null keeps the current directory. `run --scenarios` writes `<out>/agent-<k>.cfg` for scenario *k* (1-based) with `mode=play`, `out=<out>`, `scenario.1=<absolute path>` and the pass-through options; `--log a,b` becomes `log.1`, `log.2`. `record` writes a temporary file (`Files.createTempFile("smoker-agent-", ".cfg")`, deleted after the run) with `mode=record`, `scenario.1`.
- Pass-through options of `run`: `screenshots`, `failRegex`, `allowRegex`, `logPattern`, `failLevel`, `logCharset`, `stopTimeoutMs`. Of `record`: `logPattern`, `failLevel`, `logCharset`, and `log`.

- [ ] **Step 1: Write the failing core tests** in `ConfigFileTest`: `readKeepsBackslashesAndSeparators` (write `out=C:\tools\new dir;a=b #1` and `failRegex=\b(ERROR|SEVERE|FATAL)\b`; `read` gives both values unchanged, `get("out").length()` equals the source length), `readSkipsCommentsAndEmptyLines`, `readFirstEqualsEndsTheKey` (`logPattern=%d %msg%n=x` gives key `logPattern`), `readRefusesALineWithoutEquals`, `readRefusesADuplicateKey`, `writeRefusesALineBreak`, `writeThenReadRoundTripsInUtf8` (value `Çağrı`).
- [ ] **Step 2: Run** `mvn -B -pl smoker-core test -Dtest=ConfigFileTest` — expected: compile failure, `ConfigFile` missing.
- [ ] **Step 3: Implement `ConfigFile`.** Read with `Files.readAllLines(file, UTF_8)`; strip a UTF-8 BOM on the first line.
- [ ] **Step 4: Write the failing agent tests** in `AgentConfigTest` (rewrite the existing cases on `fromMap`): `fromArgsRefusesTheOldForm` (`mode=play;scenario=a.json` gives `IllegalArgumentException` with message `use config=<file> (see README)`), `fromArgsTakesAPathWithSemicolonsAndSpaces` (a temporary folder `out;1 a=b`, file `agent.cfg` inside it; `fromArgs("config=" + path)` reads it), `unknownKeyIsRefused` (message `unknown key 'scenaro.1' in test`), `numberedKeysHaveNoGap`, `scenarioOneIsRequired`, `logFilesAreNumbered` (`log.1=a,b.log` gives one path `a,b.log`), `resetCommandsGetTheirTimeouts` (`resetCommand.1`, `resetCommand.2`, `resetTimeoutS.2=5` give `[60, 5]`), `stopTimeoutAndAliveFile`, and the existing default and error cases (`failLevel=LOUD`, `logPattern=%xyz`, `logCharset=no-such-cs`).
- [ ] **Step 5: Run** `mvn -B -pl smoker-core,smoker-agent install -DskipTests -q && mvn -B -pl smoker-agent test -Dtest=AgentConfigTest` — expected: FAIL.
- [ ] **Step 6: Implement** the `AgentConfig` factories, change `SmokerAgent.premain`/`agentmain` to `fromArgs`, make `SmokerAgent.play` compute `outDir = cfg.out.resolve(<file name without extension>)` and use the 7-argument `Player` constructor, and change `Recorder` to `cfg.scenarios.get(0)`. The fatal message stays one line `[smoker] fatal: <message>` and exit code 2. Update all agent tests that call `AgentConfig.parse`.
- [ ] **Step 7: Write the failing CLI tests** in `MainTest`: `agentConfigCarriesTheOptions` (replaces `agentArgsCarryTheLogOptions`: keys `mode`, `out`, `scenario.1`, `logPattern` with a `;` in it), `agentConfigNumbersTheLogFiles` (`--log a.log,b.log` gives `log.1`, `log.2`), `runAcceptsALogPatternWithASemicolon` (no exit code 2 any more).
- [ ] **Step 8: Implement** the CLI changes. Remove `SEMICOLON` and its checks. `launch` sets `pb.directory(workDir)` when it is not null.
- [ ] **Step 9: Add the IT check** `outFolderWithSemicolonAndSpacesWorks` in `SelfTestIT`: run `greet.json` (approved first, as the existing test does, or a copy with its golden) with `--out <work>/out;1 a=b`; expected: status PASS and `<out>/greet/run-result.json` exists.
- [ ] **Step 10: Update `README.md`**: section "Agent configuration file" with the format rules, the key table (all keys of today plus the new keys), and the remark that a hand-made `-javaagent` option now needs `config=<file>`. Remove the sentence that a value cannot contain `;`.
- [ ] **Step 11: Run** `mvn -B verify` and `mvn -B verify -Plocale-tr` — expected: all tests pass.
- [ ] **Step 12: Commit** `WP-05: agent configuration file (F-11, R-39)` with the files above.

---

### Task 2: Three-stage stop of the application (F-10, R-38)

**Files:**
- Create: `smoker-agent/src/main/java/smoker/agent/AppStopper.java`
- Create: `smoker-agent/src/test/java/smoker/agent/AppStopperTest.java`
- Modify: `smoker-agent/src/main/java/smoker/agent/Player.java` (the end of `run()`: no direct `System.exit`)
- Modify: `smoker-demo/src/main/java/smoker/demo/DemoApp.java` (`-Ddemo.lockFile`, `-Ddemo.ignoreClose`)
- Test: `smoker-it/src/test/java/smoker/it/SelfTestIT.java`
- Docs: `README.md` (key `stopTimeoutMs`, a section "Stop of the application"), `docs/architecture.md`

**Interfaces:**
- Produces: `final class AppStopper { interface Exit { void exit(int code); void halt(int code); } AppStopper(long stopTimeoutMs, Exit exit); void stop(int code, boolean edtBlocked); static Object systemBundleOf(ClassLoader loader); static Object findSystemBundle(); static final Exit SYSTEM; }`. `SYSTEM` calls `System.exit` and `Runtime.getRuntime().halt`.
- `stop` sequence (section 8): log `stop: closing the windows`, post `WindowEvent.WINDOW_CLOSING` to each showing `Frame` that `Player.isMainWindow` accepts, on the EDT, unless `edtBlocked`; sleep `stopTimeoutMs`. Log `stop: stopping the OSGi framework`, call `stop()` on `findSystemBundle()` when it is not null, sleep `stopTimeoutMs`; when it is null, log `stop: no OSGi framework` and go on at once. Start a daemon thread `smoker-halt` that sleeps `stopTimeoutMs` and then logs `stop: halt(<code>)` and calls `exit.halt(code)`. Log `stop: System.exit(<code>)` and call `exit.exit(code)`. Each log line goes through `SmokerAgent.log` (prefix `[smoker] `).
- `systemBundleOf(loader)`: when `loader` has a public method `getBundle()` (no parameters), return `getBundle().getBundleContext().getBundle(0L)` by reflection; return null on any failure. `findSystemBundle()`: try `systemBundleOf` on the class loader of each showing window and of its components (on the EDT, with a time limit of 1 s), then `org.eclipse.core.runtime.adaptor.EclipseStarter.getSystemBundleContext().getBundle(0L)` through the system class loader and through the context class loader of each live thread. Use method names only; no OSGi type at compile time.
- Player: the end of `run()` computes the code (0 for PASS or NO_GOLDEN, else 1) and calls `new AppStopper(cfg.stopTimeoutMs, AppStopper.SYSTEM).stop(code, result.status == HANG)` when `cfg.exitWhenDone`.
- Demo: `-Ddemo.lockFile=<file>` creates the file at start and deletes it in a `windowClosing` listener of the main frame. `-Ddemo.ignoreClose=true` sets `DO_NOTHING_ON_CLOSE` and the listener deletes nothing. Do not use `deleteOnExit`.

- [ ] **Step 1: Write the failing tests** in `AppStopperTest` with a recording `Exit` and `stopTimeoutMs` 200: `stopClosesTheFramesFirst` (a test frame with a `windowClosing` listener gets the event before `exit` is called), `stopSkipsTheCloseWhenTheEdtIsBlocked`, `stopCallsExitWithTheCode` (code 1), `haltFollowsWhenExitDoesNotReturn` (the `exit` of the fake blocks on a latch; `halt(1)` is called within 1 s), `systemBundleOfUsesGetBundle` (a test class `FakeBundleLoader extends ClassLoader` with `public Object getBundle()` that returns a fake bundle whose `getBundleContext().getBundle(0L)` returns an object with a `stop()` that sets a flag), `systemBundleOfGivesNullForAPlainLoader`. Dispose every test frame in `@After` (test isolation rule).
- [ ] **Step 2: Run** `mvn -B -pl smoker-agent test -Dtest=AppStopperTest` — expected: compile failure.
- [ ] **Step 3: Implement `AppStopper`** and the change in `Player`.
- [ ] **Step 4: Run** the test — expected: PASS.
- [ ] **Step 5: Implement the demo options.**
- [ ] **Step 6: Add the IT checks** in `SelfTestIT`: `cleanStopRemovesTheLockFile` (run `greet.json` with `-Ddemo.lockFile=<work>/lock`; expected: the lock file does not exist after the run, and `app-output.log` contains `[smoker] stop: closing the windows` and not `stop: System.exit`); `blockedCloseEndsWithExit` (with `-Ddemo.ignoreClose=true` and `--stopTimeoutMs 1000`; expected: the CLI ends, the result is complete with `finishedAt` > 0, `app-output.log` contains `[smoker] stop: System.exit(`, and the lock file still exists).
- [ ] **Step 7: Update `README.md`** and `docs/architecture.md` (the stop stages and the effect on the exit code, section 8 last paragraph).
- [ ] **Step 8: Run** `mvn -B verify` and `mvn -B verify -Plocale-tr` — expected: all pass.
- [ ] **Step 9: Commit** `WP-05: stop the application in three stages (F-10, R-38)`.

---

### Task 3: Many scenarios in one JVM, agent side (F-03, R-36)

**Files:**
- Create: `smoker-agent/src/main/java/smoker/agent/Session.java`
- Create: `smoker-agent/src/test/java/smoker/agent/SessionTest.java`
- Modify: `smoker-agent/src/main/java/smoker/agent/Player.java`, `SmokerAgent.java`
- Modify: `smoker-core/src/main/java/smoker/core/model/RunResult.java` (fields `sharedJvm`, `note`)
- Test: `PlayerTest`, `PlayerExitTest` (adapt to the new entry points)

**Interfaces:**
- Consumes: `AgentConfig.scenarios`, `cfg.aliveFile`, `AppStopper` (Task 2), the 7-argument `Player` constructor (Task 1).
- Produces: `RunResult.sharedJvm` (boolean, default false) and `RunResult.note` (String, null). Gson reads old files without them.
- Produces: `final class Session implements Runnable { Session(AgentConfig cfg, Observations obs, EdtWatchdog watchdog, AppStopper.Exit exit); static boolean cliAlive(Path aliveFile, long nowMs); }`. `cliAlive`: true when `aliveFile` is null; false when the file is missing or its last-modified time is more than 10 000 ms before `nowMs`.
- Produces: Player entry points `enum First { STARTUP, RESET }`; `void begin(First first)` (sets the result header and writes progress with `currentStep` `startup` or `reset`); `RunResult.Status play(First first, long lastEnd)` (runs the first step, then the steps; writes the final result; returns the status; does not register a hook and does not stop the JVM); `long lastEnd()`; `void onExit()` (unchanged behaviour). The constructor takes `boolean sharedJvm` as an 8th argument; the 7-argument form gives false. Task 3 implements `First.RESET` as a step with id `reset`, name `reset between scenarios`, that only takes the log hits (Task 4 fills it).
- `Session.run()` (section 6.2): register one shutdown hook `smoker-exit` that calls `onExit()` of the current player (a `volatile Player current`). For scenario *i* (0-based): when *i* > 0 and `!cliAlive(cfg.aliveFile, now)`, log `the CLI stopped; stopping the application` and leave the loop. Read the scenario and its golden; a read failure writes a result with status ERROR and the error text into its folder and leaves the loop. Apply `Locators.useWindowKeys` and `AutoNames.enable` of that scenario. Create the player with `sharedJvm = i > 0`, call `begin(i == 0 ? STARTUP : RESET)`, set `current`, call `play`. Carry `lastEnd` to the next player. Leave the loop when the status is FAIL, HANG or ERROR. After the loop: when `cfg.exitWhenDone`, `new AppStopper(cfg.stopTimeoutMs, exit).stop(code, lastStatus == HANG)`; `code` is 0 when each scenario that ran is PASS or NO_GOLDEN, else 1. Then `obs.stop()` and `watchdog.shutdown()`.
- `SmokerAgent.play` starts a `Session` instead of a `Player`.

- [ ] **Step 1: Write the failing tests** in `SessionTest` (an in-process test frame with a named `JTextField`; scenarios with one `SET_TEXT` step written to a temporary folder; `exitWhenDone` false, or a recording `Exit`): `twoScenariosGiveTwoResultFiles` (both PASS or NO_GOLDEN; the second has `sharedJvm` true and its first step id `reset`; the first has `startup`), `failStopsTheList` (scenario 2 has a golden that does not match: scenario 3 has no result folder), `logWindowsFollowEachOther` (a `System.err` line printed between the scenarios belongs to the `reset` step of scenario 2, not to scenario 1), `stoppedCliStopsBeforeTheNextScenario` (an `aliveFile` with last-modified 20 s ago: only scenario 1 runs), `cliAliveRules` (null file, missing file, fresh file, old file), `settingsChangeForEachScenario` (scenario 2 has `autoNames` false; after its start `AutoNames.enabled()` is false).
- [ ] **Step 2: Run** `mvn -B -pl smoker-agent test -Dtest=SessionTest` — expected: compile failure.
- [ ] **Step 3: Implement** `Session`, the `RunResult` fields and the `Player` entry points. Move the hook and the stop out of `Player`. Keep the `startup` behaviour of today unchanged for `First.STARTUP`.
- [ ] **Step 4: Adapt** `PlayerTest` and `PlayerExitTest` to `begin`/`play`. The behaviour that they check stays.
- [ ] **Step 5: Run** `mvn -B verify` and `mvn -B verify -Plocale-tr` — expected: all pass (the CLI still gives one scenario to each JVM, so the IT behaviour is the same).
- [ ] **Step 6: Commit** `WP-05: the agent runs many scenarios in one JVM (F-03, R-36)`.

---

### Task 4: The reset step (F-04, R-37)

**Files:**
- Create: `smoker-core/src/main/java/smoker/core/proc/Processes.java`
- Create: `smoker-core/src/test/java/smoker/core/proc/ProcessesTest.java`, `smoker-core/src/test/java/smoker/core/proc/FakeCommand.java`
- Create: `smoker-agent/src/main/java/smoker/agent/DialogCloser.java`
- Create: `smoker-agent/src/test/java/smoker/agent/DialogCloserTest.java`, `smoker-agent/src/test/java/smoker/agent/ResetStepTest.java`
- Modify: `smoker-agent/src/main/java/smoker/agent/Player.java` (the `RESET` first step), `Session.java` (pass the reset scenario)
- Modify: `smoker-cli/src/main/java/smoker/cli/Main.java` (`splitCommand` moves to `Processes.split`)

**Interfaces:**
- Produces (core): `public final class Processes { public static final int TIMEOUT = -999; public static List<String> split(String commandLine); public static Process start(List<String> cmd, Path workDir, Path log) throws IOException; public static int run(List<String> cmd, Path workDir, Path log, long timeoutMs) throws IOException, InterruptedException; public static boolean stopTree(Process p); }`. `split`: the rules of `Main.splitCommand` today. `start`: output and errors appended to `log`; when the first word is a relative path and `workDir.resolve(word)` is a file, use its absolute path (Review Focus 3); `workDir` null keeps the current directory. `run`: `start`, then wait; on timeout `stopTree` and return `TIMEOUT`. `stopTree`: on Java 9 or later, call `destroyForcibly()` on each element of `ProcessHandle.descendants()` through reflection, then on the process; return true. On Java 8, destroy the process only and return false.
- Produces (agent): `final class DialogCloser { static String closeAll(int maxRounds); }` — on the EDT: `MenuSelectionManager.defaultManager().clearSelectedPath()`, then `dispose()` each showing `java.awt.Dialog` whose name does not start with `smoker.`; repeat until none shows, at most `maxRounds` (10). Returns null, or the title of a dialog that still shows. Each EDT call has a time limit (`stepTimeoutMs` of the next scenario); a timeout returns `"(the EDT does not answer)"`.
- The `reset` step in `Player.play(RESET, …)` (section 7), with `currentStep` `reset` for its whole time:
  1. `DialogCloser.closeAll(10)`; a non-null title gives ERROR `reset: a dialog stays open: <title>`.
  2. Each `cfg.resetCommands[k]` with `Processes.run(Processes.split(cmd), null, outDir.resolve("reset-command.log"), cfg.resetTimeoutS[k] * 1000)`; exit code not 0 gives ERROR `reset command <k> failed: exit code <n>`; `TIMEOUT` gives ERROR `reset command <k> timed out` and, when `stopTree` returned false, the text `reset command timed out; child processes can remain`.
  3. When `cfg.resetScenario` is not null: apply its `windowKeys` and `autoNames`, run each of its steps with the existing step code without a snapshot and with progress id `reset`; a step that is not PASS gives the reset step its status and the error `reset scenario: step <id>: <error>`. Then apply the settings of the next scenario again.
  4. `Quiescence.await`, drain, take the hits (window `(lastEnd, now]`); a hit gives FAIL.
- Session passes the reset scenario (read one time at session start; its golden is not read) to each player.

- [ ] **Step 1: Write the failing core tests** in `ProcessesTest` with `FakeCommand` (a `main` that can exit with a code, sleep, print, or start a child copy of itself that writes its PID to a file and sleeps): `runGivesTheExitCode` (3), `runAppendsTheOutputToTheLog`, `runStopsATimedOutCommand` (`TIMEOUT` within timeout + 2 s), `stopTreeStopsTheChildToo` (Java 9+ only, `Assume`: the child PID is not alive after `stopTree`), `relativeProgramIsResolvedAgainstWorkDir` (a script `bin/hello` (`.bat` on Windows, `.sh` with the executable bit elsewhere) in a temporary `workDir`; the command `bin/hello` runs it while the current directory of the test JVM is elsewhere), `splitKeepsQuotedSpaces`.
- [ ] **Step 2: Run** `mvn -B -pl smoker-core test -Dtest=ProcessesTest` — expected: compile failure.
- [ ] **Step 3: Implement `Processes`**, move `Main.splitCommand` to it (keep `MainTest` cases, now on `Processes.split`).
- [ ] **Step 4: Write the failing agent tests**: `DialogCloserTest.closesAModalOptionPane` (open `JOptionPane.showMessageDialog` through `invokeLater`; `closeAll(10)` returns null and no dialog shows; Review Focus 2), `DialogCloserTest.skipsSmokerWindows` (a dialog named `smoker.x` stays), `ResetStepTest.resetCommandFailureGivesError` (`FakeCommand` exit 3: the reset step is ERROR, the next scenario does not run its steps, the list stops), `ResetStepTest.resetScenarioRunsWithoutSnapshot` (a reset scenario that clears the text field; the next scenario passes; the reset step has no `actual`), `ResetStepTest.resetScenarioStepFailureNamesTheStep` (a locator that does not exist: error starts with `reset scenario: step s001:`), `ResetStepTest.logHitDuringResetFails`.
- [ ] **Step 5: Run** `mvn -B -pl smoker-core install -DskipTests -q && mvn -B -pl smoker-agent test -Dtest='DialogCloserTest,ResetStepTest'` — expected: FAIL.
- [ ] **Step 6: Implement** `DialogCloser`, the reset step and the Session change.
- [ ] **Step 7: Run** `mvn -B verify` and `mvn -B verify -Plocale-tr` — expected: all pass.
- [ ] **Step 8: Commit** `WP-05: reset step between scenarios (F-04, R-37)`.

---

### Task 5: The suite file (F-03, F-04)

**Files:**
- Create: `smoker-cli/src/main/java/smoker/cli/SuiteFile.java`
- Create: `smoker-cli/src/test/java/smoker/cli/SuiteFileTest.java`

**Interfaces:**
- Consumes: `Main.validated(Path)`, `Main.scenarios(String)` folder rules, `ScenarioFiles.isGoldenFile`.
- Produces: `final class SuiteFile { String app; Path workDir; LinkedHashMap<String,String> agentConfig; List<String> logFiles; List<Command> before, reset, after; Path resetScenario; List<Path> scenarios; Map<Path,Scenario> parsed; Scenario parsedReset; static SuiteFile read(Path file) throws IOException; static final class Command { String command; long timeoutS = 60; boolean background; Integer readyPort; long readyTimeoutS = 60; } }`.
- `read` rules (section 5): read the tree with `Json.readTree` and check each field name. Errors are `IllegalArgumentException` with a text that starts with `suite: `. Paths in `scenarios`, `resetScenario`, `workDir` are relative to the folder of the suite file; `workDir` default is the current directory of the CLI; `agentConfig.log` (string or array of strings) is relative to `workDir` and goes to `logFiles`, not to `agentConfig`. Allowed `agentConfig` keys: `logPattern`, `failLevel`, `failRegex`, `allowRegex`, `logCharset`, `screenshots`, `edtHangMs`, `stopTimeoutMs`; a number value becomes its decimal text. A folder in `scenarios` gives its `*.json` files in name sequence without golden files. Each scenario and the reset scenario are read with `Main.validated` (the reset scenario with `ScenarioFiles.readScenario` only).

- [ ] **Step 1: Write the failing tests** in `SuiteFileTest`: `readsTheExampleOfTheDesign` (the JSON of section 5 with the files created in a temporary folder; check each field and that `scenarios` of a folder are sorted), `unknownFieldIsRefused` (`"aftr": []` gives `suite: unknown field 'aftr'`), `unknownAgentKeyIsRefused`, `unknownCommandFieldIsRefused`, `logAsStringOrArray` (a path with `,` stays one file), `backgroundOnlyInBefore`, `readyPortOnlyWithBackground`, `appAndScenariosAreRequired`, `sameScenarioNameInTwoFoldersIsRefused` (`a/x.json`, `b/x.json`: `suite: two scenarios have the name 'x'`; Review Focus 5), `missingScenarioFileIsRefused`, `wrongScenarioFileIsRefused` (old format: the message of `Main.validated`).
- [ ] **Step 2: Run** `mvn -B -pl smoker-cli test -Dtest=SuiteFileTest` — expected: compile failure.
- [ ] **Step 3: Implement `SuiteFile`.**
- [ ] **Step 4: Run** the test — expected: PASS.
- [ ] **Step 5: Commit** `WP-05: suite file (F-03, F-04)`.

---

### Task 6: Verdicts and the retry rule (F-03)

**Files:**
- Create: `smoker-cli/src/main/java/smoker/cli/SuitePlan.java`
- Create: `smoker-cli/src/test/java/smoker/cli/SuitePlanTest.java`
- Modify: `smoker-core/src/main/java/smoker/core/report/HtmlReportWriter.java`, `JUnitXmlWriter.java` (show `note`)
- Modify: `smoker-cli/src/main/java/smoker/cli/Main.java` (`approve` warning)
- Test: `HtmlReportWriterTest`, `JUnitXmlWriterTest`, `MainTest`

**Interfaces:**
- Consumes: `RunResult.sharedJvm`, `RunResult.note` (Task 3).
- Produces: `final class SuitePlan { SuitePlan(List<String> names); List<String> launchList(); List<String> record(List<String> launched, Map<String,RunResult> results); void giveUp(String error, String retryNote); boolean done(); int applicationStarts(); int startupFailuresInSequence(); RunResult verdict(String name); int attempts(String name); RunResult applicationStartEntry(); }`.
- `launchList()`: the scenario that waits for its retry first, then the names without a verdict in suite sequence. Each call counts one application start.
- `record(launched, results)` (section 6.3): `results` has an entry for each launched name with a result file (the CLI makes it with `Main.resultOf`); a missing entry means "not run". The first launched name had a startup failure when its entry is missing, or its `startup` step is not PASS, or its `currentStep` is `startup` (the agent sets `currentStep` to null in its final write, and `Main.resultOf` does not change it, so a non-null `startup` means that the JVM stopped during the startup). A startup failure is not a verdict; it increments `startupFailuresInSequence` and keeps the result for `applicationStartEntry()` (a `RunResult` named `(application start)` with the `startup` step); a passing startup sets the count to 0. At 3, call `giveUp("not run: the application did not start 3 times", "failed in a shared JVM; the application did not start for the retry")`. A first launched name otherwise gets its result as verdict; when it was the retry, the note is `failed in a shared JVM, passed in a new JVM` (PASS or NO_GOLDEN) or `failed in a shared JVM and in a new JVM`. A later name: PASS and NO_GOLDEN are verdicts; FAIL, HANG or ERROR makes it the scenario that waits for its retry, and `record` returns its name in the list of folders to move to `<name>.shared`.
- `giveUp(error, retryNote)`: each name without a verdict gets a `RunResult` with status ERROR and `error`; the name that waits for its retry keeps its shared result as verdict with `retryNote`. `done()` is true.
- `attempts(name)`: number of launches in which the name had a result.
- Reports: `HtmlReportWriter` writes `<p class='note'>` with the escaped note under the scenario heading and in the summary row; `JUnitXmlWriter` writes the note as `<system-out>` of the test case. `approve` prints `approve: warning: the run used a shared JVM; the golden holds the state after other scenarios` on `System.err` when `sharedJvm` is true, and continues.

- [ ] **Step 1: Write the failing tests** in `SuitePlanTest` (build `RunResult` objects in the test): `allPassInOneLaunch` (1 start, `done`), `sharedFailureIsRetriedFirst` (launch 1: a PASS, b FAIL shared; `record` returns `[b]`; `launchList()` is `[b, c]`), `retryThatPassesGetsTheNote`, `retryThatFailsGetsTheSecondNote` (verdict status FAIL), `firstScenarioFailureIsAVerdict` (no retry), `notRunScenarioStaysWithoutVerdict`, `startupFailureIsNotAVerdict` (the name stays first of the next list), `threeStartupFailuresGiveUp` (each name without verdict: ERROR `not run: the application did not start 3 times`; the retry name keeps its shared result with the third note; `applicationStartEntry()` has the last `startup` step), `passingStartupResetsTheCount`, `giveUpWithResetFailure`, `attemptsCount`.
- [ ] **Step 2: Run** `mvn -B -pl smoker-cli test -Dtest=SuitePlanTest` — expected: compile failure.
- [ ] **Step 3: Implement `SuitePlan`.**
- [ ] **Step 4: Write the failing report and approve tests**: `HtmlReportWriterTest.noteShowsUnderTheScenario` (a note with `<` is escaped), `JUnitXmlWriterTest.noteGoesToSystemOut` (valid XML), `MainTest.approveWarnsOnASharedJvmResult` (exit code 0, the warning on `System.err`).
- [ ] **Step 5: Implement** the report and approve changes.
- [ ] **Step 6: Run** `mvn -B verify` and `mvn -B verify -Plocale-tr` — expected: all pass.
- [ ] **Step 7: Commit** `WP-05: verdicts and the retry rule of a suite (F-03)`.

---

### Task 7: `run --suite` in the CLI (F-03, F-04, R-36, R-37)

**Files:**
- Create: `smoker-cli/src/main/java/smoker/cli/SuiteRunner.java`, `smoker-cli/src/main/java/smoker/cli/SuiteResult.java`
- Create: `smoker-cli/src/test/java/smoker/cli/SuiteRunnerTest.java`
- Modify: `smoker-cli/src/main/java/smoker/cli/Main.java` (`run` dispatch, usage text)

**Interfaces:**
- Consumes: `SuiteFile` (Task 5), `SuitePlan` (Task 6), `Processes` (Task 4), `ConfigFile` (Task 1), `Main.resultOf`, `Main.budgetMs`, `Main.launch` (Task 1 form).
- Produces: `final class SuiteRunner { SuiteRunner(SuiteFile suite, Path agentJar, Path out, boolean strict, Long timeoutS); int run() throws Exception; static long limitMs(Scenario sc, boolean first, SuiteFile suite, long stopTimeoutMs, Long timeoutS); static int exitCode(boolean commandFailed, Collection<RunResult> verdicts, boolean strict); static boolean waitForPort(int port, long timeoutMs, Process p) throws InterruptedException; }`.
- Produces: `final class SuiteResult { int applicationStarts; List<Entry> scenarios; List<String> commandErrors; static final class Entry { String name; RunResult.Status status; int attempts; String note; } }`, written to `<out>/suite-result.json` with `Json.write`.
- `limitMs` (section 6.4): first: `budgetMs(sc) + 3 * stopTimeoutMs`. Not first: `budgetMs(sc) - sc.startupTimeoutMs + sum(reset timeoutS) * 1000 + (parsedReset == null ? 0 : budgetMs(parsedReset) - parsedReset.startupTimeoutMs) + 3 * stopTimeoutMs`. With `timeoutS`: `timeoutS * 1000 + 3 * stopTimeoutMs`.
- `exitCode`: 2 when `commandFailed`; else 1 when a verdict is not PASS (NO_GOLDEN counts as PASS without `strict`); else 0.
- `waitForPort`: try `new Socket()` connect to `127.0.0.1:<port>` with 500 ms each 250 ms; return false at once when `p` is no longer alive (Review Focus 4), false at the time limit.
- `run()` (section 6.1): delete `<out>/<name>` and `<out>/<name>.shared` for each scenario; run `before` (output of command *n* to `<out>/before-<n>.log`; background processes kept in a list); a setup failure skips the scenarios. Loop while `!plan.done()`: `launchList()`; run the CLI `reset` commands before each launch after the first; a failed one calls `plan.giveUp("not run: reset command failed", "failed in a shared JVM; not run again: reset command failed")`. Write `<out>/agent-<k>.cfg` (`mode=play`, `out`, `scenario.n`, `log.n`, the `agentConfig` keys, `resetCommand.n`, `resetTimeoutS.n`, `resetScenario`, `aliveFile=<out>/cli-alive`), write `cli-alive`, start the application with `Main.launch`-style code in `workDir` (asynchronous: keep the `Process`), and while it runs: rewrite `cli-alive` each second, poll the result files two times each second, track the scenario in work and its start time, and stop the process tree with `Processes.stopTree` when its `limitMs` passes (exit code `Processes.TIMEOUT`). After the end: read the raw results, make them with `Main.resultOf`, `plan.record`, move the returned folders to `<name>.shared`. Then run `after` (each command with its time limit; a failure adds to `commandErrors` and the other commands run), stop each background process with `stopTree` (false: print `cannot stop child processes on Java 8: use an 'after' command` and add it to `commandErrors`). Write the reports with the verdicts in suite sequence plus `applicationStartEntry()` when it is not null; screenshot paths get the prefix `<name>/` or `<name>.shared/`. Print each verdict as `run` does today, the note, and `application starts: <n>`. Return `exitCode`.
- A CLI shutdown hook does the cleanup (stop the application process tree, `after`, background processes) when `run()` did not do it; an `AtomicBoolean` makes the cleanup run one time.
- `Main.run`: with `--suite`, permit only `--agent`, `--out`, `--strict`, `--timeout`; another option prints `run: --<option> is not permitted with --suite; put it in the suite file` and returns 2. A `SuiteFile.read` error prints the message and returns 2.

- [ ] **Step 1: Write the failing tests** in `SuiteRunnerTest`: `limitOfFirstAndLaterScenario` (with a reset command of 30 s and a reset scenario; exact numbers from the formula), `limitWithTimeoutOption`, `exitCodeRules` (command failure 2 also with all PASS; FAIL 1; NO_GOLDEN 0 and with `strict` 1), `waitForPortSeesAListener` (a `ServerSocket` in the test), `waitForPortStopsWhenTheProcessEnds` (a `FakeCommand`-style process that exits at once; returns false within 2 s although the limit is 60 s), `suiteOptionRefusesOtherOptions` (`run --suite s.json --log a.log` gives 2 and the message), `beforeFailureSkipsTheScenariosAndRunsAfter` (a `before` command that exits 1, an `after` command that writes a marker file; `run()` returns 2, no `agent-1.cfg` exists, the marker exists).
- [ ] **Step 2: Run** `mvn -B -pl smoker-cli test -Dtest=SuiteRunnerTest` — expected: compile failure.
- [ ] **Step 3: Implement** `SuiteRunner`, `SuiteResult` and the `Main` change. Update the usage text and the class comment of `Main`.
- [ ] **Step 4: Run** `mvn -B verify` and `mvn -B verify -Plocale-tr` — expected: all pass.
- [ ] **Step 5: Commit** `WP-05: run --suite (F-03, F-04, R-36, R-37)`.

---

### Task 8: Demo simulator, suite checks and documentation (R-36, R-37)

**Files:**
- Create: `smoker-demo/src/main/java/smoker/demo/DemoSimulator.java`
- Create: `smoker-demo/sim/start-sim.bat`, `smoker-demo/sim/start-sim.sh` (start the simulator with `java -cp <demo jar> smoker.demo.DemoSimulator <port>`; the jar path and the port are arguments)
- Create: `smoker-demo/suite/` (scenario files of the checks below, a reset scenario `reset.json`, and a `suite.json` example that the README shows)
- Modify: `smoker-demo/src/main/java/smoker/demo/DemoApp.java` (`-Ddemo.simPort`)
- Test: `smoker-it/src/test/java/smoker/it/SelfTestIT.java`
- Docs: `README.md` (section "Suite file" and an example in "Use"), `docs/architecture.md` (session, reset step, suite runner, retry), `CLAUDE.md` (SelfTestIT expectations, state, test counts)

**Interfaces:**
- Produces: `DemoSimulator.main(String[] args)`: `<port>` listens on `127.0.0.1:<port>` and answers lines: `PING` → `PONG`, `INC` → the counter after +1, `GET` → the counter, `RESET` → sets the counter to 0 and answers `OK`. `--send <port> <command>` connects, sends one line, prints the answer, exits 0 (the IT uses it as the `reset` command).
- Produces: with `-Ddemo.simPort=<port>`, the demo has a button `demo.sim.count` that sends `INC` and a label `demo.sim.value` that shows the answer.
- The IT writes the suite files at run time (paths of the work folder, a free port from `new ServerSocket(0)`, `.bat` on Windows and `sh <script>` elsewhere).

- [ ] **Step 1: Implement** `DemoSimulator`, the scripts and the demo option.
- [ ] **Step 2: Add the IT checks** (each asserts the CLI exit code and reads `suite-result.json`):
  1. `suiteOfTwentyUsesOneStart` (R-36): 20 copies of a short scenario that ends where it started, and a reset scenario. Run 1 gives NO_GOLDEN for each; `approve` each; run 2 gives PASS for each, `applicationStarts` 1, exit code 0, output contains `application starts: 1`.
  2. `sharedFailureIsRetriedInANewJvm`: scenario `a` types a name and does not clear it; scenario `b` has a golden approved in a run of `b` alone (empty name). Suite `[a, b]` without a reset scenario: `b` verdict PASS, note `failed in a shared JVM, passed in a new JVM`, `attempts` 2, `applicationStarts` 2, folder `b.shared` exists.
  3. `simulatorRunsBeforeResetAndAfter` (R-37): `before` starts the simulator in the background with `readyPort`; scenarios `[sim-a, sim-b, bad]` with `-Ddemo.simPort`; `reset` command `DemoSimulator --send <port> RESET`; `after` writes a marker file. `sim-a` and `sim-b` click `demo.sim.count` and expect `1` (`sim-b` passes in the shared JVM only because the reset command operates); `bad` has a changed golden. Expected: `sim-a` and `sim-b` PASS, `bad` FAIL with note `failed in a shared JVM and in a new JVM`, exit code 1, the marker file exists, and a connection to the port fails after the CLI ended.
  4. `failingBeforeGivesExitCode2`: `before` with a command that exits 1: exit code 2, no `agent-1.cfg`, `after` marker exists.
- [ ] **Step 3: Run** `mvn -B verify` and `mvn -B verify -Plocale-tr` — expected: all pass.
- [ ] **Step 4: Update the documentation.** README: the suite file (fields, command object, rules, options of `run --suite`, exit codes, the retry rule and its notes, `cli-alive` and the hard stop, the Java 8 limit of `stopTree`, that a fixed `-javaagent` line in `eclipse.ini` does not operate with `run`). `docs/architecture.md`: the class map with `Session`, `AppStopper`, `DialogCloser`, `Processes`, `ConfigFile`, `SuiteFile`, `SuitePlan`, `SuiteRunner`. `CLAUDE.md`: the WP-05 SelfTestIT expectations and the new test counts (from the test output).
- [ ] **Step 5: Commit** `WP-05: demo simulator and suite checks (R-36, R-37)`.

---

### Task 9: Windows runbook for the pilot check

**Files:**
- Create: `docs/wp-05-windows-check.md`, `docs/wp-05-windows-check.ps1`

**Interfaces:**
- Consumes: `docs/wp-04-windows-check.md` and `.ps1` (copy their form: numbered steps for Claude Code on the Windows PC, outputs in `C:\smoke\out\wp05-<run>.txt` and a summary file), `docs/wp-01-handoff.md` section 4.2 (the `p2.product` command).

- [ ] **Step 1: Write the runbook** in full Simplified Technical English (`asd-ste100` skill). Steps:
  1. Build the branch and copy the two jars to `C:\tools`.
  2. Record a reset scenario on `p2.product` that brings the application back to the start state of `pilot.json`; save it as `C:\smoke\suite\reset.json`.
  3. R-36: make 20 copies of `pilot.json` (with its golden) in `C:\smoke\suite\scenarios`, write `suite.json` with the `p2.product` command, `resetScenario` and `out` `C:\smoke\out\wp05 suite;1`; run `run --suite`; expected: 20 PASS, `application starts: 1` (this also proves R-39: the `out` path has `;` and a space).
  4. R-38: run `run --scenarios` on the 20 copies (20 JVMs, 20 stops); collect the `[smoker] stop:` lines of each `app-output.log`; list the lock files in `configuration\` before and after; expected: each stop ends at stage 1 or 2 (no `stop: System.exit` and no `stop: halt` line), and the next start has no startup FAIL.
  5. R-37 is covered by the Windows CI (SelfTestIT `simulatorRunsBeforeResetAndAfter`); the runbook states this and asks for the CI link.
  6. Post the summary as a PR comment.
- [ ] **Step 2: Write the script** that does steps 3 and 4 and writes the output and summary files. The script stops with a message when a precondition is missing (jars, `pilot.json`, `reset.json`).
- [ ] **Step 3: Check** the script syntax on macOS with `pwsh -NoProfile -Command "[System.Management.Automation.Language.Parser]::ParseFile('docs/wp-05-windows-check.ps1',[ref]$null,[ref]$null)"` when `pwsh` exists; else state in the report that the syntax is not checked.
- [ ] **Step 4: Commit** `WP-05: Windows runbook for the pilot check (R-36, R-38, R-39)`.
