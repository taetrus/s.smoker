package smoker.agent;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import smoker.core.model.Action;
import smoker.core.model.Locator;
import smoker.core.model.Scenario;
import smoker.core.model.Snapshot;
import smoker.core.model.Step;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** The preview, group and move of the recorder toolbar (WP-06, spec 7). Needs a display. */
public class RecorderToolbarWindowTest {

    private static final String FILE = "target/recorder-toolbar-window-test.json";

    private JFrame frame;
    private JLabel label;
    private Recorder recorder;
    private RecorderToolbar toolbar;
    private Step a;
    private Step b;
    private Step c;

    @Before
    public void setUp() throws Exception {
        Assume.assumeFalse("needs a display", GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                frame = new JFrame("Toolbar");
                label = new JLabel("start");
                label.setName("t.label");
                frame.getContentPane().add(label);
                frame.pack();
                frame.setVisible(true);
            }
        });
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put("mode", "record");
        m.put("scenario.1", FILE);
        m.put("screenshots", "off");
        recorder = new Recorder(AgentConfig.fromMap(m, "test"), new Scenario(), new HashMap<String, Snapshot>());
        a = addWithSnapshot("a");
        b = addWithSnapshot("b");
        c = addWithSnapshot("c");
        onEdt(new Callable<Void>() {
            public Void call() {
                toolbar = new RecorderToolbar(recorder);
                return null;
            }
        });
    }

    @After
    public void tearDown() throws Exception {
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                if (toolbar != null) toolbar.dispose();
                if (frame != null) frame.dispose();
            }
        });
    }

    @Test
    public void previewShowsTheChangeOfTheSelectedStep() throws Exception {
        select(1);
        String p = preview();
        assertTrue(p, p.contains("CHANGED"));
        assertTrue(p, p.contains("label b"));
    }

    @Test
    public void previewOfTheFirstStep() throws Exception {
        select(0);
        assertEquals("first golden: no earlier step to compare", preview());
    }

    @Test
    public void previewUpdatesWhenTheGoldenArrives() throws Exception {
        final Step d = onEdt(new Callable<Step>() {
            public Step call() {
                label.setText("label d");
                Step s = recorder.addForTest(Action.click(textLocator("d")));
                toolbar.stepAdded(s); // the recorder calls this for a recorded step
                return s;
            }
        });
        flush();
        select(3);
        assertEquals("snapshot pending...", preview());
        awaitSnapshot(d);
        flush();
        assertFalse(preview(), preview().equals("snapshot pending..."));
    }

    @Test
    public void moveDownMovesTheStepAndKeepsTheSelection() throws Exception {
        select(0);
        move(+1);
        assertEquals("[b, a, c]", ids());
        assertEquals(1, (int) onEdt(new Callable<Integer>() {
            public Integer call() {
                return toolbar.selectedIndex();
            }
        }));
        assertTrue(listText(0), listText(0).endsWith("(no golden: run and approve)"));
        assertTrue(listText(1), listText(1).endsWith("(no golden: run and approve)"));
        assertEquals("no golden: run and approve", preview());
    }

    @Test
    public void upIsDisabledOnTheFirstRowAndDownOnTheLast() throws Exception {
        select(0);
        assertFalse(enabled("smoker.toolbar.up"));
        assertTrue(enabled("smoker.toolbar.down"));
        select(1);
        assertTrue(enabled("smoker.toolbar.up"));
        assertTrue(enabled("smoker.toolbar.down"));
        select(2);
        assertTrue(enabled("smoker.toolbar.up"));
        assertFalse(enabled("smoker.toolbar.down"));
    }

    @Test
    public void groupHeaderShowsAboveItsStep() throws Exception {
        select(1);
        group("Create order");
        assertEquals("Create order", header(1));
        assertNull(header(0));
        assertNull(header(2));
        assertEquals(3, (int) onEdt(new Callable<Integer>() {
            public Integer call() {
                return toolbar.modelSize();
            }
        }));
        assertEquals(1, (int) onEdt(new Callable<Integer>() {
            public Integer call() {
                return toolbar.selectedIndex();
            }
        }));
    }

    @Test
    public void emptyGroupRemovesTheHeader() throws Exception {
        select(1);
        group("Create order");
        group("");
        assertNull(header(1));
        assertNull(b.group);
    }

    @Test
    public void groupMovesWithItsStep() throws Exception {
        select(1);
        group("Create order");
        move(-1);
        assertEquals("Create order", header(0));
        assertNull(header(1));
    }

    @Test
    public void groupDialogIsATool() throws Exception {
        select(1);
        final String[] seen = new String[1];
        onEdt(new Callable<Void>() {
            public Void call() {
                javax.swing.Timer t = new javax.swing.Timer(200, new java.awt.event.ActionListener() {
                    public void actionPerformed(java.awt.event.ActionEvent e) {
                        for (Window w : Window.getWindows()) {
                            if (w.isShowing() && "smoker.group".equals(w.getName())) {
                                seen[0] = w.getName();
                                w.dispose();
                            }
                        }
                    }
                });
                t.setRepeats(false);
                t.start();
                ((JButton) find(toolbar, "smoker.toolbar.group")).doClick(); // modal: returns after the timer closed it
                return null;
            }
        });
        assertEquals("smoker.group", seen[0]);
    }

    @Test
    public void moveIsLogged() throws Exception {
        final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        final PrintStream old = System.out;
        System.setOut(new PrintStream(new OutputStream() {
            public void write(int x) {
                old.write(x);
                synchronized (buf) { buf.write(x); }
            }
        }, true));
        try {
            select(1);
            move(-1);
            flush();
            String log;
            synchronized (buf) { log = buf.toString("UTF-8"); }
            assertTrue(log, log.contains("[smoker] step list: moved s002 up"));
        } finally {
            System.setOut(old);
        }
    }

    // ---- helpers -----------------------------------------------------------------------------

    private Step addWithSnapshot(final String text) throws Exception {
        Step s = onEdt(new Callable<Step>() {
            public Step call() {
                label.setText("label " + text);
                return recorder.addForTest(Action.click(textLocator(text)));
            }
        });
        awaitSnapshot(s);
        return s;
    }

    private static Locator textLocator(String text) {
        Locator l = new Locator();
        l.text = text;
        return l;
    }

    private void awaitSnapshot(Step s) throws Exception {
        long deadline = System.currentTimeMillis() + 10000;
        while (!recorder.hasSnapshot(s)) {
            if (System.currentTimeMillis() > deadline) fail("no snapshot for " + s.id);
            Thread.sleep(50);
        }
    }

    private void select(final int i) throws Exception {
        onEdt(new Callable<Void>() {
            public Void call() {
                toolbar.selectStep(i);
                return null;
            }
        });
    }

    private void move(final int delta) throws Exception {
        onEdt(new Callable<Void>() {
            public Void call() {
                toolbar.moveSelected(delta);
                return null;
            }
        });
    }

    private void group(final String g) throws Exception {
        onEdt(new Callable<Void>() {
            public Void call() {
                toolbar.setGroupOfSelected(g);
                return null;
            }
        });
    }

    private String preview() throws Exception {
        return onEdt(new Callable<String>() {
            public String call() {
                return toolbar.previewText();
            }
        });
    }

    private String header(final int i) throws Exception {
        return onEdt(new Callable<String>() {
            public String call() {
                return toolbar.headerOf(i);
            }
        });
    }

    private String listText(final int i) throws Exception {
        return onEdt(new Callable<String>() {
            public String call() {
                return toolbar.listText(i);
            }
        });
    }

    private boolean enabled(final String name) throws Exception {
        return onEdt(new Callable<Boolean>() {
            public Boolean call() {
                return find(toolbar, name).isEnabled();
            }
        });
    }

    private String ids() throws Exception {
        return onEdt(new Callable<String>() {
            public String call() {
                java.util.List<String> ids = new java.util.ArrayList<String>();
                for (Step s : recorder.scenario().steps) ids.add(s.id.equals(a.id) ? "a" : s.id.equals(b.id) ? "b" : "c");
                return ids.toString();
            }
        });
    }

    private void flush() throws Exception {
        onEdt(new Callable<Void>() {
            public Void call() {
                return null;
            }
        });
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
            }
        });
    }

    private static Component find(Container root, String name) {
        for (Component k : root.getComponents()) {
            if (name.equals(k.getName())) return k;
            if (k instanceof Container) {
                Component f = find((Container) k, name);
                if (f != null) return f;
            }
        }
        return null;
    }

    private static <T> T onEdt(Callable<T> c) throws Exception {
        return Quiescence.onEdt(c);
    }
}
