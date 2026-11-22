package smoker.cli;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import smoker.core.io.ConfigFile;
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
import java.util.Arrays;
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
    public void approveWarnsOnASharedJvmResult() throws Exception {
        Path sc = scenario("all");
        Path run = run("all", snap(node("[W]/#a", "a")), snap(node("[W]/#a", "b")));
        RunResult r = Json.read(run.resolve("run-result.json"), RunResult.class);
        r.sharedJvm = true;
        Json.write(run.resolve("run-result.json"), r);
        assertEquals(0, Main.approve(options("run", run.toString(), "scenario", sc.toString())));
        assertTrue(err(), err().contains("approve: warning: the run used a shared JVM; the golden holds the state after other scenarios"));
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

    @Test
    public void budgetAddsWaitForTimeouts() {
        smoker.core.model.Locator l = new smoker.core.model.Locator();
        l.name = "x";
        Scenario sc = new Scenario();
        sc.steps.add(new Step("s001", "a", smoker.core.model.Action.click(l)));
        long base = Main.budgetMs(sc);
        sc.steps.add(new Step("s002", "b", smoker.core.model.Action.waitFor(l, "text", "a", 20000L)));
        long old = sc.startupTimeoutMs + (sc.steps.size() + 1) * (sc.stepTimeoutMs + 2000) + 10_000;
        assertEquals(old + 20000, Main.budgetMs(sc));
        assertTrue(base > 0);
    }

    // ---- WP-04: unfinished results, log options, approve without a result ---------------------

    private Path runDirWith(String json) throws Exception {
        Path dir = tmp.newFolder().toPath();
        if (json != null) Files.write(dir.resolve("run-result.json"), json.replace('\'', '"').getBytes(StandardCharsets.UTF_8));
        return dir;
    }

    @Test
    public void approveWithoutRunResultGivesExitCode2() throws Exception {
        Path dir = tmp.newFolder().toPath();
        Path sc = file("a.json", "{'schemaVersion':2,'steps':[]}");
        assertEquals(2, Main.approve(options("run", dir.toString(), "scenario", sc.toString())));
        assertTrue(err(), err().contains("approve: no run-result.json in " + dir));
        assertFalse(err(), err().contains("Exception"));
        assertFalse(err(), err().contains("\tat "));
    }

    @Test
    public void unfinishedResultNamesTheStep() throws Exception {
        Path dir = runDirWith("{'scenarioName':'x','status':'PASS','currentStep':'s003','finishedAt':0}");
        Path sc = tmp.getRoot().toPath().resolve("x.json");
        RunResult r = Main.resultOf(dir, "x", sc, 7, 5000, "app-output.log");
        assertEquals(RunResult.Status.ERROR, r.status);
        assertEquals("the application stopped during step s003; exit code 7", r.error);
        RunResult k = Main.resultOf(dir, "x", sc, -999, 5000, "app-output.log");
        assertEquals(RunResult.Status.HANG, k.status);
        assertTrue(k.error, k.error.contains("s003"));
    }

    @Test
    public void hookResultGetsTheExitCode() throws Exception {
        Path dir = runDirWith("{'scenarioName':'x','status':'ERROR','finishedAt':5,"
                + "'error':'the application exited during step s001'}");
        RunResult r = Main.resultOf(dir, "x", tmp.getRoot().toPath().resolve("x.json"), 7, 5000, "app-output.log");
        assertEquals(RunResult.Status.ERROR, r.status);
        assertEquals("the application exited during step s001; exit code 7", r.error);
    }

    @Test
    public void finishedResultIsKept() throws Exception {
        Path dir = runDirWith("{'scenarioName':'x','status':'PASS','finishedAt':5}");
        RunResult r = Main.resultOf(dir, "x", tmp.getRoot().toPath().resolve("x.json"), 0, 5000, "app-output.log");
        assertEquals(RunResult.Status.PASS, r.status);
        assertEquals(null, r.error);
    }

    @Test
    public void missingResultKeepsTheOldMessage() throws Exception {
        Path dir = runDirWith(null);
        RunResult r = Main.resultOf(dir, "x", tmp.getRoot().toPath().resolve("x.json"), 7, 5000, "app-output.log");
        assertEquals(RunResult.Status.ERROR, r.status);
        assertEquals("no run-result.json produced; app exit code 7 \u2014 see app-output.log", r.error);
        RunResult k = Main.resultOf(dir, "x", tmp.getRoot().toPath().resolve("x.json"), -999, 5000, "app-output.log");
        assertEquals(RunResult.Status.HANG, k.status);
        assertEquals("application did not exit within 5000 ms (killed)", k.error);
    }

    @Test
    public void agentConfigCarriesTheOptions() throws Exception {
        Path sc = tmp.getRoot().toPath().resolve("x.json");
        Path out = tmp.getRoot().toPath().resolve("out");
        Map<String, String> c = Main.agentConfig(options("logPattern", "%d;%msg%n", "failLevel", "WARN", "logCharset", "UTF-8"),
                Arrays.asList(sc), out);
        assertEquals(out.toString(), c.get("out"));
        assertEquals(sc.toAbsolutePath().toString(), c.get("scenario.1"));
        assertEquals("%d;%msg%n", c.get("logPattern"));
        assertEquals("WARN", c.get("failLevel"));
        assertEquals("UTF-8", c.get("logCharset"));
        assertFalse(Main.agentConfig(options(), Arrays.asList(sc), out).containsKey("logPattern"));
    }

    @Test
    public void agentConfigNumbersTheLogFiles() throws Exception {
        Path sc = tmp.getRoot().toPath().resolve("x.json");
        Map<String, String> c = Main.agentConfig(options("log", "a.log,b.log"), Arrays.asList(sc), tmp.getRoot().toPath());
        assertEquals("a.log", c.get("log.1"));
        assertEquals("b.log", c.get("log.2"));
        assertFalse(c.containsKey("log"));
    }

    @Test
    public void runAcceptsALogPatternWithASemicolon() throws Exception {
        Path sc = file("x.json", "{'schemaVersion':2,'steps':[]}");
        Path out = tmp.getRoot().toPath().resolve("o");
        try {
            Main.run(options("agent", "none.jar", "app", "no-such-app", "scenarios", sc.toString(),
                    "out", out.toString(), "logPattern", "%d;%msg"));
            fail("the application cannot start");
        } catch (java.io.IOException e) {
            // expected: the command does not exist; the configuration file was written before the start
        }
        assertFalse(err(), err().contains("must not contain ';'"));
        assertEquals("%d;%msg", ConfigFile.read(out.resolve("agent-1.cfg")).get("logPattern"));
    }
}
