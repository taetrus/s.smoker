package smoker.cli;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import smoker.core.io.Json;
import smoker.core.model.JobResult;
import smoker.core.model.RunResult.Status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ReportTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

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
        java.io.PrintStream old = System.err;
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        System.setErr(new java.io.PrintStream(buf, true));
        int code;
        try {
            code = Report.run(new String[] {"--merge", a.toString(), empty.toString(), "--out", out().toString()});
        } finally {
            System.setErr(old);
        }
        assertEquals(2, code);
        assertTrue(buf.toString(), buf.toString().contains(empty.toString()));
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
}
