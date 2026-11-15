package smoker.agent;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import smoker.core.diff.SnapshotDiff;
import smoker.core.model.IgnoreRules;
import smoker.core.model.NodeSnapshot;
import smoker.core.model.Snapshot;

import javax.swing.Box;
import javax.swing.CellRendererPane;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Paths of the flat snapshot (WP-02, F-05, spec section 4.2). Skipped without a display (Linux: use xvfb-run).
 * All Swing access goes through the EDT.
 */
public class SnapshotTakerPathTest {

    /** An application subclass of a layout container. Unnamed, it is not in paths. */
    static class ChatPanel extends JPanel {
    }

    private final List<Window> windows = new ArrayList<Window>();

    @Before
    public void needsADisplay() {
        Assume.assumeFalse("needs a display", GraphicsEnvironment.isHeadless());
    }

    @After
    public void disposeWindows() throws Exception {
        onEdt(new Callable<Void>() {
            public Void call() {
                for (Window w : windows) w.dispose();
                return null;
            }
        });
    }

    @Test
    public void layoutContainersAreNotInPaths() throws Exception {
        List<String> paths = paths(onEdt(new Callable<Snapshot>() {
            public Snapshot call() {
                JFrame f = frame("Orders");
                JPanel outer = new JPanel();
                JPanel inner = new JPanel();
                inner.add(new JTextField(10));
                outer.add(inner);
                f.getContentPane().add(outer);
                return show(f);
            }
        }), "[Orders]");
        assertEquals(Arrays.asList("[Orders]", "[Orders]/JTextField[0]"), paths);
    }

    @Test
    public void newUnnamedPanelGivesNoDifference() throws Exception {
        final AtomicReference<JPanel> panel = new AtomicReference<JPanel>();
        final AtomicReference<JTextField> field = new AtomicReference<JTextField>();
        Snapshot before = onEdt(new Callable<Snapshot>() {
            public Snapshot call() {
                JFrame f = frame("Orders");
                panel.set(new JPanel());
                field.set(new JTextField("abc", 10));
                panel.get().add(field.get());
                f.getContentPane().add(panel.get());
                return show(f);
            }
        });
        Snapshot after = onEdt(new Callable<Snapshot>() {
            public Snapshot call() {
                panel.get().remove(field.get());
                JPanel wrapper = new JPanel();
                wrapper.add(field.get());
                panel.get().add(wrapper);
                panel.get().revalidate();
                return new SnapshotTaker(20).take();
            }
        });
        assertEquals(new ArrayList<String>(), SnapshotDiff.diff(before, after, new IgnoreRules()));
    }

    @Test
    public void namedAncestorIsTheAnchor() throws Exception {
        List<String> paths = paths(onEdt(new Callable<Snapshot>() {
            public Snapshot call() {
                JFrame f = frame("Orders");
                JPanel form = new JPanel();
                form.setName("orders.form");
                form.add(new JButton("OK"));
                f.getContentPane().add(form);
                f.getContentPane().add(new JButton("Cancel"), java.awt.BorderLayout.SOUTH);
                return show(f);
            }
        }), "[Orders]");
        assertEquals(Arrays.asList("[Orders]", "[Orders]/#orders.form", "[Orders]/#orders.form/JButton[0]",
                "[Orders]/JButton[0]"), paths);
    }

    @Test
    public void namedWindowUsesItsName() throws Exception {
        final AtomicReference<JFrame> frame = new AtomicReference<JFrame>();
        Snapshot before = onEdt(new Callable<Snapshot>() {
            public Snapshot call() {
                JFrame f = frame("Orders");
                f.setName("orders.frame");
                frame.set(f);
                JPanel p = new JPanel();
                p.add(new JButton("OK"));
                f.getContentPane().add(p);
                return show(f);
            }
        });
        assertEquals(Arrays.asList("[#orders.frame]", "[#orders.frame]/JButton[0]"), paths(before, "[#orders.frame]"));
        Snapshot after = onEdt(new Callable<Snapshot>() {
            public Snapshot call() {
                frame.get().setTitle("Orders - changed");
                return new SnapshotTaker(20).take();
            }
        });
        assertEquals(Arrays.asList("CHANGED [#orders.frame] .title: expected=\"Orders\" actual=\"Orders - changed\""),
                SnapshotDiff.diff(before, after, new IgnoreRules()));
    }

    @Test
    public void sameTitleTwiceGetsAnIndex() throws Exception {
        List<String> paths = paths(onEdt(new Callable<Snapshot>() {
            public Snapshot call() {
                for (int i = 0; i < 2; i++) {
                    JDialog d = new JDialog((JFrame) null, "Error");
                    windows.add(d);
                    d.getContentPane().add(new JButton("OK"));
                    d.pack();
                    d.setVisible(true);
                }
                return new SnapshotTaker(20).take();
            }
        }), "[Error]");
        assertEquals(Arrays.asList("[Error]", "[Error]/JButton[0]", "[Error][1]", "[Error][1]/JButton[0]"), paths);
    }

    @Test
    public void repeatedNameGetsAnIndex() throws Exception {
        List<String> paths = paths(onEdt(new Callable<Snapshot>() {
            public Snapshot call() {
                JFrame f = frame("Orders");
                JPanel p = new JPanel();
                JButton a = new JButton("A");
                a.setName("x");
                JButton b = new JButton("B");
                b.setName("x");
                p.add(a);
                p.add(b);
                f.getContentPane().add(p);
                return show(f);
            }
        }), "[Orders]");
        assertEquals(Arrays.asList("[Orders]", "[Orders]/#x", "[Orders]/#x[1]"), paths);
    }

    @Test
    public void applicationSubclassOfJPanelIsALayoutContainer() throws Exception {
        List<String> paths = paths(onEdt(new Callable<Snapshot>() {
            public Snapshot call() {
                JFrame f = frame("Orders");
                ChatPanel chat = new ChatPanel();
                chat.add(new JTextField(10));
                f.getContentPane().add(chat);
                return show(f);
            }
        }), "[Orders]");
        assertEquals(Arrays.asList("[Orders]", "[Orders]/JTextField[0]"), paths);
        assertTrue(SnapshotTaker.isLayoutContainer(new ChatPanel()));
        JPanel named = new JPanel();
        named.setName("orders.form");
        assertFalse(SnapshotTaker.isLayoutContainer(named));
        Dimension d = new Dimension(1, 1);
        assertTrue(SnapshotTaker.isLayoutContainer(new JScrollBar()));
        assertTrue(SnapshotTaker.isLayoutContainer(new CellRendererPane()));
        assertTrue(SnapshotTaker.isLayoutContainer(new Box.Filler(d, d, d)));
        assertFalse(SnapshotTaker.isLayoutContainer(new JButton()));
    }

    @Test
    public void scrollBarAndFillerAreNotInPaths() throws Exception {
        List<String> paths = paths(onEdt(new Callable<Snapshot>() {
            public Snapshot call() {
                JFrame f = frame("Orders");
                JPanel p = new JPanel();
                p.add(new JScrollBar());
                p.add(Box.createHorizontalStrut(5));
                p.add(new JButton("OK"));
                f.getContentPane().add(p);
                return show(f);
            }
        }), "[Orders]");
        assertEquals(Arrays.asList("[Orders]", "[Orders]/JButton[0]"), paths);
    }

    @Test
    public void namingAComponentLeavesSiblingPathsUnchanged() throws Exception {
        final List<JButton> buttons = new ArrayList<JButton>();
        Snapshot before = onEdt(new Callable<Snapshot>() {
            public Snapshot call() {
                JFrame f = frame("Orders");
                JPanel p = new JPanel();
                for (int i = 0; i < 3; i++) {
                    JButton b = new JButton("B" + i);
                    buttons.add(b);
                    p.add(b);
                }
                f.getContentPane().add(p);
                return show(f);
            }
        });
        assertEquals(Arrays.asList("[Orders]", "[Orders]/JButton[0]", "[Orders]/JButton[1]", "[Orders]/JButton[2]"),
                paths(before, "[Orders]"));
        Snapshot after = onEdt(new Callable<Snapshot>() {
            public Snapshot call() {
                buttons.get(0).setName("orders.a");
                return new SnapshotTaker(20).take();
            }
        });
        assertEquals(Arrays.asList("[Orders]", "[Orders]/#orders.a", "[Orders]/JButton[1]", "[Orders]/JButton[2]"),
                paths(after, "[Orders]"));
    }

    @Test
    public void everyPathIsUniqueEvenWhenANameLooksLikeAnIndex() throws Exception {
        Snapshot s = onEdt(new Callable<Snapshot>() {
            public Snapshot call() {
                JFrame f = frame("Orders");
                JPanel p = new JPanel();
                for (String n : Arrays.asList("x", "x", "x[1]")) {
                    JButton b = new JButton(n);
                    b.setName(n);
                    p.add(b);
                }
                f.getContentPane().add(p);
                return show(f);
            }
        });
        List<String> paths = paths(s, "[Orders]");
        assertEquals(Arrays.asList("[Orders]", "[Orders]/#x", "[Orders]/#x[1]", "[Orders]/#x[1][1]"), paths);
        List<String> all = new ArrayList<String>();
        for (NodeSnapshot n : s.nodes) all.add(n.path);
        assertEquals(all.size(), new HashSet<String>(all).size());
    }

    @Test
    public void unnamedInteractiveListsPathsInTheNewFormat() throws Exception {
        final SnapshotTaker taker = new SnapshotTaker(20);
        onEdt(new Callable<Snapshot>() {
            public Snapshot call() {
                JFrame f = frame("Orders");
                JPanel p = new JPanel();
                p.add(new JButton("OK"));
                f.getContentPane().add(p);
                f.pack();
                f.setVisible(true);
                return taker.take();
            }
        });
        assertTrue(String.valueOf(taker.unnamedInteractive()), taker.unnamedInteractive().contains("[Orders]/JButton[0]"));
    }

    private JFrame frame(String title) {
        JFrame f = new JFrame(title);
        windows.add(f);
        return f;
    }

    private static Snapshot show(JFrame f) {
        f.pack();
        f.setVisible(true);
        return new SnapshotTaker(20).take();
    }

    /** The paths of the nodes of one window part, in snapshot order. */
    private static List<String> paths(Snapshot s, String windowPart) {
        List<String> out = new ArrayList<String>();
        for (NodeSnapshot n : s.nodes) {
            if (n.path.equals(windowPart) || n.path.startsWith(windowPart + "/") || n.path.startsWith(windowPart + "[")) {
                out.add(n.path);
            }
        }
        return out;
    }

    private static <T> T onEdt(final Callable<T> c) throws Exception {
        final AtomicReference<T> result = new AtomicReference<T>();
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
