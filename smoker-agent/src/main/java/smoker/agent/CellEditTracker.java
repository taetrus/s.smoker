package smoker.agent;

import javax.swing.DefaultCellEditor;
import javax.swing.JComboBox;
import javax.swing.JPasswordField;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.table.TableCellEditor;
import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Point;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;

/**
 * Finds the start and the end of a table cell edit for the recorder (WP-06, spec 6.4). EDT only.
 * <p>The AWT event listener sees an event before the table processes it. So {@link #event} remembers the cell and its
 * value before the event, and the recorder calls {@link #check} in a later EDT task. One table at a time.
 */
final class CellEditTracker {

    /** An edit that ended with a value different from the value before. */
    static final class Edit {
        final JTable table;
        final int row;
        final String column;
        final String value;
        final boolean password;

        Edit(JTable table, int row, String column, String value, boolean password) {
            this.table = table;
            this.row = row;
            this.column = column;
            this.value = value;
            this.password = password;
        }
    }

    private JTable table;
    /** The editor component of the last edit. A combo box popup can get a click after its edit stopped. */
    private Component editor;
    /** A cell is watched: the fields below are set. */
    private boolean watching;
    private int viewRow;
    private String column;
    private int modelRow;
    private int modelColumn;
    private String before;
    private boolean password;

    /**
     * Called for each event before the recorder rules. Returns true if the event belongs to a cell edit: its source
     * is a table in a cell edit, or is in the editor component (also in the popup of an editor combo box). The
     * recorder then records no other step for the event. A mouse press or a key press on a table that is not in a
     * cell edit makes the cell at the point, or the lead cell, the watched cell.
     */
    boolean event(AWTEvent e, Component src) {
        if (inEdit(src)) return true;
        int id = e.getID();
        if (id != MouseEvent.MOUSE_PRESSED && id != KeyEvent.KEY_PRESSED) return false;
        editor = null;
        if (!(src instanceof JTable) || watching) return false;
        JTable t = (JTable) src;
        table = t;
        if (e instanceof MouseEvent) {
            Point p = ((MouseEvent) e).getPoint();
            watch(t.rowAtPoint(p), t.columnAtPoint(p));
        } else {
            watch(t.getSelectionModel().getLeadSelectionIndex(), t.getColumnModel().getSelectionModel().getLeadSelectionIndex());
        }
        return false;
    }

    private boolean inEdit(Component src) {
        JComboBox<?> combo = Recorder.comboOf(src);
        if (editor != null && (SwingUtilities.isDescendingFrom(src, editor) || combo == editor)) return true;
        Component c = combo != null ? combo : src;
        JTable t = c instanceof JTable ? (JTable) c : (JTable) SwingUtilities.getAncestorOfClass(JTable.class, c);
        if (t == null || !t.isEditing()) return false;
        if (!watching) table = t;
        if (t == table) editor = t.getEditorComponent();
        return true;
    }

    /** True when the recorder must call {@link #check} after the current event. */
    boolean active() {
        return watching || (table != null && table.isEditing());
    }

    /** Must run in a later EDT task than the event. Returns the edit that ended with a new value, or null. */
    Edit check() {
        if (table == null) return null;
        if (!table.isEditing()) return watching ? finish() : null;
        Edit ended = null;
        int r = table.getEditingRow();
        int c = table.getEditingColumn();
        if (watching && (r != viewRow || c != table.convertColumnIndexToView(modelColumn))) ended = finish();
        if (!watching) watch(r, c);
        editor = table.getEditorComponent();
        if (editor instanceof JPasswordField) password = true;
        return ended;
    }

    private void watch(int r, int c) {
        if (r < 0 || c < 0 || r >= table.getRowCount() || c >= table.getColumnCount()) return;
        watching = true;
        viewRow = r;
        column = Places.columnKey(table, c);
        modelRow = table.convertRowIndexToModel(r);
        modelColumn = table.convertColumnIndexToModel(c);
        before = text(table.getModel().getValueAt(modelRow, modelColumn));
        password = false;
    }

    private Edit finish() {
        watching = false;
        if (modelRow >= table.getModel().getRowCount() || modelColumn >= table.getModel().getColumnCount()) return null;
        // A value that changed without an edit (for example by the application) is not a SET_CELL.
        if (!table.getModel().isCellEditable(modelRow, modelColumn)) return null;
        String now = text(table.getModel().getValueAt(modelRow, modelColumn));
        if (now.equals(before)) return null;
        return new Edit(table, viewRow, column, now, password || passwordEditor());
    }

    /** True if the editor of the watched cell is a password field. No call to the editor: it has no side effect. */
    private boolean passwordEditor() {
        int c = table.convertColumnIndexToView(modelColumn);
        if (c < 0) return false;
        TableCellEditor ed = table.getCellEditor(viewRow, c);
        return ed instanceof DefaultCellEditor && ((DefaultCellEditor) ed).getComponent() instanceof JPasswordField;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
