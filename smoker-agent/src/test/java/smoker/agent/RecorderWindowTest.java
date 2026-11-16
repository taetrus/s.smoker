package smoker.agent;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import nametest.SearchPanel;
import smoker.core.io.ScenarioFiles;
import smoker.core.model.Action;
import smoker.core.model.Locator;
import smoker.core.model.NodeSnapshot;
import smoker.core.model.Scenario;
import smoker.core.model.Snapshot;
import smoker.core.model.Step;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.MenuElement;
import javax.swing.MenuSelectionManager;
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
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
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
    private JButton flat;
    private JPopupMenu popup;
    private JMenuItem item;
    private JLabel status;
    private KeyboardFocusManager previousFocusManager;
    private Recorder recorder;
    private RecorderToolbar toolbar;
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
                ActionListener setDone = new ActionListener() {
                    public void actionPerformed(ActionEvent e) {
                        status.setText("done");   // at once, before the background snapshot could run
                    }
                };
                done.addActionListener(setDone);
                flat = new JButton("Flat");       // a toolbar-like button: it takes no focus, so no FOCUS_LOST
                flat.setName("t.flat");
                flat.setFocusable(false);
                flat.addActionListener(setDone);
                popup = new JPopupMenu();
                item = new JMenuItem("Item");
                item.setName("t.item");
                item.addActionListener(setDone);
                popup.add(item);
                field.addActionListener(new ActionListener() {
                    public void actionPerformed(ActionEvent e) {
                        status.setText("entered");   // ENTER in the field
                    }
                });
                p.add(settings);
                p.add(clear);
                p.add(field);
                p.add(done);
                p.add(flat);
                p.add(status);
                frame.getContentPane().add(p);
                frame.pack();
                frame.setVisible(true);
            }
        });
        // The OS does not always give the window of a test JVM the focus (macOS). The focus events are synthetic:
        // DirectFocus lets them go to the component, through the AWT event listeners, as real focus events do.
        previousFocusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager();
        KeyboardFocusManager.setCurrentKeyboardFocusManager(new DirectFocus());
        recorder = new Recorder(AgentConfig.parse("mode=record;scenario=target/recorder-window-test.json"), new Scenario(),
                new HashMap<String, Snapshot>());
        recorder.start();
    }

    @After
    public void disposeFrame() throws Exception {
        if (recorder != null) recorder.pause();
        if (previousFocusManager != null) KeyboardFocusManager.setCurrentKeyboardFocusManager(previousFocusManager);
        if (frame == null) return;
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                if (toolbar != null) {
                    if (toolbar.waitForPicker().selectModeOn()) toolbar.waitForPicker().cancel();
                    toolbar.dispose();
                }
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
     * F-21: the user types into a field and clicks a focusable button at once. The golden snapshot of the SET_TEXT
     * step must show the typed text and not the state that the click produces. The events come in the AWT order:
     * KEY_PRESSED in the field, MOUSE_PRESSED on the button, FOCUS_LOST / FOCUS_GAINED, MOUSE_RELEASED.
     * The press can also ask the OS for real focus: DirectFocus gives those events to the components, and the
     * recorder takes no second snapshot (nothing is pending) and adds no second SET_TEXT (the text did not change).
     */
    @Test
    public void goldenOfATextStepDoesNotShowTheNextClick() throws Exception {
        typeIntoField();
        dispatch(press(done));
        dispatch(new FocusEvent(field, FocusEvent.FOCUS_LOST, false, done));
        dispatch(new FocusEvent(done, FocusEvent.FOCUS_GAINED, false, field));
        dispatch(release(done));
        assertTextStepThen(Action.Type.CLICK, "done");
    }

    /** F-21: a click on a button that takes no focus gives no FOCUS_LOST in the field. The text is flushed on the press. */
    @Test
    public void textIsFlushedBeforeAClickOnANonFocusableButton() throws Exception {
        typeIntoField();
        dispatch(press(flat));
        dispatch(release(flat));
        assertTextStepThen(Action.Type.CLICK, "done");
        assertEquals("t.flat", recorder.scenario().steps.get(1).action.locator.name);
    }

    /** F-21: a menu item takes no focus either. */
    @Test
    public void textIsFlushedBeforeAClickOnAMenuItem() throws Exception {
        typeIntoField();
        onEdt(new Runnable() {
            public void run() {
                popup.show(frame.getContentPane(), 10, 10);
                MenuSelectionManager.defaultManager().setSelectedPath(new MenuElement[] {popup, item});   // arms the item
            }
        });
        dispatch(press(item));
        dispatch(release(item));
        assertTextStepThen(Action.Type.CLICK, "done");
        assertEquals("t.item", recorder.scenario().steps.get(1).action.locator.name);
    }

    /** F-21: ENTER in the field flushes the text. The SET_TEXT snapshot shows the state before the field acts on ENTER. */
    @Test
    public void goldenOfATextStepDoesNotShowTheEnterKey() throws Exception {
        typeIntoField();
        dispatch(new KeyEvent(field, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, KeyEvent.VK_ENTER, '\n'));
        assertTextStepThen(Action.Type.KEY, "entered");
    }

    /** WP-09, spec 5.2: a KEY action on an automatically named field gets the locator with the automatic name. */
    @Test
    public void keyInAnAutomaticallyNamedFieldHasItsLocator() throws Exception {
        SearchPanel search = addSearchPanel();
        dispatch(new KeyEvent(search.queryField(), KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, KeyEvent.VK_ENTER, '\n'));
        List<Step> steps = recorder.scenario().steps;
        assertEquals(describe(steps), 1, steps.size());
        assertEquals(describe(steps), Action.Type.KEY, steps.get(0).action.type);
        assertEquals(describe(steps), "@SearchPanel.queryField", steps.get(0).action.locator.name);
    }

    /** WP-03, spec 6.4: a WAIT_FOR step removes the snapshot of the step before it. */
    @Test
    public void waitForRemovesTheSnapshotOfTheStepBefore() throws Exception {
        click(clear, true);
        final List<Step> steps = recorder.scenario().steps;
        assertEquals(describe(steps), 1, steps.size());
        awaitSnapshot(steps.get(0), 10000);
        onEdt(new Runnable() {
            public void run() {
                recorder.addWaitFor(Action.waitFor(named("t.status"), "text", Pattern.quote("idle"), 2000L));
            }
        });
        assertEquals(describe(steps), 2, steps.size());
        assertEquals(Action.Type.WAIT_FOR, steps.get(1).action.type);
        assertFalse("the snapshot of the step before is gone", recorder.hasSnapshot(steps.get(0)));
        awaitSnapshot(steps.get(1), 3000);
        recorder.save();
        Map<String, Snapshot> golden = ScenarioFiles.readGolden(Paths.get("target/recorder-window-test.json"));
        assertEquals(golden.keySet().toString(), 1, golden.size());
        assertTrue(golden.keySet().toString(), golden.containsKey(steps.get(1).id));
    }

    /** WP-03, spec 6.5: the snapshot of a WAIT_FOR step shows the state after the condition matched. */
    @Test
    public void waitForSnapshotWaitsForTheCondition() throws Exception {
        onEdt(new Runnable() {
            public void run() {
                status.setText("waiting\u2026");
                Timer t = new Timer(800, new ActionListener() {
                    public void actionPerformed(ActionEvent e) {
                        status.setText("ready 1");
                    }
                });
                t.setRepeats(false);
                t.start();
                recorder.addWaitFor(Action.waitFor(named("t.status"), "text", "ready \\d+", 3000L));
            }
        });
        Step w = recorder.scenario().steps.get(0);
        awaitSnapshot(w, 5000);
        assertEquals("ready 1", node(recorder.snapshot(w), "t.status").text);
    }

    /**
     * F-21 wins over WP-03: a mouse press before the WAIT_FOR condition matches takes the snapshot of the WAIT_FOR step
     * at once, before the press changes the UI, and the recorder gives a warning.
     */
    @Test
    public void inputBeforeTheMatchTakesTheWaitForSnapshotWithAWarning() throws Exception {
        final List<String> said = java.util.Collections.synchronizedList(new ArrayList<String>());
        recorder.setListener(new Recorder.Listener() {
            public void stepAdded(Step s) {
            }

            public void stepUpdated(Step s) {
            }

            public void status(String text) {
                said.add(text);
            }
        });
        onEdt(new Runnable() {
            public void run() {
                status.setText("waiting\u2026");
                recorder.addWaitFor(Action.waitFor(named("t.status"), "text", "ready \\d+", 3000L));
            }
        });
        Step w = recorder.scenario().steps.get(0);
        dispatch(press(clear));
        assertTrue("the press took the snapshot at once", recorder.hasSnapshot(w));
        assertEquals("waiting\u2026", node(recorder.snapshot(w), "t.status").text);
        assertTrue(said.toString(), said.contains(
                "WAIT_FOR #t.status did not match before the next input: its golden shows the state before the match"));
    }

    /**
     * WP-03, spec 6.5 (owner decision): the select mode of "Wait for..." refuses a component without a name and stays on.
     * A named component ends the select mode. The clicks go to the glass pane and not to the application.
     */
    @Test
    public void selectModeRefusesUnnamedAndAcceptsNamed() throws Exception {
        final AtomicInteger clearFired = new AtomicInteger();
        final List<Locator> chosen = new ArrayList<Locator>();
        final Component[] oldGlass = new Component[1];
        onEdt(new Runnable() {
            public void run() {
                clear.addActionListener(new ActionListener() {
                    public void actionPerformed(ActionEvent e) {
                        clearFired.incrementAndGet();
                    }
                });
                oldGlass[0] = frame.getGlassPane();
                toolbar = new RecorderToolbar(recorder);
                toolbar.waitForPicker().opener = new Consumer<Locator>() {
                    public void accept(Locator l) {
                        chosen.add(l);
                    }
                };
                toolbar.waitForPicker().toggle();
            }
        });
        assertTrue(onEdt(new Callable<Boolean>() {
            public Boolean call() {
                return toolbar.waitForPicker().selectModeOn();
            }
        }));
        assertFalse("the recorder pauses in select mode", recorder.isRecording());
        assertTrue("a glass pane covers the frame", glassPane() != oldGlass[0]);

        clickOnGlassPane(clear);
        String text = onEdt(new Callable<String>() {
            public String call() {
                return toolbar.statusText();
            }
        });
        assertTrue(text, text.startsWith("needs a name"));
        assertTrue(onEdt(new Callable<Boolean>() {
            public Boolean call() {
                return toolbar.waitForPicker().selectModeOn();
            }
        }));
        assertEquals("the click did not reach the button", 0, clearFired.get());
        assertTrue(chosen.toString(), chosen.isEmpty());

        clickOnGlassPane(status);
        assertFalse(onEdt(new Callable<Boolean>() {
            public Boolean call() {
                return toolbar.waitForPicker().selectModeOn();
            }
        }));
        assertEquals(chosen.toString(), 1, chosen.size());
        assertEquals("t.status", chosen.get(0).name);
        assertSame("the old glass pane is back", oldGlass[0], glassPane());
        assertFalse(onEdt(new Callable<Boolean>() {
            public Boolean call() {
                return frame.getGlassPane().isVisible();
            }
        }));
        assertTrue("the recorder records again", recorder.isRecording());
        assertTrue(describe(recorder.scenario().steps), recorder.scenario().steps.isEmpty());
        assertEquals(0, clearFired.get());
    }

    /** PRESSED, RELEASED and CLICKED on the glass pane of the frame, over the center of {@code over}. */
    private void clickOnGlassPane(final Component over) throws Exception {
        for (final int id : new int[] {MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED}) {
            onEdt(new Runnable() {
                public void run() {
                    Component glass = frame.getGlassPane();
                    java.awt.Point p = SwingUtilities.convertPoint(over, over.getWidth() / 2, over.getHeight() / 2, glass);
                    int mods = id == MouseEvent.MOUSE_PRESSED ? MouseEvent.BUTTON1_DOWN_MASK : 0;
                    glass.dispatchEvent(new MouseEvent(glass, id, System.currentTimeMillis(), mods, p.x, p.y, 1, false,
                            MouseEvent.BUTTON1));
                }
            });
        }
    }

    private Component glassPane() throws Exception {
        return onEdt(new Callable<Component>() {
            public Component call() {
                return frame.getGlassPane();
            }
        });
    }

    private static Locator named(String name) {
        Locator l = new Locator();
        l.name = name;
        return l;
    }

    private void awaitSnapshot(Step s, long ms) throws Exception {
        long deadline = System.currentTimeMillis() + ms;
        while (!recorder.hasSnapshot(s)) {
            if (System.currentTimeMillis() > deadline) fail("no snapshot for " + s.id + " within " + ms + " ms");
            Thread.sleep(50);
        }
    }

    private static <T> T onEdt(Callable<T> c) throws Exception {
        return Quiescence.onEdt(c);
    }

    private SearchPanel addSearchPanel() throws Exception {
        final SearchPanel[] out = new SearchPanel[1];
        onEdt(new Runnable() {
            public void run() {
                out[0] = new SearchPanel();
                frame.getContentPane().add(out[0], java.awt.BorderLayout.SOUTH);
                frame.pack();
            }
        });
        return out[0];
    }

    private void typeIntoField() throws Exception {
        dispatch(new FocusEvent(field, FocusEvent.FOCUS_GAINED));
        dispatch(new KeyEvent(field, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, KeyEvent.VK_K, 'K'));
        onEdt(new Runnable() {
            public void run() {
                field.setText("Kerem");   // the typed text
            }
        });
    }

    private static MouseEvent press(Component c) {
        return new MouseEvent(c, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(),
                MouseEvent.BUTTON1_DOWN_MASK, 5, 5, 1, false, MouseEvent.BUTTON1);
    }

    private static MouseEvent release(Component c) {
        return new MouseEvent(c, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(), 0, 5, 5, 1, false, MouseEvent.BUTTON1);
    }

    /**
     * The scenario is SET_TEXT, then {@code next}. The second action set the status label to {@code statusAfter}.
     * The SET_TEXT snapshot shows the typed text and the status of before; the snapshot of the next step shows the new status.
     */
    private void assertTextStepThen(Action.Type next, String statusAfter) throws Exception {
        assertEquals(statusAfter, Quiescence.onEdt(new Callable<String>() {
            public String call() {
                return status.getText();
            }
        }));
        List<Step> steps = recorder.scenario().steps;
        assertEquals(describe(steps), 2, steps.size());
        assertEquals(describe(steps), Action.Type.SET_TEXT, steps.get(0).action.type);
        assertEquals(describe(steps), next, steps.get(1).action.type);
        long deadline = System.currentTimeMillis() + 10000;
        while (!(recorder.hasSnapshot(steps.get(0)) && recorder.hasSnapshot(steps.get(1)))) {
            if (System.currentTimeMillis() > deadline) fail("no snapshots");
            Thread.sleep(50);
        }
        Snapshot setText = recorder.snapshot(steps.get(0));
        assertEquals("Kerem", node(setText, "t.name").text);
        assertEquals("idle", node(setText, "t.status").text);
        assertEquals(statusAfter, node(recorder.snapshot(steps.get(1)), "t.status").text);
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
