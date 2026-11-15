package smoker.cli;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import smoker.core.io.Json;
import smoker.core.io.ScenarioFiles;
import smoker.core.model.NodeSnapshot;
import smoker.core.model.RunResult;
import smoker.core.model.Scenario;
import smoker.core.model.Snapshot;
import smoker.core.model.Step;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** The CLI commands without a process start: file checks and approve (WP-02, F-17). */
public class MainTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private PrintStream oldOut;
    private PrintStream oldErr;

    @Before
    public void captureOutput() throws Exception {
        oldOut = System.out;
        oldErr = System.err;
        System.setOut(new PrintStream(out, true, "UTF-8"));
        System.setErr(new PrintStream(err, true, "UTF-8"));
    }

    @After
    public void restoreOutput() {
        System.setOut(oldOut);
        System.setErr(oldErr);
    }

    private String out() {
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private String err() {
        return new String(err.toByteArray(), StandardCharsets.UTF_8);
    }

    private Path file(String name, String json) throws Exception {
        Path f = tmp.getRoot().toPath().resolve(name);
        Files.write(f, json.replace('\'', '"').getBytes(StandardCharsets.UTF_8));
        return f;
    }

    private static NodeSnapshot node(String path, String text) {
        NodeSnapshot n = new NodeSnapshot();
        n.path = path;
        n.type = "JLabel";
        n.text = text;
        return n;
    }

    private static Snapshot snap(NodeSnapshot... nodes) {
        Snapshot s = new Snapshot();
        for (NodeSnapshot n : nodes) s.nodes.add(n);
        return s;
    }

    private static Map<String, String> options(String... kv) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    // ---- validated -----------------------------------------------------------------------------

    @Test
    public void validatedReadsScenarioAndGolden() throws Exception {
        Path sc = file("greet.json", "{'schemaVersion': 2, 'name': 'greet', 'steps': []}");
        assertEquals("greet", Main.validated(sc).name);
        file("greet.golden.json", "{'scenario': 'greet', 'steps': []}");
        try {
            Main.validated(sc);
            fail("old golden format accepted");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("old scenario format"));
        }
    }

    // ---- record --------------------------------------------------------------------------------

    /** The command does not exist: without the check, record fails at the process start with an IOException. */
    @Test
    public void recordRefusesAnOldScenarioBeforeTheApplicationStarts() throws Exception {
        Path sc = file("old.json", "{'name': 'old', 'steps': []}");
        int code = Main.record(options("agent", "agent.jar", "app", "no-such-command-smoker", "scenario", sc.toString()));
        assertEquals(2, code);
        assertTrue(err(), err().contains(sc + ": old scenario format"));
        assertFalse(Files.exists(tmp.getRoot().toPath().resolve("old.record.log")));
    }

    @Test
    public void recordRefusesAnOldGoldenBeforeTheApplicationStarts() throws Exception {
        Path sc = file("g.json", "{'schemaVersion': 2, 'name': 'g', 'steps': []}");
        file("g.golden.json", "{'scenario': 'g', 'steps': []}");
        assertEquals(2, Main.record(options("agent", "agent.jar", "app", "no-such-command-smoker", "scenario", sc.toString())));
        assertTrue(err(), err().contains("old scenario format"));
    }

    // ---- approve -------------------------------------------------------------------------------

    private Path scenario(String compare) throws Exception {
        Scenario sc = new Scenario();
        sc.name = "greet";
        sc.compare = compare;
        sc.steps.add(new Step("s001", "one", null));
        sc.steps.add(new Step("s002", "two", null));
        Path f = tmp.getRoot().toPath().resolve("greet.json");
        Json.write(f, sc);
        return f;
    }

    private Path run(String compare, Snapshot s001, Snapshot s002) throws Exception {
        RunResult r = new RunResult();
        r.compare = compare;
        for (Object[] st : new Object[][] {{"s001", s001}, {"s002", s002}}) {
            RunResult.StepResult sr = new RunResult.StepResult();
            sr.stepId = (String) st[0];
            sr.actual = (Snapshot) st[1];
            r.steps.add(sr);
        }
        Path dir = tmp.getRoot().toPath().resolve("run");
        Json.write(dir.resolve("run-result.json"), r);
        return dir;
    }

    @Test
    public void approvePrintsTheChangedStepsAndTheirChangedComponents() throws Exception {
        Path sc = scenario("all");
        LinkedHashMap<String, Snapshot> old = new LinkedHashMap<String, Snapshot>();
        old.put("s001", snap(node("[W]/#a", "a")));
        old.put("s002", snap(node("[W]/#a", "b")));
        ScenarioFiles.writeGolden(sc, "greet", old);
        Path run = run("all", snap(node("[W]/#a", "a")), snap(node("[W]/#a", "c"), node("[W]/#b", "new")));

        assertEquals(0, Main.approve(options("run", run.toString(), "scenario", sc.toString())));
        String o = out();
        String nl = System.lineSeparator();
        assertTrue(o, o.contains("approved 2 step(s) into " + ScenarioFiles.goldenPath(sc) + nl));
        assertTrue(o, o.contains("changed 1 step(s)" + nl));
        assertTrue(o, o.contains("  s002: 2 component(s) changed" + nl));
        assertFalse(o, o.contains("  s001: "));
        assertEquals("", err());
    }

    @Test
    public void approveWarnsForAStepThatIsNotInTheRun() throws Exception {
        Path sc = scenario("all");
        Path run = run("all", snap(node("[W]/#a", "a")), snap(node("[W]/#a", "b")));
        assertEquals(0, Main.approve(options("run", run.toString(), "scenario", sc.toString(), "steps", "s002,s009")));
        assertTrue(err(), err().contains("approve: step s009 is not in the run"));
        assertFalse(err(), err().contains("s002"));
        assertTrue(out(), out().contains("approved 1 step(s) into "));
    }

    @Test
    public void approveRefusesARunWithAnotherCompareMode() throws Exception {
        Path sc = scenario("named");
        Path run = run("all", snap(node("[W]/#a", "a")), snap(node("[W]/#a", "b")));
        assertEquals(2, Main.approve(options("run", run.toString(), "scenario", sc.toString())));
        assertTrue(err(), err().contains("approve: run used compare=all, scenario has compare=named; golden would not match"));
        assertFalse(Files.exists(ScenarioFiles.goldenPath(sc)));
    }

    @Test
    public void changedComponentsCountsAddedRemovedAndChangedPaths() {
        Snapshot before = snap(node("[W]/#same", "x"), node("[W]/#changed", "a"), node("[W]/#removed", "r"));
        Snapshot after = snap(node("[W]/#same", "x"), node("[W]/#changed", "b"), node("[W]/#added", "n"));
        assertEquals(3, Main.changedComponents(before, after));
        assertEquals(0, Main.changedComponents(before, before));
        assertEquals(2, Main.changedComponents(null, snap(node("[W]/#a", "a"), node("[W]/#b", "b"))));
    }
}
