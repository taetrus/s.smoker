package smoker.it;

import org.junit.BeforeClass;
import org.junit.Test;
import smoker.core.io.Json;
import smoker.core.io.ScenarioFiles;
import smoker.core.model.Action;
import smoker.core.model.RunResult;
import smoker.core.model.Scenario;
import smoker.core.model.Snapshot;
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
import java.util.LinkedHashMap;
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

        // approve: the observed state of each step becomes the golden snapshot in greet.golden.json.
        Cli approve = cli("approve", "--run", dir.resolve("run1/greet").toString(), "--scenario", scenario.toString());
        assertEquals(approve.output, 0, approve.exit);
        Path golden = dir.resolve("greet.golden.json");
        assertEquals(golden, ScenarioFiles.goldenPath(scenario));
        assertTrue(approve.output, approve.output.contains("approved 5 step(s) into " + golden));
        assertTrue("missing " + golden, Files.isRegularFile(golden));
        assertFalse("scenario contains expected", read(scenario).contains("\"expected\""));
        assertEquals(Arrays.asList("s001", "s002", "s003", "s004", "s005"), keys(ScenarioFiles.readGolden(scenario)));

        // run2: the same application state gives PASS.
        Cli run2 = run(scenario, dir.resolve("run2"), log);
        assertEquals(run2.output, 0, run2.exit);
        assertEquals(run2.output, RunResult.Status.PASS, result(dir.resolve("run2/greet")).status);
        assertJUnitXmlIsValid(dir.resolve("run2/junit.xml"));
        assertTrue(Files.isRegularFile(dir.resolve("run2/report.html")));

        // A new step has no golden. The steps before it keep their golden and pass.
        Scenario sc = ScenarioFiles.readScenario(scenario);
        sc.steps.add(new Step("s006", "press ESCAPE", Action.key("ESCAPE", 0)));
        Json.write(scenario, sc);
        Cli added = run(scenario, dir.resolve("run-added"), log);
        assertEquals(added.output, 0, added.exit);
        RunResult ra = result(dir.resolve("run-added/greet"));
        for (String id : Arrays.asList("s001", "s002", "s003", "s004", "s005")) assertStepStatus(ra, id, RunResult.Status.PASS);
        assertStepStatus(ra, "s006", RunResult.Status.NO_GOLDEN);

        // A step removed from the scenario leaves the golden file at the next approve.
        sc.steps.remove(sc.findStep("s005"));
        Json.write(scenario, sc);
        Cli approve2 = cli("approve", "--run", dir.resolve("run-added/greet").toString(), "--scenario", scenario.toString());
        assertEquals(approve2.output, 0, approve2.exit);
        LinkedHashMap<String, Snapshot> before = ScenarioFiles.readGolden(scenario);
        assertEquals(Arrays.asList("s001", "s002", "s003", "s004", "s006"), keys(before));

        // run3: select "de" in place of "tr". The golden snapshot for s002 no longer agrees.
        String text = read(scenario);
        String changed = text.replace("\"value\": \"tr\"", "\"value\": \"de\"");
        assertFalse("scenario contains the value tr", changed.equals(text));
        Path regressed = dir.resolve("greet-regressed.json");
        Files.write(regressed, changed.getBytes(StandardCharsets.UTF_8));
        Files.copy(golden, ScenarioFiles.goldenPath(regressed));
        Cli run3 = run(regressed, dir.resolve("run3"), log);
        assertEquals(run3.output, 1, run3.exit);
        RunResult r3 = result(dir.resolve("run3/greet-regressed"));
        assertEquals(run3.output, RunResult.Status.FAIL, r3.status);
        RunResult.StepResult s002 = assertStepStatus(r3, "s002", RunResult.Status.FAIL);
        assertTrue(String.valueOf(s002.differences),
                contains(s002.differences, "CHANGED [#demo.frame]/#demo.lang ", ".text: expected=\"tr\" actual=\"de\""));
        // F-21: the player continues after FAIL, so the steps after s002 also ran.
        for (String id : Arrays.asList("s003", "s004", "s006")) assertStepRan(r3, id);
        assertJUnitXmlIsValid(dir.resolve("run3/junit.xml"));

        // approve --steps s002: only the golden of s002 changes.
        Cli approve3 = cli("approve", "--run", dir.resolve("run3/greet-regressed").toString(),
                "--scenario", regressed.toString(), "--steps", "s002");
        assertEquals(approve3.output, 0, approve3.exit);
        assertTrue(approve3.output, approve3.output.contains("approved 1 step(s) into " + ScenarioFiles.goldenPath(regressed)));
        assertTrue(approve3.output, approve3.output.contains("changed 1 step(s)"));
        assertTrue(approve3.output, approve3.output.contains("  s002: "));
        // The same approve again changes nothing: no step line.
        Cli again = cli("approve", "--run", dir.resolve("run3/greet-regressed").toString(),
                "--scenario", regressed.toString(), "--steps", "s002");
        assertEquals(again.output, 0, again.exit);
        assertTrue(again.output, again.output.contains("changed 0 step(s)"));
        assertFalse(again.output, again.output.contains(" component(s) changed"));
        LinkedHashMap<String, Snapshot> after = ScenarioFiles.readGolden(regressed);
        assertEquals(keys(before), keys(after));
        for (String id : before.keySet()) {
            boolean same = Json.toString(before.get(id)).equals(Json.toString(after.get(id)));
            assertEquals("golden of " + id + " unchanged", !"s002".equals(id), same);
        }

        // One approve of run3 corrects the golden of all steps: the next run gives PASS.
        Cli approve4 = cli("approve", "--run", dir.resolve("run3/greet-regressed").toString(), "--scenario", regressed.toString());
        assertEquals(approve4.output, 0, approve4.exit);
        Cli run5 = run(regressed, dir.resolve("run5"), log);
        assertEquals(run5.output, 0, run5.exit);
        RunResult r5 = result(dir.resolve("run5/greet-regressed"));
        assertEquals(run5.output, RunResult.Status.PASS, r5.status);
        for (String id : Arrays.asList("s001", "s002", "s003", "s004", "s006")) assertStepStatus(r5, id, RunResult.Status.PASS);
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
        // The worst step status: s002 gives ERROR.
        assertEquals(run4.output, RunResult.Status.ERROR, empty.status);
        RunResult.StepResult s001 = assertStepStatus(empty, "s001", RunResult.Status.FAIL);
        List<String> lines = new ArrayList<String>();
        for (RunResult.LogHit h : s001.logHits) lines.add(h.source + ":" + h.line);
        assertTrue(String.valueOf(lines), contains(lines, "file:", "greet called with empty name"));
        // F-21: the player continues after the log hit of s001. The action of s002 (doClick on the EDT) throws
        // the exception of the button listener: ERROR (exception thrown by the action).
        RunResult.StepResult s002 = assertStepStatus(empty, "s002", RunResult.Status.ERROR);
        assertTrue(s002.error, s002.error.contains("IllegalStateException: demo exception on EDT"));

        RunResult hang = result(dir.resolve("run4/hang"));
        assertEquals(run4.output, RunResult.Status.HANG, hang.status);
        RunResult.StepResult h1 = assertStepStatus(hang, "s001", RunResult.Status.HANG);
        assertNotNull("thread dump", h1.threadDump);
        assertTrue("thread dump shows the EDT", h1.threadDump.contains("AWT-EventQueue"));
        assertJUnitXmlIsValid(dir.resolve("run4/junit.xml"));
    }

    @Test
    public void tableScenarioMeetsTheCompletionCriterion() throws Exception {
        Path dir = Files.createDirectories(work.resolve("table"));
        Path scenario = dir.resolve("table.json");
        Files.copy(demoDir.resolve("scenarios/table.json"), scenario);
        Path log = dir.resolve("demo.log");

        // R-25a: a scenario file with 10 steps has fewer than 80 lines.
        assertEquals(10, ScenarioFiles.readScenario(scenario).steps.size());
        assertTrue("table.json lines: " + lines(scenario), lines(scenario) < 80);

        Cli run1 = run(scenario, dir.resolve("run1"), log);
        assertEquals(run1.output, 0, run1.exit);
        Cli approve = cli("approve", "--run", dir.resolve("run1/table").toString(), "--scenario", scenario.toString());
        assertEquals(approve.output, 0, approve.exit);
        // Plan criterion: a golden file for 10 steps has fewer than 150 lines.
        Path golden = ScenarioFiles.goldenPath(scenario);
        assertTrue("table.golden.json lines: " + lines(golden), lines(golden) < 150);

        Cli base = run(scenario, dir.resolve("run-base"), log);
        assertEquals(base.output, 0, base.exit);
        assertEquals(base.output, RunResult.Status.PASS, result(dir.resolve("run-base/table")).status);

        // R-30a: a new unnamed panel causes no difference (mode all).
        // The application logs "extra panel" when it builds the wrapper panel: the PASS is not vacuous.
        Path panelLog = dir.resolve("panel.log");
        Cli panel = run(scenario, dir.resolve("run-panel"), panelLog, "-Ddemo.extraPanel=true");
        assertTrue("option extraPanel had no effect", read(panelLog).contains("extra panel"));
        assertFalse("base run built the extra panel", read(log).contains("extra panel"));
        assertEquals(panel.output, 0, panel.exit);
        assertEquals(panel.output, RunResult.Status.PASS, result(dir.resolve("run-panel/table")).status);
        // R-30a: the same in mode named.
        Path named = dir.resolve("named").resolve("table.json");
        Files.createDirectories(named.getParent());
        Scenario sc = ScenarioFiles.readScenario(scenario);
        sc.compare = "named";
        Json.write(named, sc);
        Cli namedBase = run(named, dir.resolve("run-named-base"), log);
        assertEquals(namedBase.output, 0, namedBase.exit);
        Cli namedApprove = cli("approve", "--run", dir.resolve("run-named-base/table").toString(), "--scenario", named.toString());
        assertEquals(namedApprove.output, 0, namedApprove.exit);
        Path namedLog = dir.resolve("named.log");
        Cli namedPanel = run(named, dir.resolve("run-named"), namedLog, "-Ddemo.extraPanel=true");
        assertTrue("option extraPanel had no effect", read(namedLog).contains("extra panel"));
        assertEquals(namedPanel.output, 0, namedPanel.exit);
        assertEquals(namedPanel.output, RunResult.Status.PASS, result(dir.resolve("run-named/table")).status);

        // R-23b: an incorrect cell value in row 1 causes FAIL.
        Cli bad = run(scenario, dir.resolve("run-bad"), log, "-Ddemo.badCell=true");
        assertEquals(bad.output, 1, bad.exit);
        RunResult rb = result(dir.resolve("run-bad/table"));
        assertEquals(bad.output, RunResult.Status.FAIL, rb.status);
        boolean found = false;
        for (RunResult.StepResult st : rb.steps) {
            for (String d : st.differences) if (d.startsWith("CHANGED [#demo.frame]/#demo.table .cells[0][1]: ")) found = true;
        }
        assertTrue(rb.steps.toString(), found);
    }

    private static long lines(Path f) throws IOException {
        return Files.readAllLines(f, StandardCharsets.UTF_8).size();
    }

    // ---- helpers -------------------------------------------------------------------------------

    private static final class Cli {
        int exit;
        String output;
    }

    /** Runs the CLI command {@code run} with the demo application. */
    private static Cli run(Path scenarios, Path out, Path log, String... demoOptions) throws Exception {
        StringBuilder extra = new StringBuilder();
        for (String o : demoOptions) extra.append(o).append(' ');
        String app = quote(java) + " " + localeArgs() + " " + extra + "-Ddemo.log=" + quote(log.toString()) + " -jar " + quote(demoJar.toString());
        return cli("run", "--agent", agentJar.toString(), "--app", app, "--scenarios", scenarios.toString(),
                "--out", out.toString(), "--log", log.toString());
    }

    private static Cli cli(String... args) throws Exception {
        List<String> cmd = new ArrayList<String>();
        cmd.add(java);
        cmd.addAll(Arrays.asList(localeArgs().split(" ")));
        cmd.add("-jar");
        cmd.add(cliJar.toString());
        for (String a : args) cmd.add(windowsArg(a));
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

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");

    /**
     * On Windows, ProcessBuilder joins the arguments into one command line and does not escape inner quotes.
     * An argument that starts and ends with a quote goes through unchanged, and the C runtime of the child
     * splits it into many arguments. This method escapes each quote as the C runtime expects:
     * 2n backslashes before a quote become 2n + 1 backslashes and the quote.
     * On other systems, and without a quote, the argument does not change.
     */
    static String windowsArg(String s) {
        if (!WINDOWS || s.indexOf('"') < 0) return s;
        StringBuilder sb = new StringBuilder();
        int backslashes = 0;
        for (char c : s.toCharArray()) {
            if (c == '\\') {
                backslashes++;
            } else if (c == '"') {
                for (int i = 0; i < 2 * backslashes + 1; i++) sb.append('\\');
                backslashes = 0;
            } else {
                for (int i = 0; i < backslashes; i++) sb.append('\\');
                backslashes = 0;
            }
            if (c != '\\') sb.append(c);
        }
        // Trailing backslashes stay as they are. The arguments of this test do not end with a backslash.
        for (int i = 0; i < backslashes; i++) sb.append('\\');
        return sb.toString();
    }

    private static String read(Path f) throws IOException {
        return new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
    }

    private static List<String> keys(LinkedHashMap<String, Snapshot> m) {
        return new ArrayList<String>(m.keySet());
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

    private static void assertStepRan(RunResult r, String stepId) {
        for (RunResult.StepResult s : r.steps) if (stepId.equals(s.stepId)) return;
        fail(r.scenarioName + " has no step " + stepId + ": the player stopped early");
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
