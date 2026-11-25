package smoker.agent;

import smoker.core.io.Json;
import smoker.core.io.ScenarioFiles;
import smoker.core.model.Action;
import smoker.core.model.LintFile;
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
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;
import javax.swing.tree.TreePath;
import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Point;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.Callable;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/**
 * Records user actions through {@link Toolkit#addAWTEventListener} (R-20) and, after each action settles,
 * captures the golden snapshot (R-23). Steps are auto-named and can be renamed in the toolbar (R-26).
 * {@link #save()} writes the scenario and the golden file (F-06), and the lint of the session (WP-09).
 */
final class Recorder implements AWTEventListener {

    interface Listener {
        /** A new step. It is the last step, or a SET_CELL directly before the last step (spec 6.4). */
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
    /** Change counter per step id, guarded by {@code pendingSnapshots}: {@link #changed} counts up (spec 6, Events). */
    private final Map<String, Integer> changeCount = new LinkedHashMap<String, Integer>();
    /** Ids of the steps whose golden a move removed. Guarded by {@code snapshots}. */
    private final Set<String> goldenRemoved = new HashSet<String>();
    private volatile boolean recording;
    /** Set by {@link #close}: the snapshot thread ends. */
    private volatile boolean closed;
    private final Thread snapshotThread;
    private Listener listener;
    private int counter;

    // text editing state
    private JTextComponent editing;
    private String textAtFocusGain;
    /** The password warning was given since the last password field got the focus (F-19). */
    private boolean passwordWarned;
    /** Table cell edits (spec 6.4). EDT only. */
    private final CellEditTracker cells = new CellEditTracker();
    /** Spinners with a custom editor that got the status text already (spec 6.5). EDT only. */
    private final Set<JSpinner> spinnersSaid = Collections.newSetFromMap(new WeakHashMap<JSpinner, Boolean>());

    /** @param golden the existing golden snapshots of {@code scenario}; empty for a new scenario */
    Recorder(AgentConfig cfg, Scenario scenario, Map<String, Snapshot> golden) {
        this.cfg = cfg;
        this.scenario = scenario;
        this.snapshots.putAll(golden);
        this.taker = new SnapshotTaker(scenario.maxRows, "named".equals(scenario.compare));
        for (Step s : scenario.steps) counter = Math.max(counter, numberOf(s.id));
        snapshotThread = new Thread(new Runnable() {
            @Override
            public void run() {
                snapshotLoop();
            }
        }, "smoker-recorder-snapshots");
        snapshotThread.setDaemon(true);
        snapshotThread.start();
    }

    /**
     * Stops the snapshot thread. A pending step gets no snapshot after this call. A record session has one recorder
     * for the life of the JVM; a test that makes many recorders must close each one, because each snapshot thread
     * pings the EDT while it waits, and the pings of other threads delay the quiet wait.
     */
    void close() {
        closed = true;
        snapshotThread.interrupt();
    }

    void setListener(Listener l) {
        listener = l;
    }

    Scenario scenario() {
        return scenario;
    }

    /**
     * Starts or resumes the recording. On the EDT, before the first event: the tracker drops an edit that ended during
     * the pause (the recorder saw no event of it) and keeps an open edit watched.
     */
    void start() {
        try {
            Quiescence.onEdt(new Callable<Void>() {
                @Override
                public Void call() {
                    cells.check();
                    // AWT calls a listener once per registration: after pause and start, each event came twice.
                    Toolkit.getDefaultToolkit().removeAWTEventListener(Recorder.this);
                    Toolkit.getDefaultToolkit().addAWTEventListener(Recorder.this,
                            AWTEvent.MOUSE_EVENT_MASK | AWTEvent.KEY_EVENT_MASK | AWTEvent.FOCUS_EVENT_MASK);
                    recording = true;
                    return null;
                }
            });
        } catch (Exception e) {
            throw new IllegalStateException("cannot start the recorder", e);
        }
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

    /**
     * Writes the scenario and its golden file, then the lint file {@code <scenario>.lint.json}. The golden file has
     * the steps in scenario order. A lint failure does not stop the save: the scenario is already written.
     */
    void save() throws Exception {
        Files.createDirectories(cfg.scenarios.get(0).toAbsolutePath().getParent());
        LinkedHashMap<String, Snapshot> golden = new LinkedHashMap<String, Snapshot>();
        synchronized (snapshots) {
            for (Step s : scenario.steps) {
                Snapshot snap = snapshots.get(s.id);
                if (snap != null) golden.put(s.id, snap);
            }
        }
        Json.write(cfg.scenarios.get(0), scenario);
        ScenarioFiles.writeGolden(cfg.scenarios.get(0), scenario.name, golden);
        String saved = "saved " + cfg.scenarios.get(0) + " (" + scenario.steps.size() + " steps, " + golden.size() + " snapshots)";
        // A save without steps while paused: the person can think that the clicks were recorded (WP-09 Windows check).
        String hint = scenario.steps.isEmpty() && !recording ? "; no steps: the recorder is paused, click Record to record the clicks" : "";
        Path lintFile = ScenarioFiles.lintPath(cfg.scenarios.get(0));
        try {
            LintFile lint = lint();
            Json.write(lintFile, lint);
            say(saved + "; " + lint.lint.size() + " component(s) without a real name in " + lintFile + hint);
        } catch (Exception e) {
            say(saved + "; cannot write " + lintFile + ": " + e + hint);
        }
    }

    /**
     * The lint of this session: all snapshots of the session and one more snapshot now, so that the windows that show
     * at the save count too. That snapshot is for the lint only: no step gets it.
     */
    private LintFile lint() throws Exception {
        Quiescence.onEdt(new Callable<Void>() {
            @Override
            public Void call() {
                taker.take();
                return null;
            }
        });
        LintFile f = new LintFile();
        f.scenarioName = scenario.name;
        f.lint.addAll(taker.lintEntries());
        return f;
    }

    /** True after a move removed the golden of {@code s}, until {@link #changed} clears the mark (spec 7.3). */
    boolean goldenRemoved(Step s) {
        synchronized (snapshots) {
            return goldenRemoved.contains(s.id);
        }
    }

    /** The snapshot of the nearest earlier step that has one, or null. Must run on the EDT. */
    Snapshot previousSnapshot(Step s) {
        int i = scenario.steps.indexOf(s);
        for (int k = i - 1; k >= 0; k--) {
            Snapshot snap = snapshot(scenario.steps.get(k));
            if (snap != null) return snap;
        }
        return null;
    }

    /**
     * The action of {@code s} changed in place (spec 6, Events). The step gets a new name only when its name was the
     * default name before the change. Its golden goes and the step is pending again, unless a WAIT_FOR step follows
     * (the player takes no snapshot there). A quiet wait that started before this call does not take the snapshot.
     * Call it for the last step only (Tasks 5 and 7): a step in the middle would get a snapshot of a later state.
     * Must run on the EDT.
     *
     * @param nameBefore {@code s.action.toString()} as it was before the caller changed the action
     */
    void changed(Step s, String nameBefore) {
        if (s.name == null || s.name.equals(nameBefore)) s.name = s.action.toString();
        synchronized (snapshots) {
            snapshots.remove(s.id);
            goldenRemoved.remove(s.id);
        }
        int i = scenario.steps.indexOf(s);
        boolean beforeWait = i >= 0 && i + 1 < scenario.steps.size() && isWaitFor(scenario.steps.get(i + 1));
        synchronized (pendingSnapshots) {
            Integer n = changeCount.get(s.id);
            changeCount.put(s.id, n == null ? 1 : n + 1);
            if (!beforeWait && !pendingSnapshots.contains(s)) pendingSnapshots.add(s);
            pendingSnapshots.notifyAll();
        }
        if (listener != null) listener.stepUpdated(s);
    }

    /**
     * Swaps {@code s} with its neighbour (spec 7.3). The golden of both steps is wrong now: it goes, also a pending
     * snapshot. The mark "golden removed" means that a step needs a golden and has none because of the move. Both
     * swapped steps get it, and so does the step before them when it has no golden and is not pending. A checked step
     * that is directly before a WAIT_FOR step loses its golden and gets no mark: the player takes no snapshot there.
     * No effect at the list border. Must run on the EDT.
     *
     * @param delta -1 (up) or +1 (down)
     */
    void move(Step s, int delta) {
        int i = scenario.steps.indexOf(s);
        int j = i + delta;
        if (i < 0 || j < 0 || j >= scenario.steps.size()) return;
        Step other = scenario.steps.get(j);
        scenario.steps.set(i, other);
        scenario.steps.set(j, s);
        int low = Math.min(i, j);
        List<Step> checked = new ArrayList<Step>();
        if (low > 0) checked.add(scenario.steps.get(low - 1));
        checked.add(scenario.steps.get(low));
        checked.add(scenario.steps.get(low + 1));
        for (Step c : checked) {
            int k = scenario.steps.indexOf(c);
            boolean beforeWait = k + 1 < scenario.steps.size() && isWaitFor(scenario.steps.get(k + 1));
            boolean swapped = c == s || c == other;
            if (beforeWait) {
                dropGolden(c, false);
            } else if (swapped) {
                dropGolden(c, true);
            } else if (!hasSnapshot(c) && !isPending(c)) {
                synchronized (snapshots) {
                    goldenRemoved.add(c.id);
                }
            }
        }
        if (listener != null) for (Step c : checked) listener.stepUpdated(c);
    }

    private boolean isPending(Step s) {
        synchronized (pendingSnapshots) {
            return pendingSnapshots.contains(s);
        }
    }

    /** Removes the golden and the pending state of {@code s}; the mark "golden removed" is set or cleared. */
    private void dropGolden(Step s, boolean mark) {
        synchronized (pendingSnapshots) {
            pendingSnapshots.remove(s);
        }
        synchronized (snapshots) {
            snapshots.remove(s.id);
            if (mark) goldenRemoved.add(s.id);
            else goldenRemoved.remove(s.id);
        }
    }

    private static boolean isWaitFor(Step s) {
        return s.action != null && s.action.type == Action.Type.WAIT_FOR;
    }

    /** Sets the group label of {@code s}; blank text or null removes it. Must run on the EDT. */
    void setGroup(Step s, String group) {
        String g = group == null ? "" : group.trim();
        s.group = g.isEmpty() ? null : g;
        if (listener != null) listener.stepUpdated(s);
    }

    /** Test hook: adds a step as the recorder does for a recorded action. Must run on the EDT. */
    Step addForTest(Action a) {
        add(a);
        return scenario.steps.get(scenario.steps.size() - 1);
    }

    void remove(Step s) {
        scenario.steps.remove(s);
        synchronized (snapshots) {
            snapshots.remove(s.id);
            goldenRemoved.remove(s.id);
        }
        synchronized (pendingSnapshots) {
            changeCount.remove(s.id);
        }
    }

    // ---- AWT events --------------------------------------------------------------------------

    @Override
    public void eventDispatched(AWTEvent event) {
        if (!recording || !(event.getSource() instanceof Component)) return;
        Component src = (Component) event.getSource();
        if (isSmokerUi(src)) return;

        int id = event.getID();
        // An event of a cell edit records no other step; the edit gives one SET_CELL at its end (spec 6.4).
        final boolean cellEdit = cells.event(event, src);
        final int stepsBefore = scenario.steps.size();
        final EndKey endKey = cellEdit ? EndKey.of(event, src) : null;
        if (id == MouseEvent.MOUSE_PRESSED && editing != null && !SwingUtilities.isDescendingFrom(src, editing)) {
            // A target that takes no focus (menu item, toolbar button) gives the field no FOCUS_LOST.
            // Flush the typed text now, so that SET_TEXT comes before the click and has its own snapshot (F-21).
            flushText();
        }
        if (id == MouseEvent.MOUSE_PRESSED || id == KeyEvent.KEY_PRESSED || id == FocusEvent.FOCUS_GAINED) {
            // First input of the next step: the component has not processed it yet (F-21).
            takePendingSnapshots(id == FocusEvent.FOCUS_GAINED);
        }
        if (cells.active()) {
            // The table processes the event after this listener: read the end of the edit in a later EDT task.
            SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    endOfCellEdit(!cellEdit, stepsBefore, endKey);
                }
            });
        }
        if (cellEdit) return;
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
            // The warning comes at the first key that edits the field (onKey), not here: a dialog can give the
            // focus to a password field without input (Windows check of WP-09, 2026-11-23).
            if (editing != null) flushText();
            editing = null;
            passwordWarned = false;
        } else if (e.getID() == FocusEvent.FOCUS_GAINED && src instanceof JTextComponent) {
            editing = (JTextComponent) src;
            textAtFocusGain = editing.getText();
        } else if (e.getID() == FocusEvent.FOCUS_LOST && src == editing) {
            flushText();
        }
    }

    /**
     * Adds SET_CELL when a cell edit ended with a new value (spec 6.4). A password field as editor gives the
     * warning and no step (F-19).
     *
     * <p>SET_CELL never gets a golden at record time (Ruling 7). The input that ends the edit acts before this task
     * runs: a check box toggles, Enter moves the selection, a button acts. So SET_CELL gets the mark "golden removed"
     * (run and approve), and the replay gives its golden.
     * <p>The input of a step can end the edit in its own action, for example a button whose action listener stops the
     * edit at the release. Its step is already in the list: SET_CELL goes directly before it (spec 6.4).
     * <p>Enter or Tab of the edit that moved the selection of the table comes after SET_CELL as a KEY step with the
     * normal snapshot, so that the replay moves the selection too.
     *
     * @param byOtherInput the event that ended the edit is not part of the edit, for example the press on a button
     * @param stepsBefore  the number of steps before that event
     * @param endKey       the Enter or Tab key of the edit, or null
     */
    private void endOfCellEdit(boolean byOtherInput, int stepsBefore, EndKey endKey) {
        if (!recording) return;   // start() drops an edit that ended during the pause
        CellEditTracker.Edit e = cells.check();
        if (e == null) return;
        if (e.password) {
            say("password field: a table cell edit is not recorded. Add a SET_CELL step by hand, or set the value outside the scenario.");
            return;
        }
        Step s = newStep(Action.setCell(Locators.resolve(e.table), e.row, e.column, e.value));
        int at = byOtherInput && stepsBefore < scenario.steps.size() ? stepsBefore : scenario.steps.size();
        scenario.steps.add(at, s);
        synchronized (snapshots) {
            goldenRemoved.add(s.id);
        }
        if (listener != null) listener.stepAdded(s);
        if (endKey != null && endKey.table == e.table && endKey.moved()) add(endKey.key);
    }

    /** An Enter or Tab key of a cell edit, and the lead cell of its table before the table processed the key. */
    private static final class EndKey {
        final Action key;
        final JTable table;
        final int row;
        final int column;

        private EndKey(Action key, JTable table) {
            this.key = key;
            this.table = table;
            this.row = table.getSelectionModel().getLeadSelectionIndex();
            this.column = table.getColumnModel().getSelectionModel().getLeadSelectionIndex();
        }

        /** The KEY step for a KEY_PRESSED of Enter or Tab in a table, else null. Must run on the EDT. */
        static EndKey of(AWTEvent event, Component src) {
            if (event.getID() != KeyEvent.KEY_PRESSED) return null;
            KeyEvent k = (KeyEvent) event;
            if (k.getKeyCode() != KeyEvent.VK_ENTER && k.getKeyCode() != KeyEvent.VK_TAB) return null;
            JTable t = src instanceof JTable ? (JTable) src : (JTable) SwingUtilities.getAncestorOfClass(JTable.class, src);
            if (t == null) return null;
            Action a = Action.key(vkName(k.getKeyCode()), k.getModifiersEx());
            a.locator = Locators.nameOf(t) != null ? Locators.resolve(t) : null;   // the rule of onKey
            return new EndKey(a, t);
        }

        /** True if the key moved the lead cell, as JTable does for Enter and Tab with the focus on the table. */
        boolean moved() {
            return table.getSelectionModel().getLeadSelectionIndex() != row
                    || table.getColumnModel().getSelectionModel().getLeadSelectionIndex() != column;
        }
    }

    /** Emits SET_TEXT if the focused text component changed since focus gain. */
    private void flushText() {
        if (editing == null) return;
        String now = editing.getText();
        if (!Locators.eq(now, textAtFocusGain)) {
            JSpinner spinner = spinnerOf(editing);
            if (spinner == null) {
                add(Action.setText(Locators.resolve(editing), now));
            } else if (spinner.getEditor() instanceof JSpinner.DefaultEditor) {
                spinnerValue(spinner, now, false);    // the locator is the spinner, not the field (spec 6.5)
            } else {
                customSpinner(spinner);
            }
            textAtFocusGain = now;
        }
    }

    private static JSpinner spinnerOf(Component c) {
        return (JSpinner) SwingUtilities.getAncestorOfClass(JSpinner.class, c);
    }

    /**
     * Records the value of a spinner as SET_TEXT. With {@code merge} (arrow click), the recorder changes the last step
     * when it is a SET_TEXT on the same spinner; else it adds a step.
     */
    private void spinnerValue(JSpinner spinner, String text, boolean merge) {
        Locator l = Locators.resolve(spinner);
        int n = scenario.steps.size();
        Step last = n == 0 ? null : scenario.steps.get(n - 1);
        if (merge && last != null && last.action.type == Action.Type.SET_TEXT && last.action.locator != null
                && last.action.locator.toString().equals(l.toString())) {
            if (Locators.eq(last.action.value, text)) return;
            String nameBefore = last.action.toString();
            last.action.value = text;
            changed(last, nameBefore);
            return;
        }
        add(Action.setText(l, text));
    }

    /**
     * An arrow button of a spinner (spec 6.5): no CLICK. The editor field has the new text after Swing processed the
     * event, so the recorder reads it in a later EDT task.
     */
    private void onSpinnerArrow(final JSpinner spinner) {
        if (!(spinner.getEditor() instanceof JSpinner.DefaultEditor)) {
            customSpinner(spinner);
            return;
        }
        final JTextComponent field = ((JSpinner.DefaultEditor) spinner.getEditor()).getTextField();
        final String before = field.getText();
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                if (!recording) return;
                String now = field.getText();
                if (Locators.eq(now, before)) return;     // for example an arrow at the limit
                if (field == editing) textAtFocusGain = now;     // no second step at the focus loss
                spinnerValue(spinner, now, true);
            }
        });
    }

    /** A spinner with a custom editor: the replay cannot set its value (spec 6.5). The status text comes once. */
    private void customSpinner(JSpinner spinner) {
        if (spinnersSaid.add(spinner)) say("spinner with a custom editor: the value is not recorded");
    }

    private void onKey(KeyEvent e, Component src) {
        if (e.getID() != KeyEvent.KEY_PRESSED) return;
        int code = e.getKeyCode();
        boolean modifier = (e.getModifiersEx() & (KeyEvent.CTRL_DOWN_MASK | KeyEvent.ALT_DOWN_MASK | KeyEvent.META_DOWN_MASK)) != 0;
        if (src instanceof JPasswordField && !passwordWarned && editsText(e, modifier)) {
            passwordWarned = true;
            say("password field: typing is not recorded. Add a SET_TEXT step by hand, or set the value outside the scenario.");
        }
        boolean special = code == KeyEvent.VK_ENTER || code == KeyEvent.VK_ESCAPE || code == KeyEvent.VK_TAB
                || code == KeyEvent.VK_DELETE || (code >= KeyEvent.VK_F1 && code <= KeyEvent.VK_F12);
        if (!modifier && !special) return;
        if (code == KeyEvent.VK_CONTROL || code == KeyEvent.VK_ALT || code == KeyEvent.VK_SHIFT || code == KeyEvent.VK_META) return;
        if (src == editing) {
            flushText();               // text first, then the key (e.g. ENTER in a field)
            takePendingSnapshots(false);    // the field has not processed the key yet: this is the state after typing
        }
        // Only a named component (real or automatic name) is a KEY target; else the key goes to the focus owner.
        Locator target = Locators.nameOf(src) != null ? Locators.resolve(src) : null;
        String vk = vkName(code);
        if (vk == null) return;
        Action a = Action.key(vk, e.getModifiersEx());
        a.locator = target;
        add(a);
    }

    /** True for a key that changes the text of a field: a printable character, Backspace or Delete. */
    static boolean editsText(KeyEvent e, boolean modifier) {
        int code = e.getKeyCode();
        if (code == KeyEvent.VK_BACK_SPACE || code == KeyEvent.VK_DELETE) return true;
        char ch = e.getKeyChar();
        return !modifier && ch != KeyEvent.CHAR_UNDEFINED && !Character.isISOControl(ch);
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
        final Component target = interactiveAncestor(src);
        if (e.getButton() == MouseEvent.BUTTON3) {
            // Spec 6.3: the press records the right-click on each platform; isPopupTrigger is not read.
            if (e.getID() != MouseEvent.MOUSE_PRESSED) return;
            Component on = target != null ? target : src;
            Action a = Action.click(Locators.resolve(on));
            a.button = 3;
            Places.fill(a, on, SwingUtilities.convertPoint(src, e.getPoint(), on));
            add(a);
            return;
        }
        if (e.getButton() != MouseEvent.BUTTON1) return;
        if (src instanceof AbstractButton && spinnerOf(src) != null) {
            // The spinner changes its value in the button action at the release; auto-repeat changes it while the
            // button stays pressed (spec 6.5). The recorder reads the value after the press and after the release.
            if (e.getID() == MouseEvent.MOUSE_PRESSED || e.getID() == MouseEvent.MOUSE_RELEASED) onSpinnerArrow(spinnerOf(src));
            return;
        }
        if (actsOnRelease(target)) {
            if (firesActionOnRelease(e, target)) add(Action.click(Locators.resolve(target)));
            return;
        }
        if (e.getID() != MouseEvent.MOUSE_CLICKED) return;
        boolean second = e.getClickCount() == 2;
        if (target == null) {
            Locator l = Locators.resolve(src);
            if (second) doubleClick(l);
            else add(Action.click(l));
            return;
        }
        if (target instanceof JTextComponent) return;          // typing is captured as SET_TEXT
        if (target instanceof AbstractButton) {
            add(Action.click(Locators.resolve(target)));
            return;
        }
        Point p = SwingUtilities.convertPoint(src, e.getPoint(), target);
        if ((target instanceof JTable || target instanceof JList || target instanceof JTree) && comboOf(target) == null) {
            if (second) {
                // Spec 6.2: the second click adds a CLICK with the place; the first click gave the SELECT.
                final Action a = Action.click(Locators.resolve(target));
                a.count = 2;
                if (!Places.fill(a, target, p)) return;
                SwingUtilities.invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        if (target instanceof JTable && ((JTable) target).isEditing()) return;   // SET_CELL replaces it (6.4)
                        add(a);
                    }
                });
                return;
            }
            if (target instanceof JTree) {
                // Spec 6.1: a click on a row records SELECT with the lead selection path, read after Swing processed the click.
                final JTree tree = (JTree) target;
                if (tree.getPathForLocation(p.x, p.y) == null) return;   // for example the expand handle
                SwingUtilities.invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        TreePath lead = tree.getLeadSelectionPath();
                        if (lead != null) add(Action.select(Locators.resolve(tree), Places.labels(tree, lead)));
                    }
                });
                return;
            }
        }
        // Selections: read the new state after Swing has processed the click.
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                Action a = new Action();
                a.type = Action.Type.SELECT;
                if (target instanceof JList) {
                    JComboBox<?> cb = comboOf(target);
                    if (cb != null) {
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

    /** The combo box whose popup holds {@code c}, or null. */
    static JComboBox<?> comboOf(Component c) {
        JPopupMenu popup = (JPopupMenu) SwingUtilities.getAncestorOfClass(JPopupMenu.class, c);
        return popup != null && popup.getInvoker() instanceof JComboBox ? (JComboBox<?>) popup.getInvoker() : null;
    }

    /**
     * The second click on a component with no interactive ancestor (spec 6.2). If the last step is the plain click of
     * the first click, it becomes a double-click and is pending again: its snapshot showed the state between the two
     * clicks. Else a new step comes; no earlier step changes.
     */
    private void doubleClick(Locator l) {
        Step last = scenario.steps.isEmpty() ? null : scenario.steps.get(scenario.steps.size() - 1);
        if (last != null && last.action != null && last.action.type == Action.Type.CLICK && last.action.count == null
                && last.action.button == null && !last.action.hasPlace() && sameLocator(last.action.locator, l)) {
            String before = last.action.toString();
            last.action.count = 2;
            changed(last, before);
            return;
        }
        Action a = Action.click(l);
        a.count = 2;
        add(a);
    }

    private static boolean sameLocator(Locator a, Locator b) {
        if (a == null || b == null) return a == b;
        return Locators.eq(a.name, b.name) && Locators.eq(a.windowTitle, b.windowTitle)
                && Locators.eq(a.className, b.className) && Locators.eq(a.text, b.text)
                && Locators.eq(a.index, b.index) && Locators.eq(a.x, b.x) && Locators.eq(a.y, b.y);
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
        Step s = newStep(a);
        scenario.steps.add(s);
        if (listener != null) listener.stepAdded(s);
        synchronized (pendingSnapshots) {
            pendingSnapshots.add(s);
            pendingSnapshots.notifyAll();
        }
    }

    private Step newStep(Action a) {
        return new Step(String.format(Locale.ROOT, "s%03d", ++counter), a.toString(), a);
    }

    /**
     * Adds a WAIT_FOR step (WP-03, F-02). The player takes no snapshot of the step directly before a WAIT_FOR
     * (spec 6.4), so the recorder removes the snapshot of the current last step, taken or pending. Must run on the EDT.
     */
    void addWaitFor(Action a) {
        if (!scenario.steps.isEmpty()) {
            Step last = scenario.steps.get(scenario.steps.size() - 1);
            synchronized (pendingSnapshots) {
                pendingSnapshots.remove(last);
            }
            synchronized (snapshots) {
                snapshots.remove(last.id);
            }
            if (listener != null) listener.stepUpdated(last);
        }
        add(a);
    }

    /**
     * On the EDT, at the first input of the next step: takes the snapshot of every pending step now, before the input
     * changes the UI (F-21). Normally only the newest step is pending. An older pending step had no input after it
     * either, so the current state is its state too: one snapshot serves all of them.
     * <p>A WAIT_FOR step whose condition does not hold yet (WP-03):
     * <ul>
     * <li>FOCUS_GAINED: the step stays pending. A window that shows, or a modal dialog that closes, gives this event
     * without input in the application. The snapshot thread takes the step after the match, as the player does.</li>
     * <li>MOUSE_PRESSED, KEY_PRESSED: F-21 wins. The step gets the snapshot now, and the recorder gives a warning.</li>
     * </ul>
     *
     * @param focusGained true for FOCUS_GAINED, false for a mouse press or a key press
     */
    private void takePendingSnapshots(boolean focusGained) {
        List<Step> due = new ArrayList<Step>();
        List<Step> early = new ArrayList<Step>();
        synchronized (pendingSnapshots) {
            for (Iterator<Step> it = pendingSnapshots.iterator(); it.hasNext(); ) {
                Step s = it.next();
                if (isWaiting(s)) {
                    if (focusGained) continue;
                    early.add(s);
                }
                due.add(s);
                it.remove();
            }
        }
        for (Step s : early) {
            say("WAIT_FOR " + s.action.locator + " did not match before the next input: its golden shows the state"
                    + " before the match");
        }
        if (due.isEmpty()) return;
        try {
            store(due, taker.take());
        } catch (RuntimeException e) {
            say("snapshot failed: " + e);
        }
    }

    /** True for a WAIT_FOR step whose condition does not match now. Must run on the EDT. */
    private static boolean isWaiting(Step s) {
        if (s.action == null || s.action.type != Action.Type.WAIT_FOR) return false;
        try {
            Pattern p = WaitCondition.validate(s.action);
            return !WaitCondition.matches(p, WaitCondition.read(s.action.locator, s.action.attribute));
        } catch (IllegalArgumentException e) {
            return false;
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
            final int changesBefore;
            synchronized (pendingSnapshots) {
                while (pendingSnapshots.isEmpty()) {
                    if (closed) return;
                    try { pendingSnapshots.wait(); } catch (InterruptedException e) { return; }
                }
                if (closed) return;
                s = pendingSnapshots.get(0);
                changesBefore = changeCountOf(s);
            }
            try {
                if (s.action != null && s.action.type == Action.Type.WAIT_FOR) awaitCondition(s);
                Quiescence.await(scenario.quietMs, scenario.stepTimeoutMs);
                if (closed) return;
                Quiescence.onEdt(new Callable<Void>() {
                    @Override
                    public Void call() {
                        synchronized (pendingSnapshots) {
                            if (!pendingSnapshots.contains(s)) return null;
                            // changed() came during the quiet wait: wait again (the step stays pending)
                            if (changeCountOf(s) != changesBefore) return null;
                            pendingSnapshots.remove(s);
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

    /** Must hold the lock of {@code pendingSnapshots}. */
    private int changeCountOf(Step s) {
        Integer n = changeCount.get(s.id);
        return n == null ? 0 : n;
    }

    private static final BooleanSupplier NO_HANG = new BooleanSupplier() {
        @Override
        public boolean getAsBoolean() {
            return false;
        }
    };

    /**
     * Waits until the condition of a WAIT_FOR step matches, so that its snapshot shows the awaited state. On a time-out
     * the step keeps its place and gets the snapshot of the current state. Runs on the snapshot thread.
     */
    private void awaitCondition(Step s) {
        Pattern p;
        try {
            p = WaitCondition.validate(s.action);
        } catch (IllegalArgumentException e) {
            say("WAIT_FOR is not valid: " + e.getMessage());
            return;
        }
        WaitCondition.Outcome o = WaitCondition.await(s.action, p, NO_HANG);
        boolean pending;
        synchronized (pendingSnapshots) {
            pending = pendingSnapshots.contains(s);
        }
        if (o.kind != WaitCondition.Kind.MATCHED && pending) {
            say("WAIT_FOR did not match: " + WaitCondition.timeoutMessage(s.action, o.lastValue));
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
