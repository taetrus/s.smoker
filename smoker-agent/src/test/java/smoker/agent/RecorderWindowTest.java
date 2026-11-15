package smoker.agent;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import smoker.core.model.Action;
import smoker.core.model.NodeSnapshot;
import smoker.core.model.Scenario;
import smoker.core.model.Snapshot;
import smoker.core.model.Step;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.DefaultKeyboardFocusManager;
import java.awt.GraphicsEnvironment;
import java.awt.KeyboardFocusManager;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * The recorder with real Swing mouse events. Skipped without a display (Linux: use xvfb-run).
 * F-20: a click that opens a modal dialog gets no MOUSE_CLICKED for the button. The recorder must record it.
 */
public class RecorderWindowTest {

    private JFrame frame;
    private JButton settings;
    private JButton clear;
    private JTextField field;
    private JButton done;
    private JLabel status;
    private Recorder recorder;
    private final AtomicInteger dialogsShown = new AtomicInteger();

    @Before
    public void showFrame() throws Exception {
        Assume.assumeFalse("needs a display", GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                frame = new JFrame("Chat");
                JPanel p = new JPanel();
                settings = new JButton("Settings");
                settings.addActionListener(new ActionListener() {
                    public void actionPerformed(ActionEvent e) {
                        final JDialog d = new JDialog(frame, "Settings", true);
                        d.getContentPane().add(new JButton("Cancel"));
                        d.pack();
                        Timer close = new Timer(200, new ActionListener() {
                            public void actionPerformed(ActionEvent e) {
                                d.dispose();
                            }
                        });
                        close.setRepeats(false);
                        close.start();
                        dialogsShown.incrementAndGet();
                        d.setVisible(true);   // blocks in a secondary loop until the timer disposes the dialog
                    }
                });
                clear = new JButton("Clear");
                field = new JTextField(10);
                field.setName("t.name");
                status = new JLabel("idle");
                status.setName("t.status");
                done = new JButton("Done");
                done.setName("t.done");
                done.setRequestFocusEnabled(false);   // the press asks for no real focus: the test sends the focus events
                done.addActionListener(new ActionListener() {
                    public void actionPerformed(ActionEvent e) {
                        status.setText("done");   // at once, before the background snapshot could run
                    }
                });
                p.add(settings);
                p.add(clear);
                p.add(field);
                p.add(done);
                p.add(status);
                frame.getContentPane().add(p);
                frame.pack();
                frame.setVisible(true);
            }
        });
        recorder = new Recorder(AgentConfig.parse("mode=record;scenario=target/recorder-window-test.json"), new Scenario(),
                new HashMap<String, Snapshot>());
        recorder.start();
    }

    @After
    public void disposeFrame() throws Exception {
        if (recorder != null) recorder.pause();
        if (frame == null) return;
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                frame.dispose();
            }
        });
    }

    @Test
    public void clickThatOpensAModalDialogIsRecordedOnce() throws Exception {
        // AWT sends no MOUSE_CLICKED here: the modal dialog blocks the frame, or the button is gone.
        click(settings, false);
        assertEquals("the dialog opened", 1, dialogsShown.get());
        assertEquals(clickSteps().toString(), 1, clickSteps().size());
        assertEquals("Settings", clickSteps().get(0).action.locator.text);
    }

    @Test
    public void plainButtonClickIsRecordedOnce() throws Exception {
        click(clear, true);
        assertEquals(clickSteps().toString(), 1, clickSteps().size());
        assertEquals("Clear", clickSteps().get(0).action.locator.text);
    }

    /**
     * F-21: the user types into a field and clicks a button at once. The golden snapshot of the SET_TEXT step must
     * show the typed text and not the state that the click produces. The events come in the AWT order:
     * KEY_PRESSED in the field, MOUSE_PRESSED on the button, FOCUS_LOST / FOCUS_GAINED, MOUSE_RELEASED.
     */
    @Test
    public void goldenOfATextStepDoesNotShowTheNextClick() throws Exception {
        // The OS does not always give the window of a test JVM the focus (macOS). The focus events are synthetic:
        // DirectFocus lets them go to the component, through the AWT event listeners, as real focus events do.
        final KeyboardFocusManager previous = KeyboardFocusManager.getCurrentKeyboardFocusManager();
        KeyboardFocusManager.setCurrentKeyboardFocusManager(new DirectFocus());
        try {
            dispatch(new FocusEvent(field, FocusEvent.FOCUS_GAINED));
            dispatch(new KeyEvent(field, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, KeyEvent.VK_K, 'K'));
            onEdt(new Runnable() {
                public void run() {
                    field.setText("Kerem");   // the typed text
                }
            });
            dispatch(new MouseEvent(done, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(),
                    MouseEvent.BUTTON1_DOWN_MASK, 5, 5, 1, false, MouseEvent.BUTTON1));
            dispatch(new FocusEvent(field, FocusEvent.FOCUS_LOST, false, done));
            dispatch(new FocusEvent(done, FocusEvent.FOCUS_GAINED, false, field));
            dispatch(new MouseEvent(done, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(),
                    0, 5, 5, 1, false, MouseEvent.BUTTON1));
        } finally {
            KeyboardFocusManager.setCurrentKeyboardFocusManager(previous);
        }
        assertEquals("done", Quiescence.onEdt(new Callable<String>() {
            public String call() {
                return status.getText();
            }
        }));

        List<Step> steps = recorder.scenario().steps;
        assertEquals(describe(steps), 2, steps.size());
        assertEquals(Action.Type.SET_TEXT, steps.get(0).action.type);
        assertEquals(Action.Type.CLICK, steps.get(1).action.type);
        long deadline = System.currentTimeMillis() + 10000;
        while (!(recorder.hasSnapshot(steps.get(0)) && recorder.hasSnapshot(steps.get(1)))) {
            if (System.currentTimeMillis() > deadline) fail("no snapshots");
            Thread.sleep(50);
        }
        Snapshot setText = recorder.snapshot(steps.get(0));
        assertEquals("Kerem", node(setText, "t.name").text);
        assertEquals("idle", node(setText, "t.status").text);
        assertEquals("done", node(recorder.snapshot(steps.get(1)), "t.status").text);
    }

    /** Gives focus and key events of the test frame to the component, with no check of the focused window. */
    private final class DirectFocus extends DefaultKeyboardFocusManager {
        @Override
        public boolean dispatchEvent(AWTEvent e) {
            boolean ours = e.getSource() instanceof Component && SwingUtilities.getWindowAncestor((Component) e.getSource()) == frame;
            if (ours && (e instanceof FocusEvent || e instanceof KeyEvent)) return false;
            return super.dispatchEvent(e);
        }
    }

    private static void dispatch(final AWTEvent e) throws Exception {
        onEdt(new Runnable() {
            public void run() {
                ((Component) e.getSource()).dispatchEvent(e);
            }
        });
    }

    private static String describe(List<Step> steps) {
        List<String> out = new ArrayList<String>();
        for (Step s : steps) out.add(s.id + " " + s.action);
        return out.toString();
    }

    private static NodeSnapshot node(Snapshot s, String name) {
        for (NodeSnapshot n : s.nodes) if (name.equals(n.name)) return n;
        fail("no node " + name);
        return null;
    }

    private static void onEdt(Runnable r) throws Exception {
        SwingUtilities.invokeAndWait(r);
    }

    /** PRESSED, RELEASED and, if requested, CLICKED. Each event goes through the AWT event listeners. */
    private static void click(final JButton b, boolean withClicked) throws Exception {
        int[] ids = withClicked
                ? new int[] {MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED}
                : new int[] {MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED};
        for (final int id : ids) {
            SwingUtilities.invokeAndWait(new Runnable() {
                public void run() {
                    int mods = id == MouseEvent.MOUSE_PRESSED ? MouseEvent.BUTTON1_DOWN_MASK : 0;
                    b.dispatchEvent(new MouseEvent(b, id, System.currentTimeMillis(), mods, 5, 5, 1, false, MouseEvent.BUTTON1));
                }
            });
        }
    }

    private List<Step> clickSteps() {
        List<Step> out = new ArrayList<Step>();
        for (Step s : recorder.scenario().steps) if (s.action.type == Action.Type.CLICK) out.add(s);
        return out;
    }
}
