package smoker.core.model;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import smoker.core.io.Json;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The job summary that the matrix report reads (WP-07, F-18). */
public class JobResultTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static RunResult.StepResult step(String id, String name, String group, RunResult.Status status) {
        RunResult.StepResult s = new RunResult.StepResult();
        s.stepId = id;
        s.stepName = name;
        s.group = group;
        s.status = status;
        return s;
    }

    private static RunResult orders() {
        RunResult r = new RunResult();
        r.scenarioName = "Orders";
        r.status = RunResult.Status.FAIL;
        r.steps.add(step("s001", "open", "Open", RunResult.Status.PASS));
        r.steps.add(step("s002", "save", null, RunResult.Status.FAIL));
        return r;
    }

    private static RunResult hang() {
        RunResult r = new RunResult();
        r.scenarioName = "Login";
        r.status = RunResult.Status.HANG;
        r.error = "x";
        return r;
    }

    @Test
    public void ofCopiesVerdictsAndKeys() {
        JobResult j = JobResult.of("jre8", Arrays.asList("orders", "login"), Arrays.asList(orders(), hang()));
        assertEquals("jre8", j.label);
        assertEquals(2, j.scenarios.size());
        assertEquals("orders", j.scenarios.get(0).key);
        assertEquals("Orders", j.scenarios.get(0).name);
        assertEquals("Open", j.scenarios.get(0).steps.get(0).group);
        assertEquals("s002", j.scenarios.get(0).steps.get(1).id);
        assertEquals(RunResult.Status.FAIL, j.scenarios.get(0).steps.get(1).status);
        assertEquals(RunResult.Status.HANG, j.scenarios.get(1).status);
        assertEquals("x", j.scenarios.get(1).error);
        assertTrue(j.scenarios.get(1).steps.isEmpty());
    }

    @Test
    public void headerTakesTheFirstValueThatExists() {
        RunResult a = orders();
        a.startedAt = 0;
        a.finishedAt = 50;
        a.os = "Windows 11";
        RunResult b = hang();
        b.javaVersion = "21.0.5";
        b.startedAt = 20;
        b.finishedAt = 90;
        b.lookAndFeel = "Metal";
        JobResult j = JobResult.of(null, Arrays.asList("orders", "login"), Arrays.asList(a, b));
        assertNull(j.label);
        assertEquals("21.0.5", j.javaVersion);
        assertEquals("Windows 11", j.os);
        assertEquals("Metal", j.lookAndFeel);
        assertNull(j.locale);
        assertEquals(20, j.startedAt);
        assertEquals(90, j.finishedAt);
    }

    @Test
    public void roundTrip() throws Exception {
        JobResult j = JobResult.of("jre8", Arrays.asList("orders"), Arrays.asList(orders()));
        Path f = tmp.getRoot().toPath().resolve(JobResult.FILE);
        Json.write(f, j);
        JobResult back = Json.read(f, JobResult.class);
        assertEquals(1, back.version);
        assertEquals("orders", back.scenarios.get(0).key);
        assertEquals(RunResult.Status.FAIL, back.scenarios.get(0).status);
        assertEquals("s001", back.scenarios.get(0).steps.get(0).id);
        Files.write(f, "{\"version\":1,\"scenarios\":[{\"key\":\"a\",\"name\":\"A\",\"status\":\"PASS\"}]}"
                .getBytes(StandardCharsets.UTF_8));
        JobResult old = Json.read(f, JobResult.class);
        assertTrue(old.scenarios.get(0).warnings.isEmpty());
    }
}
