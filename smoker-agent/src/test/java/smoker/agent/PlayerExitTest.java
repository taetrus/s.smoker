package smoker.agent;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import smoker.core.io.Json;
import smoker.core.model.RunResult;
import smoker.core.model.Scenario;
import smoker.core.model.Snapshot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** run-result.json before each action and from the shutdown hook on System.exit (WP-04, F-22). */
public class PlayerExitTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Player player;
    private Observations obs;
    private Path file;

    @Before
    public void setUp() throws Exception {
        AgentConfig cfg = new AgentConfig();
        cfg.out = tmp.newFolder("out").toPath();
        cfg.exitWhenDone = false;
        Scenario sc = new Scenario();
        sc.name = "exit";
        obs = new Observations(cfg);
        player = new Player(cfg, sc, Collections.<String, Snapshot>emptyMap(), obs, new EdtWatchdog(5000));
        file = cfg.out.resolve("run-result.json");
    }

    private static RunResult.StepResult step(String id) {
        RunResult.StepResult sr = new RunResult.StepResult();
        sr.stepId = id;
        return sr;
    }

    private RunResult read() throws Exception {
        return Json.read(file, RunResult.class);
    }

    @Test
    public void progressNamesTheCurrentStep() throws Exception {
        player.writeProgress("startup");
        player.addStep(step("startup"));
        player.writeProgress("s001");
        player.addStep(step("s001"));
        player.writeProgress("s002");
        RunResult r = read();
        assertEquals("s002", r.currentStep);
        assertEquals(0, r.finishedAt);
        assertEquals(2, r.steps.size());
        assertEquals("startup", r.steps.get(0).stepId);
        assertEquals("s001", r.steps.get(1).stepId);
    }

    @Test
    public void onExitWritesAnErrorStepWithItsHits() throws Exception {
        player.addStep(step("startup"));
        player.writeProgress("s001");
        obs.accept(new LogEvent("file:app.log", LogEvent.Kind.LOGBACK, System.currentTimeMillis(), 1, LogLevel.ERROR,
                Arrays.asList("12:00:00.000 [main] ERROR demo - crashing on purpose")));
        player.onExit();
        RunResult r = read();
        assertEquals(RunResult.Status.ERROR, r.status);
        assertEquals("the application exited during step s001", r.error);
        assertTrue(r.finishedAt > 0);
        assertEquals(2, r.steps.size());
        RunResult.StepResult last = r.steps.get(1);
        assertEquals("s001", last.stepId);
        assertEquals(RunResult.Status.ERROR, last.status);
        assertEquals(1, last.logHits.size());
        assertTrue(last.logHits.get(0).line, last.logHits.get(0).line.contains("crashing on purpose"));
    }

    @Test
    public void onExitDuringStartupWritesAStartupStep() throws Exception {
        player.writeProgress("startup");
        player.onExit();
        RunResult r = read();
        assertEquals("the application exited during step startup", r.error);
        assertEquals(1, r.steps.size());
        assertEquals("startup", r.steps.get(0).stepId);
        assertEquals(RunResult.Status.ERROR, r.steps.get(0).status);
    }

    @Test
    public void onExitAfterAClosedStepDoesNotAddItAgain() throws Exception {
        player.writeProgress("s001");
        player.addStep(step("s001"));
        player.onExit();
        RunResult r = read();
        assertEquals(1, r.steps.size());
        assertEquals(RunResult.Status.ERROR, r.steps.get(0).status);
        assertEquals("the application exited during step s001", r.steps.get(0).error);
    }

    @Test
    public void onExitAfterTheEndDoesNothing() throws Exception {
        player.writeProgress("s001");
        player.addStep(step("s001"));
        player.writeFinal(null);
        byte[] before = Files.readAllBytes(file);
        assertEquals(RunResult.Status.PASS, read().status);
        player.onExit();
        assertArrayEquals(before, Files.readAllBytes(file));
    }

    @Test
    public void hookResultIsNotOverwrittenByThePlayer() throws Exception {
        player.writeProgress("s001");
        player.onExit();
        player.addStep(step("s001"));
        player.writeProgress("s002");
        player.writeFinal(null);
        RunResult r = read();
        assertEquals(RunResult.Status.ERROR, r.status);
        assertEquals("the application exited during step s001", r.error);
        assertEquals("s001", r.currentStep);
        assertEquals(1, r.steps.size());
    }
}
