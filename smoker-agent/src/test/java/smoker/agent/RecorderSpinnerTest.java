package smoker.agent;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import smoker.core.model.Action;
import smoker.core.model.Scenario;
import smoker.core.model.Snapshot;
import smoker.core.model.Step;

import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;
import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.DefaultKeyboardFocusManager;
import java.awt.GraphicsEnvironment;
import java.awt.KeyboardFocusManager;
import java.awt.event.FocusEvent;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * WP-06 spec 6.5: the recorder records a spinner value as SET_TEXT on the spinner. Real Swing components get
 * synthetic events. Skipped without a display (Linux: use xvfb-run).
 */
public class RecorderSpinnerTest {

    private JFrame frame;
    private JSpinner count;
    private JSpinner other;
    private JSpinner custom;
    private KeyboardFocusManager previousFocusManager;
    private Recorder recorder;
    private final List<String> statuses = Collections.synchronizedList(new ArrayList<String>());

    private static AgentConfig recordConfig() {
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put("mode", "record");
        m.put("scenario.1", "target/recorder-spinner-test.json");
        m.put("screenshots", "off");
        return AgentConfig.fromMap(m, "test");
    }

    @Before
    public void showFrame() throws Exception {
        Assume.assumeFalse("needs a display", GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                count = new JSpinner(new SpinnerNumberModel(1, 0, 10, 1));
                count.setName("t.count");
                other = new JSpinner(new SpinnerNumberModel(1, 0, 10, 1));
                other.setName("t.other");
                custom = new JSpinner(new SpinnerNumberModel(1, 0, 10, 1));
                custom.setName("t.custom");
                custom.setEditor(new JLabel("x"));
                JButton ok = new JButton("OK");
                ok.setName("t.ok");
                frame = new JFrame("RecorderSpinnerTest");
                JPanel p = new JPanel();
                p.add(count);
                p.add(other);
                p.add(custom);
                p.add(ok);
                frame.getContentPane().add(p);
                frame.pack();
                frame.setVisible(true);
            }
        });
        previousFocusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager();
        KeyboardFocusManager.setCurrentKeyboardFocusManager(new DirectFocus());
        recorder = new Recorder(recordConfig(), new Scenario(), new HashMap<String, Snapshot>());
        recorder.setListener(new Recorder.Listener() {
            public void stepAdded(Step s) {}
            public void stepUpdated(Step s) {}
            public void status(String text) {
                statuses.add(text);
            }
        });
        recorder.start();
    }

    @After
    public void disposeFrame() throws Exception {
        if (recorder != null) recorder.close();
        if (recorder != null) recorder.pause();
        if (previousFocusManager != null) KeyboardFocusManager.setCurrentKeyboardFocusManager(previousFocusManager);
        if (frame == null) return;
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                frame.dispose();
            }
        });
    }

    @Test
    public void arrowClickRecordsSetTextOnTheSpinner() throws Exception {
        arrow(count, true);
        List<Step> steps = steps();
        assertEquals(describe(steps), 1, steps.size());
        assertEquals(Action.Type.SET_TEXT, steps.get(0).action.type);
        assertEquals("t.count", steps.get(0).action.locator.name);
        assertEquals("2", steps.get(0).action.value);
    }

    @Test
    public void tenArrowClicksGiveOneStep() throws Exception {
        for (int i = 0; i < 10; i++) arrow(count, true);
        List<Step> steps = steps();
        assertEquals(describe(steps), 1, steps.size());
        assertEquals("10", steps.get(0).action.value);
        awaitSnapshot(steps.get(0), 10000);
        boolean found = false;
        for (smoker.core.model.NodeSnapshot n : recorder.snapshot(steps.get(0)).nodes) {
            if ("t.count".equals(n.name)) {
                assertEquals("10", n.text);
                found = true;
            }
        }
        assertTrue("no node t.count", found);
        assertEquals("10", field(count).getText());
    }

    @Test
    public void arrowsOnTwoSpinnersGiveTwoSteps() throws Exception {
        arrow(count, true);
        arrow(other, true);
        arrow(count, true);
        List<Step> steps = steps();
        assertEquals(describe(steps), 3, steps.size());
        assertEquals("t.count", steps.get(0).action.locator.name);
        assertEquals("t.other", steps.get(1).action.locator.name);
        assertEquals("t.count", steps.get(2).action.locator.name);
        assertEquals("3", steps.get(2).action.value);
    }

    @Test
    public void typedTextRecordsOnTheSpinner() throws Exception {
        JTextComponent field = field(count);
        dispatch(new FocusEvent(field, FocusEvent.FOCUS_GAINED));
        setText(field, "7");
        dispatch(new FocusEvent(field, FocusEvent.FOCUS_LOST));
        List<Step> steps = steps();
        assertEquals(describe(steps), 1, steps.size());
        assertEquals(Action.Type.SET_TEXT, steps.get(0).action.type);
        assertEquals("t.count", steps.get(0).action.locator.name);
        assertEquals("7", steps.get(0).action.value);
    }

    @Test
    public void typedThenArrow() throws Exception {
        JTextComponent field = field(count);
        dispatch(new FocusEvent(field, FocusEvent.FOCUS_GAINED));
        setText(field, "7");
        arrow(count, true);
        List<Step> steps = steps();
        assertEquals(describe(steps), 1, steps.size());
        assertEquals("t.count", steps.get(0).action.locator.name);
        assertEquals("8", steps.get(0).action.value);
    }

    @Test
    public void arrowThenFocusLossAddsNoSecondStep() throws Exception {
        JTextComponent field = field(count);
        dispatch(new FocusEvent(field, FocusEvent.FOCUS_GAINED));
        arrow(count, true);
        dispatch(new FocusEvent(field, FocusEvent.FOCUS_LOST));
        List<Step> steps = steps();
        assertEquals(describe(steps), 1, steps.size());
        assertEquals("2", steps.get(0).action.value);
    }

    @Test
    public void customEditorRecordsNothing() throws Exception {
        arrow(custom, true);
        arrow(custom, true);
        assertEquals(describe(steps()), 0, steps().size());
        int n = 0;
        synchronized (statuses) {
            for (String s : statuses) if (s.startsWith("spinner")) n++;
        }
        assertEquals(statuses.toString(), 1, n);
    }

    /** Review M5: a typed value in a spinner with a custom editor gives the status text once, as the arrows do. */
    @Test
    public void customEditorTypedValueGivesTheStatusOnce() throws Exception {
        final javax.swing.JTextField tf = new javax.swing.JTextField("1", 4);
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                JSpinner typed = new JSpinner(new SpinnerNumberModel(1, 0, 10, 1));
                typed.setName("t.typed");
                typed.setEditor(tf);
                frame.getContentPane().add(typed, java.awt.BorderLayout.SOUTH);
                frame.pack();
            }
        });
        for (String t : new String[] {"5", "6"}) {
            dispatch(new FocusEvent(tf, FocusEvent.FOCUS_GAINED));
            setText(tf, t);
            dispatch(new FocusEvent(tf, FocusEvent.FOCUS_LOST));
        }
        assertEquals(describe(steps()), 0, steps().size());
        int n = 0;
        synchronized (statuses) {
            for (String s : statuses) if (s.equals("spinner with a custom editor: the value is not recorded")) n++;
        }
        assertEquals(statuses.toString(), 1, n);
    }

    // ---- helpers --------------------------------------------------------------------------------

    /** Press and release on the up arrow, then wait until the EDT has run the tasks of the click. */
    private void arrow(final JSpinner s, boolean up) throws Exception {
        final AbstractButton b = Quiescence.onEdt(new Callable<AbstractButton>() {
            public AbstractButton call() {
                for (Component c : s.getComponents()) if (c instanceof AbstractButton) return (AbstractButton) c;
                return null;
            }
        });
        for (final int id : new int[] {MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED}) {
            int mods = id == MouseEvent.MOUSE_PRESSED ? MouseEvent.BUTTON1_DOWN_MASK : 0;
            dispatch(new MouseEvent(b, id, System.currentTimeMillis(), mods, 3, 3, 1, false, MouseEvent.BUTTON1));
        }
        flush();
    }

    private JTextComponent field(final JSpinner s) throws Exception {
        return Quiescence.onEdt(new Callable<JTextComponent>() {
            public JTextComponent call() {
                return ((JSpinner.DefaultEditor) s.getEditor()).getTextField();
            }
        });
    }

    private void setText(final JTextComponent c, final String t) throws Exception {
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                c.setText(t);
            }
        });
    }

    private static void flush() throws Exception {
        for (int i = 0; i < 3; i++) SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {}
        });
    }

    private static void dispatch(final AWTEvent e) throws Exception {
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                ((Component) e.getSource()).dispatchEvent(e);
            }
        });
    }

    private List<Step> steps() throws Exception {
        flush();
        return recorder.scenario().steps;
    }

    private static String describe(List<Step> steps) {
        List<String> out = new ArrayList<String>();
        for (Step s : steps) out.add(s.id + " " + s.action);
        return out.toString();
    }

    private void awaitSnapshot(Step s, long ms) throws Exception {
        long deadline = System.currentTimeMillis() + ms;
        while (!recorder.hasSnapshot(s)) {
            if (System.currentTimeMillis() > deadline) fail("no snapshot for " + s.id + " within " + ms + " ms");
            Thread.sleep(50);
        }
    }

    /** Gives focus and key events of the test frame to the component, with no check of the focused window. */
    private final class DirectFocus extends DefaultKeyboardFocusManager {
        @Override
        public boolean dispatchEvent(AWTEvent e) {
            boolean ours = e.getSource() instanceof Component && SwingUtilities.getWindowAncestor((Component) e.getSource()) == frame;
            if (ours && e instanceof FocusEvent) return false;
            return super.dispatchEvent(e);
        }
    }
}
