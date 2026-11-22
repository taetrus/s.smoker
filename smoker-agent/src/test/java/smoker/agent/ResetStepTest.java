package smoker.agent;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import smoker.core.io.Json;
import smoker.core.model.Action;
import smoker.core.model.Locator;
import smoker.core.model.RunResult;
import smoker.core.model.Scenario;
import smoker.core.model.Step;

import javax.swing.JFrame;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The reset step between scenarios (WP-05, F-04, R-37). The frame tests are skipped without a display. */
public class ResetStepTest {

    /** A command for the reset: {@code exit <n>} or {@code sleep <ms>}. */
    public static final class Fake {
        public static void main(String[] args) throws Exception {
            if ("exit".equals(args[0])) System.exit(Integer.parseInt(args[1]));
            Thread.sleep(Long.parseLong(args[1]));
        }
    }

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private JFrame frame;
    private AgentConfig cfg;
    private Observations obs;
    private EdtWatchdog watchdog;
    private final List<String> texts = Collections.synchronizedList(new ArrayList<String>());

    @Before
    public void setUp() throws Exception {
        Assume.assumeFalse(GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                frame = new JFrame("reset test");
                frame.setName("reset.frame");
                final JTextField field = new JTextField(10);
                field.setName("reset.field");
                field.getDocument().addDocumentListener(new DocumentListener() {
                    @Override
                    public void insertUpdate(DocumentEvent e) {
                        texts.add(field.getText());
                    }

                    @Override
                    public void removeUpdate(DocumentEvent e) {
                        texts.add(field.getText());
                    }

                    @Override
                    public void changedUpdate(DocumentEvent e) {
                    }
                });
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

    private Path scenario(String name, String fieldName, String text) throws Exception {
        Scenario sc = new Scenario();
        sc.name = name;
        sc.quietMs = 100;
        sc.stepTimeoutMs = 3000;
        sc.startupTimeoutMs = 10000;
        Locator l = new Locator();
        l.name = fieldName;
        sc.steps.add(new Step("s001", "type", Action.setText(l, text)));
        Path file = tmp.getRoot().toPath().resolve(name + ".json");
        Json.write(file, sc);
        return file;
    }

    private static String command(String op, long arg) {
        String java = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        return "\"" + java + "\" -cp \"" + System.getProperty("java.class.path") + "\" "
                + Fake.class.getName() + " " + op + " " + arg;
    }

    private void runSession(Path... files) {
        cfg.scenarios.addAll(Arrays.asList(files));
        new Session(cfg, obs, watchdog, AppStopper.SYSTEM).run();
    }

    private RunResult result(String name) throws Exception {
        return Json.read(cfg.out.resolve(name).resolve("run-result.json"), RunResult.class);
    }

    @Test
    public void resetCommandFailureGivesError() throws Exception {
        cfg.resetCommands.add(command("exit", 3));
        cfg.resetTimeoutS.add(60L);
        runSession(scenario("one", "reset.field", "a"), scenario("two", "reset.field", "b"),
                scenario("three", "reset.field", "c"));
        RunResult two = result("two");
        assertEquals(RunResult.Status.ERROR, two.status);
        assertEquals(1, two.steps.size());
        assertEquals("reset", two.steps.get(0).stepId);
        assertEquals("reset command 1 failed: exit code 3", two.steps.get(0).error);
        assertFalse(texts.contains("b"));
        assertFalse(Files.exists(cfg.out.resolve("three")));
        assertTrue(Files.exists(cfg.out.resolve("two").resolve("reset-command.log")));
    }

    @Test
    public void resetCommandTimeoutGivesError() throws Exception {
        cfg.resetCommands.add(command("sleep", 60000));
        cfg.resetTimeoutS.add(1L);
        runSession(scenario("one", "reset.field", "a"), scenario("two", "reset.field", "b"));
        RunResult two = result("two");
        assertEquals(RunResult.Status.ERROR, two.status);
        assertTrue(two.steps.get(0).error, two.steps.get(0).error.startsWith("reset command 1 timed out"));
    }

    @Test
    public void resetScenarioRunsWithoutSnapshot() throws Exception {
        cfg.resetScenario = scenario("clear", "reset.field", "RESET");
        runSession(scenario("one", "reset.field", "a"), scenario("two", "reset.field", "b"));
        RunResult two = result("two");
        assertTrue(String.valueOf(two.status), two.status == RunResult.Status.PASS
                || two.status == RunResult.Status.NO_GOLDEN);
        assertEquals("reset", two.steps.get(0).stepId);
        assertEquals(RunResult.Status.PASS, two.steps.get(0).status);
        assertNull(two.steps.get(0).actual);
        assertEquals("s001", two.steps.get(1).stepId);
        assertEquals(2, two.steps.size());
        int a = texts.indexOf("a");
        int reset = texts.indexOf("RESET");
        int b = texts.lastIndexOf("b");
        assertTrue(texts.toString(), a >= 0 && a < reset && reset < b);
    }

    @Test
    public void resetScenarioStepFailureNamesTheStep() throws Exception {
        cfg.resetScenario = scenario("clear", "reset.missing", "x");
        runSession(scenario("one", "reset.field", "a"), scenario("two", "reset.field", "b"));
        RunResult two = result("two");
        assertEquals(RunResult.Status.ERROR, two.status);
        assertEquals(1, two.steps.size());
        String error = two.steps.get(0).error;
        assertNotNull(error);
        assertTrue(error, error.startsWith("reset scenario: step s001:"));
    }

    @Test
    public void logHitDuringResetFails() throws Exception {
        cfg.resetCommands.add(command("sleep", 1500));
        cfg.resetTimeoutS.add(60L);
        cfg.scenarios.addAll(Arrays.asList(scenario("one", "reset.field", "a"), scenario("two", "reset.field", "b")));
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
        Thread.sleep(50);
        obs.accept(new LogEvent("stderr", LogEvent.Kind.PLAIN, System.currentTimeMillis(), 1, LogLevel.ERROR,
                Arrays.asList("during the reset ERROR")));
        t.join(30000);
        RunResult two = result("two");
        assertEquals("reset", two.steps.get(0).stepId);
        assertEquals(RunResult.Status.FAIL, two.steps.get(0).status);
        assertEquals(1, two.steps.get(0).logHits.size());
        assertEquals(1, two.steps.size());
    }

    @Test
    public void hungEdtGivesHangWithAThreadDump() throws Exception {
        Scenario sc = new Scenario();
        sc.name = "hung";
        sc.quietMs = 100;
        sc.stepTimeoutMs = 500;
        Path file = tmp.getRoot().toPath().resolve("hung.json");
        Json.write(file, sc);
        Path outDir = tmp.newFolder("hungout").toPath();
        Player p = new Player(cfg, file, sc, new java.util.HashMap<String, smoker.core.model.Snapshot>(), obs,
                watchdog, outDir, true);
        final Object lock = new Object();
        final boolean[] release = {false};
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                synchronized (lock) {
                    long end = System.currentTimeMillis() + 15000;
                    while (!release[0] && System.currentTimeMillis() < end) {
                        try {
                            lock.wait(50);
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                }
            }
        });
        try {
            p.begin(Player.First.RESET);
            assertEquals(RunResult.Status.HANG, p.play(Player.First.RESET, Long.MIN_VALUE));
        } finally {
            synchronized (lock) {
                release[0] = true;
                lock.notifyAll();
            }
        }
        RunResult r = Json.read(outDir.resolve("run-result.json"), RunResult.class);
        assertEquals("reset", r.steps.get(0).stepId);
        assertEquals(RunResult.Status.HANG, r.steps.get(0).status);
        assertNotNull(r.steps.get(0).threadDump);
        assertTrue(Quiescence.edtResponds(5000));
    }
}
