package smoker.cli;

import org.junit.Test;
import smoker.core.model.RunResult;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class SuitePlanTest {

    private static RunResult.StepResult step(String id, RunResult.Status st) {
        RunResult.StepResult s = new RunResult.StepResult();
        s.stepId = id;
        s.status = st;
        return s;
    }

    private static RunResult res(String name, RunResult.Status st, boolean shared) {
        RunResult r = new RunResult();
        r.scenarioName = name;
        r.status = st;
        r.sharedJvm = shared;
        r.finishedAt = 1;
        r.steps.add(step(shared ? "reset" : "startup", RunResult.Status.PASS));
        return r;
    }

    private static Map<String, RunResult> map(RunResult... rs) {
        Map<String, RunResult> m = new HashMap<String, RunResult>();
        for (RunResult r : rs) m.put(r.scenarioName, r);
        return m;
    }

    private static List<String> l(String... s) {
        return Arrays.asList(s);
    }

    private static RunResult startupFailure(String name) {
        RunResult r = res(name, RunResult.Status.FAIL, false);
        r.steps.get(0).status = RunResult.Status.FAIL;
        return r;
    }

    @Test
    public void allPassInOneLaunch() {
        SuitePlan p = new SuitePlan(l("a", "b"));
        List<String> launch = p.launchList();
        assertEquals(l("a", "b"), launch);
        assertTrue(p.record(launch, map(res("a", RunResult.Status.PASS, false), res("b", RunResult.Status.NO_GOLDEN, true))).isEmpty());
        assertTrue(p.done());
        assertEquals(1, p.applicationStarts());
        assertEquals(RunResult.Status.NO_GOLDEN, p.verdict("b").status);
    }

    @Test
    public void sharedFailureIsRetriedFirst() {
        SuitePlan p = new SuitePlan(l("a", "b", "c"));
        List<String> launch = p.launchList();
        List<String> move = p.record(launch, map(res("a", RunResult.Status.PASS, false), res("b", RunResult.Status.FAIL, true)));
        assertEquals(l("b"), move);
        assertFalse(p.done());
        assertNull(p.verdict("b"));
        assertEquals(l("b", "c"), p.launchList());
    }

    @Test
    public void retryThatPassesGetsTheNote() {
        SuitePlan p = new SuitePlan(l("a", "b"));
        p.record(p.launchList(), map(res("a", RunResult.Status.PASS, false), res("b", RunResult.Status.FAIL, true)));
        p.record(p.launchList(), map(res("b", RunResult.Status.PASS, false)));
        assertTrue(p.done());
        assertEquals(RunResult.Status.PASS, p.verdict("b").status);
        assertEquals("failed in a shared JVM, passed in a new JVM", p.verdict("b").note);
        assertEquals(2, p.applicationStarts());
        assertNull(p.verdict("a").note);
    }

    @Test
    public void retryThatFailsGetsTheSecondNote() {
        SuitePlan p = new SuitePlan(l("a", "b"));
        p.record(p.launchList(), map(res("a", RunResult.Status.PASS, false), res("b", RunResult.Status.FAIL, true)));
        p.record(p.launchList(), map(res("b", RunResult.Status.FAIL, false)));
        assertTrue(p.done());
        assertEquals(RunResult.Status.FAIL, p.verdict("b").status);
        assertEquals("failed in a shared JVM and in a new JVM", p.verdict("b").note);
    }

    @Test
    public void firstScenarioFailureIsAVerdict() {
        SuitePlan p = new SuitePlan(l("a", "b"));
        assertTrue(p.record(p.launchList(), map(res("a", RunResult.Status.FAIL, false))).isEmpty());
        assertEquals(RunResult.Status.FAIL, p.verdict("a").status);
        assertNull(p.verdict("a").note);
        assertEquals(l("b"), p.launchList());
    }

    @Test
    public void notRunScenarioStaysWithoutVerdict() {
        SuitePlan p = new SuitePlan(l("a", "b", "c"));
        p.record(p.launchList(), map(res("a", RunResult.Status.PASS, false), res("b", RunResult.Status.PASS, true)));
        assertNull(p.verdict("c"));
        assertFalse(p.done());
        assertEquals(l("c"), p.launchList());
    }

    @Test
    public void startupFailureIsNotAVerdict() {
        SuitePlan p = new SuitePlan(l("a", "b"));
        assertTrue(p.record(p.launchList(), map(startupFailure("a"))).isEmpty());
        assertNull(p.verdict("a"));
        assertEquals(1, p.startupFailuresInSequence());
        assertEquals(l("a", "b"), p.launchList());
        // a missing result and a stop during startup count too
        p.record(l("a", "b"), Collections.<String, RunResult>emptyMap());
        assertEquals(2, p.startupFailuresInSequence());
        RunResult stopped = res("a", RunResult.Status.ERROR, false);
        stopped.currentStep = "startup";
        p.launchList();
        p.record(l("a", "b"), map(stopped));
        assertEquals(3, p.startupFailuresInSequence());
    }

    @Test
    public void threeStartupFailuresGiveUp() {
        SuitePlan p = new SuitePlan(l("a", "b", "c"));
        p.record(p.launchList(), map(res("a", RunResult.Status.PASS, false), res("b", RunResult.Status.FAIL, true)));
        RunResult shared = p.verdict("b");
        assertNull(shared);
        for (int i = 0; i < 3; i++) {
            assertFalse(p.done());
            p.record(p.launchList(), map(startupFailure("b")));
        }
        assertTrue(p.done());
        assertEquals(RunResult.Status.ERROR, p.verdict("c").status);
        assertEquals("not run: the application did not start 3 times", p.verdict("c").error);
        assertEquals(RunResult.Status.FAIL, p.verdict("b").status);
        assertEquals("failed in a shared JVM; the application did not start for the retry", p.verdict("b").note);
        RunResult e = p.applicationStartEntry();
        assertEquals("(application start)", e.scenarioName);
        assertEquals("startup", e.steps.get(0).stepId);
        assertEquals(RunResult.Status.FAIL, e.steps.get(0).status);
    }

    @Test
    public void passingStartupResetsTheCount() {
        SuitePlan p = new SuitePlan(l("a", "b"));
        p.record(p.launchList(), map(startupFailure("a")));
        p.record(p.launchList(), map(startupFailure("a")));
        assertEquals(2, p.startupFailuresInSequence());
        p.record(p.launchList(), map(res("a", RunResult.Status.PASS, false)));
        assertEquals(0, p.startupFailuresInSequence());
        assertEquals(RunResult.Status.PASS, p.verdict("a").status);
    }

    @Test
    public void giveUpWithResetFailure() {
        SuitePlan p = new SuitePlan(l("a", "b", "c"));
        RunResult shared = res("b", RunResult.Status.ERROR, true);
        p.record(p.launchList(), map(res("a", RunResult.Status.PASS, false), shared));
        p.giveUp("not run: reset failed", "failed in a shared JVM; no retry");
        assertTrue(p.done());
        assertSame(shared, p.verdict("b"));
        assertEquals("failed in a shared JVM; no retry", p.verdict("b").note);
        assertEquals("not run: reset failed", p.verdict("c").error);
        assertEquals(RunResult.Status.PASS, p.verdict("a").status);
    }

    @Test
    public void attemptsCount() {
        SuitePlan p = new SuitePlan(l("a", "b", "c"));
        p.record(p.launchList(), map(res("a", RunResult.Status.PASS, false), res("b", RunResult.Status.FAIL, true)));
        p.record(p.launchList(), map(res("b", RunResult.Status.PASS, false), res("c", RunResult.Status.PASS, true)));
        assertEquals(1, p.attempts("a"));
        assertEquals(2, p.attempts("b"));
        assertEquals(1, p.attempts("c"));
        assertEquals(0, p.attempts("zz"));
    }
}
