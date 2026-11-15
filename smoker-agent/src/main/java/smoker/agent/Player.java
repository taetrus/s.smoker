package smoker.agent;

import smoker.core.diff.SnapshotDiff;
import smoker.core.io.Json;
import smoker.core.model.RunResult;
import smoker.core.model.Scenario;
import smoker.core.model.Snapshot;
import smoker.core.model.Step;

import javax.swing.SwingUtilities;
import java.awt.Frame;
import java.awt.Window;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Replays a scenario inside the app's JVM (R-30, R-35) and writes {@code run-result.json} into the out dir.
 * Runs on its own daemon thread; every UI touch goes through the EDT.
 */
final class Player implements Runnable {

    private final AgentConfig cfg;
    private final Scenario scenario;
    private final Map<String, Snapshot> golden;
    private final Observations obs;
    private final EdtWatchdog watchdog;
    private final SnapshotTaker taker;
    private final Screenshots shots = new Screenshots();
    private final RunResult result = new RunResult();

    /** @param golden golden snapshot per step id, from the golden file; a step without an entry gives NO_GOLDEN */
    Player(AgentConfig cfg, Scenario scenario, Map<String, Snapshot> golden, Observations obs, EdtWatchdog watchdog) {
        this.cfg = cfg;
        this.scenario = scenario;
        this.golden = golden;
        this.taker = new SnapshotTaker(scenario.maxRows);
        this.obs = obs;
        this.watchdog = watchdog;
    }

    @Override
    public void run() {
        result.scenarioName = scenario.name;
        result.scenarioFile = cfg.scenario.toString();
        result.startedAt = System.currentTimeMillis();
        result.javaVersion = System.getProperty("java.version");
        result.os = System.getProperty("os.name") + " " + System.getProperty("os.version");
        try {
            startup();
            for (Step step : scenario.steps) {
                RunResult.StepResult sr = runStep(step);
                result.steps.add(sr);
                result.status = worse(result.status, sr.status);
                // Continue after FAIL, so that one approve covers all steps (F-21).
                // After HANG or ERROR the state of the UI is not known: stop (R-35).
                if (sr.status == RunResult.Status.HANG || sr.status == RunResult.Status.ERROR) break;
            }
        } catch (Throwable t) {
            result.status = RunResult.Status.ERROR;
            result.error = String.valueOf(t);
        } finally {
            result.finishedAt = System.currentTimeMillis();
            result.unnamedComponents.addAll(taker.unnamedInteractive());
            try {
                Json.write(cfg.out.resolve("run-result.json"), result);
            } catch (Exception e) {
                System.err.println("smoker: cannot write result: " + e);
            }
            SmokerAgent.log("scenario '" + scenario.name + "' " + result.status + " (" + (result.finishedAt - result.startedAt) + " ms)");
            obs.stop();
            watchdog.shutdown();
            if (cfg.exitWhenDone) {
                int code = result.status == RunResult.Status.PASS || result.status == RunResult.Status.NO_GOLDEN ? 0 : 1;
                System.exit(code);
            }
        }
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
        sr.durationMs = System.currentTimeMillis() - t0;
        sr.logHits.addAll(obs.hitsBetween(t0, System.currentTimeMillis()));
        if (sr.status == RunResult.Status.PASS && !sr.logHits.isEmpty()) sr.status = RunResult.Status.FAIL;
        if (cfg.screenshots) sr.screenshot = shots.capture(cfg.out, "startup");
        result.steps.add(sr);
        if (sr.status != RunResult.Status.PASS) {
            result.status = sr.status;
            throw new IllegalStateException("startup failed: " + (sr.error != null ? sr.error : "log hits during startup"));
        }
    }

    private boolean mainWindowShowing() {
        for (Window w : Window.getWindows()) {
            if (w instanceof Frame && w.isShowing()
                    && (w.getName() == null || !w.getName().startsWith(SnapshotTaker.SMOKER_WINDOW_PREFIX))) return true;
        }
        return false;
    }

    private RunResult.StepResult runStep(final Step step) {
        RunResult.StepResult sr = new RunResult.StepResult();
        sr.stepId = step.id;
        sr.stepName = step.name;
        long t0 = System.currentTimeMillis();
        watchdog.reset();
        SmokerAgent.log("step " + step.id + " " + step.action);
        try {
            // 1. action on the EDT, bounded so a hung EDT cannot hang the player
            final Throwable[] failure = new Throwable[1];
            final CountDownLatch done = new CountDownLatch(1);
            SwingUtilities.invokeLater(new Runnable() {
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

            // 3. observe
            sr.actual = Quiescence.onEdt(new Callable<Snapshot>() {
                @Override
                public Snapshot call() {
                    return taker.take();
                }
            });
            if (cfg.screenshots) sr.screenshot = shots.capture(cfg.out, step.id);

            // 4. compare
            Snapshot expected = golden.get(step.id);
            if (expected == null) {
                sr.status = RunResult.Status.NO_GOLDEN;
            } else {
                sr.differences.addAll(SnapshotDiff.diff(expected, sr.actual, scenario.ignore));
                if (!sr.differences.isEmpty()) sr.status = RunResult.Status.FAIL;
            }
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
        long now = System.currentTimeMillis();
        sr.durationMs = now - t0;
        sr.logHits.addAll(obs.hitsBetween(t0, now));
    }

    Path outDir() {
        return cfg.out;
    }
}
