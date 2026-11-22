package smoker.agent;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.awt.Dialog;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.util.concurrent.Callable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The reset closes the dialogs that the application left open (WP-05, F-04). Skipped without a display. */
public class DialogCloserTest {

    private JDialog smokerDialog;

    @Before
    public void assumeDisplay() {
        Assume.assumeFalse(GraphicsEnvironment.isHeadless());
    }

    @After
    public void cleanUp() throws Exception {
        if (smokerDialog == null) return;
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                smokerDialog.dispose();
            }
        });
    }

    private static boolean anyDialogShows() throws Exception {
        return Quiescence.onEdt(new Callable<Boolean>() {
            @Override
            public Boolean call() {
                for (Window w : Window.getWindows()) if (w instanceof Dialog && w.isShowing()) return true;
                return false;
            }
        });
    }

    @Test
    public void closesAModalOptionPane() throws Exception {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                JOptionPane.showMessageDialog(null, "left open"); // blocks this runnable in a nested loop
            }
        });
        long deadline = System.currentTimeMillis() + 10000;
        while (!anyDialogShows() && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assertTrue("the dialog did not open", anyDialogShows());
        assertNull(DialogCloser.closeAll(10));
        assertFalse(anyDialogShows());
        assertTrue(Quiescence.edtResponds(5000));
    }

    @Test
    public void skipsSmokerWindows() throws Exception {
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                smokerDialog = new JDialog((java.awt.Frame) null, "tool dialog");
                smokerDialog.setName("smoker.x");
                smokerDialog.setSize(100, 50);
                smokerDialog.setVisible(true);
            }
        });
        assertNull(DialogCloser.closeAll(10));
        assertTrue(anyDialogShows());
    }

    @Test
    public void givesTheSentinelWhenTheEdtIsSilent() throws Exception {
        final Object lock = new Object();
        final boolean[] release = {false};
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                synchronized (lock) {
                    long end = System.currentTimeMillis() + 10000;
                    while (!release[0] && System.currentTimeMillis() < end) {
                        try {
                            lock.wait(50);
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                }
            }
        });
        try {
            assertEquals(DialogCloser.EDT_SILENT, DialogCloser.closeAll(10, 300));
        } finally {
            synchronized (lock) {
                release[0] = true;
                lock.notifyAll();
            }
        }
        assertTrue(Quiescence.edtResponds(5000));
    }
}
