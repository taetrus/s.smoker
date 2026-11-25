package smoker.agent;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import smoker.core.model.RunResult;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** The window size of the configuration (WP-07, R-34). Skipped without a display. */
public class ConditionsWindowTest {

    private JFrame frame;

    @Before
    public void display() {
        Assume.assumeFalse(GraphicsEnvironment.isHeadless());
        Conditions.resetForTest();
    }

    @After
    public void dispose() throws Exception {
        Conditions.resetForTest();
        if (frame == null) return;
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                frame.dispose();
            }
        });
    }

    private static AgentConfig cfg(String size) {
        Map<String, String> m = new HashMap<String, String>();
        m.put("scenario.1", "a.json");
        m.put("windowSize", size);
        return AgentConfig.fromMap(m, "test");
    }

    private void show(final int w, final int h, final Dimension minimum, final boolean maximized) throws Exception {
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                frame = new JFrame("conditions");
                frame.setName("conditions.frame");
                if (minimum != null) frame.setMinimumSize(minimum);
                frame.setSize(w, h);
                frame.setVisible(true);
                if (maximized) frame.setExtendedState(Frame.MAXIMIZED_BOTH);
            }
        });
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline && !Player.mainWindowShown(1000)) Thread.sleep(50);
        Quiescence.await(300, 3000);
    }

    private static boolean screenHolds(int w, int h) {
        Rectangle b = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        return b.width >= w && b.height >= h;
    }

    @Test
    public void maximizedFrameGetsNormalStateAndTheSize() throws Exception {
        show(400, 300, null, true);
        AgentConfig c = cfg("640x480");
        Conditions.applyWindowSize(c);
        assertEquals(Frame.NORMAL, frame.getExtendedState() & Frame.MAXIMIZED_BOTH);
        Assume.assumeTrue(screenHolds(640, 480));
        assertEquals(new Dimension(640, 480), frame.getSize());
        RunResult r = new RunResult();
        Conditions.describe(c, r);
        assertEquals("640x480", r.windowSize);
        assertEquals(Collections.<String>emptyList(), r.warnings);
    }

    @Test
    public void minimumSizeGivesAWarning() throws Exception {
        Assume.assumeTrue(screenHolds(800, 600));
        show(800, 600, new Dimension(800, 600), false);
        AgentConfig c = cfg("320x200");
        Conditions.applyWindowSize(c);
        RunResult r = new RunResult();
        Conditions.describe(c, r);
        assertEquals("800x600", r.windowSize);
        assertEquals(Collections.singletonList("window size is 800x600, not the configured 320x200"), r.warnings);
    }

    @Test
    public void secondCallDoesNotResize() throws Exception {
        Assume.assumeTrue(screenHolds(640, 480));
        show(400, 300, null, false);
        AgentConfig c = cfg("640x480");
        Conditions.applyWindowSize(c);
        assertEquals(new Dimension(640, 480), frame.getSize());
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                frame.setSize(500, 350);
            }
        });
        Quiescence.await(300, 3000);
        Conditions.applyWindowSize(c);
        assertEquals(new Dimension(500, 350), frame.getSize());
    }
}
