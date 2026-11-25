package smoker.core.report;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import smoker.core.model.JobResult;
import smoker.core.model.RunHistory;
import smoker.core.model.RunResult.Status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MatrixReportWriterTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static JobResult.Step step(String id, String group, Status status) {
        JobResult.Step s = new JobResult.Step();
        s.id = id;
        s.name = "name " + id;
        s.group = group;
        s.status = status;
        return s;
    }

    private static JobResult.Scenario scenario(String key, String name, Status status, JobResult.Step... steps) {
        JobResult.Scenario s = new JobResult.Scenario();
        s.key = key;
        s.name = name;
        s.status = status;
        s.steps.addAll(Arrays.asList(steps));
        return s;
    }

    private static MatrixReportWriter.Job job(String column, JobResult.Scenario... scenarios) {
        JobResult r = new JobResult();
        r.label = column;
        r.scenarios.addAll(Arrays.asList(scenarios));
        return new MatrixReportWriter.Job(column, column + "/", r);
    }

    private String page(MatrixReportWriter.Header header, MatrixReportWriter.Job... jobs) throws Exception {
        Path f = tmp.getRoot().toPath().resolve("report.html");
        List<MatrixReportWriter.Job> l = new ArrayList<MatrixReportWriter.Job>(Arrays.asList(jobs));
        MatrixReportWriter.write(f, l, header);
        return new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
    }

    private String page(MatrixReportWriter.Job... jobs) throws Exception {
        return page(new MatrixReportWriter.Header(), jobs);
    }

    @Test
    public void twoColumns() throws Exception {
        String html = page(
                job("jre8", scenario("orders", "Orders", Status.PASS, step("s001", null, Status.PASS), step("s002", null, Status.PASS))),
                job("jre21", scenario("orders", "Orders", Status.FAIL, step("s001", null, Status.PASS), step("s002", null, Status.FAIL))));
        assertTrue(html, html.contains("<th>jre8</th>"));
        assertTrue(html, html.contains("<th>jre21</th>"));
        assertTrue(html, html.contains("<td class='PASS'><a href='jre8/report.html#Orders-s002'>PASS</a></td>"));
        assertTrue(html, html.contains("<td class='FAIL'><a href='jre21/report.html#Orders-s002'>FAIL</a></td>"));
        assertTrue(html, html.contains("href='jre21/report.html#Orders'"));
    }

    @Test
    public void missingScenarioAndStepGiveADash() throws Exception {
        String html = page(
                job("a", scenario("one", "one", Status.PASS, step("s001", null, Status.PASS))),
                job("b", scenario("one", "one", Status.PASS, step("s001", null, Status.PASS), step("s002", null, Status.PASS)),
                        scenario("two", "two", Status.PASS)));
        assertTrue(html, html.indexOf("name s002") > html.indexOf("name s001"));
        assertTrue(html, html.indexOf(">two<") > html.indexOf("name s002"));
        // s002 row: job a has no step, so a dash; the scenario "two" row too
        assertTrue(html, html.matches("(?s).*name s002</td><td>-</td><td class='PASS'>.*"));
        assertTrue(html, html.matches("(?s).*>two</td><td>-</td><td class='PASS'>.*"));
    }

    @Test
    public void sameKeyDifferentNamesIsOneRow() throws Exception {
        String html = page(
                job("a", scenario("orders", "Orders", Status.PASS)),
                job("b", scenario("orders", "orders", Status.PASS)));
        assertEquals(1, html.split("class='scenario'", -1).length - 1);
        assertTrue(html, html.contains("href='a/report.html#Orders'"));
        assertTrue(html, html.contains("href='b/report.html#orders'"));
    }

    @Test
    public void groupRowComesOnce() throws Exception {
        String html = page(job("a", scenario("o", "o", Status.PASS,
                step("s001", "Open", Status.PASS), step("s002", "Open", Status.PASS), step("s003", "Save", Status.PASS))));
        assertEquals(2, html.split("<tr class='group'>", -1).length - 1);
        assertTrue(html.indexOf(">Open<") < html.indexOf("name s001"));
        assertTrue(html.indexOf(">Save<") > html.indexOf("name s002"));
    }

    @Test
    public void noteErrorAndWarningsShowWithTheColumnName() throws Exception {
        JobResult.Scenario s = scenario("o", "o", Status.ERROR);
        s.note = "a <note>";
        s.error = "boom";
        s.warnings.add("look and feel differs");
        String html = page(job("jre8", scenario("o", "o", Status.PASS)), job("jre21", s));
        assertTrue(html, html.contains("jre21: a &lt;note&gt;"));
        assertTrue(html, html.contains("jre21: boom"));
        assertTrue(html, html.contains("jre21: look and feel differs"));
        assertFalse(html, html.contains("jre8: "));
    }

    @Test
    public void environmentRows() throws Exception {
        MatrixReportWriter.Job j = job("jre8", scenario("a", "a", Status.PASS), scenario("b", "b", Status.FAIL));
        j.result.javaVersion = "1.8.0_341";
        j.result.os = "Windows 11";
        j.result.locale = "en-US";
        j.result.timeZone = "UTC";
        MatrixReportWriter.Job k = job("jre21", scenario("a", "a", Status.PASS));
        String html = page(j, k);
        assertTrue(html, html.contains("<th>Java</th><td>1.8.0_341</td>"));
        assertTrue(html, html.contains("<th>OS</th><td>Windows 11</td>"));
        assertTrue(html, html.contains("<th>Locale</th><td>en-US, UTC</td><td></td>"));
        assertTrue(html, html.contains("<th>Scenarios</th><td>1 of 2 PASS</td><td>1 of 1 PASS</td>"));
    }

    @Test
    public void headerShowsRunRefAndHistoryMessage() throws Exception {
        MatrixReportWriter.Header h = new MatrixReportWriter.Header();
        h.runId = "4711";
        h.ref = "master";
        h.time = 1764079320000L;
        h.historyMessage = "history: new file";
        String html = page(h, job("a", scenario("o", "o", Status.PASS)));
        assertTrue(html, html.contains("run 4711, master, 2025-11-25"));
        assertTrue(html, html.contains("history: new file"));
    }

    private static RunHistory history(Status... statuses) {
        RunHistory h = new RunHistory();
        int n = 1;
        for (Status st : statuses) {
            java.util.Map<String, JobResult> by = new java.util.LinkedHashMap<String, JobResult>();
            JobResult r = new JobResult();
            r.scenarios.add(scenario("orders", "Orders", st, step("s001", null, st)));
            by.put("jre8", r);
            h.add(RunHistory.runOf(1764050090000L + n * 60000L, "id" + n, "master", by));
            n++;
        }
        return h;
    }

    private String pageWithHistory(RunHistory h, MatrixReportWriter.Header header) throws Exception {
        Path f = tmp.getRoot().toPath().resolve("report.html");
        List<MatrixReportWriter.Job> l = new ArrayList<MatrixReportWriter.Job>();
        l.add(job("jre8", scenario("orders", "Orders", Status.PASS, step("s001", null, Status.PASS))));
        MatrixReportWriter.write(f, l, h, header);
        return new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
    }

    @Test
    public void historyColumns() throws Exception {
        String html = pageWithHistory(history(Status.PASS, Status.FAIL, Status.PASS), new MatrixReportWriter.Header());
        assertTrue(html, html.contains("<th>Last 20 runs: jre8</th>"));
        // three letters in the scenario row and three in the step row
        assertEquals(html, 6, count(html, "<span class='h PASS' title=") + count(html, "<span class='h FAIL' title="));
        assertTrue(html, html.contains("<span class='h FAIL' title='"));
        assertTrue(html, html.matches("(?s).*<span class='h PASS' title='[^']*id1[^']*master[^']*'>P</span>.*"));
    }

    @Test
    public void unstableMark() throws Exception {
        String unstable = pageWithHistory(history(Status.PASS, Status.FAIL, Status.PASS), new MatrixReportWriter.Header());
        assertTrue(unstable, unstable.contains(">unstable</b>"));
        String stable = pageWithHistory(history(Status.PASS, Status.PASS), new MatrixReportWriter.Header());
        assertFalse(stable, stable.contains(">unstable</b>"));
    }

    @Test
    public void historyMessageShows() throws Exception {
        MatrixReportWriter.Header header = new MatrixReportWriter.Header();
        header.historyMessage = "history: new file";
        assertTrue(pageWithHistory(history(Status.PASS), header).contains("history: new file"));
    }

    @Test
    public void withoutHistoryNoHistoryColumn() throws Exception {
        assertFalse(page(job("a", scenario("one", "one", Status.PASS))).contains("Last 20 runs"));
    }

    @Test
    public void hrefWithAQuoteIsEscaped() throws Exception {
        MatrixReportWriter.Job j = job("a", scenario("one", "one", Status.PASS, step("s001", null, Status.PASS)));
        MatrixReportWriter.Job q = new MatrixReportWriter.Job("a", "file:///x/it's/", j.result);
        String html = page(q);
        assertTrue(html, html.contains("href='file:///x/it%27s/report.html#"));
        assertFalse(html, html.contains("it's"));
    }

    private static int count(String text, String part) {
        int n = 0;
        for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + 1)) n++;
        return n;
    }
}
