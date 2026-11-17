package smoker.agent;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import smoker.core.model.Action;
import smoker.core.model.Locator;
import smoker.core.model.RunResult;

import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** {@code Player.runWaitFor}: the status of a WAIT_FOR step (WP-03, F-02). Needs a display. */
public class PlayerWaitForTest {

    private final List<Window> windows = new ArrayList<Window>();

    private static final BooleanSupplier NO_HANG = new BooleanSupplier() {
        public boolean getAsBoolean() {
            return false;
        }
    };

    @Before
    public void needsADisplay() {
        Assume.assumeFalse("needs a display", GraphicsEnvironment.isHeadless());
    }

    @After
    public void disposeWindows() throws Exception {
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                for (Window w : windows) w.dispose();
            }
        });
    }

    private static Action waitFor(String name, String attribute, String matches, long timeout) {
        Locator l = new Locator();
        l.name = name;
        return Action.waitFor(l, attribute, matches, timeout);
    }

    private void show(final javax.swing.JComponent c) throws Exception {
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                JFrame f = new JFrame("pw");
                f.setName("pw.frame");
                JPanel p = new JPanel();
                p.add(c);
                f.setContentPane(p);
                f.pack();
                f.setVisible(true);
                windows.add(f);
            }
        });
    }

    @Test
    public void matchedContinues() throws Exception {
        JLabel l = new JLabel("ready 42");
        l.setName("t.lbl");
        show(l);
        RunResult.StepResult sr = new RunResult.StepResult();
        assertTrue(Player.runWaitFor(waitFor("t.lbl", "text", "ready \\d+", 2000), sr, NO_HANG));
        assertEquals(RunResult.Status.PASS, sr.status);
        assertTrue(sr.differences.isEmpty());
    }

    @Test
    public void timeoutGivesFailWithLastValue() throws Exception {
        JLabel l = new JLabel("waiting…");
        l.setName("t.lbl");
        show(l);
        RunResult.StepResult sr = new RunResult.StepResult();
        assertTrue(Player.runWaitFor(waitFor("t.lbl", "text", "ready", 300), sr, NO_HANG));
        assertEquals(RunResult.Status.FAIL, sr.status);
        boolean found = false;
        for (String d : sr.differences) if (d.contains("last value: \"waiting…\"")) found = true;
        assertTrue(sr.differences.toString(), found);
    }

    @Test
    public void invalidRegexGivesError() {
        RunResult.StepResult sr = new RunResult.StepResult();
        assertFalse(Player.runWaitFor(waitFor("t.lbl", "text", "(", 300), sr, NO_HANG));
        assertEquals(RunResult.Status.ERROR, sr.status);
        assertNotNull(sr.error);
    }

    @Test
    public void blockedEdtGivesHang() throws Exception {
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        RunResult.StepResult sr = new RunResult.StepResult();
        BooleanSupplier hang = new BooleanSupplier() {
            public boolean getAsBoolean() {
                return true;
            }
        };
        try {
            assertFalse(Player.runWaitFor(waitFor("t.lbl", "text", "x", 2000), sr, hang));
            assertEquals(RunResult.Status.HANG, sr.status);
        } finally {
            assertTrue(Quiescence.edtResponds(5000));
        }
    }

    @Test
    public void timeoutMessageKeepsAPasswordMasked() throws Exception {
        JPasswordField pw = new JPasswordField("secret");
        pw.setName("t.pw");
        show(pw);
        RunResult.StepResult sr = new RunResult.StepResult();
        Player.runWaitFor(waitFor("t.pw", "text", "nomatch", 300), sr, NO_HANG);
        assertEquals(RunResult.Status.FAIL, sr.status);
        for (String d : sr.differences) assertFalse(d, d.contains("secret"));
    }

    private JDialog dialog;

    @After
    public void closeDialog() throws Exception {
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                if (dialog != null) dialog.dispose();
            }
        });
    }

    @Test
    public void waitsForAComponentInAModalDialog() throws Exception {
        final JLabel l = new JLabel("waiting");
        l.setName("t.dlg");
        // Never wait on this Runnable: it stays in the modal loop.
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                dialog = new JDialog((Window) null, "modal", java.awt.Dialog.ModalityType.APPLICATION_MODAL);
                dialog.setName("t.modal");
                dialog.add(l);
                dialog.pack();
                Timer t = new Timer(500, new ActionListener() {
                    public void actionPerformed(ActionEvent e) {
                        l.setText("done");
                    }
                });
                t.setRepeats(false);
                t.start();
                dialog.setVisible(true);
            }
        });
        RunResult.StepResult sr = new RunResult.StepResult();
        assertTrue(Player.runWaitFor(waitFor("t.dlg", "text", "done", 5000), sr, NO_HANG));
        assertEquals(RunResult.Status.PASS, sr.status);
    }
}
