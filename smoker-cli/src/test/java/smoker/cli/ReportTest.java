package smoker.cli;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import smoker.core.io.Json;
import smoker.core.model.JobResult;
import smoker.core.model.RunHistory;
import smoker.core.model.RunResult.Status;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ReportTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final ByteArrayOutputStream outText = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errText = new ByteArrayOutputStream();
    private PrintStream oldOut;
    private PrintStream oldErr;

    @Before
    public void captureStreams() throws Exception {
        oldOut = System.out;
        oldErr = System.err;
        System.setOut(new PrintStream(outText, true, "UTF-8"));
        System.setErr(new PrintStream(errText, true, "UTF-8"));
    }

    @After
    public void restoreStreams() {
        System.setOut(oldOut);
        System.setErr(oldErr);
    }

    private String err() throws Exception {
        return errText.toString("UTF-8");
    }

    private Path jobFolder(String folder, String label) throws Exception {
        Path d = tmp.getRoot().toPath().resolve("jobs").resolve(folder);
        Files.createDirectories(d);
        JobResult r = new JobResult();
        r.label = label;
        JobResult.Scenario s = new JobResult.Scenario();
        s.key = "orders";
        s.name = "Orders";
        s.status = Status.PASS;
        r.scenarios.add(s);
        Json.write(d.resolve(JobResult.FILE), r);
        return d;
    }

    private Path out() {
        return tmp.getRoot().toPath().resolve("merged");
    }

    private String html() throws Exception {
        return new String(Files.readAllBytes(out().resolve("report.html")), StandardCharsets.UTF_8);
    }

    @Test
    public void mergeWritesThePage() throws Exception {
        Path a = jobFolder("x1", "a");
        Path b = jobFolder("x2", "b");
        int code = Report.run(new String[] {"--merge", a.toString(), b.toString(), "--out", out().toString()});
        assertEquals(0, code);
        String html = html();
        assertTrue(html, html.contains("<th>a</th>"));
        assertTrue(html, html.contains("<th>b</th>"));
        assertTrue(html, html.contains("href='../jobs/x1/report.html#Orders'"));
    }

    @Test
    public void columnNameIsTheFolderNameWithoutALabel() throws Exception {
        Path a = jobFolder("folder-a", null);
        assertEquals(0, Report.run(new String[] {"--merge", a.toString(), "--out", out().toString()}));
        assertTrue(html(), html().contains("<th>folder-a</th>"));
    }

    @Test
    public void duplicateColumnGives2() throws Exception {
        Path a = jobFolder("x1", "same");
        Path b = jobFolder("x2", "same");
        assertEquals(2, Report.run(new String[] {"--merge", a.toString(), b.toString(), "--out", out().toString()}));
        assertFalse(Files.exists(out().resolve("report.html")));
    }

    @Test
    public void folderWithoutJobResultGives2AndNamesIt() throws Exception {
        Path a = jobFolder("x1", "a");
        Path empty = tmp.newFolder("empty").toPath();
        int code = Report.run(new String[] {"--merge", a.toString(), empty.toString(), "--out", out().toString()});
        assertEquals(2, code);
        assertTrue(err(), err().contains(empty.toString()));
    }

    @Test
    public void noMergeFolderGives2() throws Exception {
        assertEquals(2, Report.run(new String[] {"--out", out().toString()}));
        assertEquals(2, Report.run(new String[] {"--merge", "--out", out().toString()}));
        Path a = jobFolder("x1", "a");
        assertEquals(2, Report.run(new String[] {"--merge", a.toString()}));
        assertEquals(2, Report.run(new String[] {"--merge", a.toString(), "--out", out().toString(), "--bogus", "1"}));
    }

    @Test
    public void outThatIsAJobFolderGives2() throws Exception {
        Path a = jobFolder("x1", "a");
        assertEquals(2, Report.run(new String[] {"--merge", a.toString(), "--out", a.toString()}));
    }

    @Test
    public void linkIsRelativeToOut() throws Exception {
        Path root = tmp.getRoot().toPath();
        assertEquals("../jobs/a%20b/", Report.link(root.resolve("merged"), root.resolve("jobs").resolve("a b")));
        assertEquals("jobs/x/", Report.link(root, root.resolve("jobs").resolve("x")));
    }

    @Test
    public void linkIsAFileUriWithoutARelativePath() throws Exception {
        Path job = tmp.getRoot().toPath().resolve("jobs").resolve("a b");
        String link = Report.link(tmp.getRoot().toPath().resolve("merged"), job, false);
        assertTrue(link, link.startsWith("file:"));
        assertTrue(link, link.endsWith("/jobs/a%20b/"));
    }

    private int merge(Path a, String... more) {
        String[] base = {"--merge", a.toString(), "--out", out().toString()};
        String[] all = new String[base.length + more.length];
        System.arraycopy(base, 0, all, 0, base.length);
        System.arraycopy(more, 0, all, base.length, more.length);
        return Report.run(all);
    }

    private RunHistory history(Path file) throws Exception {
        return Json.read(file, RunHistory.class);
    }

    @Test
    public void historyGrows() throws Exception {
        Path a = jobFolder("x1", "a");
        Path h = tmp.getRoot().toPath().resolve("h.json");
        for (String id : new String[] {"1", "2", "3"}) {
            assertEquals(0, merge(a, "--history", h.toString(), "--run-id", id, "--ref", "master"));
        }
        assertEquals(3, history(h).runs.size());
        assertEquals("master", history(h).runs.get(2).ref);
        assertEquals(0, merge(a, "--history", h.toString(), "--run-id", "3"));
        assertEquals(3, history(h).runs.size());
        String html = html();
        assertTrue(html, html.contains("Last 20 runs: a"));
        assertTrue(html, html.contains("run 3"));
    }

    @Test
    public void damagedHistoryStillWritesThePage() throws Exception {
        Path a = jobFolder("x1", "a");
        Path h = tmp.getRoot().toPath().resolve("h.json");
        Files.write(h, "{\"version\":1,\"runs\":[{".getBytes(StandardCharsets.UTF_8));
        assertEquals(0, merge(a, "--history", h.toString(), "--run-id", "1"));
        assertTrue(err(), err().contains("history: the file was damaged, a new history starts"));
        assertTrue(Files.exists(tmp.getRoot().toPath().resolve("h.json.damaged")));
        assertTrue(html(), html().contains("history: the file was damaged, a new history starts"));
        assertEquals(1, history(h).runs.size());
    }

    @Test
    public void withoutHistoryNoFile() throws Exception {
        Path a = jobFolder("x1", "a");
        assertEquals(0, merge(a, "--run-id", "1", "--ref", "master"));
        assertFalse(html(), html().contains("Last 20 runs"));
        assertTrue(html(), html().contains("run 1"));
        assertEquals(1, out().toFile().list().length);
    }

    @Test
    public void historyOptionsNeedAValue() throws Exception {
        Path a = jobFolder("x1", "a");
        assertEquals(2, merge(a, "--history"));
        assertEquals(2, merge(a, "--run-id"));
        assertEquals(2, merge(a, "--ref", "--history", "h.json"));
    }

    @Test
    public void jobResultWithNullPartsGives2AndNamesTheFolder() throws Exception {
        String[] bad = {
            "{\"version\":1,\"scenarios\":null}",
            "{\"version\":1,\"scenarios\":[null]}",
            "{\"version\":1,\"scenarios\":[{\"name\":\"x\"}]}",
            "{\"version\":1,\"scenarios\":[{\"key\":\"k\",\"steps\":null}]}",
            "{\"version\":1,\"scenarios\":[{\"key\":\"k\",\"steps\":[null]}]}",
            "{\"version\":1,\"scenarios\":[{\"key\":\"k\",\"steps\":[{\"name\":\"n\"}]}]}",
            "{\"version\":1,\"scenarios\":[{\"key\":\"k\",\"warnings\":null}]}"
        };
        for (int i = 0; i < bad.length; i++) {
            Path d = tmp.newFolder("bad" + i).toPath();
            Files.write(d.resolve(JobResult.FILE), bad[i].getBytes(StandardCharsets.UTF_8));
            errText.reset();
            assertEquals(bad[i], 2, merge(d));
            assertTrue(err(), err().contains(d.toString()));
            assertTrue(err(), err().contains("is not a job result"));
        }
    }
}
