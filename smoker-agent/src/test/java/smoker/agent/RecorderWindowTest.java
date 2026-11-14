package smoker.agent;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import smoker.core.model.Action;
import smoker.core.model.Scenario;
import smoker.core.model.Step;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.GraphicsEnvironment;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;

/**
 * The recorder with real Swing mouse events. Skipped without a display (Linux: use xvfb-run).
 * F-20: a click that opens a modal dialog gets no MOUSE_CLICKED for the button. The recorder must record it.
 */
public class RecorderWindowTest {

    private JFrame frame;
    private JButton settings;
    private JButton clear;
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
                p.add(settings);
                p.add(clear);
                frame.getContentPane().add(p);
                frame.pack();
                frame.setVisible(true);
            }
        });
        recorder = new Recorder(AgentConfig.parse("mode=record;scenario=target/recorder-window-test.json"), new Scenario());
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
