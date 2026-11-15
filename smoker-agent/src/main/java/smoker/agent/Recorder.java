package smoker.agent;

import smoker.core.io.Json;
import smoker.core.io.ScenarioFiles;
import smoker.core.model.Action;
import smoker.core.model.Locator;
import smoker.core.model.Scenario;
import smoker.core.model.Snapshot;
import smoker.core.model.Step;

import javax.swing.AbstractButton;
import javax.swing.ButtonModel;
import javax.swing.JComboBox;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPasswordField;
import javax.swing.JPopupMenu;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;
import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Records user actions through {@link Toolkit#addAWTEventListener} (R-20) and, after each action settles,
 * captures the golden snapshot (R-23). Steps are auto-named and can be renamed in the toolbar (R-26).
 * {@link #save()} writes the scenario and the golden file (F-06).
 */
final class Recorder implements AWTEventListener {

    interface Listener {
        void stepAdded(Step s);
        void stepUpdated(Step s);
        void status(String text);
    }

    private final AgentConfig cfg;
    private final Scenario scenario;
    private final SnapshotTaker taker;
    /** Golden snapshot per step id. The snapshot thread writes it, the EDT reads it: guard it with its own lock. */
    private final LinkedHashMap<String, Snapshot> snapshots = new LinkedHashMap<String, Snapshot>();
    /** Steps without a snapshot yet. Only the EDT removes a step from it (F-21), together with its snapshot. */
    private final List<Step> pendingSnapshots = new LinkedList<Step>();
    private volatile boolean recording;
    private Listener listener;
    private int counter;

    // text editing state
    private JTextComponent editing;
    private String textAtFocusGain;

    /** @param golden the existing golden snapshots of {@code scenario}; empty for a new scenario */
    Recorder(AgentConfig cfg, Scenario scenario, Map<String, Snapshot> golden) {
        this.cfg = cfg;
        this.scenario = scenario;
        this.snapshots.putAll(golden);
        this.taker = new SnapshotTaker(scenario.maxRows);
        for (Step s : scenario.steps) counter = Math.max(counter, numberOf(s.id));
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                snapshotLoop();
            }
        }, "smoker-recorder-snapshots");
        t.setDaemon(true);
        t.start();
    }

    void setListener(Listener l) {
        listener = l;
    }

    Scenario scenario() {
        return scenario;
    }

    void start() {
        Toolkit.getDefaultToolkit().addAWTEventListener(this,
                AWTEvent.MOUSE_EVENT_MASK | AWTEvent.KEY_EVENT_MASK | AWTEvent.FOCUS_EVENT_MASK);
        recording = true;
        say("recording");
    }

    void pause() {
        recording = false;
        say("paused");
    }

    boolean isRecording() {
        return recording;
    }

    boolean hasSnapshot(Step s) {
        synchronized (snapshots) {
            return snapshots.containsKey(s.id);
        }
    }

    /** The golden snapshot of {@code s}, or null while it is pending. */
    Snapshot snapshot(Step s) {
        synchronized (snapshots) {
            return snapshots.get(s.id);
        }
    }

    /** Writes the scenario and its golden file. The golden file has the steps in scenario order. */
    void save() throws Exception {
        Files.createDirectories(cfg.scenario.toAbsolutePath().getParent());
        LinkedHashMap<String, Snapshot> golden = new LinkedHashMap<String, Snapshot>();
        synchronized (snapshots) {
            for (Step s : scenario.steps) {
                Snapshot snap = snapshots.get(s.id);
                if (snap != null) golden.put(s.id, snap);
            }
        }
        Json.write(cfg.scenario, scenario);
        ScenarioFiles.writeGolden(cfg.scenario, scenario.name, golden);
        say("saved " + cfg.scenario + " (" + scenario.steps.size() + " steps, " + golden.size() + " snapshots)");
    }

    void remove(Step s) {
        scenario.steps.remove(s);
        synchronized (snapshots) {
            snapshots.remove(s.id);
        }
    }

    // ---- AWT events --------------------------------------------------------------------------

    @Override
    public void eventDispatched(AWTEvent event) {
        if (!recording || !(event.getSource() instanceof Component)) return;
        Component src = (Component) event.getSource();
        if (isSmokerUi(src)) return;

        int id = event.getID();
        if (id == MouseEvent.MOUSE_PRESSED && editing != null && !SwingUtilities.isDescendingFrom(src, editing)) {
            // A target that takes no focus (menu item, toolbar button) gives the field no FOCUS_LOST.
            // Flush the typed text now, so that SET_TEXT comes before the click and has its own snapshot (F-21).
            flushText();
        }
        if (id == MouseEvent.MOUSE_PRESSED || id == KeyEvent.KEY_PRESSED || id == FocusEvent.FOCUS_GAINED) {
            // First input of the next step: the component has not processed it yet (F-21).
            takePendingSnapshots();
        }
        if (event instanceof FocusEvent) {
            onFocus((FocusEvent) event, src);
        } else if (event instanceof KeyEvent) {
            onKey((KeyEvent) event, src);
        } else if (event instanceof MouseEvent) {
            onMouse((MouseEvent) event, src);
        }
    }

    private void onFocus(FocusEvent e, Component src) {
        if (e.getID() == FocusEvent.FOCUS_GAINED && src instanceof JPasswordField) {
            // Do not record a password: the scenario file would contain it in plain text (F-19).
            if (editing != null) flushText();
            editing = null;
            say("password field: typing is not recorded. Add a SET_TEXT step by hand, or set the value outside the scenario.");
        } else if (e.getID() == FocusEvent.FOCUS_GAINED && src instanceof JTextComponent) {
            editing = (JTextComponent) src;
            textAtFocusGain = editing.getText();
        } else if (e.getID() == FocusEvent.FOCUS_LOST && src == editing) {
            flushText();
        }
    }

    /** Emits SET_TEXT if the focused text component changed since focus gain. */
    private void flushText() {
        if (editing == null) return;
        String now = editing.getText();
        if (!Locators.eq(now, textAtFocusGain)) {
            add(Action.setText(Locators.resolve(editing), now));
            textAtFocusGain = now;
        }
    }

    private void onKey(KeyEvent e, Component src) {
        if (e.getID() != KeyEvent.KEY_PRESSED) return;
        int code = e.getKeyCode();
        boolean modifier = (e.getModifiersEx() & (KeyEvent.CTRL_DOWN_MASK | KeyEvent.ALT_DOWN_MASK | KeyEvent.META_DOWN_MASK)) != 0;
        boolean special = code == KeyEvent.VK_ENTER || code == KeyEvent.VK_ESCAPE || code == KeyEvent.VK_TAB
                || code == KeyEvent.VK_DELETE || (code >= KeyEvent.VK_F1 && code <= KeyEvent.VK_F12);
        if (!modifier && !special) return;
        if (code == KeyEvent.VK_CONTROL || code == KeyEvent.VK_ALT || code == KeyEvent.VK_SHIFT || code == KeyEvent.VK_META) return;
        if (src == editing) {
            flushText();               // text first, then the key (e.g. ENTER in a field)
            takePendingSnapshots();    // the field has not processed the key yet: this is the state after typing
        }
        Locator target = src.getName() != null && !Locators.isAutoName(src.getName()) ? Locators.resolve(src) : null;
        String vk = vkName(code);
        if (vk == null) return;
        Action a = Action.key(vk, e.getModifiersEx());
        a.locator = target;
        add(a);
    }

    private static java.util.Map<Integer, String> VK_NAMES;

    /** "ENTER", "F5", … — the names KeyStroke.getKeyStroke("pressed X") understands (locale independent). */
    static synchronized String vkName(int code) {
        if (VK_NAMES == null) {
            VK_NAMES = new java.util.HashMap<Integer, String>();
            for (java.lang.reflect.Field f : KeyEvent.class.getFields()) {
                if (f.getName().startsWith("VK_") && f.getType() == int.class) {
                    try {
                        Integer v = f.getInt(null);
                        if (!VK_NAMES.containsKey(v)) VK_NAMES.put(v, f.getName().substring(3));
                    } catch (IllegalAccessException ignored) {
                    }
                }
            }
        }
        return VK_NAMES.get(code);
    }

    private void onMouse(MouseEvent e, Component src) {
        if (e.getButton() != MouseEvent.BUTTON1) return;
        final Component target = interactiveAncestor(src);
        if (actsOnRelease(target)) {
            if (firesActionOnRelease(e, target)) add(Action.click(Locators.resolve(target)));
            return;
        }
        if (e.getID() != MouseEvent.MOUSE_CLICKED) return;
        if (target == null) {
            add(Action.click(Locators.resolve(src)));
            return;
        }
        if (target instanceof JTextComponent) return;          // typing is captured as SET_TEXT
        if (target instanceof AbstractButton) {
            add(Action.click(Locators.resolve(target)));
            return;
        }
        // Selections: read the new state after Swing has processed the click.
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                Action a = new Action();
                a.type = Action.Type.SELECT;
                if (target instanceof JList) {
                    JPopupMenu popup = (JPopupMenu) SwingUtilities.getAncestorOfClass(JPopupMenu.class, target);
                    if (popup != null && popup.getInvoker() instanceof JComboBox) {
                        JComboBox<?> cb = (JComboBox<?>) popup.getInvoker();
                        a.locator = Locators.resolve(cb);
                        a.value = String.valueOf(cb.getSelectedItem());
                    } else {
                        a.locator = Locators.resolve(target);
                        a.value = String.valueOf(((JList<?>) target).getSelectedIndex());
                    }
                } else if (target instanceof JTable) {
                    a.locator = Locators.resolve(target);
                    a.value = String.valueOf(((JTable) target).getSelectedRow());
                } else if (target instanceof JTabbedPane) {
                    a.locator = Locators.resolve(target);
                    a.value = String.valueOf(((JTabbedPane) target).getSelectedIndex());
                } else if (target instanceof JComboBox) {
                    return; // opening the popup is not a step; the selection in its list is
                } else {
                    a = Action.click(Locators.resolve(target));
                }
                if (a.value == null || !"-1".equals(a.value)) add(a);
            }
        });
    }

    /**
     * Buttons and menu items fire their action on MOUSE_RELEASED. If the action opens or closes a modal dialog,
     * no MOUSE_CLICKED arrives for the button (F-20), so the recorder records these on the release.
     * A JMenu only opens its menu and stays on MOUSE_CLICKED.
     */
    static boolean actsOnRelease(Component target) {
        return target instanceof AbstractButton && !(target instanceof JMenu);
    }

    /**
     * True if Swing fires the action of the button for this event. The AWT event listener sees the event before
     * the button, so the model still has the state of before the release. Must run on the EDT.
     */
    static boolean firesActionOnRelease(MouseEvent e, Component target) {
        if (e.getID() != MouseEvent.MOUSE_RELEASED || e.getButton() != MouseEvent.BUTTON1) return false;
        if (!actsOnRelease(target) || !target.isEnabled()) return false;
        ButtonModel m = ((AbstractButton) target).getModel();
        if (target instanceof JMenuItem) return m.isArmed();
        return m.isArmed() && m.isPressed();
    }

    private static Component interactiveAncestor(Component c) {
        for (Component p = c; p != null && !(p instanceof Window); p = p.getParent()) {
            if (Locators.isInteractive(p)) return p;
        }
        return null;
    }

    static boolean isSmokerUi(Component c) {
        Window w = Locators.windowOf(c);
        return w != null && w.getName() != null && w.getName().startsWith(SnapshotTaker.SMOKER_WINDOW_PREFIX);
    }

    // ---- steps -------------------------------------------------------------------------------

    private void add(Action a) {
        Step s = new Step(String.format(Locale.ROOT, "s%03d", ++counter), a.toString(), a);
        scenario.steps.add(s);
        if (listener != null) listener.stepAdded(s);
        synchronized (pendingSnapshots) {
            pendingSnapshots.add(s);
            pendingSnapshots.notifyAll();
        }
    }

    /**
     * On the EDT, at the first input of the next step: takes the snapshot of every pending step now, before the input
     * changes the UI (F-21). Normally only the newest step is pending. An older pending step had no input after it
     * either, so the current state is its state too: one snapshot serves all of them.
     */
    private void takePendingSnapshots() {
        List<Step> due;
        synchronized (pendingSnapshots) {
            if (pendingSnapshots.isEmpty()) return;
            due = new ArrayList<Step>(pendingSnapshots);
            pendingSnapshots.clear();
        }
        try {
            store(due, taker.take());
        } catch (RuntimeException e) {
            say("snapshot failed: " + e);
        }
    }

    private void store(List<Step> steps, Snapshot snap) {
        synchronized (snapshots) {
            for (Step s : steps) {
                if (!snapshots.containsKey(s.id)) snapshots.put(s.id, snap);
            }
        }
        if (listener != null) for (Step s : steps) listener.stepUpdated(s);
    }

    /**
     * Off-EDT loop for the last step and for steps that become quiet in time: wait for the UI to settle, then store
     * the golden snapshot. The step stays pending while the loop waits. The snapshot runs on the EDT and takes the
     * step only if it is still pending: if an input came first, the EDT has already taken its snapshot.
     */
    private void snapshotLoop() {
        while (true) {
            final Step s;
            synchronized (pendingSnapshots) {
                while (pendingSnapshots.isEmpty()) {
                    try { pendingSnapshots.wait(); } catch (InterruptedException e) { return; }
                }
                s = pendingSnapshots.get(0);
            }
            try {
                Quiescence.await(scenario.quietMs, scenario.stepTimeoutMs);
                Quiescence.onEdt(new Callable<Void>() {
                    @Override
                    public Void call() {
                        synchronized (pendingSnapshots) {
                            if (!pendingSnapshots.remove(s)) return null;
                        }
                        store(Collections.singletonList(s), taker.take());
                        return null;
                    }
                });
            } catch (Exception e) {
                say("snapshot failed: " + e);
            }
        }
    }

    private void say(String s) {
        SmokerAgent.log(s);
        if (listener != null) listener.status(s);
    }

    private static int numberOf(String id) {
        try {
            return Integer.parseInt(id.replaceAll("\\D", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
