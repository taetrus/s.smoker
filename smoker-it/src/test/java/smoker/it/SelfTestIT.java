package smoker.it;

import org.junit.BeforeClass;
import org.junit.Test;
import smoker.core.io.Json;
import smoker.core.model.RunResult;
import smoker.core.model.Scenario;
import smoker.core.model.Step;

import javax.xml.parsers.DocumentBuilderFactory;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The full record-free cycle on the demo application (replaces selftest.sh, F-15).
 * The CLI and the application run in new JVMs. They use the JVM and the locale of this test,
 * so {@code -Plocale-tr} and the JRE that runs Maven also apply to them.
 * Scenario files are copied into the work directory. The test does not change files in the repository.
 */
public class SelfTestIT {

    private static final long PROCESS_TIMEOUT_S = 300;

    private static Path work;
    private static Path cliJar;
    private static Path agentJar;
    private static Path demoJar;
    private static Path demoDir;
    private static String java;

    @BeforeClass
    public static void setUp() throws IOException {
        if (GraphicsEnvironment.isHeadless()) {
            fail("No display. On Linux without a display, run the build under xvfb-run -a.");
        }
        cliJar = existing("smoker.cliJar");
        agentJar = existing("smoker.agentJar");
        demoJar = existing("smoker.demoJar");
        demoDir = existing("smoker.demoDir");
        work = Paths.get(System.getProperty("smoker.workDir")).toAbsolutePath();
        deleteTree(work);
        Files.createDirectories(work);
        java = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        String expected = System.getProperty("smoker.expectLocale", "");
        if (!expected.isEmpty()) assertEquals(expected, Locale.getDefault().toLanguageTag());
    }

    @Test
    public void greetScenarioGivesNoGoldenThenPassThenFailAfterAChange() throws Exception {
        Path dir = Files.createDirectories(work.resolve("greet"));
        Path scenario = dir.resolve("greet.json");
        Files.copy(demoDir.resolve("scenarios/greet.json"), scenario);
        Path log = dir.resolve("demo.log");

        // run1: no golden snapshot yet. NO_GOLDEN does not fail the run without --strict.
        Cli run1 = run(scenario, dir.resolve("run1"), log);
        assertEquals(run1.output, 0, run1.exit);
        RunResult r1 = result(dir.resolve("run1/greet"));
        assertEquals(run1.output, RunResult.Status.NO_GOLDEN, r1.status);
        assertStepStatus(r1, "s001", RunResult.Status.NO_GOLDEN);
        assertStepStatus(r1, "s005", RunResult.Status.NO_GOLDEN);
        assertJUnitXmlIsValid(dir.resolve("run1/junit.xml"));

        // approve: the observed state of each step becomes the golden snapshot.
        Cli approve = cli("approve", "--run", dir.resolve("run1/greet").toString(), "--scenario", scenario.toString());
        assertEquals(approve.output, 0, approve.exit);
        Scenario approved = Json.read(scenario, Scenario.class);
        assertEquals(5, approved.steps.size());
        for (Step s : approved.steps) assertNotNull("golden for " + s.id, s.expected);

        // run2: the same application state gives PASS.
        Cli run2 = run(scenario, dir.resolve("run2"), log);
        assertEquals(run2.output, 0, run2.exit);
        assertEquals(run2.output, RunResult.Status.PASS, result(dir.resolve("run2/greet")).status);
        assertJUnitXmlIsValid(dir.resolve("run2/junit.xml"));
        assertTrue(Files.isRegularFile(dir.resolve("run2/report.html")));

        // run3: select "de" in place of "tr". The golden snapshot for s002 no longer agrees.
        String text = new String(Files.readAllBytes(scenario), StandardCharsets.UTF_8);
        String changed = text.replace("\"value\": \"tr\"", "\"value\": \"de\"");
        assertFalse("scenario contains the value tr", changed.equals(text));
        Path regressed = dir.resolve("greet-regressed.json");
        Files.write(regressed, changed.getBytes(StandardCharsets.UTF_8));
        Cli run3 = run(regressed, dir.resolve("run3"), log);
        assertEquals(run3.output, 1, run3.exit);
        RunResult r3 = result(dir.resolve("run3/greet-regressed"));
        assertEquals(run3.output, RunResult.Status.FAIL, r3.status);
        RunResult.StepResult s002 = assertStepStatus(r3, "s002", RunResult.Status.FAIL);
        assertTrue(String.valueOf(s002.differences),
                contains(s002.differences, "CHANGED ", "/#demo.lang .text: expected=\"tr\" actual=\"de\""));
        assertJUnitXmlIsValid(dir.resolve("run3/junit.xml"));
    }

    @Test
    public void failingScenariosGiveFailOnALogHitAndHangOnABlockedEdt() throws Exception {
        Path dir = Files.createDirectories(work.resolve("failing"));
        Path scenarios = dir.resolve("scenarios");
        Files.createDirectories(scenarios);
        for (String n : Arrays.asList("empty-name.json", "hang.json")) {
            Files.copy(demoDir.resolve("scenarios-failing").resolve(n), scenarios.resolve(n), StandardCopyOption.REPLACE_EXISTING);
        }
        Cli run4 = run(scenarios, dir.resolve("run4"), dir.resolve("demo.log"));
        assertEquals(run4.output, 1, run4.exit);

        RunResult empty = result(dir.resolve("run4/empty-name"));
        assertEquals(run4.output, RunResult.Status.FAIL, empty.status);
        RunResult.StepResult s001 = assertStepStatus(empty, "s001", RunResult.Status.FAIL);
        List<String> lines = new ArrayList<String>();
        for (RunResult.LogHit h : s001.logHits) lines.add(h.source + ":" + h.line);
        assertTrue(String.valueOf(lines), contains(lines, "file:", "greet called with empty name"));

        RunResult hang = result(dir.resolve("run4/hang"));
        assertEquals(run4.output, RunResult.Status.HANG, hang.status);
        RunResult.StepResult h1 = assertStepStatus(hang, "s001", RunResult.Status.HANG);
        assertNotNull("thread dump", h1.threadDump);
        assertTrue("thread dump shows the EDT", h1.threadDump.contains("AWT-EventQueue"));
        assertJUnitXmlIsValid(dir.resolve("run4/junit.xml"));
    }

    // ---- helpers -------------------------------------------------------------------------------

    private static final class Cli {
        int exit;
        String output;
    }

    /** Runs the CLI command {@code run} with the demo application. */
    private static Cli run(Path scenarios, Path out, Path log) throws Exception {
        String app = quote(java) + " " + localeArgs() + " -Ddemo.log=" + quote(log.toString()) + " -jar " + quote(demoJar.toString());
        return cli("run", "--agent", agentJar.toString(), "--app", app, "--scenarios", scenarios.toString(),
                "--out", out.toString(), "--log", log.toString());
    }

    private static Cli cli(String... args) throws Exception {
        List<String> cmd = new ArrayList<String>();
        cmd.add(java);
        cmd.addAll(Arrays.asList(localeArgs().split(" ")));
        cmd.add("-jar");
        cmd.add(cliJar.toString());
        cmd.addAll(Arrays.asList(args));
        File outFile = Files.createTempFile(work, "cli-", ".log").toFile();
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(outFile);
        pb.environment().remove("JAVA_TOOL_OPTIONS");
        Process p = pb.start();
        Cli c = new Cli();
        if (!p.waitFor(PROCESS_TIMEOUT_S, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            fail("CLI did not stop within " + PROCESS_TIMEOUT_S + " s: " + cmd);
        }
        c.exit = p.exitValue();
        c.output = "CLI " + cmd + "\n" + new String(Files.readAllBytes(outFile.toPath()), StandardCharsets.UTF_8);
        return c;
    }

    /** The child JVMs get the locale of this JVM. */
    private static String localeArgs() {
        return "-Duser.language=" + System.getProperty("user.language") + " -Duser.country=" + System.getProperty("user.country", "");
    }

    private static String quote(String s) {
        return "\"" + s + "\"";
    }

    private static RunResult result(Path runDir) throws IOException {
        Path f = runDir.resolve("run-result.json");
        assertTrue("missing " + f, Files.isRegularFile(f));
        return Json.read(f, RunResult.class);
    }

    private static RunResult.StepResult assertStepStatus(RunResult r, String stepId, RunResult.Status status) {
        for (RunResult.StepResult s : r.steps) {
            if (stepId.equals(s.stepId)) {
                assertEquals(r.scenarioName + " " + stepId + " " + s.differences + " " + s.error, status, s.status);
                return s;
            }
        }
        fail(r.scenarioName + " has no step " + stepId);
        return null;
    }

    private static boolean contains(List<String> lines, String prefix, String part) {
        for (String l : lines) if (l.startsWith(prefix) && l.contains(part)) return true;
        return false;
    }

    /** The report must parse as XML, and each time attribute must be a decimal number with a point. */
    private static void assertJUnitXmlIsValid(Path f) throws Exception {
        org.w3c.dom.NodeList all = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f.toFile())
                .getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            String time = ((org.w3c.dom.Element) all.item(i)).getAttribute("time");
            if (!time.isEmpty()) assertTrue(f + " time=\"" + time + "\"", time.matches("\\d+\\.\\d+"));
        }
    }

    private static Path existing(String property) {
        String v = System.getProperty(property);
        assertNotNull("system property " + property, v);
        Path p = Paths.get(v).toAbsolutePath().normalize();
        assertTrue("missing " + p + " — build with mvn verify, not only the IT module", Files.exists(p));
        return p;
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        Stream<Path> s = Files.walk(root);
        try {
            Object[] paths = s.sorted(Comparator.reverseOrder()).toArray();
            for (Object p : paths) Files.delete((Path) p);
        } finally {
            s.close();
        }
    }
}
