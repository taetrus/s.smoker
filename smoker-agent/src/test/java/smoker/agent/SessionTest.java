package smoker.agent;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import smoker.core.io.Json;
import smoker.core.io.ScenarioFiles;
import smoker.core.model.Action;
import smoker.core.model.Locator;
import smoker.core.model.RunResult;
import smoker.core.model.Scenario;
import smoker.core.model.Snapshot;
import smoker.core.model.Step;

import javax.swing.JFrame;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Many scenarios in one JVM (WP-05, F-03, R-36). The frame tests are skipped without a display. */
public class SessionTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private JFrame frame;
    private AgentConfig cfg;
    private Observations obs;
    private EdtWatchdog watchdog;

    @Before
    public void setUp() throws Exception {
        Assume.assumeFalse(GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                frame = new JFrame("session test");
                frame.setName("session.frame");
                JTextField field = new JTextField(10);
                field.setName("session.field");
                frame.add(field);
                frame.pack();
                frame.setVisible(true);
            }
        });
        cfg = new AgentConfig();
        cfg.out = tmp.newFolder("out").toPath();
        cfg.exitWhenDone = false;
        obs = new Observations(cfg);
        watchdog = new EdtWatchdog(5000);
    }

    @After
    public void dispose() throws Exception {
        Locators.useWindowKeys(new WindowKeys(null));
        AutoNames.enable(true);
        if (watchdog != null) watchdog.shutdown();
        if (frame == null) return;
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                frame.dispose();
            }
        });
    }

    private Path scenario(String name, String text, long quietMs, boolean autoNames) throws Exception {
        Scenario sc = new Scenario();
        sc.name = name;
        sc.quietMs = quietMs;
        sc.stepTimeoutMs = 5000;
        sc.startupTimeoutMs = 10000;
        sc.autoNames = autoNames;
        Locator l = new Locator();
        l.name = "session.field";
        sc.steps.add(new Step("s001", "type", Action.setText(l, text)));
        Path file = tmp.getRoot().toPath().resolve(name + ".json");
        Json.write(file, sc);
        return file;
    }

    private Path scenario(String name, String text) throws Exception {
        return scenario(name, text, 100, true);
    }

    private void runSession(Path... files) {
        cfg.scenarios.addAll(Arrays.asList(files));
        new Session(cfg, obs, watchdog, AppStopper.SYSTEM).run();
    }

    private RunResult result(String name) throws Exception {
        return Json.read(cfg.out.resolve(name).resolve("run-result.json"), RunResult.class);
    }

    private static boolean ok(RunResult r) {
        return r.status == RunResult.Status.PASS || r.status == RunResult.Status.NO_GOLDEN;
    }

    @Test
    public void twoScenariosGiveTwoResultFiles() throws Exception {
        runSession(scenario("one", "a"), scenario("two", "b"));
        RunResult one = result("one");
        RunResult two = result("two");
        assertTrue(String.valueOf(one.status), ok(one));
        assertTrue(String.valueOf(two.status), ok(two));
        assertFalse(one.sharedJvm);
        assertTrue(two.sharedJvm);
        assertEquals("startup", one.steps.get(0).stepId);
        assertEquals("reset", two.steps.get(0).stepId);
        assertEquals("s001", two.steps.get(1).stepId);
    }

    @Test
    public void failStopsTheList() throws Exception {
        Path one = scenario("one", "a");
        Path two = scenario("two", "b");
        // a golden for scenario 2 that does not match the snapshot
        runSession(two);
        RunResult first = result("two");
        Snapshot actual = first.steps.get(first.steps.size() - 1).actual;
        assertNotNull(actual);
        actual.nodes.get(actual.nodes.size() - 1).text = "not the text";
        LinkedHashMap<String, Snapshot> golden = new LinkedHashMap<String, Snapshot>();
        golden.put("s001", actual);
        ScenarioFiles.writeGolden(two, "two", golden);
        Files.delete(cfg.out.resolve("two").resolve("run-result.json"));
        cfg.scenarios.clear();
        runSession(one, two, scenario("three", "c"));
        assertEquals(RunResult.Status.FAIL, result("two").status);
        assertFalse(Files.exists(cfg.out.resolve("three")));
    }

    @Test
    public void logWindowsFollowEachOther() throws Exception {
        Path one = scenario("one", "a");
        Path two = scenario("two", "b", 1500, true); // the reset waits 1.5 s for a quiet EDT
        cfg.scenarios.addAll(Arrays.asList(one, two));
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                new Session(cfg, obs, watchdog, AppStopper.SYSTEM).run();
            }
        });
        t.start();
        Path resultOne = cfg.out.resolve("one").resolve("run-result.json");
        long deadline = System.currentTimeMillis() + 20000;
        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(resultOne) && Json.read(resultOne, RunResult.class).finishedAt > 0) break;
            Thread.sleep(20);
        }
        Thread.sleep(20);
        obs.accept(new LogEvent("stderr", LogEvent.Kind.PLAIN, System.currentTimeMillis(), 1, LogLevel.ERROR,
                Arrays.asList("between the scenarios ERROR")));
        t.join(30000);
        RunResult r1 = result("one");
        RunResult r2 = result("two");
        for (RunResult.StepResult s : r1.steps) assertTrue("scenario 1: " + s.stepId, s.logHits.isEmpty());
        assertEquals("reset", r2.steps.get(0).stepId);
        assertEquals(1, r2.steps.get(0).logHits.size());
        assertEquals(RunResult.Status.FAIL, r2.steps.get(0).status);
    }

    @Test
    public void stoppedCliStopsBeforeTheNextScenario() throws Exception {
        Path alive = tmp.newFile("alive").toPath();
        Files.setLastModifiedTime(alive, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 20000));
        cfg.aliveFile = alive;
        runSession(scenario("one", "a"), scenario("two", "b"));
        assertTrue(Files.exists(cfg.out.resolve("one").resolve("run-result.json")));
        assertFalse(Files.exists(cfg.out.resolve("two")));
    }

    @Test
    public void cliAliveRules() throws Exception {
        long now = System.currentTimeMillis();
        assertTrue(Session.cliAlive(null, now));
        assertFalse(Session.cliAlive(tmp.getRoot().toPath().resolve("missing"), now));
        Path f = tmp.newFile("alive2").toPath();
        Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.fromMillis(now - 1000));
        assertTrue(Session.cliAlive(f, now));
        Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.fromMillis(now - 10001));
        assertFalse(Session.cliAlive(f, now));
    }

    @Test
    public void settingsChangeForEachScenario() throws Exception {
        final AtomicInteger seen = new AtomicInteger(-1);
        Path one = scenario("one", "a", 100, true);
        Path two = scenario("two", "b", 100, false);
        cfg.scenarios.addAll(Arrays.asList(one, two));
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                new Session(cfg, obs, watchdog, AppStopper.SYSTEM).run();
            }
        });
        t.start();
        Path resultTwo = cfg.out.resolve("two").resolve("run-result.json");
        long deadline = System.currentTimeMillis() + 20000;
        while (System.currentTimeMillis() < deadline && !Files.exists(resultTwo)) Thread.sleep(5);
        seen.set(AutoNames.enabled() ? 1 : 0);
        t.join(30000);
        assertEquals(0, seen.get());
    }

    @Test
    public void writeErrorGivesAnErrorResultInTheFolderOfTheScenario() throws Exception {
        Path file = scenario("bad", "a");
        Path outDir = cfg.out.resolve("bad");
        Session.writeError(file, "bad", outDir, true, new IllegalStateException("boom"));
        RunResult r = result("bad");
        assertEquals(RunResult.Status.ERROR, r.status);
        assertTrue(r.error, r.error.contains("boom"));
        assertEquals("bad", r.scenarioName);
        assertTrue(r.sharedJvm);
    }

    @Test
    public void fatalErrorPrintsTheStackTraceAndStopsTheList() throws Exception {
        Path blocker = tmp.newFile("blocker").toPath(); // the out folder is a file: no folder can be made
        cfg.out = blocker;
        java.io.PrintStream err = System.err;
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        System.setErr(new java.io.PrintStream(captured, true));
        try {
            runSession(scenario("one", "a"), scenario("two", "b"));
        } finally {
            System.setErr(err);
        }
        String text = captured.toString();
        assertTrue(text, text.contains("[smoker] fatal:"));
        assertTrue(text, text.contains("\tat smoker.agent.Session.run"));
        assertFalse(text, text.contains("scenario 'two'"));
    }
}
