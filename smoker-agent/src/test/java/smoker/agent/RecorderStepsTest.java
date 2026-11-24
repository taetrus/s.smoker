package smoker.agent;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import smoker.core.io.ScenarioFiles;
import smoker.core.model.Action;
import smoker.core.model.Locator;
import smoker.core.model.Scenario;
import smoker.core.model.Snapshot;
import smoker.core.model.Step;

import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Step operations of the recorder: change in place, move, group (WP-06, spec 6 "Events", 7.3, 5.4). */
public class RecorderStepsTest {

    private static final String FILE = "target/recorder-steps-test.json";

    private JFrame frame;
    private Recorder recorder;

    private static AgentConfig recordConfig() {
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put("mode", "record");
        m.put("scenario.1", FILE);
        m.put("screenshots", "off");
        return AgentConfig.fromMap(m, "test");
    }

    @Before
    public void showFrame() throws Exception {
        Assume.assumeFalse("needs a display", GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                frame = new JFrame("Steps");
                JLabel status = new JLabel("idle");
                status.setName("t.status");
                frame.getContentPane().add(status);
                frame.pack();
                frame.setVisible(true);
            }
        });
        recorder = new Recorder(recordConfig(), new Scenario(), new HashMap<String, Snapshot>());
    }

    @After
    public void disposeFrame() throws Exception {
        if (frame == null) return;
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                frame.dispose();
            }
        });
    }

    @Test
    public void moveSwapsAndRemovesBothGoldens() throws Exception {
        Step a = addWithSnapshot("a");
        Step b = addWithSnapshot("b");
        Step c = addWithSnapshot("c");
        move(b, -1);
        assertEquals(java.util.Arrays.asList(b, a, c), recorder.scenario().steps);
        assertFalse(recorder.hasSnapshot(a));
        assertFalse(recorder.hasSnapshot(b));
        assertTrue(recorder.hasSnapshot(c));
        assertTrue(recorder.goldenRemoved(a));
        assertTrue(recorder.goldenRemoved(b));
        assertFalse(recorder.goldenRemoved(c));
    }

    @Test
    public void moveAtTheBorderDoesNothing() throws Exception {
        Step a = addWithSnapshot("a");
        Step b = addWithSnapshot("b");
        move(a, -1);
        move(b, +1);
        assertEquals(java.util.Arrays.asList(a, b), recorder.scenario().steps);
        assertTrue(recorder.hasSnapshot(a));
        assertTrue(recorder.hasSnapshot(b));
        assertFalse(recorder.goldenRemoved(a));
    }

    @Test
    public void moveKeepsIdNameAndGroup() throws Exception {
        addWithSnapshot("a");
        Step b = addWithSnapshot("b");
        addWithSnapshot("c");
        b.group = "G";
        String id = b.id;
        String name = b.name;
        move(b, +1);
        assertEquals(id, b.id);
        assertEquals(name, b.name);
        assertEquals("G", b.group);
    }

    @Test
    public void moveBeforeAWaitForRemovesTheGoldenOfTheStepBeforeIt() throws Exception {
        Step a = addWithSnapshot("a");
        Step b = addWithSnapshot("b");
        final Step[] w = new Step[1];
        onEdt(new Callable<Void>() {
            public Void call() {
                recorder.addWaitFor(Action.waitFor(named("t.status"), "text", "idle", 1000L));
                w[0] = recorder.scenario().steps.get(2);
                return null;
            }
        });
        assertTrue(recorder.hasSnapshot(a));
        move(w[0], -1);
        assertEquals(java.util.Arrays.asList(a, w[0], b), recorder.scenario().steps);
        assertFalse("a is directly before the WAIT_FOR", recorder.hasSnapshot(a));
        assertTrue(recorder.goldenRemoved(a));
    }

    @Test
    public void movedStepGetsNoNewSnapshot() throws Exception {
        addWithSnapshot("a");
        Step b = addWithSnapshot("b");
        move(b, -1);
        Thread.sleep(recorder.scenario().quietMs * 3L);
        assertFalse(recorder.hasSnapshot(b));
    }

    @Test
    public void saveWritesNoGoldenForAMovedStep() throws Exception {
        Step a = addWithSnapshot("a");
        Step b = addWithSnapshot("b");
        Step c = addWithSnapshot("c");
        move(b, -1);
        recorder.save();
        Map<String, Snapshot> golden = ScenarioFiles.readGolden(Paths.get(FILE));
        assertTrue(golden.keySet().toString(), golden.containsKey(c.id));
        assertFalse(golden.containsKey(a.id));
        assertFalse(golden.containsKey(b.id));
    }

    @Test
    public void changedMakesTheStepPendingAgain() throws Exception {
        final Step a = addWithSnapshot("a");
        final String before = a.action.toString();
        onEdt(new Callable<Void>() {
            public Void call() {
                a.action.count = 2;
                recorder.changed(a, before);
                assertFalse(recorder.hasSnapshot(a));
                return null;
            }
        });
        assertEquals(a.action.toString(), a.name);
        assertFalse(before.equals(a.name));
        awaitSnapshot(a, 5000);
    }

    @Test
    public void changedKeepsACustomName() throws Exception {
        final Step a = addWithSnapshot("a");
        a.name = "my name";
        final String before = a.action.toString();
        onEdt(new Callable<Void>() {
            public Void call() {
                a.action.count = 2;
                recorder.changed(a, before);
                return null;
            }
        });
        assertEquals("my name", a.name);
    }

    @Test
    public void changedClearsTheGoldenRemovedMark() throws Exception {
        final Step a = addWithSnapshot("a");
        Step b = addWithSnapshot("b");
        move(b, -1);
        assertTrue(recorder.goldenRemoved(a));
        onEdt(new Callable<Void>() {
            public Void call() {
                recorder.changed(a, a.action.toString());
                return null;
            }
        });
        assertFalse(recorder.goldenRemoved(a));
        awaitSnapshot(a, 5000);
    }

    @Test
    public void previousSnapshotSkipsStepsWithoutOne() throws Exception {
        Step a = addWithSnapshot("a");
        Step b = addWithSnapshot("b");
        Step c = addWithSnapshot("c");
        Snapshot snapA = recorder.snapshot(a);
        move(c, -1);   // order a, c, b; the goldens of b and c are gone
        assertSame(snapA, recorder.previousSnapshot(b));
        assertNull(recorder.previousSnapshot(a));
    }

    @Test
    public void setGroupTrims() throws Exception {
        final Step a = addWithSnapshot("a");
        onEdt(new Callable<Void>() {
            public Void call() {
                recorder.setGroup(a, "  Create order ");
                assertEquals("Create order", a.group);
                recorder.setGroup(a, " ");
                assertNull(a.group);
                return null;
            }
        });
    }

    // ---- helpers -----------------------------------------------------------------------------

    private Step addWithSnapshot(final String text) throws Exception {
        Step s = onEdt(new Callable<Step>() {
            public Step call() {
                return recorder.addForTest(Action.click(textLocator(text)));
            }
        });
        awaitSnapshot(s, 10000);
        return s;
    }

    private void move(final Step s, final int delta) throws Exception {
        onEdt(new Callable<Void>() {
            public Void call() {
                recorder.move(s, delta);
                return null;
            }
        });
    }

    private static Locator textLocator(String text) {
        Locator l = new Locator();
        l.text = text;
        return l;
    }

    private static Locator named(String name) {
        Locator l = new Locator();
        l.name = name;
        return l;
    }

    private void awaitSnapshot(Step s, long ms) throws Exception {
        long deadline = System.currentTimeMillis() + ms;
        while (!recorder.hasSnapshot(s)) {
            if (System.currentTimeMillis() > deadline) fail("no snapshot for " + s.id + " within " + ms + " ms");
            Thread.sleep(50);
        }
    }

    private static <T> T onEdt(Callable<T> c) throws Exception {
        return Quiescence.onEdt(c);
    }
}
