package smoker.agent;

import org.junit.Assume;
import org.junit.Test;
import smoker.core.io.Json;
import smoker.core.model.Snapshot;

import javax.swing.JFrame;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** A password field must not put its value into a snapshot (F-19). All Swing access goes through the EDT. */
public class SnapshotTakerTest {

    private static final String SECRET = "sk-or-v1-secret";

    @Test
    public void filledPasswordFieldGivesTheMask() throws Exception {
        assertEquals(SnapshotTaker.MASK, textOf(new Callable<String>() {
            public String call() {
                return SnapshotTaker.textOf(new JPasswordField(SECRET));
            }
        }));
    }

    @Test
    public void emptyPasswordFieldGivesAnEmptyText() throws Exception {
        assertEquals("", textOf(new Callable<String>() {
            public String call() {
                return SnapshotTaker.textOf(new JPasswordField());
            }
        }));
    }

    @Test
    public void textFieldGivesItsValue() throws Exception {
        assertEquals("tr", textOf(new Callable<String>() {
            public String call() {
                return SnapshotTaker.textOf(new JTextField("tr"));
            }
        }));
    }

    @Test
    public void snapshotOfAShownWindowDoesNotContainThePassword() throws Exception {
        Assume.assumeFalse("needs a display", GraphicsEnvironment.isHeadless());
        final AtomicReference<JFrame> frame = new AtomicReference<JFrame>();
        try {
            String json = textOf(new Callable<String>() {
                public String call() {
                    JFrame f = new JFrame("Settings");
                    frame.set(f);
                    JPasswordField key = new JPasswordField(SECRET);
                    key.setName("settings.apiKey");
                    f.getContentPane().add(key);
                    f.pack();
                    f.setVisible(true);
                    Snapshot s = new SnapshotTaker().take();
                    return Json.toString(s);
                }
            });
            assertFalse(json, json.contains(SECRET));
            assertTrue(json, json.contains("\"" + SnapshotTaker.MASK + "\""));
        } finally {
            textOf(new Callable<String>() {
                public String call() {
                    if (frame.get() != null) frame.get().dispose();
                    return null;
                }
            });
        }
    }

    private static String textOf(final Callable<String> c) throws Exception {
        final AtomicReference<String> result = new AtomicReference<String>();
        final AtomicReference<Exception> error = new AtomicReference<Exception>();
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                try {
                    result.set(c.call());
                } catch (Exception e) {
                    error.set(e);
                }
            }
        });
        if (error.get() != null) throw error.get();
        return result.get();
    }
}
