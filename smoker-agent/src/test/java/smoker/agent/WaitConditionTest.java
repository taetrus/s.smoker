package smoker.agent;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import smoker.core.model.Action;
import smoker.core.model.Locator;
import smoker.core.model.NodeSnapshot;

import javax.swing.JComboBox;
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
import java.util.concurrent.Callable;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/** Reading and polling one attribute (WP-03, F-02). Needs a display. */
public class WaitConditionTest {

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

    private static Locator named(String name) {
        Locator l = new Locator();
        l.name = name;
        return l;
    }

    private <T> T onEdt(Callable<T> c) throws Exception {
        final Object[] box = new Object[1];
        final Exception[] err = new Exception[1];
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                try {
                    box[0] = c.call();
                } catch (Exception e) {
                    err[0] = e;
                }
            }
        });
        if (err[0] != null) throw err[0];
        @SuppressWarnings("unchecked") T t = (T) box[0];
        return t;
    }

    /** A frame with {@code comp} inside; shown. */
    private JFrame show(final javax.swing.JComponent comp) throws Exception {
        return onEdt(new Callable<JFrame>() {
            public JFrame call() {
                JFrame f = new JFrame("wait test");
                f.setName("wait.frame");
                JPanel p = new JPanel();
                p.add(comp);
                f.setContentPane(p);
                f.pack();
                f.setVisible(true);
                windows.add(f);
                return f;
            }
        });
    }

    private String value(final javax.swing.JComponent c, final String attribute) throws Exception {
        return onEdt(new Callable<String>() {
            public String call() {
                return WaitCondition.valueOf(c, attribute);
            }
        });
    }

    @Test
    public void valueOfGivesTrueForVisibleAndEnabled() throws Exception {
        JLabel l = new JLabel("a");
        show(l);
        assertEquals("true", value(l, "visible"));
        assertEquals("true", value(l, "enabled"));
        l.setEnabled(false);
        assertEquals("false", value(l, "enabled"));
    }

    @Test
    public void valueOfGivesCountsAsText() throws Exception {
        JComboBox<String> cb = new JComboBox<String>(new String[]{"a", "b", "c"});
        cb.setSelectedIndex(1);
        show(cb);
        assertEquals("3", value(cb, "itemCount"));
        assertEquals("1", value(cb, "selectedIndex"));
        assertEquals("b", value(cb, "text"));
    }

    @Test
    public void valueOfGivesTheWindowTitle() throws Exception {
        JLabel l = new JLabel("a");
        final JFrame f = show(l);
        assertEquals("wait test", onEdt(new Callable<String>() {
            public String call() {
                return WaitCondition.valueOf(f, "title");
            }
        }));
        assertEquals(null, value(l, "title"));
    }

    @Test
    public void passwordFieldValueIsMasked() throws Exception {
        final JPasswordField pw = new JPasswordField("secret");
        show(pw);
        String v = value(pw, "text");
        assertNotEquals("secret", v);
        NodeSnapshot n = onEdt(new Callable<NodeSnapshot>() {
            public NodeSnapshot call() {
                return SnapshotTaker.attributes(pw, 10);
            }
        });
        assertEquals(n.text, v);
    }

    @Test
    public void readGivesNotFoundForAMissingName() throws Exception {
        String v = onEdt(new Callable<String>() {
            public String call() {
                return WaitCondition.read(named("no.such.name"), "text");
            }
        });
        assertEquals(WaitCondition.NOT_FOUND, v);
    }

    @Test
    public void awaitMatchesWhenTheLabelChanges() throws Exception {
        final JLabel l = new JLabel("waiting…");
        l.setName("t.answer");
        show(l);
        onEdt(new Callable<Object>() {
            public Object call() {
                Timer t = new Timer(500, new ActionListener() {
                    public void actionPerformed(ActionEvent e) {
                        l.setText("ready 42");
                    }
                });
                t.setRepeats(false);
                t.start();
                return null;
            }
        });
        Action a = Action.waitFor(named("t.answer"), "text", "ready \\d+", 3000L);
        long t0 = System.currentTimeMillis();
        WaitCondition.Outcome o = WaitCondition.await(a, WaitCondition.validate(a), NO_HANG);
        assertEquals(WaitCondition.Kind.MATCHED, o.kind);
        assertEquals("ready 42", o.lastValue);
        assertTrue(System.currentTimeMillis() - t0 < 2000);
    }

    @Test
    public void awaitTimesOutWithTheLastValue() throws Exception {
        JLabel l = new JLabel("waiting…");
        l.setName("t.answer");
        show(l);
        Action a = Action.waitFor(named("t.answer"), "text", "ready \\d+", 300L);
        WaitCondition.Outcome o = WaitCondition.await(a, WaitCondition.validate(a), NO_HANG);
        assertEquals(WaitCondition.Kind.TIMEOUT, o.kind);
        assertEquals("waiting…", o.lastValue);
        String m = WaitCondition.timeoutMessage(a, o.lastValue);
        assertTrue(m, m.contains("last value: \"waiting…\""));
        assertTrue(m, m.contains("within 300 ms"));
    }

    @Test
    public void timeoutMessageShowsNullWithoutQuotes() {
        Action a = Action.waitFor(named("x"), "text", "a", 300L);
        assertTrue(WaitCondition.timeoutMessage(a, null).endsWith("last value: null"));
    }
}
