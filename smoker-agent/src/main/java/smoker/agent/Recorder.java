package smoker.agent;

import smoker.core.io.Json;
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
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;

/**
 * Records user actions through {@link Toolkit#addAWTEventListener} (R-20) and, after each action settles,
 * captures the golden snapshot (R-23). Steps are auto-named and can be renamed in the toolbar (R-26).
 */
final class Recorder implements AWTEventListener {

    interface Listener {
        void stepAdded(Step s);
        void stepUpdated(Step s);
        void status(String text);
    }

    private final AgentConfig cfg;
    private final Scenario scenario;
    private final SnapshotTaker taker = new SnapshotTaker();
    private final List<Step> pendingSnapshots = new LinkedList<Step>();
    private volatile boolean recording;
    private Listener listener;
    private int counter;

    // text editing state
    private JTextComponent editing;
    private String textAtFocusGain;

    Recorder(AgentConfig cfg, Scenario scenario) {
        this.cfg = cfg;
        this.scenario = scenario;
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

    void save() throws Exception {
        Files.createDirectories(cfg.scenario.toAbsolutePath().getParent());
        Json.write(cfg.scenario, scenario);
        say("saved " + cfg.scenario + " (" + scenario.steps.size() + " steps)");
    }

    void remove(Step s) {
        scenario.steps.remove(s);
    }

    // ---- AWT events --------------------------------------------------------------------------

    @Override
    public void eventDispatched(AWTEvent event) {
        if (!recording || !(event.getSource() instanceof Component)) return;
        Component src = (Component) event.getSource();
        if (isSmokerUi(src)) return;

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
        if (src == editing) flushText(); // text first, then the key (e.g. ENTER in a field)
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

    /** Off-EDT loop: wait for the UI to settle after each recorded action, then store the golden snapshot. */
    private void snapshotLoop() {
        while (true) {
            Step s;
            synchronized (pendingSnapshots) {
                while (pendingSnapshots.isEmpty()) {
                    try { pendingSnapshots.wait(); } catch (InterruptedException e) { return; }
                }
                s = pendingSnapshots.remove(0);
            }
            try {
                Quiescence.await(scenario.quietMs, scenario.stepTimeoutMs);
                // if more actions were recorded meanwhile, this snapshot still belongs to the oldest step
                s.expected = Quiescence.onEdt(new Callable<Snapshot>() {
                    @Override
                    public Snapshot call() {
                        return taker.take();
                    }
                });
                if (listener != null) listener.stepUpdated(s);
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
