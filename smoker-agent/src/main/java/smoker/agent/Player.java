package smoker.agent;

import smoker.core.diff.SnapshotDiff;
import smoker.core.io.Json;
import smoker.core.model.Action;
import smoker.core.model.LintEntry;
import smoker.core.model.RunResult;
import smoker.core.model.Scenario;
import smoker.core.model.Snapshot;
import smoker.core.model.Step;

import javax.swing.SwingUtilities;
import java.awt.Frame;
import java.awt.Window;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Replays a scenario inside the app's JVM (R-30, R-35) and writes {@code run-result.json} into the out dir.
 * Runs on its own daemon thread; every UI touch goes through the EDT.
 */
final class Player implements Runnable {

    private final AgentConfig cfg;
    private final Path scenarioFile;
    private final Path outDir;
    private final Scenario scenario;
    private final Map<String, Snapshot> golden;
    private final Observations obs;
    private final EdtWatchdog watchdog;
    private final SnapshotTaker taker;
    private final Screenshots shots = new Screenshots();
    private final RunResult result = new RunResult();
    /** End of the last step window: each step takes the hits in (lastEnd, now] (WP-04, F-08). The hook reads it. */
    private volatile long lastEnd = Long.MIN_VALUE;
    /** Guards {@link #result} and the file, {@link #finished} and {@link #exiting}: the player and the hook (F-22). */
    final ReentrantLock resultLock = new ReentrantLock();
    /** The player wrote its final result. Guarded by {@link #resultLock}. */
    private boolean finished;
    /** The shutdown hook took the result. Guarded by {@link #resultLock}. */
    private boolean exiting;

    /** @param golden golden snapshot per step id, from the golden file; a step without an entry gives NO_GOLDEN */
    Player(AgentConfig cfg, Scenario scenario, Map<String, Snapshot> golden, Observations obs, EdtWatchdog watchdog) {
        this(cfg, cfg.scenarios.isEmpty() ? null : cfg.scenarios.get(0), scenario, golden, obs, watchdog, cfg.out);
    }

    /** @param outDir the folder of the result file and the screenshots of this scenario */
    Player(AgentConfig cfg, Path scenarioFile, Scenario scenario, Map<String, Snapshot> golden, Observations obs,
            EdtWatchdog watchdog, Path outDir) {
        this.cfg = cfg;
        this.scenarioFile = scenarioFile;
        this.outDir = outDir;
        this.scenario = scenario;
        this.golden = golden;
        this.taker = new SnapshotTaker(scenario.maxRows, "named".equals(scenario.compare));
        this.obs = obs;
        this.watchdog = watchdog;
    }

    @Override
    public void run() {
        result.scenarioName = scenario.name;
        result.compare = scenario.compare;
        result.scenarioFile = scenarioFile == null ? null : scenarioFile.toString();
        result.startedAt = System.currentTimeMillis();
        result.javaVersion = System.getProperty("java.version");
        result.os = System.getProperty("os.name") + " " + System.getProperty("os.version");
        writeProgress("startup"); // before the hook: the hook always has a currentStep
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                onExit();
            }
        }, "smoker-exit"));
        Throwable failure = null;
        try {
            startup();
            for (int i = 0; i < scenario.steps.size(); i++) {
                RunResult.StepResult sr = runStep(scenario.steps.get(i), !skipsSnapshot(scenario, i));
                addStep(sr);
                // Continue after FAIL, so that one approve covers all steps (F-21).
                // After HANG or ERROR the state of the UI is not known: stop (R-35).
                if (sr.status == RunResult.Status.HANG || sr.status == RunResult.Status.ERROR) break;
            }
        } catch (Throwable t) {
            failure = t;
        } finally {
            writeFinal(failure);
            SmokerAgent.log("scenario '" + scenario.name + "' " + result.status + " (" + (result.finishedAt - result.startedAt) + " ms)");
            obs.stop();
            watchdog.shutdown();
            if (cfg.exitWhenDone) {
                int code = result.status == RunResult.Status.PASS || result.status == RunResult.Status.NO_GOLDEN ? 0 : 1;
                System.exit(code);
            }
        }
    }

    private Path resultFile() {
        return outDir.resolve("run-result.json");
    }

    /** Adds a closed step and its status to the result. Ignored after the hook took the result. */
    void addStep(RunResult.StepResult sr) {
        resultLock.lock();
        try {
            if (exiting) return;
            result.steps.add(sr);
            result.status = worse(result.status, sr.status);
        } finally {
            resultLock.unlock();
        }
    }

    /** Writes the result with the closed steps and {@code currentStep}, before startup and each action (F-22). */
    void writeProgress(String stepId) {
        Exception failed = null;
        resultLock.lock();
        try {
            if (exiting || finished) return;
            result.currentStep = stepId;
            Json.writeAtomic(resultFile(), result);
        } catch (Exception e) {
            failed = e;
        } finally {
            resultLock.unlock();
        }
        if (failed != null) System.err.println("smoker: cannot write progress: " + failed);
    }

    /** The final write of the player. Writes nothing when the hook took the result (Review Focus 4). */
    void writeFinal(Throwable failure) {
        List<String> unnamed = new ArrayList<String>();
        List<String> duplicates = new ArrayList<String>();
        List<LintEntry> lint = new ArrayList<LintEntry>();
        List<String> autoNames = new ArrayList<String>();
        try {
            unnamed.addAll(taker.unnamedInteractive());
            duplicates.addAll(taker.duplicateNames());
            lint.addAll(taker.lintEntries());
            autoNames.addAll(taker.autoNamesUsed());
        } catch (RuntimeException e) {
            // The lint is optional: the result file must still be written.
            SmokerAgent.log("cannot collect the lint: " + e);
        }
        Exception failed = null;
        resultLock.lock();
        try {
            if (exiting) return;
            finished = true; // before the System.exit of the player: the hook then does nothing
            if (failure != null) {
                result.status = RunResult.Status.ERROR;
                result.error = String.valueOf(failure);
            }
            result.currentStep = null;
            result.finishedAt = System.currentTimeMillis();
            result.unnamedComponents.addAll(unnamed);
            result.duplicateNames.addAll(duplicates);
            result.lint.addAll(lint);
            result.autoNames.addAll(autoNames);
            Json.writeAtomic(resultFile(), result);
        } catch (Exception e) {
            failed = e;
        } finally {
            resultLock.unlock();
        }
        if (failed != null) System.err.println("smoker: cannot write result: " + failed);
    }

    /**
     * Body of the shutdown hook: the application called {@code System.exit} before the player finished (F-22).
     * Waits at most 1 s for the lock and at most 1 s for the hits. Does not touch Swing and does not print: a print
     * can block on the lock of a console stream that an application thread holds.
     */
    void onExit() {
        try {
            if (!resultLock.tryLock(1, TimeUnit.SECONDS)) return;
        } catch (InterruptedException e) {
            return;
        }
        try {
            if (finished || exiting) return;
            exiting = true;
            String stepId = result.currentStep;
            RunResult.StepResult sr = null;
            if (!result.steps.isEmpty()) {
                RunResult.StepResult last = result.steps.get(result.steps.size() - 1);
                if (last.stepId != null && last.stepId.equals(stepId)) sr = last; // the step closed: no second entry
            }
            if (sr == null) {
                sr = new RunResult.StepResult();
                sr.stepId = stepId;
                for (Step s : scenario.steps) if (s.id != null && s.id.equals(stepId)) sr.stepName = s.name;
                result.steps.add(sr);
            }
            String error = "the application exited during step " + stepId;
            sr.status = RunResult.Status.ERROR;
            sr.error = error;
            sr.logHits.addAll(hitsWithin(lastEnd, 1000));
            result.status = RunResult.Status.ERROR;
            result.error = error;
            result.finishedAt = System.currentTimeMillis();
            Json.writeAtomic(resultFile(), result);
        } catch (Throwable ignored) {
            // The JVM stops: nothing to report to. The file from before the action still names the step.
        } finally {
            resultLock.unlock();
        }
    }

    /**
     * The hits after {@code from}, taken on a helper thread: the drain takes the console stream locks, which an
     * application thread can hold. When the drain does not end within {@code maxWaitMs}, gives the hits
     * collected so far without a drain.
     */
    private List<RunResult.LogHit> hitsWithin(final long from, long maxWaitMs) throws InterruptedException {
        final AtomicReference<List<RunResult.LogHit>> hits = new AtomicReference<List<RunResult.LogHit>>();
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                hits.set(obs.takeHits(from, Long.MAX_VALUE)); // the application stops: every hit is of this step
            }
        }, "smoker-exit-drain");
        t.setDaemon(true);
        t.start();
        t.join(maxWaitMs);
        List<RunResult.LogHit> got = hits.get();
        return got != null ? got : obs.takeHitsNoDrain(from, Long.MAX_VALUE);
    }

    /** Order ERROR > HANG > FAIL > NO_GOLDEN > PASS. */
    static RunResult.Status worse(RunResult.Status a, RunResult.Status b) {
        return rank(b) > rank(a) ? b : a;
    }

    private static int rank(RunResult.Status s) {
        switch (s) {
            case ERROR: return 4;
            case HANG: return 3;
            case FAIL: return 2;
            case NO_GOLDEN: return 1;
            default: return 0;
        }
    }

    /** R-14: main window within startupTimeoutMs, no fail-rule hits meanwhile. */
    private void startup() throws Exception {
        long t0 = System.currentTimeMillis();
        long deadline = t0 + scenario.startupTimeoutMs;
        while (System.currentTimeMillis() < deadline && !mainWindowShowing()) {
            if (watchdog.hangDetected()) break;
            Thread.sleep(100);
        }
        RunResult.StepResult sr = new RunResult.StepResult();
        sr.stepId = "startup";
        sr.stepName = "main window visible";
        if (!mainWindowShowing()) {
            sr.status = watchdog.hangDetected() ? RunResult.Status.HANG : RunResult.Status.FAIL;
            sr.threadDump = watchdog.threadDump();
            sr.error = "no frame became visible within " + scenario.startupTimeoutMs + " ms";
        }
        Quiescence.await(scenario.quietMs, scenario.stepTimeoutMs);
        // before the hits, as in runStep: no gap between takeHits and addStep where an exit loses the hits
        if (cfg.screenshots) sr.screenshot = shots.capture(outDir, "startup");
        obs.drain(); // a plain file line gets the time of reading: read before the end of the window
        long now = System.currentTimeMillis();
        sr.durationMs = now - t0;
        sr.logHits.addAll(obs.takeHits(Long.MIN_VALUE, now)); // the startup window starts at the agent start
        lastEnd = now;
        if (sr.status == RunResult.Status.PASS && !sr.logHits.isEmpty()) sr.status = RunResult.Status.FAIL;
        addStep(sr);
        if (sr.status != RunResult.Status.PASS) {
            throw new IllegalStateException("startup failed: " + (sr.error != null ? sr.error : "log hits during startup"));
        }
    }

    private boolean mainWindowShowing() {
        return mainWindowShown(1000);
    }

    /** A frame that is shown and displayable (not half built) and is not the tool's own window. */
    static boolean isMainWindow(Window w) {
        return w instanceof Frame && w.isShowing() && w.isDisplayable()
                && (w.getName() == null || !w.getName().startsWith(SnapshotTaker.SMOKER_WINDOW_PREFIX));
    }

    /** Scans the windows on the EDT. An EDT that does not answer within maxWaitMs counts as "no main window". */
    static boolean mainWindowShown(long maxWaitMs) {
        FutureTask<Boolean> task = new FutureTask<Boolean>(new Callable<Boolean>() {
            @Override
            public Boolean call() {
                for (Window w : Window.getWindows()) if (isMainWindow(w)) return true;
                return false;
            }
        });
        SwingUtilities.invokeLater(task);
        try {
            return task.get(maxWaitMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            task.cancel(false);
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) { // timeout or failure
            task.cancel(false);
            return false;
        }
    }

    /** Status after the compare. A FAIL (for example a WAIT_FOR timeout) stays FAIL, also without a golden. */
    static RunResult.Status afterCompare(RunResult.Status current, boolean hasGolden, boolean hasDifferences) {
        if (current != RunResult.Status.PASS) return current;
        if (!hasGolden) return RunResult.Status.NO_GOLDEN;
        return hasDifferences ? RunResult.Status.FAIL : current;
    }

    /** True when the next step is a WAIT_FOR: this step takes no snapshot and has no golden (WP-03, decision B). */
    static boolean skipsSnapshot(Scenario s, int index) {
        if (index + 1 >= s.steps.size()) return false;
        Action next = s.steps.get(index + 1).action;
        return next != null && next.type == Action.Type.WAIT_FOR;
    }

    /**
     * Runs the wait of a WAIT_FOR step on the player thread. Sets status, error and differences.
     *
     * @return true when the step continues to settle and snapshot (MATCHED or TIMEOUT); false on HANG or ERROR
     */
    static boolean runWaitFor(Action a, RunResult.StepResult sr, java.util.function.BooleanSupplier hang) {
        java.util.regex.Pattern p;
        try {
            p = WaitCondition.validate(a);
        } catch (IllegalArgumentException e) {
            sr.status = RunResult.Status.ERROR;
            sr.error = e.getMessage();
            return false;
        }
        WaitCondition.Outcome o = WaitCondition.await(a, p, hang);
        if (Thread.currentThread().isInterrupted()) {
            sr.status = RunResult.Status.ERROR;
            sr.error = "WAIT_FOR interrupted";
            return false;
        }
        switch (o.kind) {
            case MATCHED:
                return true;
            case TIMEOUT:
                sr.status = RunResult.Status.FAIL;
                sr.differences.add(WaitCondition.timeoutMessage(a, o.lastValue));
                return true;
            default:
                sr.status = RunResult.Status.HANG;
                return false;
        }
    }

    private RunResult.StepResult runStep(final Step step, boolean takeSnapshot) {
        RunResult.StepResult sr = new RunResult.StepResult();
        sr.stepId = step.id;
        sr.stepName = step.name;
        writeProgress(step.id); // a System.exit or a crash in the action leaves a file that names the step
        long t0 = System.currentTimeMillis();
        watchdog.reset();
        SmokerAgent.log("step " + step.id + " " + step.action);
        try {
            if (step.action != null && step.action.type == Action.Type.WAIT_FOR) {
                // WAIT_FOR runs on the player thread; each read goes through the EDT (WP-03)
                if (!runWaitFor(step.action, sr, new java.util.function.BooleanSupplier() {
                    @Override
                    public boolean getAsBoolean() {
                        return watchdog.hangDetected();
                    }
                })) {
                    if (sr.status == RunResult.Status.HANG) return hang(sr, t0, "WAIT_FOR: EDT blocked");
                    finish(sr, t0);
                    return sr;
                }
            }
            // 1. action on the EDT, bounded so a hung EDT cannot hang the player
            final Throwable[] failure = new Throwable[1];
            final CountDownLatch done = new CountDownLatch(1);
            if (step.action != null && step.action.type == Action.Type.WAIT_FOR) {
                done.countDown();
            } else SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    try {
                        ActionExecutor.execute(step.action);
                    } catch (Throwable t) {
                        failure[0] = t;
                    } finally {
                        done.countDown();
                    }
                }
            });
            // A ping posted after the action is serviced when the action has finished OR when it opened a
            // modal dialog (nested event loop). Both mean "the UI is alive again"; only silence means a hang.
            if (!Quiescence.edtResponds(scenario.stepTimeoutMs)) {
                return hang(sr, t0, "action did not complete within " + scenario.stepTimeoutMs + " ms");
            }
            done.await(50, TimeUnit.MILLISECONDS);
            if (failure[0] != null) {
                sr.status = RunResult.Status.ERROR;
                sr.error = String.valueOf(failure[0]);
                finish(sr, t0);
                return sr;
            }

            // 2. settle
            boolean quiet = Quiescence.await(scenario.quietMs, scenario.stepTimeoutMs);
            if (!quiet && watchdog.hangDetected()) return hang(sr, t0, "EDT blocked after action");

            if (!takeSnapshot) {
                SmokerAgent.log("step " + step.id + ": no snapshot, the next step is WAIT_FOR");
                finish(sr, t0);
                if (!sr.logHits.isEmpty() && sr.status != RunResult.Status.ERROR) sr.status = RunResult.Status.FAIL;
                return sr;
            }

            // 3. observe
            sr.actual = Quiescence.onEdt(new Callable<Snapshot>() {
                @Override
                public Snapshot call() {
                    return taker.take();
                }
            });
            if (cfg.screenshots) sr.screenshot = shots.capture(outDir, step.id);

            // 4. compare
            Snapshot expected = golden.get(step.id);
            if (expected != null) sr.differences.addAll(SnapshotDiff.diff(expected, sr.actual, scenario.ignore));
            sr.status = afterCompare(sr.status, expected != null, !sr.differences.isEmpty());
            finish(sr, t0);
            if (!sr.logHits.isEmpty() && sr.status != RunResult.Status.ERROR) sr.status = RunResult.Status.FAIL;
            if (!quiet && sr.status == RunResult.Status.PASS) sr.differences.add("WARN UI did not become quiet within " + scenario.stepTimeoutMs + " ms");
        } catch (Throwable t) {
            sr.status = RunResult.Status.ERROR;
            sr.error = String.valueOf(t);
            finish(sr, t0);
        }
        return sr;
    }

    private RunResult.StepResult hang(RunResult.StepResult sr, long t0, String why) {
        sr.status = RunResult.Status.HANG;
        sr.error = why;
        sr.threadDump = watchdog.threadDump() != null ? watchdog.threadDump() : EdtWatchdog.dumpThreads();
        finish(sr, t0);
        return sr;
    }

    private void finish(RunResult.StepResult sr, long t0) {
        obs.drain(); // a plain file line gets the time of reading: read before the end of the window
        long now = System.currentTimeMillis();
        sr.durationMs = now - t0;
        sr.logHits.addAll(obs.takeHits(lastEnd, now));
        lastEnd = now;
    }

    Path outDir() {
        return outDir;
    }
}
