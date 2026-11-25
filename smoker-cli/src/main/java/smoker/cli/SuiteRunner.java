package smoker.cli;

import smoker.core.io.ConfigFile;
import smoker.core.io.Json;
import smoker.core.model.JobResult;
import smoker.core.model.RunResult;
import smoker.core.model.Scenario;
import smoker.core.proc.Processes;
import smoker.core.report.HtmlReportWriter;
import smoker.core.report.JUnitXmlWriter;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@code run --suite}: the CLI side of a suite (WP-05, F-03, F-04, R-36, R-37). It runs the commands around the
 * scenarios, starts the application with the launch list of {@link SuitePlan}, watches the run and writes the reports.
 * The cleanup (application, {@code after} commands, background processes) runs one time, also after an exception
 * and on Ctrl+C.
 */
final class SuiteRunner {

    private static final String STOPPED = "not run: the CLI was stopped";
    private static final long POLL_MS = 500;
    private static final long ALIVE_MS = 1000;
    private static final long BACKGROUND_WAIT_MS = 5000;
    private static final long DEFAULT_STOP_TIMEOUT_MS = 10_000;

    private final SuiteFile suite;
    private final Path agentJar;
    private final Path out;
    private final boolean strict;
    private final Long timeoutS;
    private final String label;
    private final long stopTimeoutMs;
    private final List<String> names = new ArrayList<String>();
    private final Map<String, Path> files = new LinkedHashMap<String, Path>();
    private final SuitePlan plan;
    private boolean cleaned;
    private final AtomicInteger cleanups = new AtomicInteger();
    private final List<Process> background = Collections.synchronizedList(new ArrayList<Process>());
    private final List<String> commandErrors = Collections.synchronizedList(new ArrayList<String>());
    /** The results that moved to {@code <name>.shared}, by identity: the verdict can be one of them. */
    private final Map<RunResult, Boolean> shared = new IdentityHashMap<RunResult, Boolean>();
    private volatile Process app;
    /** Guards {@code stopping}, {@code app}, {@code background} and the cleanup: a process is refused or seen by it. */
    private final Object lock = new Object();
    private boolean stopping;
    /** Test seam: runs between two application starts. */
    Runnable betweenLaunches;
    /** Test seam: stops a process and its descendants; true when the descendants stopped too. */
    interface Stopper {
        boolean stopTree(Process p);
    }
    Stopper stopper = new Stopper() {
        @Override
        public boolean stopTree(Process p) {
            return Processes.stopTree(p);
        }
    };

    SuiteRunner(SuiteFile suite, Path agentJar, Path out, boolean strict, Long timeoutS, String label) {
        this.suite = suite;
        this.agentJar = agentJar;
        this.out = out;
        this.strict = strict;
        this.timeoutS = timeoutS;
        this.label = label;
        String st = suite.agentConfig.get("stopTimeoutMs");
        this.stopTimeoutMs = st == null ? DEFAULT_STOP_TIMEOUT_MS : Long.parseLong(st);
        for (Path p : suite.scenarios) {
            String name = Main.stripExt(p.getFileName().toString());
            names.add(name);
            files.put(name, p);
        }
        this.plan = new SuitePlan(names);
    }

    int cleanupCount() {
        return cleanups.get();
    }

    // ---- pure rules ----------------------------------------------------------------------------

    /** Time limit of the scenario in work (design section 6.4). */
    static long limitMs(Scenario sc, boolean first, SuiteFile suite, long stopTimeoutMs, Long timeoutS) {
        long stop = 3 * stopTimeoutMs;
        if (timeoutS != null) return timeoutS * 1000 + stop;
        long ms = Main.budgetMs(sc);
        if (first) return ms + stop;
        ms -= sc.startupTimeoutMs;
        for (SuiteFile.Command c : suite.reset) ms += c.timeoutS * 1000;
        if (suite.parsedReset != null) ms += Main.budgetMs(suite.parsedReset) - suite.parsedReset.startupTimeoutMs;
        return ms + stop;
    }

    static int exitCode(boolean commandFailed, Collection<RunResult> verdicts, boolean strict) {
        if (commandFailed) return 2;
        for (RunResult r : verdicts) {
            if (r.status == RunResult.Status.PASS) continue;
            if (r.status == RunResult.Status.NO_GOLDEN && !strict) continue;
            return 1;
        }
        return 0;
    }

    /** Waits until {@code 127.0.0.1:port} accepts a connection. False at the time limit and when {@code p} ended. */
    static boolean waitForPort(int port, long timeoutMs, Process p) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMs;
        while (true) {
            if (p != null && !p.isAlive()) return false;
            Socket s = new Socket();
            try {
                s.connect(new InetSocketAddress("127.0.0.1", port), 500);
                return true;
            } catch (IOException e) {
                // not open yet
            } finally {
                try {
                    s.close();
                } catch (IOException e) {
                    // ignored
                }
            }
            long left = end - System.currentTimeMillis();
            if (left <= 0) return false;
            Thread.sleep(Math.min(250, left));
        }
    }

    // ---- run -----------------------------------------------------------------------------------

    int run() throws Exception {
        Thread hook = new Thread(new Runnable() {
            @Override
            public void run() {
                cleanup();
            }
        }, "smoker-suite-cleanup");
        Runtime.getRuntime().addShutdownHook(hook);
        try {
            try {
                Files.createDirectories(out);
                for (String n : names) {
                    deleteTree(out.resolve(n));
                    deleteTree(out.resolve(n + ".shared"));
                }
                if (runBefore()) runLaunches();
                else plan.giveUp("not run: a before command failed", "not run: a before command failed");
            } catch (IOException e) {
                failRun(e);
            } catch (RuntimeException e) {
                failRun(e);
            }
            cleanup();
            try {
                return report();
            } catch (IOException e) {
                System.err.println("suite: cannot write the reports: " + e.getMessage());
                return 2;
            }
        } finally {
            cleanup();
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException e) {
                // the JVM is already in shutdown: the hook runs
            }
        }
    }

    /** A failure that the run did not expect (a wrong executable, a locked file): the reports still get written. */
    private void failRun(Exception e) {
        String m = e.getMessage() != null ? e.getMessage() : e.toString();
        fail("run stopped: " + m);
        plan.giveUp("not run: " + m, "failed in a shared JVM; not run again: " + m);
    }

    private void runLaunches() throws Exception {
        while (!plan.done()) {
            if (isStopping()) {
                plan.giveUp(STOPPED, "failed in a shared JVM; not run again: " + STOPPED);
                break;
            }
            if (plan.applicationStarts() > 0 && !runCommands(suite.reset, "reset-" + plan.applicationStarts())) {
                plan.giveUp("not run: reset command failed", "failed in a shared JVM; not run again: reset command failed");
                break;
            }
            List<String> launched = plan.launchList();
            Map<String, RunResult> results = launch(plan.applicationStarts(), launched);
            if (results == null) {
                plan.giveUp(STOPPED, "failed in a shared JVM; not run again: " + STOPPED);
                break;
            }
            for (String n : plan.record(launched, results)) {
                Path dir = out.resolve(n);
                Path moved = out.resolve(n + ".shared");
                deleteTree(moved);
                if (Files.isDirectory(dir)) Files.move(dir, moved);
                shared.put(results.get(n), Boolean.TRUE);
            }
            if (betweenLaunches != null) betweenLaunches.run();
        }
    }

    // ---- commands ------------------------------------------------------------------------------

    private boolean runBefore() throws Exception {
        int n = 0;
        for (SuiteFile.Command c : suite.before) {
            n++;
            String where = "before[" + n + "]";
            Path log = out.resolve("before-" + n + ".log");
            System.out.println("   $ " + c.command);
            if (!c.background) {
                if (isStopping()) return fail(where + ": not started: the CLI was stopped");
                if (!runOne(c, log, where)) return false;
                continue;
            }
            Process p;
            try {
                synchronized (lock) {
                    if (stopping) return fail(where + ": not started: the CLI was stopped");
                    p = Processes.start(Processes.split(c.command), suite.workDir, log);
                    background.add(p);
                }
            } catch (IOException e) {
                return fail(where + ": cannot start: " + e.getMessage());
            }
            if (c.readyPort != null && !waitForPort(c.readyPort, c.readyTimeoutS * 1000, p)) {
                return fail(where + ": port " + c.readyPort + " did not open (the process ended or " + c.readyTimeoutS + " s passed)");
            }
        }
        return true;
    }

    /** Runs the commands in sequence; stops at the first failure. */
    private boolean runCommands(List<SuiteFile.Command> list, String logPrefix) throws Exception {
        int n = 0;
        for (SuiteFile.Command c : list) {
            n++;
            if (isStopping()) return fail(logPrefix + "[" + n + "]: not started: the CLI was stopped");
            System.out.println("   $ " + c.command);
            if (!runOne(c, out.resolve(logPrefix + "-" + n + ".log"), logPrefix + "[" + n + "]")) return false;
        }
        return true;
    }

    private boolean runOne(SuiteFile.Command c, Path log, String where) throws Exception {
        int code;
        try {
            code = Processes.run(Processes.split(c.command), suite.workDir, log, c.timeoutS * 1000);
        } catch (IOException e) {
            return fail(where + ": cannot start: " + e.getMessage());
        }
        if (code == Processes.TIMEOUT) return fail(where + ": no end within " + c.timeoutS + " s");
        if (code != 0) return fail(where + ": exit code " + code + "; see " + log.getFileName());
        return true;
    }

    private boolean fail(String message) {
        System.err.println("suite: " + message);
        commandErrors.add(message);
        return false;
    }

    // ---- one application start -----------------------------------------------------------------

    private Map<String, RunResult> launch(int k, List<String> launched) throws Exception {
        for (String n : launched) deleteTree(out.resolve(n));
        if (isStopping()) return null;
        Path cfgFile = out.resolve("agent-" + k + ".cfg");
        ConfigFile.write(cfgFile, agentConfig(launched));
        Path alive = out.resolve("cli-alive");
        touch(alive);
        System.out.println("== application start " + k + ": " + launched);
        Process p;
        synchronized (lock) {
            if (stopping) return null;
            p = Main.start(suite.app, agentJar, cfgFile, suite.workDir, out.resolve("app-output-" + k + ".log"));
            app = p;
        }
        int exit = watch(p, launched, alive);
        return collect(launched, exit, k);
    }

    LinkedHashMap<String, String> agentConfig(List<String> launched) {
        LinkedHashMap<String, String> c = new LinkedHashMap<String, String>();
        c.put("mode", "play");
        c.put("out", out.toString());
        for (int i = 0; i < launched.size(); i++) c.put("scenario." + (i + 1), files.get(launched.get(i)).toString());
        for (int i = 0; i < suite.logFiles.size(); i++) {
            c.put("log." + (i + 1), suite.workDir.resolve(suite.logFiles.get(i)).normalize().toString());
        }
        c.putAll(suite.agentConfig);
        if (label != null) c.put("label", label);
        for (int i = 0; i < suite.reset.size(); i++) {
            c.put("resetCommand." + (i + 1), suite.reset.get(i).command);
            c.put("resetTimeoutS." + (i + 1), Long.toString(suite.reset.get(i).timeoutS));
        }
        if (suite.resetScenario != null) c.put("resetScenario", suite.resetScenario.toString());
        c.put("aliveFile", out.resolve("cli-alive").toString());
        return c;
    }

    /** Watches the application until it ends or the limit of the scenario in work passes. */
    private int watch(Process p, List<String> launched, Path alive) throws Exception {
        long workStart = System.currentTimeMillis();
        long lastAlive = workStart;
        long lastPoll = 0;
        int inWork = 0;
        while (!p.waitFor(250, TimeUnit.MILLISECONDS)) {
            long now = System.currentTimeMillis();
            if (now - lastAlive >= ALIVE_MS) {
                touch(alive);
                lastAlive = now;
            }
            if (now - lastPoll >= POLL_MS) {
                lastPoll = now;
                int before = inWork;
                while (inWork < launched.size() && isFinal(out.resolve(launched.get(inWork)))) inWork++;
                if (inWork != before) workStart = now;
            }
            if (now - workStart > limitOf(launched, inWork)) {
                Processes.stopTree(p);
                p.waitFor(10, TimeUnit.SECONDS);
                return Processes.TIMEOUT;
            }
        }
        return p.exitValue();
    }

    private long limitOf(List<String> launched, int inWork) {
        if (inWork >= launched.size()) return 3 * stopTimeoutMs; // the run ended: the application stops itself
        Scenario sc = suite.parsed.get(files.get(launched.get(inWork)));
        return limitMs(sc, inWork == 0, suite, stopTimeoutMs, timeoutS);
    }

    private static boolean isFinal(Path runDir) {
        try {
            Path f = runDir.resolve("run-result.json");
            return Files.exists(f) && Json.read(f, RunResult.class).finishedAt != 0;
        } catch (Exception e) {
            return false; // the agent is still writing the file
        }
    }

    /** The raw results. A scenario after the first one without a good result did not run: null. */
    private Map<String, RunResult> collect(List<String> launched, int exit, int k) throws IOException {
        Map<String, RunResult> results = new LinkedHashMap<String, RunResult>();
        boolean stopped = false;
        for (int i = 0; i < launched.size(); i++) {
            String n = launched.get(i);
            Path runDir = out.resolve(n);
            if (stopped && !Files.exists(runDir.resolve("run-result.json"))) {
                results.put(n, null);
                continue;
            }
            long budget = limitOf(launched, i);
            RunResult r = Main.resultOf(runDir, n, files.get(n), exit, budget,
                    "app-output-" + k + ".log");
            results.put(n, r);
            if (r.status != RunResult.Status.PASS && r.status != RunResult.Status.NO_GOLDEN) stopped = true;
        }
        return results;
    }

    // ---- cleanup -------------------------------------------------------------------------------

    /** Stops the application, runs the {@code after} commands, stops the background processes. Runs one time. */
    void cleanup() {
        synchronized (lock) { // a second caller waits until the first one ends
            stopping = true;
            if (cleaned) return;
            cleaned = true;
            cleanupBody();
        }
    }

    private boolean isStopping() {
        synchronized (lock) {
            return stopping;
        }
    }

    private void cleanupBody() {
        cleanups.incrementAndGet();
        Process p = app;
        if (p != null && p.isAlive()) Processes.stopTree(p);
        int n = 0;
        for (SuiteFile.Command c : suite.after) {
            n++;
            System.out.println("   $ " + c.command);
            try {
                runOne(c, out.resolve("after-" + n + ".log"), "after[" + n + "]");
            } catch (Exception e) {
                fail("after[" + n + "]: " + e);
            }
        }
        synchronized (background) {
            // An 'after' command can stop a process, but the wrapper (a .bat) ends some time later: wait for it.
            long deadline = System.currentTimeMillis() + BACKGROUND_WAIT_MS;
            boolean interrupted = false;
            for (Process b : background) {
                try {
                    if (!interrupted) b.waitFor(Math.max(0, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
            for (Process b : background) {
                if (!b.isAlive()) continue;
                if (!stopper.stopTree(b)) {
                    String m = "cannot stop child processes on Java 8: use an 'after' command";
                    System.err.println(m);
                    commandErrors.add(m);
                }
            }
        }
    }

    // ---- reports -------------------------------------------------------------------------------

    private int report() throws IOException {
        List<RunResult> verdicts = new ArrayList<RunResult>();
        List<String> keys = new ArrayList<String>();
        SuiteResult sr = new SuiteResult();
        sr.applicationStarts = plan.applicationStarts();
        for (String n : names) {
            RunResult r = plan.verdict(n);
            String dir = shared.containsKey(r) ? n + ".shared/" : n + "/";
            for (RunResult.StepResult s : r.steps) if (s.screenshot != null) s.screenshot = dir + s.screenshot;
            r.label = label;
            verdicts.add(r);
            keys.add(n);
            sr.scenarios.add(entry(n, r, plan.attempts(n)));
        }
        if (plan.startupFailuresInSequence() >= 3 && plan.applicationStartEntry() != null) {
            RunResult e = plan.applicationStartEntry();
            e.label = label;
            verdicts.add(e);
            keys.add(e.scenarioName);
            sr.scenarios.add(entry(e.scenarioName, e, plan.startupFailuresInSequence()));
        }
        sr.commandErrors.addAll(commandErrors);
        JUnitXmlWriter.write(out.resolve("junit.xml"), verdicts);
        HtmlReportWriter.write(out.resolve("report.html"), verdicts);
        Json.write(out.resolve("suite-result.json"), sr);
        Json.write(out.resolve(JobResult.FILE), JobResult.of(label, keys, verdicts));
        int failures = 0;
        for (RunResult r : verdicts) {
            System.out.println("== " + r.scenarioName);
            Main.printResult(r);
            if (r.note != null) System.out.println("   note: " + r.note);
            if (r.status != RunResult.Status.PASS && !(r.status == RunResult.Status.NO_GOLDEN && !strict)) failures++;
        }
        System.out.println("\napplication starts: " + plan.applicationStarts());
        System.out.println(verdicts.size() + " scenario(s), " + failures + " failed. Report: " + out.resolve("report.html"));
        return exitCode(!commandErrors.isEmpty(), verdicts, strict);
    }

    private static SuiteResult.Entry entry(String name, RunResult r, int attempts) {
        SuiteResult.Entry e = new SuiteResult.Entry();
        e.name = name;
        e.status = r.status;
        e.attempts = attempts;
        e.note = r.note;
        return e;
    }

    // ---- files ---------------------------------------------------------------------------------

    /** Writes the alive file. A short lock (Windows) is no failure: the agent tolerates 10 s without a change. */
    private static void touch(Path f) {
        try {
            Files.write(f, Long.toString(System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // tried again at the next tick
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                Files.delete(f);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
