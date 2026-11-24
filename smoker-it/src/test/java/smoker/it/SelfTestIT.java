package smoker.it;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.BeforeClass;
import org.junit.Test;
import smoker.core.io.Json;
import smoker.core.io.ScenarioFiles;
import smoker.core.model.Action;
import smoker.core.model.NodeSnapshot;
import smoker.core.model.RunResult;
import smoker.core.model.Scenario;
import smoker.core.model.Snapshot;
import smoker.core.model.Step;

import javax.xml.parsers.DocumentBuilderFactory;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
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
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
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
        for (String n : Arrays.asList("empty-name.json", "hang.json", "wait-timeout.json")) {
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

        // R-23a: a WAIT_FOR that does not match in time gives FAIL, with the last value in the difference.
        RunResult timeout = result(dir.resolve("run4/wait-timeout"));
        RunResult.StepResult w2 = assertStepStatus(timeout, "s002", RunResult.Status.FAIL);
        assertTrue(String.valueOf(w2.differences), w2.differences.toString().contains("last value: \"waiting\u2026\""));
    }

    @Test
    public void waitForPassesOnADelayedAnswer() throws Exception {
        Path dir = Files.createDirectories(work.resolve("wait"));
        Path scenario = dir.resolve("wait.json");
        Files.copy(demoDir.resolve("scenarios/wait.json"), scenario);
        Path log = dir.resolve("demo.log");

        // run1: the step before a WAIT_FOR has no snapshot. The WAIT_FOR step has no golden yet.
        Cli run1 = run(scenario, dir.resolve("run1"), log);
        assertEquals(run1.output, 0, run1.exit);
        RunResult r1 = result(dir.resolve("run1/wait"));
        RunResult.StepResult c1 = assertStepStatus(r1, "s001", RunResult.Status.PASS);
        assertEquals("step before WAIT_FOR has no snapshot", null, c1.actual);
        assertStepStatus(r1, "s002", RunResult.Status.NO_GOLDEN);

        Cli approve = cli("approve", "--run", dir.resolve("run1/wait").toString(), "--scenario", scenario.toString());
        assertEquals(approve.output, 0, approve.exit);
        assertTrue(approve.output, approve.output.contains("approved 1 step(s)"));
        assertEquals(Arrays.asList("s002"), keys(ScenarioFiles.readGolden(scenario)));
        assertTrue("golden has the answer", read(ScenarioFiles.goldenPath(scenario)).contains("ready 42"));

        // run2: PASS. The wait is real: the demo answers after 3 s.
        Cli run2 = run(scenario, dir.resolve("run2"), log);
        assertEquals(run2.output, 0, run2.exit);
        RunResult r2 = result(dir.resolve("run2/wait"));
        assertEquals(run2.output, RunResult.Status.PASS, r2.status);
        RunResult.StepResult w2 = assertStepStatus(r2, "s002", RunResult.Status.PASS);
        // The 3 s of the demo start at the click of s001, which comes after the start of s001. The match comes at
        // least 3 s after the click, so the two durations together reach 2900 ms.
        RunResult.StepResult c2 = assertStepStatus(r2, "s001", RunResult.Status.PASS);
        assertTrue("s001+s002 durationMs: " + (c2.durationMs + w2.durationMs), c2.durationMs + w2.durationMs >= 2900);
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

    @Test
    public void automaticNamesSurviveARealName() throws Exception {
        Path dir = Files.createDirectories(work.resolve("notes"));
        Path scenario = dir.resolve("notes.json");
        Files.copy(demoDir.resolve("scenarios/notes.json"), scenario);
        Path log = dir.resolve("demo.log");

        // The fields of NotesPanel have no name: the locators use the automatic names.
        Cli run1 = run(scenario, dir.resolve("run1"), log);
        assertEquals(run1.output, 0, run1.exit);
        RunResult r1 = result(dir.resolve("run1/notes"));
        assertEquals(run1.output, RunResult.Status.NO_GOLDEN, r1.status);
        assertStepStatus(r1, "s001", RunResult.Status.NO_GOLDEN);
        assertStepStatus(r1, "s002", RunResult.Status.NO_GOLDEN);
        Cli approve = cli("approve", "--run", dir.resolve("run1/notes").toString(), "--scenario", scenario.toString());
        assertEquals(approve.output, 0, approve.exit);
        assertTrue(read(ScenarioFiles.goldenPath(scenario)).contains("#@NotesPanel.saveButton"));
        Cli run2 = run(scenario, dir.resolve("run2"), log);
        assertEquals(run2.output, 0, run2.exit);
        assertEquals(run2.output, RunResult.Status.PASS, result(dir.resolve("run2/notes")).status);

        // The application adds real names. The locators still find the components.
        // The paths change: each difference is a MISSING or UNEXPECTED path with an automatic or a real name.
        Cli real = run(scenario, dir.resolve("run-real"), log, "-Ddemo.realNames=true");
        assertEquals(real.output, 1, real.exit);
        RunResult rr = result(dir.resolve("run-real/notes"));
        assertEquals(real.output, RunResult.Status.FAIL, rr.status);
        boolean differences = false;
        for (RunResult.StepResult st : rr.steps) {
            assertFalse(real.output + st.error, st.status == RunResult.Status.ERROR);
            for (String d : st.differences) {
                differences = true;
                assertTrue(d, d.startsWith("MISSING ") || d.startsWith("UNEXPECTED "));
                assertTrue(d, d.contains("@NotesPanel.") || d.contains("notes."));
            }
        }
        assertTrue("no difference", differences);
        Cli approveReal = cli("approve", "--run", dir.resolve("run-real/notes").toString(), "--scenario", scenario.toString());
        assertEquals(approveReal.output, 0, approveReal.exit);
        Cli real2 = run(scenario, dir.resolve("run-real2"), log, "-Ddemo.realNames=true");
        assertEquals(real2.output, 0, real2.exit);
        assertEquals(real2.output, RunResult.Status.PASS, result(dir.resolve("run-real2/notes")).status);

        // The lint of the first run proposes the real name that the application uses.
        Path patch = dir.resolve("notes.patch");
        Cli lint = cli("lint", "--run", dir.resolve("run1").toString(), "--emit-patch", patch.toString());
        assertEquals(lint.output, 0, lint.exit);
        assertTrue(lint.output, lint.output.contains("notes.btn.save"));
        assertTrue(lint.output, Pattern.compile("(?m)^\\d+ unnamed interactive component\\(s\\)").matcher(lint.output).find());
        assertTrue(read(patch), read(patch).contains("saveButton.setName(\"notes.btn.save\");"));
    }

    @Test
    public void recorderTypesReplayOnTheDemo() throws Exception {
        Path dir = Files.createDirectories(work.resolve("recorder"));
        Path scenario = dir.resolve("recorder.json");
        Files.copy(demoDir.resolve("scenarios-recorder/recorder.json"), scenario);
        Path log = dir.resolve("demo.log");
        String panel = "-Ddemo.recorderPanel=true";

        Cli run1 = run(scenario, dir.resolve("run1"), log, panel);
        assertEquals(run1.output, 0, run1.exit);
        RunResult r1 = result(dir.resolve("run1/recorder"));
        for (int i = 1; i <= 9; i++) assertStepStatus(r1, String.format("s%03d", i), RunResult.Status.NO_GOLDEN);
        Cli approve = cli("approve", "--run", dir.resolve("run1/recorder").toString(), "--scenario", scenario.toString());
        assertEquals(approve.output, 0, approve.exit);

        LinkedHashMap<String, Snapshot> golden = ScenarioFiles.readGolden(scenario);
        assertEquals("opened Main.java", nodeText(golden.get("s002"), "demo.picked"));
        assertEquals("menu docs", nodeText(golden.get("s004"), "demo.picked"));
        assertEquals("total 10", nodeText(golden.get("s005"), "demo.total"));
        assertEquals("total 0", nodeText(golden.get("s008"), "demo.total"));
        boolean selected = false;
        for (NodeSnapshot n : golden.get("s001").nodes) {
            if ("demo.tree".equals(n.name) && Integer.valueOf(2).equals(n.selectedIndex)) selected = true;
        }
        assertTrue("tree selectedIndex 2 in s001", selected);

        Cli run2 = run(scenario, dir.resolve("run2"), log, panel);
        assertEquals(run2.output, 0, run2.exit);
        RunResult r2 = result(dir.resolve("run2/recorder"));
        assertEquals(run2.output, RunResult.Status.PASS, r2.status);
        for (int i = 1; i <= 9; i++) assertStepStatus(r2, String.format("s%03d", i), RunResult.Status.PASS);
        String report = read(dir.resolve("run2/report.html"));
        assertEquals(3, countOf(report, "<tr class='group'>"));
        assertTrue(report.contains("Tree"));
        assertTrue(report.contains("Table"));
        assertTrue(report.contains("Spinner"));

        // A changed value in s005 gives a difference in demo.total.
        Path changed = dir.resolve("changed").resolve("recorder.json");
        Files.createDirectories(changed.getParent());
        Scenario sc = ScenarioFiles.readScenario(scenario);
        sc.findStep("s005").action.value = "9";
        Json.write(changed, sc);
        Files.copy(ScenarioFiles.goldenPath(scenario), ScenarioFiles.goldenPath(changed));
        Cli run3 = run(changed, dir.resolve("run3"), log, panel);
        assertEquals(run3.output, 1, run3.exit);
        RunResult.StepResult s005 = assertStepStatus(result(dir.resolve("run3/recorder")), "s005", RunResult.Status.FAIL);
        boolean total = false;
        for (String d : s005.differences) if (d.contains("demo.total")) total = true;
        assertTrue(s005.differences.toString(), total);

        // A value that the cell editor refuses gives ERROR.
        Path bad = dir.resolve("bad-cell.json");
        Files.copy(demoDir.resolve("scenarios-failing-recorder/bad-cell.json"), bad);
        Cli runBad = run(bad, dir.resolve("run-bad"), log, panel);
        RunResult.StepResult b = assertStepStatus(result(dir.resolve("run-bad/bad-cell")), "s001", RunResult.Status.ERROR);
        assertTrue(runBad.output + b.error, b.error.contains("the cell editor refused the value \"abc\""));
    }

    private static String nodeText(Snapshot s, String name) {
        assertNotNull("no golden snapshot", s);
        for (NodeSnapshot n : s.nodes) if (name.equals(n.name)) return n.text;
        fail("no node " + name);
        return null;
    }

    private static int countOf(String text, String part) {
        int n = 0;
        for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + part.length())) n++;
        return n;
    }

    @Test
    public void logScenarioGivesOneHitForEachEvent() throws Exception {
        Path dir = Files.createDirectories(work.resolve("log"));
        Path scenario = dir.resolve("log.json");
        Files.copy(demoDir.resolve("scenarios-log/log.json"), scenario);
        Cli run1 = run(scenario, dir.resolve("run1"), dir.resolve("demo.log"), "-Ddemo.logPanel=true");
        RunResult r = result(dir.resolve("run1/log"));
        // R-12a: a JUL error with a 40-line stack trace is one hit with the whole text.
        RunResult.StepResult s001 = assertStepStatus(r, "s001", RunResult.Status.FAIL);
        assertEquals(run1.output + s001.logHits, 1, s001.logHits.size());
        RunResult.LogHit h = s001.logHits.get(0);
        assertEquals("file:demo.log", h.source);
        assertTrue(h.text, h.text.contains("trace demo"));
        assertEquals(h.text, 40, countTraceLines(h.text));
        // R-10a: "Error count 0 of 3" and "Exception handler ready" are no hits.
        RunResult.StepResult s002 = assertStepStatus(r, "s002", RunResult.Status.NO_GOLDEN);
        assertEquals(String.valueOf(s002.logHits), 0, s002.logHits.size());
        // printStackTrace writes to stderr: one hit for the whole trace.
        RunResult.StepResult s003 = assertStepStatus(r, "s003", RunResult.Status.FAIL);
        assertEquals(run1.output + s003.logHits, 1, s003.logHits.size());
        assertEquals("stderr", s003.logHits.get(0).source);
    }

    @Test
    public void exitInAStepNamesTheStep() throws Exception {
        Path dir = Files.createDirectories(work.resolve("exit"));
        Path scenario = dir.resolve("exit.json");
        Files.copy(demoDir.resolve("scenarios-log/exit.json"), scenario);
        Cli run1 = run(scenario, dir.resolve("run1"), dir.resolve("demo.log"), "-Ddemo.logPanel=true");
        assertEquals(run1.output, 1, run1.exit);
        assertTrue(run1.output, run1.output.contains("exit code 7"));
        RunResult r = result(dir.resolve("run1/exit"));
        assertEquals(run1.output, RunResult.Status.ERROR, r.status);
        assertEquals("s001", r.currentStep);
        assertTrue(r.error, r.error.contains("during step s001"));
        RunResult.StepResult s001 = assertStepStatus(r, "s001", RunResult.Status.ERROR);
        boolean found = false;
        for (RunResult.LogHit h : s001.logHits) {
            if (h.source.endsWith("file:demo.log") && h.text.contains("exit on purpose")) found = true;
        }
        assertTrue(String.valueOf(s001.logHits), found);
    }

    @Test
    public void equinoxErrorAtStartupGivesFail() throws Exception {
        Path dir = Files.createDirectories(work.resolve("equinox"));
        Path scenario = dir.resolve("greet.json");
        Files.copy(demoDir.resolve("scenarios/greet.json"), scenario);
        Cli run1 = run(scenario, dir.resolve("run1"), dir.resolve("demo.log"), "-Ddemo.equinoxError=true");
        RunResult r = result(dir.resolve("run1/greet"));
        RunResult.StepResult startup = assertStepStatus(r, "startup", RunResult.Status.FAIL);
        assertEquals(run1.output + startup.logHits, 4, startup.logHits.size());
        int frameworkEvents = 0;
        for (RunResult.LogHit h : startup.logHits) {
            assertEquals("stdout", h.source);
            if (h.text.contains("FrameworkEvent ERROR")) frameworkEvents++;
        }
        assertEquals(String.valueOf(startup.logHits), 2, frameworkEvents);
        for (RunResult.StepResult s : r.steps) assertFalse("s001 ran", "s001".equals(s.stepId));
    }

    /** R-39: the configuration file carries a folder name with ';', ' ' and '='. The compare itself is not the point. */
    @Test
    public void outFolderWithSemicolonAndSpacesWorks() throws Exception {
        Path dir = Files.createDirectories(work.resolve("odd-out"));
        Path scenario = dir.resolve("greet.json");
        Files.copy(demoDir.resolve("scenarios/greet.json"), scenario);
        Path out = dir.resolve("out;1 a=b");
        Cli run1 = run(scenario, out, dir.resolve("demo.log"));
        Path resultFile = out.resolve("greet/run-result.json");
        assertTrue(run1.output, Files.exists(resultFile));
        assertTrue(run1.output, Files.exists(out.resolve("agent-1.cfg")));
        RunResult.Status status = result(out.resolve("greet")).status;
        assertTrue(run1.output, status == RunResult.Status.PASS || status == RunResult.Status.NO_GOLDEN);
    }

    /** WP-05, F-10: the stage 1 closes the frame, the demo deletes its lock file, and the stop ends without System.exit. */
    @Test
    public void cleanStopRemovesTheLockFile() throws Exception {
        Path dir = Files.createDirectories(work.resolve("stop-clean"));
        Path scenario = dir.resolve("greet.json");
        Files.copy(demoDir.resolve("scenarios/greet.json"), scenario);
        Path lock = dir.resolve("lock");
        Cli run1 = run(scenario, dir.resolve("run1"), dir.resolve("demo.log"), "-Ddemo.lockFile=" + quote(lock.toString()));
        assertFalse(run1.output, Files.exists(lock));
        String appOutput = read(dir.resolve("run1/greet/app-output.log"));
        assertTrue(appOutput, appOutput.contains("[smoker] stop: closing the windows"));
        assertFalse(appOutput, appOutput.contains("stop: System.exit"));
    }

    /** WP-05, F-10: the application ignores WINDOW_CLOSING. The stop ends with System.exit, and the result stays complete. */
    @Test
    public void blockedCloseEndsWithExit() throws Exception {
        Path dir = Files.createDirectories(work.resolve("stop-blocked"));
        Path scenario = dir.resolve("greet.json");
        Files.copy(demoDir.resolve("scenarios/greet.json"), scenario);
        Path lock = dir.resolve("lock");
        Cli run1 = runWithStopTimeout("1000", scenario, dir.resolve("run1"), dir.resolve("demo.log"),
                "-Ddemo.lockFile=" + quote(lock.toString()), "-Ddemo.ignoreClose=true");
        assertTrue(run1.output, Files.exists(lock));
        assertTrue(run1.output, result(dir.resolve("run1/greet")).finishedAt > 0);
        String appOutput = read(dir.resolve("run1/greet/app-output.log"));
        assertTrue(appOutput, appOutput.contains("[smoker] stop: System.exit("));
    }

    @Test
    public void approveWithoutRunResultGivesExitCode2() throws Exception {
        Path dir = Files.createDirectories(work.resolve("approve-empty"));
        Path scenario = dir.resolve("greet.json");
        Files.copy(demoDir.resolve("scenarios/greet.json"), scenario);
        Path empty = Files.createDirectories(dir.resolve("empty-run"));
        Cli approve = cli("approve", "--run", empty.toString(), "--scenario", scenario.toString());
        assertEquals(approve.output, 2, approve.exit);
    }


    // ---- WP-05: suite mode (R-36, R-37) -------------------------------------------------------

    /** R-36: 20 scenarios run in one application start; a reset scenario puts the state back between them. */
    @Test
    public void suiteOfTwentyUsesOneStart() throws Exception {
        Path dir = Files.createDirectories(work.resolve("suite-twenty"));
        Path scenarios = Files.createDirectories(dir.resolve("scenarios"));
        List<String> names = new ArrayList<String>();
        for (int i = 1; i <= 20; i++) {
            String name = String.format(Locale.ROOT, "copy-%02d", i);
            names.add(name);
            String text = read(demoDir.resolve("suite/short.json")).replace("\"name\": \"short\"", "\"name\": \"" + name + "\"");
            Files.write(scenarios.resolve(name + ".json"), text.getBytes(StandardCharsets.UTF_8));
        }
        Path reset = dir.resolve("reset.json");
        Files.copy(demoDir.resolve("suite/reset.json"), reset);
        Map<String, Object> suite = suiteBase(dir, suiteApp(dir), "screenshots", "off");
        suite.put("resetScenario", reset.toString());
        suite.put("scenarios", Arrays.asList(scenarios.toString()));
        Path suiteFile = writeSuite(dir.resolve("suite.json"), suite);

        long t0 = System.currentTimeMillis();
        Cli run1 = cli("run", "--agent", agentJar.toString(), "--suite", suiteFile.toString(), "--out", dir.resolve("out").toString());
        assertEquals(run1.output, 0, run1.exit);
        JsonObject r1 = suiteResult(dir.resolve("out"));
        assertEquals(run1.output, 1, r1.get("applicationStarts").getAsInt());
        for (String n : names) assertEntry(run1.output, r1, n, "NO_GOLDEN", 1, null);
        for (String n : names) {
            Cli approve = cli("approve", "--run", dir.resolve("out").resolve(n).toString(), "--scenario", scenarios.resolve(n + ".json").toString());
            assertEquals(approve.output, 0, approve.exit);
        }

        Cli run2 = cli("run", "--agent", agentJar.toString(), "--suite", suiteFile.toString(), "--out", dir.resolve("out").toString());
        assertEquals(run2.output, 0, run2.exit);
        JsonObject r2 = suiteResult(dir.resolve("out"));
        assertEquals(run2.output, 1, r2.get("applicationStarts").getAsInt());
        for (String n : names) assertEntry(run2.output, r2, n, "PASS", 1, null);
        assertTrue(run2.output, run2.output.contains("application starts: 1"));
        System.out.println("suiteOfTwentyUsesOneStart: " + (System.currentTimeMillis() - t0) + " ms");
    }

    /** F-03: a scenario that fails in a shared JVM runs again in a new JVM; the PASS there is the verdict, with a note. */
    @Test
    public void sharedFailureIsRetriedInANewJvm() throws Exception {
        Path dir = Files.createDirectories(work.resolve("suite-retry"));
        Path a = dir.resolve("a.json");
        Path b = dir.resolve("b.json");
        Files.copy(demoDir.resolve("suite/shared-a.json"), a);
        Files.copy(demoDir.resolve("suite/shared-b.json"), b);
        // The golden of b comes from a run of b alone: the name field is empty.
        Cli alone = run(b, dir.resolve("alone"), dir.resolve("alone.log"));
        assertEquals(alone.output, 0, alone.exit);
        Cli approve = cli("approve", "--run", dir.resolve("alone/b").toString(), "--scenario", b.toString());
        assertEquals(approve.output, 0, approve.exit);

        Map<String, Object> suite = suiteBase(dir, suiteApp(dir), "screenshots", "off");
        suite.put("scenarios", Arrays.asList(a.toString(), b.toString()));
        Path suiteFile = writeSuite(dir.resolve("suite.json"), suite);
        long t0 = System.currentTimeMillis();
        Cli run = cli("run", "--agent", agentJar.toString(), "--suite", suiteFile.toString(), "--out", dir.resolve("out").toString());
        assertEquals(run.output, 0, run.exit);
        JsonObject r = suiteResult(dir.resolve("out"));
        assertEquals(run.output, 2, r.get("applicationStarts").getAsInt());
        assertEntry(run.output, r, "a", "NO_GOLDEN", 1, null);
        assertEntry(run.output, r, "b", "PASS", 2, "failed in a shared JVM, passed in a new JVM");
        assertTrue(run.output, Files.isDirectory(dir.resolve("out/b.shared")));
        assertEquals(RunResult.Status.FAIL, result(dir.resolve("out/b.shared")).status);
        assertEquals(RunResult.Status.PASS, result(dir.resolve("out/b")).status);
        assertTrue(run.output, run.output.contains("application starts: 2"));
        System.out.println("sharedFailureIsRetriedInANewJvm: " + (System.currentTimeMillis() - t0) + " ms");
    }

    /** R-37: the simulator starts before the scenarios, the reset command resets it, the after commands end it. */
    @Test
    public void simulatorRunsBeforeResetAndAfter() throws Exception {
        List<Integer> ports = new ArrayList<Integer>();
        try {
            simulatorCheck(ports);
        } finally {
            // A CLI timeout must not leave the simulator JVM: it locks smoker-demo.jar on the Windows runner.
            for (int port : ports) quitSimulator(port);
        }
    }

    private void simulatorCheck(List<Integer> ports) throws Exception {
        Path dir = Files.createDirectories(work.resolve("suite-sim"));
        Path scenarios = Files.createDirectories(dir.resolve("scenarios"));
        Files.copy(demoDir.resolve("suite/sim-a.json"), scenarios.resolve("sim-a.json"));
        Files.copy(demoDir.resolve("suite/sim-b.json"), scenarios.resolve("sim-b.json"));
        Files.write(scenarios.resolve("bad.json"),
                read(demoDir.resolve("suite/sim-a.json")).replace("\"name\": \"sim-a\"", "\"name\": \"bad\"").getBytes(StandardCharsets.UTF_8));
        Path marker = dir.resolve("after-marker");
        Path out = dir.resolve("out");

        // run1: no golden. The counter is 1 for each scenario, because the reset command runs between them.
        int port1 = freePort();
        ports.add(port1);
        Cli run1 = cli("run", "--agent", agentJar.toString(), "--suite", simSuite(dir, scenarios, port1, marker).toString(), "--out", out.toString());
        assertEquals(run1.output, 0, run1.exit);
        assertEquals(run1.output, 1, suiteResult(out).get("applicationStarts").getAsInt());
        for (String n : Arrays.asList("sim-a", "sim-b", "bad")) {
            assertEntry(run1.output, suiteResult(out), n, "NO_GOLDEN", 1, null);
            Cli approve = cli("approve", "--run", out.resolve(n).toString(), "--scenario", scenarios.resolve(n + ".json").toString());
            assertEquals(approve.output, 0, approve.exit);
        }
        assertTrue("after marker of run1", Files.exists(marker));
        assertSimulatorStopped(port1);
        // The golden of bad expects the counter 9: the scenario fails in each JVM.
        Path badGolden = ScenarioFiles.goldenPath(scenarios.resolve("bad.json"));
        String golden = read(badGolden);
        String changed = golden.replaceFirst("\"text\": \"1\"", "\"text\": \"9\"");
        assertFalse("golden of bad has no text 1: " + golden, changed.equals(golden));
        Files.write(badGolden, changed.getBytes(StandardCharsets.UTF_8));
        Files.delete(marker);

        long t0 = System.currentTimeMillis();
        int port2 = freePort();
        ports.add(port2);
        Cli run2 = cli("run", "--agent", agentJar.toString(), "--suite", simSuite(dir, scenarios, port2, marker).toString(), "--out", out.toString());
        assertEquals(run2.output, 1, run2.exit);
        JsonObject r = suiteResult(out);
        assertEntry(run2.output, r, "sim-a", "PASS", 1, null);
        assertEntry(run2.output, r, "bad", "FAIL", 2, "failed in a shared JVM and in a new JVM");
        assertEntry(run2.output, r, "sim-b", "PASS", 1, null);
        // Start 1: sim-a, bad (fails). Start 2: the retry of bad (fails again). Start 3: sim-b. The CLI resets the
        // simulator before starts 2 and 3: without it sim-b would read the counter 2 and fail.
        assertEquals(run2.output, 3, r.get("applicationStarts").getAsInt());
        assertEquals(run2.output, 0, r.getAsJsonArray("commandErrors").size());
        assertTrue("after marker of run2", Files.exists(marker));
        assertSimulatorStopped(port2);
        System.out.println("simulatorRunsBeforeResetAndAfter run2: " + (System.currentTimeMillis() - t0) + " ms");
    }

    /** A before command with exit code 1 stops the suite with exit code 2. The after commands still run. */
    @Test
    public void failingBeforeGivesExitCode2() throws Exception {
        Path dir = Files.createDirectories(work.resolve("suite-before"));
        Path a = dir.resolve("a.json");
        Files.copy(demoDir.resolve("suite/shared-a.json"), a);
        Path marker = dir.resolve("after-marker");
        Map<String, Object> suite = suiteBase(dir, suiteApp(dir), "screenshots", "off");
        suite.put("before", Arrays.asList(command(simulator("--exit 1"))));
        suite.put("after", Arrays.asList(command(simulator("--touch " + quote(marker.toString())))));
        suite.put("scenarios", Arrays.asList(a.toString()));
        Path out = dir.resolve("out");
        Cli run = cli("run", "--agent", agentJar.toString(), "--suite", writeSuite(dir.resolve("suite.json"), suite).toString(), "--out", out.toString());
        assertEquals(run.output, 2, run.exit);
        assertFalse(run.output, Files.exists(out.resolve("agent-1.cfg")));
        assertTrue(run.output, Files.exists(marker));
        assertEquals(run.output, 0, suiteResult(out).get("applicationStarts").getAsInt());
        assertTrue(run.output, suiteResult(out).getAsJsonArray("commandErrors").toString().contains("before[1]"));
    }

    // ---- helpers for the suite checks ---------------------------------------------------------

    /** The application command line of a suite: the CLI puts the agent option after the first word. */
    private static String suiteApp(Path dir, String... demoOptions) {
        StringBuilder extra = new StringBuilder();
        for (String o : demoOptions) extra.append(o).append(' ');
        return quote(java) + " " + localeArgs() + " " + extra + "-Ddemo.log=" + quote(dir.resolve("demo.log").toString())
                + " -jar " + quote(demoJar.toString());
    }

    private static Map<String, Object> suiteBase(Path dir, String app, String... agentConfig) {
        Map<String, Object> suite = new LinkedHashMap<String, Object>();
        suite.put("app", app);
        suite.put("workDir", dir.toString());
        Map<String, Object> cfg = new LinkedHashMap<String, Object>();
        cfg.put("log", dir.resolve("demo.log").toString());
        for (int i = 0; i + 1 < agentConfig.length; i += 2) cfg.put(agentConfig[i], agentConfig[i + 1]);
        suite.put("agentConfig", cfg);
        return suite;
    }

    private static Path writeSuite(Path file, Map<String, Object> suite) throws IOException {
        Json.write(file, suite);
        return file;
    }

    private static Map<String, Object> command(String command) {
        Map<String, Object> c = new LinkedHashMap<String, Object>();
        c.put("command", command);
        c.put("timeoutS", 30);
        return c;
    }

    /** A command line of the demo simulator class, with the JVM of this test. */
    private static String simulator(String args) {
        return quote(java) + " -cp " + quote(demoJar.toString()) + " smoker.demo.DemoSimulator " + args;
    }

    /** The suite of the simulator check: the script starts the simulator, the reset command resets it. */
    private static Path simSuite(Path dir, Path scenarios, int port, Path marker) throws IOException {
        Map<String, Object> suite = suiteBase(dir, suiteApp(dir, "-Ddemo.simPort=" + port), "screenshots", "off");
        String script = demoDir.resolve(WINDOWS ? "sim/start-sim.bat" : "sim/start-sim.sh").toString();
        Map<String, Object> before = command((WINDOWS ? "" : "sh ") + quote(script) + " " + quote(demoJar.toString()) + " " + port);
        before.put("background", true);
        before.put("readyPort", port);
        before.put("readyTimeoutS", 30);
        suite.put("before", Arrays.asList(before));
        suite.put("reset", Arrays.asList(command(simulator("--send " + port + " RESET"))));
        suite.put("after", Arrays.asList(command(simulator("--touch " + quote(marker.toString()))),
                command(simulator("--send " + port + " QUIT"))));
        // The order matters: a scenario that fails first in a JVM is not retried, and sim-b after bad needs a retry launch.
        suite.put("scenarios", Arrays.asList(scenarios.resolve("sim-a.json").toString(),
                scenarios.resolve("bad.json").toString(), scenarios.resolve("sim-b.json").toString()));
        return writeSuite(dir.resolve("suite-" + port + ".json"), suite);
    }

    /** Sends QUIT to the simulator. Errors are ignored: the simulator is usually stopped already. */
    private static void quitSimulator(int port) {
        try {
            java.net.Socket sock = new java.net.Socket();
            try {
                sock.connect(new java.net.InetSocketAddress("127.0.0.1", port), 1000);
                sock.getOutputStream().write("QUIT\n".getBytes(StandardCharsets.US_ASCII));
                sock.getOutputStream().flush();
            } finally {
                sock.close();
            }
        } catch (IOException e) {
            // ignored
        }
    }

    private static int freePort() throws IOException {
        ServerSocket s = new ServerSocket(0);
        try {
            return s.getLocalPort();
        } finally {
            s.close();
        }
    }

    private static void assertSimulatorStopped(int port) throws Exception {
        long end = System.currentTimeMillis() + 5000;
        while (true) {
            Socket s = new Socket();
            try {
                s.connect(new InetSocketAddress("127.0.0.1", port), 500);
            } catch (IOException e) {
                return; // nobody listens
            } finally {
                s.close();
            }
            if (System.currentTimeMillis() > end) fail("the simulator still listens on port " + port);
            Thread.sleep(200);
        }
    }

    private static JsonObject suiteResult(Path out) throws IOException {
        Path f = out.resolve("suite-result.json");
        assertTrue("missing " + f, Files.isRegularFile(f));
        return Json.readTree(f).getAsJsonObject();
    }

    private static void assertEntry(String output, JsonObject suiteResult, String name, String status, int attempts, String note) {
        JsonArray list = suiteResult.getAsJsonArray("scenarios");
        for (JsonElement e : list) {
            JsonObject o = e.getAsJsonObject();
            if (!name.equals(o.get("name").getAsString())) continue;
            assertEquals(output + name, status, o.get("status").getAsString());
            assertEquals(output + name, attempts, o.get("attempts").getAsInt());
            String actual = o.has("note") && !o.get("note").isJsonNull() ? o.get("note").getAsString() : null;
            assertEquals(output + name, note, actual);
            return;
        }
        fail(output + "suite-result.json has no scenario " + name + ": " + list);
    }

    private static int countTraceLines(String text) {
        int n = 0;
        for (String l : text.split("\n")) if (l.startsWith("\tat ")) n++;
        return n;
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
        return runWithStopTimeout(null, scenarios, out, log, demoOptions);
    }

    /** As {@link #run}; a non-null {@code stopTimeoutMs} goes to the CLI as {@code --stopTimeoutMs}. */
    private static Cli runWithStopTimeout(String stopTimeoutMs, Path scenarios, Path out, Path log, String... demoOptions)
            throws Exception {
        StringBuilder extra = new StringBuilder();
        for (String o : demoOptions) extra.append(o).append(' ');
        String app = quote(java) + " " + localeArgs() + " " + extra + "-Ddemo.log=" + quote(log.toString()) + " -jar " + quote(demoJar.toString());
        List<String> args = new ArrayList<String>(Arrays.asList("run", "--agent", agentJar.toString(), "--app", app,
                "--scenarios", scenarios.toString(), "--out", out.toString(), "--log", log.toString()));
        if (stopTimeoutMs != null) args.addAll(Arrays.asList("--stopTimeoutMs", stopTimeoutMs));
        return cli(args.toArray(new String[0]));
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
        pb.environment().put("SMOKER_JAVA", java); // start-sim.sh and start-sim.bat use the JVM of this test
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
