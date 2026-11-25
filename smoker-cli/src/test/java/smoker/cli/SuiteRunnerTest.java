package smoker.cli;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import smoker.core.model.RunResult;
import smoker.core.model.Scenario;
import smoker.core.proc.Processes;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** {@code run --suite} (WP-05, F-03, F-04, R-36, R-37). */
public class SuiteRunnerTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static String q(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private Path file(String rel, String content) throws Exception {
        Path f = tmp.getRoot().toPath().resolve(rel);
        Files.createDirectories(f.getParent());
        Files.write(f, content.getBytes(StandardCharsets.UTF_8));
        return f;
    }

    private static RunResult result(RunResult.Status s) {
        RunResult r = new RunResult();
        r.status = s;
        return r;
    }

    private static Scenario scenario(long startupMs, long stepMs, int steps) {
        Scenario sc = new Scenario();
        sc.startupTimeoutMs = startupMs;
        sc.stepTimeoutMs = stepMs;
        for (int i = 0; i < steps; i++) sc.steps.add(new smoker.core.model.Step());
        return sc;
    }

    // ---- limits and exit code ------------------------------------------------------------------

    @Test
    public void limitOfFirstAndLaterScenario() throws Exception {
        Path sc = file("a.json", "{\"schemaVersion\": 2, \"name\": \"a\", \"steps\": []}");
        Path reset = file("reset.json", "{\"schemaVersion\": 2, \"name\": \"reset\", \"steps\": []}");
        Path suiteFile = file("suite.json", "{\"app\": \"x\", \"resetScenario\": \"reset.json\","
                + " \"reset\": [{\"command\": \"x\", \"timeoutS\": 30}, {\"command\": \"y\", \"timeoutS\": 5}],"
                + " \"scenarios\": [\"a.json\"]}");
        SuiteFile suite = SuiteFile.read(suiteFile);
        Scenario s = scenario(1000, 2000, 3);
        long budget = Main.budgetMs(s);
        assertEquals(budget + 3 * 7000, SuiteRunner.limitMs(s, true, suite, 7000, null));
        long resetBudget = Main.budgetMs(suite.parsedReset) - suite.parsedReset.startupTimeoutMs;
        assertEquals(budget - 1000 + 35_000 + resetBudget + 3 * 7000, SuiteRunner.limitMs(s, false, suite, 7000, null));
    }

    @Test
    public void limitWithTimeoutOption() throws Exception {
        file("a.json", "{\"schemaVersion\": 2, \"name\": \"a\", \"steps\": []}");
        SuiteFile suite = SuiteFile.read(file("suite.json", "{\"app\": \"x\", \"scenarios\": [\"a.json\"]}"));
        Scenario s = scenario(1000, 2000, 3);
        assertEquals(20_000 + 3 * 7000, SuiteRunner.limitMs(s, true, suite, 7000, 20L));
        assertEquals(20_000 + 3 * 7000, SuiteRunner.limitMs(s, false, suite, 7000, 20L));
    }

    @Test
    public void exitCodeRules() {
        List<RunResult> pass = Arrays.asList(result(RunResult.Status.PASS));
        assertEquals(0, SuiteRunner.exitCode(false, pass, false));
        assertEquals(2, SuiteRunner.exitCode(true, pass, false));
        assertEquals(1, SuiteRunner.exitCode(false, Arrays.asList(result(RunResult.Status.PASS),
                result(RunResult.Status.FAIL)), false));
        assertEquals(2, SuiteRunner.exitCode(true, Arrays.asList(result(RunResult.Status.FAIL)), false));
        List<RunResult> noGolden = Arrays.asList(result(RunResult.Status.NO_GOLDEN));
        assertEquals(0, SuiteRunner.exitCode(false, noGolden, false));
        assertEquals(1, SuiteRunner.exitCode(false, noGolden, true));
    }

    // ---- waitForPort ---------------------------------------------------------------------------

    @Test
    public void waitForPortSeesAListener() throws Exception {
        ServerSocket server = new ServerSocket(0, 5, java.net.InetAddress.getByName("127.0.0.1"));
        try {
            assertTrue(SuiteRunner.waitForPort(server.getLocalPort(), 5000, null));
        } finally {
            server.close();
        }
    }

    @Test
    public void waitForPortGivesUpAtTheLimit() throws Exception {
        ServerSocket probe = new ServerSocket(0, 5, java.net.InetAddress.getByName("127.0.0.1"));
        int port = probe.getLocalPort();
        probe.close();
        long t0 = System.currentTimeMillis();
        assertFalse(SuiteRunner.waitForPort(port, 600, null));
        assertTrue(System.currentTimeMillis() - t0 < 3000);
    }

    @Test
    public void waitForPortStopsWhenTheProcessEnds() throws Exception {
        Path log = tmp.getRoot().toPath().resolve("fake.log");
        Process p = Processes.start(Processes.split(FakeCommand.line("exit", "0")), null, log);
        long t0 = System.currentTimeMillis();
        try {
            assertFalse(SuiteRunner.waitForPort(1, 60_000, p));
            assertTrue(System.currentTimeMillis() - t0 < 2000 + 2000);
        } finally {
            Processes.stopTree(p);
        }
    }

    // ---- Main and run() ------------------------------------------------------------------------

    @Test
    public void suiteOptionRefusesOtherOptions() throws Exception {
        PrintStream old = System.err;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setErr(new PrintStream(buf, true, "UTF-8"));
        int code;
        try {
            Map<String, String> o = new LinkedHashMap<String, String>();
            o.put("suite", "s.json");
            o.put("log", "a.log");
            code = Main.run(o);
        } finally {
            System.setErr(old);
        }
        assertEquals(2, code);
        assertTrue(buf.toString("UTF-8"), buf.toString("UTF-8")
                .contains("run: --log is not permitted with --suite; put it in the suite file"));
    }

    @Test
    public void beforeFailureSkipsTheScenariosAndRunsAfter() throws Exception {
        file("a.json", "{\"schemaVersion\": 2, \"name\": \"a\", \"steps\": []}");
        Path marker = tmp.getRoot().toPath().resolve("after.marker");
        Path suiteFile = file("suite.json", "{\"app\": \"x\","
                + " \"before\": [{\"command\": " + q(FakeCommand.line("exit", "1")) + "}],"
                + " \"after\": [{\"command\": " + q(FakeCommand.line("touch", marker.toString())) + "}],"
                + " \"scenarios\": [\"a.json\"]}");
        Path out = tmp.getRoot().toPath().resolve("out");
        SuiteRunner runner = new SuiteRunner(SuiteFile.read(suiteFile), tmp.getRoot().toPath().resolve("agent.jar"),
                out, false, null, null);
        assertEquals(2, runner.run());
        assertFalse(Files.exists(out.resolve("agent-1.cfg")));
        assertTrue(Files.exists(marker));
        assertTrue(Files.exists(out.resolve("before-1.log")));
        assertTrue(Files.exists(out.resolve("suite-result.json")));
        assertTrue(Files.exists(out.resolve("report.html")));
    }

    @Test
    public void afterRunsOnceAndOtherAfterCommandsRunWhenOneFails() throws Exception {
        file("a.json", "{\"schemaVersion\": 2, \"name\": \"a\", \"steps\": []}");
        Path marker = tmp.getRoot().toPath().resolve("after.marker");
        List<String> after = new ArrayList<String>();
        after.add("{\"command\": " + q(FakeCommand.line("exit", "3")) + "}");
        after.add("{\"command\": " + q(FakeCommand.line("touch", marker.toString())) + "}");
        Path suiteFile = file("suite.json", "{\"app\": \"x\","
                + " \"before\": [{\"command\": " + q(FakeCommand.line("exit", "1")) + "}],"
                + " \"after\": [" + after.get(0) + "," + after.get(1) + "],"
                + " \"scenarios\": [\"a.json\"]}");
        SuiteRunner runner = new SuiteRunner(SuiteFile.read(suiteFile), tmp.getRoot().toPath().resolve("agent.jar"),
                tmp.getRoot().toPath().resolve("out"), false, null, null);
        assertEquals(2, runner.run());
        assertTrue(Files.exists(marker));
        assertEquals(1, runner.cleanupCount());
    }

    @Test
    public void labelGoesToTheConfigurationAndTheJobResult() throws Exception {
        Path suiteFile = suiteWithApp(FakeCommand.line("exit", "0"), "");
        Path out = tmp.getRoot().toPath().resolve("out");
        SuiteRunner runner = new SuiteRunner(SuiteFile.read(suiteFile), tmp.getRoot().toPath().resolve("agent.jar"),
                out, false, null, "a");
        assertEquals("a", runner.agentConfig(Arrays.asList("a")).get("label"));
        runner.run();
        smoker.core.model.JobResult j = smoker.core.io.Json.read(out.resolve("job-result.json"),
                smoker.core.model.JobResult.class);
        assertEquals("a", j.label);
        assertEquals("a", j.scenarios.get(0).key);
    }

    private Path suiteWithApp(String appArgs, String extra) throws Exception {
        file("a.json", "{\"schemaVersion\": 2, \"name\": \"a\", \"steps\": []}");
        return file("suite.json", "{\"app\": " + q(appArgs + " {agent}") + ", " + extra + " \"scenarios\": [\"a.json\"]}");
    }

    @Test
    public void applicationThatNeverWritesAResultGivesUpAfterThreeStarts() throws Exception {
        Path marker = tmp.getRoot().toPath().resolve("reset.marker");
        Path suiteFile = suiteWithApp(FakeCommand.line("exit", "0"),
                "\"reset\": [{\"command\": " + q(FakeCommand.line("touch", marker.toString())) + "}],");
        Path out = tmp.getRoot().toPath().resolve("out");
        int code = new SuiteRunner(SuiteFile.read(suiteFile), tmp.getRoot().toPath().resolve("agent.jar"), out, false, null, null).run();
        assertEquals(1, code);
        String json = new String(Files.readAllBytes(out.resolve("suite-result.json")), StandardCharsets.UTF_8);
        assertTrue(json, json.contains("\"applicationStarts\": 3"));
        assertTrue(json, json.contains("(application start)"));
        String report = new String(Files.readAllBytes(out.resolve("report.html")), StandardCharsets.UTF_8);
        assertTrue(report, report.contains("not run: the application did not start 3 times"));
        assertTrue(Files.exists(out.resolve("agent-3.cfg")));
        assertTrue(Files.exists(out.resolve("reset-2-1.log")));
        assertTrue(Files.exists(marker));
        String cfg = new String(Files.readAllBytes(out.resolve("agent-1.cfg")), StandardCharsets.UTF_8);
        assertTrue(cfg, cfg.contains("mode=play"));
        assertTrue(cfg, cfg.contains("scenario.1="));
        assertTrue(cfg, cfg.contains("resetCommand.1="));
        assertTrue(cfg, cfg.contains("aliveFile="));
    }

    @Test
    public void applicationThatHangsIsStoppedAtTheLimit() throws Exception {
        Path suiteFile = suiteWithApp(FakeCommand.line("sleep", "60000"),
                "\"agentConfig\": {\"stopTimeoutMs\": 0},");
        Path out = tmp.getRoot().toPath().resolve("out");
        long t0 = System.currentTimeMillis();
        int code = new SuiteRunner(SuiteFile.read(suiteFile), tmp.getRoot().toPath().resolve("agent.jar"), out, false, 1L, null).run();
        assertEquals(1, code);
        assertTrue(System.currentTimeMillis() - t0 < 30_000);
        String json = new String(Files.readAllBytes(out.resolve("suite-result.json")), StandardCharsets.UTF_8);
        assertTrue(json, json.contains("\"applicationStarts\": 3"));
    }

    @Test
    public void hookBetweenLaunchesStartsNoNewApplication() throws Exception {
        Path suiteFile = suiteWithApp(FakeCommand.line("exit", "0"), "");
        Path out = tmp.getRoot().toPath().resolve("out");
        final SuiteRunner runner = new SuiteRunner(SuiteFile.read(suiteFile),
                tmp.getRoot().toPath().resolve("agent.jar"), out, false, null, null);
        runner.betweenLaunches = new Runnable() {
            @Override
            public void run() {
                runner.cleanup(); // the body of the shutdown hook
            }
        };
        int code = runner.run();
        assertEquals(1, code);
        assertTrue(Files.exists(out.resolve("agent-1.cfg")));
        assertFalse(Files.exists(out.resolve("agent-2.cfg")));
        assertEquals(1, runner.cleanupCount());
        String report = new String(Files.readAllBytes(out.resolve("report.html")), StandardCharsets.UTF_8);
        assertTrue(report, report.contains("not run: the CLI was stopped"));
    }

    @Test
    public void secondCleanupWaitsForTheFirst() throws Exception {
        Path marker = tmp.getRoot().toPath().resolve("slow.marker");
        file("a.json", "{\"schemaVersion\": 2, \"name\": \"a\", \"steps\": []}");
        Path suiteFile = file("suite.json", "{\"app\": \"x\","
                + " \"after\": [{\"command\": " + q(FakeCommand.line("sleeptouch", "1000", marker.toString())) + "}],"
                + " \"scenarios\": [\"a.json\"]}");
        final SuiteRunner runner = new SuiteRunner(SuiteFile.read(suiteFile),
                tmp.getRoot().toPath().resolve("agent.jar"), tmp.getRoot().toPath().resolve("out"), false, null, null);
        Files.createDirectories(tmp.getRoot().toPath().resolve("out"));
        Thread first = new Thread(new Runnable() {
            @Override
            public void run() {
                runner.cleanup();
            }
        });
        first.start();
        long end = System.currentTimeMillis() + 10_000;
        while (runner.cleanupCount() == 0 && System.currentTimeMillis() < end) Thread.sleep(10);
        runner.cleanup();
        assertTrue(Files.exists(marker));
        first.join();
        assertEquals(1, runner.cleanupCount());
    }

    private SuiteResult resultOf(Path out) throws Exception {
        return smoker.core.io.Json.read(out.resolve("suite-result.json"), SuiteResult.class);
    }

    @Test
    public void wrongExecutableGivesExitCode2AndTheReports() throws Exception {
        Path missing = tmp.getRoot().toPath().resolve("no-such-dir").resolve("no-such-program");
        Path suiteFile = suiteWithApp(missing.toString(), "");
        Path out = tmp.getRoot().toPath().resolve("out");
        SuiteRunner runner = new SuiteRunner(SuiteFile.read(suiteFile), tmp.getRoot().toPath().resolve("agent.jar"), out, false, null, null);
        assertEquals(2, runner.run());
        SuiteResult sr = resultOf(out);
        assertEquals(1, sr.scenarios.size());
        assertEquals(RunResult.Status.ERROR, sr.scenarios.get(0).status);
        assertEquals(1, sr.commandErrors.size());
        assertTrue(sr.commandErrors.get(0), sr.commandErrors.get(0).contains("no-such-program"));
        assertTrue(Files.exists(out.resolve("junit.xml")));
        assertTrue(Files.exists(out.resolve("report.html")));
        assertEquals(1, runner.cleanupCount());
    }

    @Test
    public void reportWriteFailureGivesExitCode2WithoutAnException() throws Exception {
        Path suiteFile = suiteWithApp(FakeCommand.line("exit", "0"), "");
        Path out = tmp.getRoot().toPath().resolve("out");
        Files.createDirectories(out.resolve("report.html")); // the HTML write fails
        SuiteRunner runner = new SuiteRunner(SuiteFile.read(suiteFile), tmp.getRoot().toPath().resolve("agent.jar"), out, false, null, null);
        assertEquals(2, runner.run());
        assertEquals(1, runner.cleanupCount());
    }

    @Test
    public void outputFolderThatCannotBeMadeGivesExitCode2() throws Exception {
        Path suiteFile = suiteWithApp(FakeCommand.line("exit", "0"), "");
        Path out = file("blocker", "x"); // a file, not a folder
        SuiteRunner runner = new SuiteRunner(SuiteFile.read(suiteFile), tmp.getRoot().toPath().resolve("agent.jar"), out, false, null, null);
        assertEquals(2, runner.run());
        assertEquals(1, runner.cleanupCount());
    }

    @Test
    public void backgroundProcessThatEndsAfterTheAfterCommandIsNotStopped() throws Exception {
        file("a.json", "{\"schemaVersion\": 2, \"name\": \"a\", \"steps\": []}");
        // The background process (4 s) ends 2 to 3 s after the 'after' command ran, inside the 5 s wait; stopTree reports "no tree" (Java 8).
        Path suiteFile = file("suite.json", "{\"app\": \"x\","
                + " \"before\": [{\"command\": " + q(FakeCommand.line("sleep", "4000")) + ", \"background\": true},"
                + " {\"command\": " + q(FakeCommand.line("exit", "1")) + "}],"
                + " \"after\": [{\"command\": " + q(FakeCommand.line("exit", "0")) + "}],"
                + " \"scenarios\": [\"a.json\"]}");
        Path out = tmp.getRoot().toPath().resolve("out");
        SuiteRunner runner = new SuiteRunner(SuiteFile.read(suiteFile), tmp.getRoot().toPath().resolve("agent.jar"), out, false, null, null);
        final AtomicInteger stops = new AtomicInteger();
        runner.stopper = new SuiteRunner.Stopper() {
            @Override
            public boolean stopTree(Process p) {
                stops.incrementAndGet();
                p.destroyForcibly();
                return false;
            }
        };
        assertEquals(2, runner.run()); // the failed before command
        assertEquals(0, stops.get());
        SuiteResult sr = resultOf(out);
        assertEquals(1, sr.commandErrors.size()); // only the before command; no "cannot stop child processes"
        assertTrue(sr.commandErrors.get(0), sr.commandErrors.get(0).startsWith("before[2]"));
    }
}
