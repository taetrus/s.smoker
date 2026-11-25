package smoker.core.model;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import smoker.core.model.RunResult.Status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class RunHistoryTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static RunHistory.Run run(String id, String column, String key, Status scenario, String stepId, Status step) {
        Map<String, JobResult> byColumn = new LinkedHashMap<String, JobResult>();
        if (column != null) {
            JobResult r = new JobResult();
            JobResult.Scenario s = new JobResult.Scenario();
            s.key = key;
            s.status = scenario;
            JobResult.Step st = new JobResult.Step();
            st.id = stepId;
            st.status = step;
            s.steps.add(st);
            r.scenarios.add(s);
            byColumn.put(column, r);
        }
        return RunHistory.runOf(1000L, id, "master", byColumn);
    }

    private static RunHistory of(Status... statuses) {
        RunHistory h = new RunHistory();
        for (Status s : statuses) h.add(s == null ? run(null, "other", "k", Status.PASS, "s001", Status.PASS)
                : run(null, "jre8", "orders", s, "s001", s));
        return h;
    }

    @Test
    public void addKeepsTheLast20() {
        RunHistory h = new RunHistory();
        for (int i = 1; i <= 21; i++) h.add(run(String.valueOf(i), "a", "k", Status.PASS, "s001", Status.PASS));
        assertEquals(20, h.runs.size());
        assertEquals("2", h.runs.get(0).id);
        assertEquals("21", h.runs.get(19).id);
    }

    @Test
    public void sameIdReplaces() {
        RunHistory h = new RunHistory();
        h.add(run("6", "a", "k", Status.PASS, "s001", Status.PASS));
        h.add(run("7", "a", "k", Status.PASS, "s001", Status.PASS));
        h.add(run("8", "a", "k", Status.PASS, "s001", Status.PASS));
        h.add(run("7", "a", "k", Status.FAIL, "s001", Status.FAIL));
        assertEquals(3, h.runs.size());
        assertEquals("7", h.runs.get(1).id);
        assertEquals("FAIL", h.runs.get(1).jobs.get("a").get("k").status);
    }

    @Test
    public void noIdAlwaysAppends() {
        RunHistory h = new RunHistory();
        h.add(run(null, "a", "k", Status.PASS, "s001", Status.PASS));
        h.add(run(null, "a", "k", Status.PASS, "s001", Status.PASS));
        assertEquals(2, h.runs.size());
    }

    @Test
    public void letters() {
        RunHistory h = new RunHistory();
        h.add(run("1", "jre8", "orders", Status.PASS, "s001", Status.PASS));
        h.add(run("2", "jre8", "orders", Status.FAIL, "s001", Status.FAIL));
        h.add(run("3", null, null, null, null, null));
        h.add(run("4", "jre8", "orders", Status.HANG, "s001", Status.HANG));
        h.add(run("5", "jre8", "orders", Status.ERROR, "s001", Status.ERROR));
        h.add(run("6", "jre8", "orders", Status.NO_GOLDEN, "s001", Status.NO_GOLDEN));
        assertEquals("PF-HEN", h.letters("jre8", "orders", "s001"));
        assertEquals("PF-HEN", h.letters("jre8", "orders", null));
        assertEquals("------", h.letters("jre8", "orders", "s999"));
    }

    @Test
    public void unstable() {
        assertTrue(of(Status.PASS, Status.PASS, Status.FAIL).unstable("jre8", "orders", "s001"));
        assertFalse(of(Status.PASS, Status.PASS, Status.PASS).unstable("jre8", "orders", "s001"));
        assertFalse(of(Status.FAIL, Status.FAIL, Status.FAIL).unstable("jre8", "orders", "s001"));
        assertFalse(of(Status.PASS, Status.NO_GOLDEN).unstable("jre8", "orders", "s001"));
        assertTrue(of(Status.PASS, null, Status.HANG).unstable("jre8", "orders", "s001"));
    }

    @Test
    public void loadMissingFile() throws Exception {
        RunHistory.Loaded l = RunHistory.load(tmp.getRoot().toPath().resolve("h.json"));
        assertEquals(0, l.history.runs.size());
        assertEquals("history: new file", l.message);
    }

    @Test
    public void loadDamagedFileMovesIt() throws Exception {
        Path f = tmp.getRoot().toPath().resolve("h.json");
        Files.write(f, "{\"version\":1,\"runs\":[{".getBytes(StandardCharsets.UTF_8));
        Path damaged = tmp.getRoot().toPath().resolve("h.json.damaged");
        Files.write(damaged, "old".getBytes(StandardCharsets.UTF_8));
        RunHistory.Loaded l = RunHistory.load(f);
        assertEquals(0, l.history.runs.size());
        assertEquals("history: the file was damaged, a new history starts", l.message);
        assertFalse(Files.exists(f));
        assertEquals("{\"version\":1,\"runs\":[{", new String(Files.readAllBytes(damaged), StandardCharsets.UTF_8));
    }

    @Test
    public void loadOtherVersionOrNullRunsIsDamaged() throws Exception {
        Path f = tmp.getRoot().toPath().resolve("h.json");
        Files.write(f, "{\"version\":2,\"runs\":[]}".getBytes(StandardCharsets.UTF_8));
        assertEquals("history: the file was damaged, a new history starts", RunHistory.load(f).message);
        assertTrue(Files.exists(tmp.getRoot().toPath().resolve("h.json.damaged")));
        Files.write(f, "{\"version\":1,\"runs\":null}".getBytes(StandardCharsets.UTF_8));
        assertNotNull(RunHistory.load(f).message);
        Files.write(f, "null".getBytes(StandardCharsets.UTF_8));
        assertNotNull(RunHistory.load(f).message);
    }

    @Test
    public void saveThenLoad() throws Exception {
        Path f = tmp.getRoot().toPath().resolve("sub").resolve("h.json");
        RunHistory h = new RunHistory();
        h.add(run("1", "jre8", "orders", Status.PASS, "s001", Status.FAIL));
        h.save(f);
        RunHistory.Loaded l = RunHistory.load(f);
        assertNull(l.message);
        assertEquals(1, l.history.runs.size());
        assertEquals("master", l.history.runs.get(0).ref);
        assertEquals("F", l.history.letters("jre8", "orders", "s001"));
        assertEquals("P", l.history.letters("jre8", "orders", null));
    }
}
