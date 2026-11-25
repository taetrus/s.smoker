package smoker.agent;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import smoker.core.model.Action;
import smoker.core.model.NodeSnapshot;
import smoker.core.model.Scenario;
import smoker.core.model.Snapshot;
import smoker.core.model.Step;

import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.RowSorter;
import javax.swing.SortOrder;
import javax.swing.SwingUtilities;
import javax.swing.plaf.basic.BasicComboPopup;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import javax.swing.text.JTextComponent;
import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.DefaultKeyboardFocusManager;
import java.awt.GraphicsEnvironment;
import java.awt.KeyboardFocusManager;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.FocusEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * WP-06 spec 6.4: the recorder records a table cell edit as SET_CELL. Real Swing components get synthetic events.
 * Skipped without a display (Linux: use xvfb-run).
 */
public class RecorderCellEditTest {

    private static final int NAME = 0;
    private static final int QTY = 1;
    private static final int DONE = 2;
    private static final int KIND = 3;

    private JFrame frame;
    private JTable table;
    private DefaultTableModel model;
    private JButton ok;
    private JLabel label;
    private KeyboardFocusManager previousFocusManager;
    private Recorder recorder;
    private final List<String> statuses = Collections.synchronizedList(new ArrayList<String>());

    private static AgentConfig recordConfig() {
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put("mode", "record");
        m.put("scenario.1", "target/recorder-cell-edit-test.json");
        m.put("screenshots", "off");
        return AgentConfig.fromMap(m, "test");
    }

    @Before
    public void showFrame() throws Exception {
        Assume.assumeFalse("needs a display", GraphicsEnvironment.isHeadless());
        onEdt(new Runnable() {
            public void run() {
                model = new DefaultTableModel(new Object[][] {
                        {"a", 1, false, "a", "f"}, {"b", 2, false, "a", "f"}, {"c", 3, false, "a", "f"}},
                        new Object[] {"Name", "Qty", "Done", "Kind", "Fixed"}) {
                    @Override public Class<?> getColumnClass(int c) {
                        return c == 1 ? Integer.class : c == 2 ? Boolean.class : String.class;
                    }
                    @Override public boolean isCellEditable(int r, int c) { return c != 4; }
                };
                table = new JTable(model);
                table.setName("t.items");
                table.getColumnModel().getColumn(KIND).setCellEditor(
                        new DefaultCellEditor(new JComboBox<String>(new String[] {"a", "b", "c"})));
                label = new JLabel("idle");
                label.setName("t.label");
                ok = new JButton("OK");
                ok.setName("t.ok");
                ok.addActionListener(new ActionListener() {
                    public void actionPerformed(ActionEvent e) {
                        label.setText("ok");
                    }
                });
                frame = new JFrame("RecorderCellEditTest");
                JPanel p = new JPanel();
                p.add(new JScrollPane(table));
                p.add(ok);
                p.add(label);
                frame.getContentPane().add(p);
                frame.pack();
                frame.setVisible(true);
            }
        });
        // The focus and key events are synthetic: DirectFocus gives them to the component, through the AWT event listeners.
        previousFocusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager();
        KeyboardFocusManager.setCurrentKeyboardFocusManager(new DirectFocus());
        recorder = new Recorder(recordConfig(), new Scenario(), new HashMap<String, Snapshot>());
        recorder.setListener(new Recorder.Listener() {
            public void stepAdded(Step s) {}
            public void stepUpdated(Step s) {}
            public void status(String text) {
                statuses.add(text);
            }
        });
        recorder.start();
    }

    @After
    public void disposeFrame() throws Exception {
        if (recorder != null) recorder.pause();
        if (previousFocusManager != null) KeyboardFocusManager.setCurrentKeyboardFocusManager(previousFocusManager);
        if (frame == null) return;
        onEdt(new Runnable() {
            public void run() {
                if (table.isEditing()) table.getCellEditor().cancelCellEditing();
                frame.dispose();
            }
        });
    }

    // ---- tests ----------------------------------------------------------------------------------

    @Test
    public void textCellEditEndedByEnter() throws Exception {
        clickCell(1, NAME, 1);
        startEdit(1, NAME);
        type("Ana");
        enter();
        List<Step> steps = steps();
        Action a = last(steps).action;
        assertSetCell(a, 1, "Name", "Ana");
        assertEquals("t.items", a.locator.name);
        assertNoType(steps, Action.Type.SET_TEXT);
        assertNoType(steps, Action.Type.KEY);
        // The recorded step replays (Task 3).
        recorder.pause();
        onEdt(new Runnable() {
            public void run() {
                model.setValueAt("b", 1, NAME);
            }
        });
        final Action recorded = a;
        onEdt(new Callable<Void>() {
            public Void call() throws Exception {
                ActionExecutor.execute(recorded);
                return null;
            }
        });
        assertEquals("Ana", valueAt(1, NAME));
    }

    /**
     * Review Focus 2, F-21: the press on a button ends the edit. SET_CELL comes first. Ruling 7: SET_CELL has no
     * golden and the mark "golden removed"; the CLICK golden shows the state after the action.
     */
    @Test
    public void editEndedByAButtonClick() throws Exception {
        clickCell(0, QTY, 1);
        startEdit(0, QTY);
        type("5");
        onEdt(new Runnable() {
            public void run() {
                ok.dispatchEvent(mouse(ok, MouseEvent.MOUSE_PRESSED, new Point(5, 5), 1));
                table.getCellEditor().stopCellEditing();   // the press stops the edit
            }
        });
        flush();
        dispatch(mouse(ok, MouseEvent.MOUSE_RELEASED, new Point(5, 5), 1));
        dispatch(mouse(ok, MouseEvent.MOUSE_CLICKED, new Point(5, 5), 1));
        flush();
        assertEquals("ok", onEdt(new Callable<String>() {
            public String call() {
                return label.getText();
            }
        }));
        List<Step> steps = steps();
        assertTrue(describe(steps), steps.size() >= 2);
        Step cell = steps.get(steps.size() - 2);
        Step click = steps.get(steps.size() - 1);
        assertSetCell(cell.action, 0, "Qty", "5");
        assertEquals(describe(steps), Action.Type.CLICK, click.action.type);
        assertEquals("t.ok", click.action.locator.name);
        awaitSnapshot(click, 10000);
        Snapshot snap = recorder.snapshot(click);
        assertEquals("ok", node(snap, "t.label").text);
        assertEquals("5", node(snap, "t.items").cells.get(0).get(QTY));
        assertNoGolden(cell);
    }

    /**
     * Ruling 4: the button action stops the edit at the release, after the CLICK step came. SET_CELL goes directly
     * before the CLICK, with no golden and the mark "golden removed". The toolbar list shows the same order.
     */
    @Test
    public void editEndedByTheButtonActionComesBeforeTheClick() throws Exception {
        final RecorderToolbar[] toolbar = new RecorderToolbar[1];
        onEdt(new Runnable() {
            public void run() {
                ok.addActionListener(new ActionListener() {
                    public void actionPerformed(ActionEvent e) {
                        if (table.isEditing()) table.getCellEditor().stopCellEditing();
                    }
                });
                toolbar[0] = new RecorderToolbar(recorder);
            }
        });
        try {
            clickCell(0, QTY, 1);
            startEdit(0, QTY);
            type("5");
            dispatch(mouse(ok, MouseEvent.MOUSE_PRESSED, new Point(5, 5), 1));
            flush();
            assertTrue("the press did not end the edit", isEditing());
            dispatch(mouse(ok, MouseEvent.MOUSE_RELEASED, new Point(5, 5), 1));
            dispatch(mouse(ok, MouseEvent.MOUSE_CLICKED, new Point(5, 5), 1));
            flush();
            assertFalse("the action stopped the edit", isEditing());
            assertEquals(5, valueAt(0, QTY));
            final List<Step> steps = steps();
            assertTrue(describe(steps), steps.size() >= 2);
            final Step cell = steps.get(steps.size() - 2);
            Step click = steps.get(steps.size() - 1);
            assertSetCell(cell.action, 0, "Qty", "5");
            assertEquals(describe(steps), Action.Type.CLICK, click.action.type);
            assertEquals("t.ok", click.action.locator.name);
            awaitSnapshot(click, 10000);
            assertEquals("ok", node(recorder.snapshot(click), "t.label").text);
            assertFalse("SET_CELL gets no golden", recorder.hasSnapshot(cell));
            assertTrue(recorder.goldenRemoved(cell));
            String[] rows = onEdt(new Callable<String[]>() {
                public String[] call() {
                    return new String[] {toolbar[0].listText(steps.size() - 2), toolbar[0].listText(steps.size() - 1)};
                }
            });
            assertTrue(rows[0], rows[0].startsWith(cell.id + " "));
            assertTrue(rows[1], rows[1].startsWith(click.id + " "));
        } finally {
            onEdt(new Runnable() {
                public void run() {
                    toolbar[0].dispose();
                }
            });
        }
    }

    /**
     * Fix round 1, Important 1: with terminateEditOnFocusLost, the focus change of the press stops the edit. The
     * FOCUS_LOST of the editor and the FOCUS_GAINED of the button come one after the other; the stop is in the
     * FOCUS_GAINED handling. Ruling 7: SET_CELL has no golden and the mark "golden removed"; the CLICK golden shows
     * the state after the action.
     */
    @Test
    public void editEndedByTheFocusChangeGivesSetCellWithoutAGolden() throws Exception {
        onEdt(new Runnable() {
            public void run() {
                table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
                // The test sends the focus events itself. In a focused window (Windows CI) the press would move the
                // real focus to the button and stop the edit before the test sends them.
                ok.setRequestFocusEnabled(false);
                ok.addFocusListener(new java.awt.event.FocusAdapter() {
                    @Override
                    public void focusGained(FocusEvent e) {
                        if (table.isEditing()) table.getCellEditor().stopCellEditing();   // as JTable.CellEditorRemover
                    }
                });
            }
        });
        clickCell(0, QTY, 1);
        startEdit(0, QTY);
        type("5");
        final Component ed = editor();
        dispatch(mouse(ok, MouseEvent.MOUSE_PRESSED, new Point(5, 5), 1));
        flush();
        assertTrue("the press alone does not stop the edit", isEditing());
        // The press asked for the focus: both focus events are in the queue, so the task of FOCUS_LOST runs after both.
        onEdt(new Runnable() {
            public void run() {
                ed.dispatchEvent(new FocusEvent(ed, FocusEvent.FOCUS_LOST, false, ok));
                ok.dispatchEvent(new FocusEvent(ok, FocusEvent.FOCUS_GAINED, false, ed));
            }
        });
        dispatch(mouse(ok, MouseEvent.MOUSE_RELEASED, new Point(5, 5), 1));
        dispatch(mouse(ok, MouseEvent.MOUSE_CLICKED, new Point(5, 5), 1));
        flush();
        assertFalse(isEditing());
        List<Step> steps = steps();
        assertTrue(describe(steps), steps.size() >= 2);
        Step cell = steps.get(steps.size() - 2);
        Step click = steps.get(steps.size() - 1);
        assertSetCell(cell.action, 0, "Qty", "5");
        assertEquals("t.ok", click.action.locator.name);
        awaitSnapshot(click, 10000);
        Snapshot snap = recorder.snapshot(click);
        assertEquals("5", node(snap, "t.items").cells.get(0).get(QTY));
        assertEquals("ok", node(snap, "t.label").text);
        assertNoGolden(cell);
    }

    /**
     * Ruling 7: one click on a check box cell of a row that is not selected toggles the value and selects the row.
     * SET_CELL has no golden; the replay of the recorded steps gives the state of the recording.
     */
    @Test
    public void checkBoxCellRoundTrip() throws Exception {
        clickCell(1, DONE, 1);
        assertEquals(Boolean.TRUE, valueAt(1, DONE));
        int recordedRow = selectedRow();
        assertEquals(1, recordedRow);
        List<Step> steps = steps();
        assertEquals(describe(steps), 2, steps.size());
        Step cell = steps.get(0);
        Step select = steps.get(1);
        assertSetCell(cell.action, 1, "Done", "true");
        assertEquals(describe(steps), Action.Type.SELECT, select.action.type);
        assertEquals("1", select.action.value);
        awaitSnapshot(select, 10000);
        assertNoGolden(cell);
        replay(steps);
        assertEquals(recordedRow, selectedRow());
        assertEquals(Boolean.TRUE, valueAt(1, DONE));
    }

    /**
     * Ruling 7: with the focus on the table, Enter ends the edit and JTable moves the selection down
     * (selectNextRowCell). The recorder adds KEY ENTER on the table after SET_CELL, so the replay moves it too.
     */
    @Test
    public void enterEndsTheEditAndMovesDown() throws Exception {
        clickCell(1, NAME, 1);
        onEdt(new Runnable() {
            public void run() {
                table.editCellAt(1, NAME);   // the focus stays on the table, as for an edit that typing starts
            }
        });
        dispatch(key(table, KeyEvent.VK_A, 'A'));
        onEdt(new Runnable() {
            public void run() {
                ((JTextComponent) table.getEditorComponent()).setText("Ana");
            }
        });
        dispatch(key(table, KeyEvent.VK_ENTER, '\n'));
        flush();
        assertFalse("ENTER stopped the edit", isEditing());
        assertEquals("Ana", valueAt(1, NAME));
        assertEquals("JTable moved the selection down", 2, selectedRow());
        List<Step> steps = steps();
        assertTrue(describe(steps), steps.size() >= 2);
        Step cell = steps.get(steps.size() - 2);
        Step enter = steps.get(steps.size() - 1);
        assertSetCell(cell.action, 1, "Name", "Ana");
        assertEquals(describe(steps), Action.Type.KEY, enter.action.type);
        assertEquals("ENTER", enter.action.value);
        assertEquals("t.items", enter.action.locator.name);
        awaitSnapshot(enter, 10000);
        assertNoGolden(cell);
        replay(steps);
        assertEquals("Ana", valueAt(1, NAME));
        assertEquals(2, selectedRow());
    }

    /** Fix round 1, Important 2: the editable rule of the JTable (as the replay uses it), not only of the model. */
    @Test
    public void tableThatOverridesIsCellEditableGivesSetCell() throws Exception {
        final JTable custom = onEdt(new Callable<JTable>() {
            public JTable call() {
                final String[] data = {"a", "b"};
                javax.swing.table.AbstractTableModel m = new javax.swing.table.AbstractTableModel() {
                    public int getRowCount() { return data.length; }
                    public int getColumnCount() { return 1; }
                    public String getColumnName(int c) { return "Name"; }
                    public Object getValueAt(int r, int c) { return data[r]; }
                    @Override public void setValueAt(Object v, int r, int c) {
                        data[r] = String.valueOf(v);
                        fireTableCellUpdated(r, c);
                    }
                };   // isCellEditable of the model: false
                JTable t = new JTable(m) {
                    @Override public boolean isCellEditable(int r, int c) { return true; }
                };
                t.setName("t.custom");
                frame.getContentPane().add(t, java.awt.BorderLayout.SOUTH);
                frame.pack();
                return t;
            }
        });
        clickCellOf(custom, 1, 0, 1);
        onEdt(new Runnable() {
            public void run() {
                custom.editCellAt(1, 0);
            }
        });
        final Component ed = onEdt(new Callable<Component>() {
            public Component call() {
                return custom.getEditorComponent();
            }
        });
        dispatch(new FocusEvent(ed, FocusEvent.FOCUS_GAINED));
        dispatch(key(ed, KeyEvent.VK_Z, 'Z'));
        onEdt(new Runnable() {
            public void run() {
                ((JTextComponent) ed).setText("Zoe");
            }
        });
        dispatch(key(ed, KeyEvent.VK_ENTER, '\n'));
        flush();
        List<Step> steps = steps();
        Action a = last(steps).action;
        assertSetCell(a, 1, "Name", "Zoe");
        assertEquals("t.custom", a.locator.name);
    }

    /** Fix round 1, F-19: a custom editor that wraps a password field in a panel. */
    @Test
    public void wrappedPasswordEditorIsNotRecorded() throws Exception {
        final JPasswordField pf = new JPasswordField();
        onEdt(new Runnable() {
            public void run() {
                table.getColumnModel().getColumn(NAME).setCellEditor(new WrappedPasswordEditor(pf));
            }
        });
        final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        final PrintStream old = System.out;
        System.setOut(new PrintStream(new OutputStream() {
            public void write(int b) {
                old.write(b);
                synchronized (buf) { buf.write(b); }
            }
        }, true));
        try {
            clickCell(1, NAME, 1);
            onEdt(new Runnable() {
                public void run() {
                    table.editCellAt(1, NAME);
                }
            });
            dispatch(new FocusEvent(pf, FocusEvent.FOCUS_GAINED));
            dispatch(key(pf, KeyEvent.VK_S, 's'));
            onEdt(new Runnable() {
                public void run() {
                    pf.setText("secret");
                }
            });
            dispatch(key(pf, KeyEvent.VK_ENTER, '\n'));
            flush();
            assertFalse(isEditing());
            assertEquals("secret", valueAt(1, NAME));
        } finally {
            System.setOut(old);
        }
        List<Step> steps = steps();
        assertNoType(steps, Action.Type.SET_CELL);
        assertNoType(steps, Action.Type.SET_TEXT);
        for (Step s : steps) {
            assertFalse(describe(steps), String.valueOf(s.action.value).contains("secret"));
            assertFalse(describe(steps), String.valueOf(s.name).contains("secret"));
        }
        synchronized (statuses) {
            for (String s : statuses) assertFalse(s, s.contains("secret"));
            assertTrue(statuses.toString(), statuses.contains("password field: a table cell edit is not recorded."
                    + " Add a SET_CELL step by hand, or set the value outside the scenario."));
        }
        String log;
        synchronized (buf) { log = buf.toString("UTF-8"); }
        assertFalse(log, log.contains("secret"));
    }

    /** Fix round 1: an edit that ends while the recorder is paused gives no SET_CELL after the resume. */
    @Test
    public void editEndedWhilePausedRecordsNothing() throws Exception {
        clickCell(1, NAME, 1);
        startEdit(1, NAME);
        type("Ana");
        recorder.pause();
        flush();
        enter();
        recorder.start();
        clickCell(2, NAME, 1);
        clickCell(0, QTY, 1);
        List<Step> steps = steps();
        assertNoType(steps, Action.Type.SET_CELL);
        // start() after pause() registers the listener one time only: one click gives one SELECT.
        int selects = 0;
        for (Step s : steps) if (s.action.type == Action.Type.SELECT && "2".equals(s.action.value)) selects++;
        assertEquals(describe(steps), 1, selects);
    }

    /** Review I2: an edit that stays open across a pause is recorded when it ends after the resume. */
    @Test
    public void editOpenAcrossAPauseIsRecorded() throws Exception {
        clickCell(1, NAME, 1);
        startEdit(1, NAME);
        type("Ana");
        recorder.pause();
        flush();
        recorder.start();
        enter();
        List<Step> steps = steps();
        assertSetCell(last(steps).action, 1, "Name", "Ana");
    }

    /** Fix round 1: the application changes the pressed cell, with no edit: no SET_CELL. */
    @Test
    public void valueChangeWithoutAnEditRecordsNothing() throws Exception {
        onEdt(new Runnable() {
            public void run() {
                table.getSelectionModel().addListSelectionListener(new javax.swing.event.ListSelectionListener() {
                    public void valueChanged(javax.swing.event.ListSelectionEvent e) {
                        int r = table.getSelectedRow();
                        if (r >= 0 && !"x".equals(model.getValueAt(r, NAME))) model.setValueAt("x", r, NAME);
                    }
                });
            }
        });
        clickCell(1, NAME, 1);
        assertFalse(isEditing());
        assertEquals("x", valueAt(1, NAME));
        assertNoType(steps(), Action.Type.SET_CELL);
    }

    /** A custom cell editor: a panel that holds a password field. */
    private static final class WrappedPasswordEditor extends javax.swing.AbstractCellEditor
            implements javax.swing.table.TableCellEditor {
        private final JPasswordField field;
        private final JPanel panel = new JPanel(new java.awt.BorderLayout());

        WrappedPasswordEditor(JPasswordField field) {
            this.field = field;
            panel.add(field);
            field.addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent e) {
                    stopCellEditing();
                }
            });
        }

        public Object getCellEditorValue() {
            return new String(field.getPassword());
        }

        public Component getTableCellEditorComponent(JTable t, Object value, boolean selected, int row, int column) {
            field.setText("");
            return panel;
        }
    }

    @Test
    public void cancelledEditRecordsNothing() throws Exception {
        clickCell(1, NAME, 1);
        startEdit(1, NAME);
        type("x");
        onEdt(new Runnable() {
            public void run() {
                Component ed = table.getEditorComponent();
                ed.dispatchEvent(key(ed, KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED));
                if (table.isEditing()) table.getCellEditor().cancelCellEditing();
            }
        });
        flush();
        List<Step> steps = steps();
        assertNoType(steps, Action.Type.SET_CELL);
        assertNoType(steps, Action.Type.KEY);
        assertNoType(steps, Action.Type.SET_TEXT);
        assertEquals("b", valueAt(1, NAME));
    }

    @Test
    public void editWithoutChangeRecordsNothing() throws Exception {
        clickCell(1, NAME, 1);
        startEdit(1, NAME);
        enter();
        List<Step> steps = steps();
        assertNoType(steps, Action.Type.SET_CELL);
        assertNoType(steps, Action.Type.KEY);
    }

    @Test
    public void booleanCellClick() throws Exception {
        clickCell(2, DONE, 1);
        assertEquals(Boolean.TRUE, valueAt(2, DONE));
        List<Step> steps = steps();
        int cells = 0;
        for (Step s : steps) {
            if (s.action.type == Action.Type.SET_CELL) {
                cells++;
                assertSetCell(s.action, 2, "Done", "true");
            }
            if (s.action.type == Action.Type.CLICK) fail("no CLICK: " + describe(steps));
        }
        assertEquals(describe(steps), 1, cells);
    }

    /** Review Focus 3: a combo box as cell editor gives one SET_CELL and no SELECT on the editor combo. */
    @Test
    public void comboCellEditor() throws Exception {
        clickCell(0, KIND, 1);
        startEdit(0, KIND);
        final JList<?> list = onEdt(new Callable<JList<?>>() {
            public JList<?> call() {
                JComboBox<?> cb = (JComboBox<?>) table.getEditorComponent();
                if (!cb.isPopupVisible()) cb.setPopupVisible(true);
                JList<?> l = ((BasicComboPopup) cb.getUI().getAccessibleChild(cb, 0)).getList();
                l.setSelectedIndex(1);
                return l;
            }
        });
        Point p = center(onEdt(new Callable<Rectangle>() {
            public Rectangle call() {
                return list.getCellBounds(1, 1);
            }
        }));
        dispatch(mouse(list, MouseEvent.MOUSE_PRESSED, p, 1));
        dispatch(mouse(list, MouseEvent.MOUSE_RELEASED, p, 1));
        dispatch(mouse(list, MouseEvent.MOUSE_CLICKED, p, 1));
        flush();
        assertFalse("the edit stopped", isEditing());
        assertEquals("b", valueAt(0, KIND));
        List<Step> steps = steps();
        int cells = 0;
        for (Step s : steps) {
            if (s.action.type == Action.Type.SET_CELL) {
                cells++;
                assertSetCell(s.action, 0, "Kind", "b");
            }
            if (s.action.type == Action.Type.SELECT) {
                assertEquals("only the SELECT of the row click: " + describe(steps), "t.items", s.action.locator.name);
            }
        }
        assertEquals(describe(steps), 1, cells);
    }

    @Test
    public void doubleClickThatStartsAnEdit() throws Exception {
        clickCell(1, NAME, 1);
        clickCell(1, NAME, 2);
        assertTrue("the double-click started the edit", isEditing());
        for (Step s : steps()) {
            if (s.action.type == Action.Type.CLICK) fail("no CLICK: " + describe(steps()));
        }
        startEdit(1, NAME);   // the editor field gets the focus, as the press reposted by Swing gives it
        type("Zed");
        enter();
        List<Step> steps = steps();
        assertSetCell(last(steps).action, 1, "Name", "Zed");
        assertNoType(steps, Action.Type.CLICK);
        assertNoType(steps, Action.Type.SET_TEXT);
    }

    /** F-19: a password field as cell editor gives no SET_CELL, and the password shows in no step and no log line. */
    @Test
    public void passwordEditorIsNotRecorded() throws Exception {
        onEdt(new Runnable() {
            public void run() {
                table.getColumnModel().getColumn(NAME).setCellEditor(new DefaultCellEditor(new JPasswordField()));
            }
        });
        final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        final PrintStream old = System.out;
        System.setOut(new PrintStream(new OutputStream() {
            public void write(int b) {
                old.write(b);
                synchronized (buf) { buf.write(b); }
            }
        }, true));
        try {
            clickCell(1, NAME, 1);
            startEdit(1, NAME);
            assertTrue(onEdt(new Callable<Boolean>() {
                public Boolean call() {
                    return table.getEditorComponent() instanceof JPasswordField;
                }
            }));
            type("secret");
            enter();
            assertEquals("secret", valueAt(1, NAME));
        } finally {
            System.setOut(old);
        }
        List<Step> steps = steps();
        assertNoType(steps, Action.Type.SET_CELL);
        assertNoType(steps, Action.Type.SET_TEXT);
        for (Step s : steps) {
            assertFalse(describe(steps), String.valueOf(s.action.value).contains("secret"));
            assertFalse(describe(steps), String.valueOf(s.name).contains("secret"));
        }
        synchronized (statuses) {
            for (String s : statuses) assertFalse(s, s.contains("secret"));
            assertTrue(statuses.toString(), statuses.contains("password field: a table cell edit is not recorded."
                    + " Add a SET_CELL step by hand, or set the value outside the scenario."));
        }
        String log;
        synchronized (buf) { log = buf.toString("UTF-8"); }
        assertFalse(log, log.contains("secret"));
    }

    /** Review Focus 1: with a row sorter, the row of SET_CELL is the view row. */
    @Test
    public void sortedTableUsesViewRow() throws Exception {
        onEdt(new Runnable() {
            public void run() {
                TableRowSorter<DefaultTableModel> sorter = new TableRowSorter<DefaultTableModel>(model);
                sorter.setSortKeys(Collections.singletonList(new RowSorter.SortKey(QTY, SortOrder.DESCENDING)));
                table.setRowSorter(sorter);
            }
        });
        assertEquals("view row 0 is model row 2", "c", onEdt(new Callable<Object>() {
            public Object call() {
                return table.getValueAt(0, NAME);
            }
        }));
        clickCell(0, NAME, 1);
        startEdit(0, NAME);
        type("Cy");
        enter();
        assertEquals("Cy", valueAt(2, NAME));
        List<Step> steps = steps();
        assertSetCell(last(steps).action, 0, "Name", "Cy");
    }

    @Test
    public void rowSelectStays() throws Exception {
        clickCell(1, NAME, 1);
        startEdit(1, NAME);
        type("Ana");
        enter();
        List<Step> steps = steps();
        assertEquals(describe(steps), 2, steps.size());
        assertEquals(describe(steps), Action.Type.SELECT, steps.get(0).action.type);
        assertEquals("t.items", steps.get(0).action.locator.name);
        assertEquals("1", steps.get(0).action.value);
        assertSetCell(steps.get(1).action, 1, "Name", "Ana");
    }

    // ---- helpers --------------------------------------------------------------------------------

    /** Ruling 7: a recorded SET_CELL has no golden and the mark "golden removed". */
    private void assertNoGolden(Step cell) {
        assertFalse("SET_CELL gets no golden", recorder.hasSnapshot(cell));
        assertTrue("SET_CELL has the mark golden removed", recorder.goldenRemoved(cell));
    }

    /**
     * Pauses the recorder, gives the table the values and the selection of before the recording, and replays the
     * actions of {@code steps} through ActionExecutor.
     */
    private void replay(final List<Step> steps) throws Exception {
        recorder.pause();
        onEdt(new Callable<Void>() {
            public Void call() throws Exception {
                if (table.isEditing()) table.getCellEditor().cancelCellEditing();
                Object[][] start = {{"a", 1, false, "a", "f"}, {"b", 2, false, "a", "f"}, {"c", 3, false, "a", "f"}};
                for (int r = 0; r < start.length; r++) {
                    for (int c = 0; c < start[r].length; c++) model.setValueAt(start[r][c], r, c);
                }
                table.clearSelection();
                table.getSelectionModel().setAnchorSelectionIndex(-1);
                table.getSelectionModel().setLeadSelectionIndex(-1);
                for (Step s : steps) ActionExecutor.execute(s.action);
                return null;
            }
        });
        flush();
    }

    private int selectedRow() throws Exception {
        return onEdt(new Callable<Integer>() {
            public Integer call() {
                return table.getSelectedRow();
            }
        });
    }

    private static void assertSetCell(Action a, int row, String column, String value) {
        assertEquals(String.valueOf(a), Action.Type.SET_CELL, a.type);
        assertEquals(String.valueOf(a), Integer.valueOf(row), a.row);
        assertEquals(String.valueOf(a), column, a.column);
        assertEquals(String.valueOf(a), value, a.value);
    }

    private static void assertNoType(List<Step> steps, Action.Type type) {
        for (Step s : steps) {
            if (s.action.type == type) fail("no " + type + ": " + describe(steps));
        }
    }

    private static Step last(List<Step> steps) {
        assertFalse("no steps", steps.isEmpty());
        return steps.get(steps.size() - 1);
    }

    /** Press, release and clicked on the cell, each through the AWT event listeners; then the EDT is flushed. */
    private void clickCell(int row, int column, int count) throws Exception {
        clickCellOf(table, row, column, count);
    }

    private static void clickCellOf(final JTable t, final int row, final int column, int count) throws Exception {
        Point p = center(onEdt(new Callable<Rectangle>() {
            public Rectangle call() {
                return t.getCellRect(row, column, false);
            }
        }));
        dispatch(mouse(t, MouseEvent.MOUSE_PRESSED, p, count));
        dispatch(mouse(t, MouseEvent.MOUSE_RELEASED, p, count));
        dispatch(mouse(t, MouseEvent.MOUSE_CLICKED, p, count));
        flush();
    }

    /** Starts the edit if the clicks did not, and gives the editor component the focus as Swing does. */
    private void startEdit(final int row, final int column) throws Exception {
        onEdt(new Runnable() {
            public void run() {
                if (!table.isEditing()) table.editCellAt(row, column);
            }
        });
        final Component ed = editor();
        dispatch(new FocusEvent(ed, FocusEvent.FOCUS_GAINED));
        flush();
    }

    /** The editor field gets a key press, then the typed text. */
    private void type(final String text) throws Exception {
        final Component ed = editor();
        dispatch(key(ed, KeyEvent.VK_A, text.charAt(0)));
        onEdt(new Runnable() {
            public void run() {
                ((JTextComponent) ed).setText(text);
            }
        });
        flush();
    }

    /** ENTER in the editor field: the field action stops the edit. */
    private void enter() throws Exception {
        dispatch(key(editor(), KeyEvent.VK_ENTER, '\n'));
        flush();
        assertFalse("ENTER stopped the edit", isEditing());
    }

    private Component editor() throws Exception {
        Component ed = onEdt(new Callable<Component>() {
            public Component call() {
                return table.getEditorComponent();
            }
        });
        if (ed == null) fail("the table is not editing");
        return ed;
    }

    private boolean isEditing() throws Exception {
        return onEdt(new Callable<Boolean>() {
            public Boolean call() {
                return table.isEditing();
            }
        });
    }

    private Object valueAt(final int modelRow, final int column) throws Exception {
        return onEdt(new Callable<Object>() {
            public Object call() {
                return model.getValueAt(modelRow, column);
            }
        });
    }

    private static KeyEvent key(Component c, int code, char ch) {
        return new KeyEvent(c, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, code, ch);
    }

    private static MouseEvent mouse(Component c, int id, Point p, int count) {
        int mods = id == MouseEvent.MOUSE_PRESSED ? InputEvent.BUTTON1_DOWN_MASK : 0;
        return new MouseEvent(c, id, System.currentTimeMillis(), mods, p.x, p.y, count, false, MouseEvent.BUTTON1);
    }

    private static void dispatch(final AWTEvent e) throws Exception {
        onEdt(new Runnable() {
            public void run() {
                ((Component) e.getSource()).dispatchEvent(e);
            }
        });
    }

    /** Two EDT round trips: the recorder reads the end of an edit in a later EDT task. */
    private static void flush() throws Exception {
        for (int i = 0; i < 2; i++) {
            onEdt(new Runnable() {
                public void run() {
                }
            });
        }
    }

    private Rectangle cellRect(final int row, final int column) throws Exception {
        return onEdt(new Callable<Rectangle>() {
            public Rectangle call() {
                return table.getCellRect(row, column, false);
            }
        });
    }

    private static Point center(Rectangle r) {
        return new Point(r.x + r.width / 2, r.y + r.height / 2);
    }

    private List<Step> steps() throws Exception {
        return onEdt(new Callable<List<Step>>() {
            public List<Step> call() {
                return new ArrayList<Step>(recorder.scenario().steps);
            }
        });
    }

    private void awaitSnapshot(Step s, long ms) throws Exception {
        long deadline = System.currentTimeMillis() + ms;
        long probeAt = System.currentTimeMillis() + 2000;
        while (!recorder.hasSnapshot(s)) {
            if (probeAt > 0 && System.currentTimeMillis() > probeAt) {
                probeAt = 0;
                System.out.println("[probe] slow snapshot " + s.id + QueueProbe.describe(500) + QueueProbe.quietTrace(1500) + QueueProbe.snapshotThreads());
            }
            if (System.currentTimeMillis() > deadline) fail("no snapshot for " + s.id + " within " + ms + " ms" + QueueProbe.describe(1000));
            Thread.sleep(50);
        }
    }

    private static NodeSnapshot node(Snapshot s, String name) {
        for (NodeSnapshot n : s.nodes) if (name.equals(n.name)) return n;
        fail("no node " + name);
        return null;
    }

    private static String describe(List<Step> steps) {
        List<String> out = new ArrayList<String>();
        for (Step s : steps) out.add(s.id + " " + s.action);
        return out.toString();
    }

    private static <T> T onEdt(Callable<T> c) throws Exception {
        return Quiescence.onEdt(c);
    }

    private static void onEdt(Runnable r) throws Exception {
        SwingUtilities.invokeAndWait(r);
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
}
