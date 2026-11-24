package smoker.agent;

import smoker.core.model.Action;
import smoker.core.model.Locator;

import javax.swing.AbstractButton;
import javax.swing.JComboBox;
import javax.swing.JFormattedTextField;
import javax.swing.JSpinner;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTree;
import javax.swing.tree.TreePath;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;
import java.awt.Component;
import java.awt.KeyboardFocusManager;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.WindowEvent;
import java.text.ParseException;
import java.util.Locale;

/**
 * Semantic replay (R-31): invokes the component API on the EDT instead of moving the OS mouse,
 * so it needs no focus, no screen, and is immune to DPI/LAF/layout differences.
 * Falls back to synthesized AWT events for generic components. A java.awt.Robot fallback for native
 * dialogs is a planned extension (see architecture.md).
 */
final class ActionExecutor {

    private ActionExecutor() {}

    /** Must be called on the EDT. */
    static void execute(Action a) throws Exception {
        switch (a.type) {
            case CLICK: click(require(a.locator), a); break;
            case SET_TEXT: setText(require(a.locator), a.value == null ? "" : a.value); break;
            case SELECT: select(require(a.locator), a); break;
            case SET_CELL: setCell(require(a.locator), a); break;
            case KEY: key(a); break;
            case WAIT: Thread.sleep(Long.parseLong(a.value)); break;
            case CLOSE_WINDOW: closeWindow(a.locator); break;
            case WAIT_FOR: throw new IllegalStateException("WAIT_FOR runs in the player");
            default: throw new IllegalArgumentException("unknown action " + a.type);
        }
    }

    private static Component require(Locator l) {
        Component c = Locators.find(l);
        if (c == null) throw new IllegalStateException("component not found: " + l);
        return c;
    }

    /** True on Windows: a popup menu opens at the release. Elsewhere it opens at the press. */
    static boolean popupOnRelease() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    private static void click(Component c, Action a) {
        if (a.clickCount() != 1 || a.mouseButton() != 1 || a.hasPlace()) {
            placeClick(c, a);
            return;
        }
        if (c instanceof JMenu) {
            ((JMenu) c).doClick();
            return;
        }
        if (c instanceof AbstractButton) {
            c.requestFocusInWindow();
            ((AbstractButton) c).doClick();
            closeOpenMenus();
            return;
        }
        // generic: synthesize press/release/click at the centre
        c.requestFocusInWindow();
        int x = c.getWidth() / 2, y = c.getHeight() / 2;
        long t = System.currentTimeMillis();
        c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_PRESSED, t, MouseEvent.BUTTON1_DOWN_MASK, x, y, 1, false, MouseEvent.BUTTON1));
        c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_RELEASED, t, 0, x, y, 1, false, MouseEvent.BUTTON1));
        c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_CLICKED, t, 0, x, y, 1, false, MouseEvent.BUTTON1));
    }

    /** Press, release and click at the centre of the place, once for each click of the count (spec 6.2, 6.3). */
    private static void placeClick(Component c, Action a) {
        Rectangle r = Places.reveal(c, a);
        int x = r.x + r.width / 2, y = r.y + r.height / 2;
        int button = a.mouseButton() == 3 ? MouseEvent.BUTTON3 : MouseEvent.BUTTON1;
        int down = button == MouseEvent.BUTTON3 ? MouseEvent.BUTTON3_DOWN_MASK : MouseEvent.BUTTON1_DOWN_MASK;
        boolean popup = button == MouseEvent.BUTTON3;
        c.requestFocusInWindow();
        int n = a.clickCount();
        for (int i = 1; i <= n; i++) {
            boolean last = i == n;
            long t = System.currentTimeMillis();
            c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_PRESSED, t, down, x, y, i, popup && last && !popupOnRelease(), button));
            c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_RELEASED, t, 0, x, y, i, popup && last && popupOnRelease(), button));
            c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_CLICKED, t, 0, x, y, i, false, button));
        }
    }

    /** After a JMenuItem.doClick() the menu hierarchy is normally closed by Swing; make sure. */
    private static void closeOpenMenus() {
        javax.swing.MenuSelectionManager.defaultManager().clearSelectedPath();
    }

    private static void setText(Component c, String text) {
        if (c instanceof JTextComponent) {
            c.requestFocusInWindow();
            ((JTextComponent) c).setText(text);
            return;
        }
        if (c instanceof JComboBox && ((JComboBox<?>) c).isEditable()) {
            ((JComboBox<?>) c).getEditor().setItem(text);
            return;
        }
        if (c instanceof JSpinner) {
            setSpinner((JSpinner) c, text);
            return;
        }
        throw new IllegalStateException("SET_TEXT not supported on " + c.getClass().getSimpleName());
    }

    /** Sets the text of the editor field, then commits it (spec 6.5). A refused text leaves the old text. */
    private static void setSpinner(JSpinner s, String text) {
        if (!(s.getEditor() instanceof JSpinner.DefaultEditor)) {
            throw new IllegalStateException("SET_TEXT needs the default editor of a JSpinner, not "
                    + s.getEditor().getClass().getSimpleName());
        }
        JFormattedTextField f = ((JSpinner.DefaultEditor) s.getEditor()).getTextField();
        String old = f.getText();
        f.setText(text);
        try {
            s.commitEdit();
        } catch (ParseException e) {
            f.setText(old);
            throw new IllegalStateException("the spinner refused the value \"" + text + "\"");
        }
    }

    /** Edits the cell, sets the value in the editor component and stops the edit (spec 6.4). */
    private static void setCell(Component c, Action a) {
        if (!(c instanceof JTable)) throw new IllegalStateException("SET_CELL not supported on " + c.getClass().getSimpleName());
        JTable t = (JTable) c;
        String value = a.value == null ? "" : a.value;
        int col = Places.columnOf(t, a.column);
        if (a.row == null || a.row < 0 || a.row >= t.getRowCount()) throw new IllegalStateException("row not found: " + a.row);
        Places.reveal(t, a);
        if (!t.isCellEditable(a.row, col) || !t.editCellAt(a.row, col)) {
            throw new IllegalStateException("cell not editable: row " + a.row + ", column " + a.column);
        }
        Component ed = t.getEditorComponent();
        boolean accepted = false;
        try {
            if (ed instanceof JTextComponent) {
                ((JTextComponent) ed).setText(value);
                accepted = true;
            } else if (ed instanceof AbstractButton) {
                ((AbstractButton) ed).setSelected(Boolean.parseBoolean(value));
                accepted = true;
            } else if (ed instanceof JComboBox) {
                accepted = chooseItem((JComboBox<?>) ed, value);
            } else {
                throw new IllegalStateException("SET_CELL not supported on editor "
                        + (ed == null ? "null" : ed.getClass().getSimpleName()));
            }
            // a combo box editor may end the edit itself when it takes an item
            if (accepted && t.getCellEditor() != null) accepted = t.getCellEditor().stopCellEditing();
        } finally {
            if (!accepted && t.getCellEditor() != null) t.getCellEditor().cancelCellEditing();
        }
        if (!accepted) throw new IllegalStateException("the cell editor refused the value \"" + value + "\"");
    }

    /** An editable combo box takes the text; another one takes the item whose text equals it. */
    private static boolean chooseItem(JComboBox<?> cb, String value) {
        if (cb.isEditable()) {
            cb.getEditor().setItem(value);
            return true;
        }
        for (int i = 0; i < cb.getItemCount(); i++) {
            if (value.equals(String.valueOf(cb.getItemAt(i)))) {
                cb.setSelectedIndex(i);
                return true;
            }
        }
        return false;
    }

    private static void select(Component c, Action a) {
        String value = a.value;
        if (c instanceof JTree) {
            if (a.path == null) throw new IllegalStateException("JTree SELECT needs a path");
            JTree t = (JTree) c;
            TreePath p = Places.findPath(t, a.path);
            t.setSelectionPath(p);
            t.scrollPathToVisible(p);
            return;
        }
        Integer idx = asInt(value);
        if (c instanceof JComboBox) {
            JComboBox<?> cb = (JComboBox<?>) c;
            if (idx != null) cb.setSelectedIndex(idx);
            else {
                for (int i = 0; i < cb.getItemCount(); i++) if (value.equals(String.valueOf(cb.getItemAt(i)))) { cb.setSelectedIndex(i); return; }
                throw new IllegalStateException("item not found in combo: " + value);
            }
        } else if (c instanceof JList) {
            JList<?> l = (JList<?>) c;
            if (idx != null) l.setSelectedIndex(idx);
            else {
                for (int i = 0; i < l.getModel().getSize(); i++) if (value.equals(String.valueOf(l.getModel().getElementAt(i)))) { l.setSelectedIndex(i); return; }
                throw new IllegalStateException("item not found in list: " + value);
            }
        } else if (c instanceof JTabbedPane) {
            JTabbedPane tp = (JTabbedPane) c;
            if (idx != null) tp.setSelectedIndex(idx);
            else {
                int i = tp.indexOfTab(value);
                if (i < 0) throw new IllegalStateException("tab not found: " + value);
                tp.setSelectedIndex(i);
            }
        } else if (c instanceof JTable) {
            if (idx == null) throw new IllegalStateException("JTable SELECT needs a row index");
            ((JTable) c).setRowSelectionInterval(idx, idx);
        } else {
            throw new IllegalStateException("SELECT not supported on " + c.getClass().getSimpleName());
        }
    }

    private static void key(Action a) {
        Component target = a.locator != null ? Locators.find(a.locator) : null;
        if (target == null) target = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        Window front = Locators.frontWindow();
        if (front != null && (target == null || Locators.windowOf(target) != front)) {
            // no OS focus (headless CI, no window manager): aim at the front window's last focus owner,
            // else its root pane — JComponent key bindings (default button, ESC=close) still fire there.
            target = front.getMostRecentFocusOwner();
            if (target == null && front instanceof javax.swing.RootPaneContainer) target = ((javax.swing.RootPaneContainer) front).getRootPane();
            if (target == null) target = front;
        }
        if (target == null) throw new IllegalStateException("no focus owner for KEY " + a.value);
        KeyStroke ks = KeyStroke.getKeyStroke("pressed " + a.value);
        if (ks == null) throw new IllegalArgumentException("unknown key: " + a.value);
        int mods = a.modifiers == null ? 0 : a.modifiers;
        int code = ks.getKeyCode();
        long t = System.currentTimeMillis();
        target.dispatchEvent(new KeyEvent(target, KeyEvent.KEY_PRESSED, t, mods, code, KeyEvent.CHAR_UNDEFINED));
        char ch = charFor(code);
        if (ch != KeyEvent.CHAR_UNDEFINED) target.dispatchEvent(new KeyEvent(target, KeyEvent.KEY_TYPED, t, mods, KeyEvent.VK_UNDEFINED, ch));
        target.dispatchEvent(new KeyEvent(target, KeyEvent.KEY_RELEASED, t, mods, code, KeyEvent.CHAR_UNDEFINED));
    }

    private static char charFor(int code) {
        switch (code) {
            case KeyEvent.VK_ENTER: return '\n';
            case KeyEvent.VK_TAB: return '\t';
            case KeyEvent.VK_SPACE: return ' ';
            case KeyEvent.VK_BACK_SPACE: return '\b';
            default: return KeyEvent.CHAR_UNDEFINED;
        }
    }

    private static void closeWindow(Locator l) {
        for (Window w : Window.getWindows()) {
            if (w.isShowing() && (l == null || Locators.eq(Locators.keyOf(w), l.windowTitle))) {
                w.dispatchEvent(new WindowEvent(w, WindowEvent.WINDOW_CLOSING));
                return;
            }
        }
        throw new IllegalStateException("window not found: " + (l == null ? "<front>" : l.windowTitle));
    }

    private static Integer asInt(String s) {
        if (s == null) return null;
        try {
            return Integer.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static boolean isEdt() {
        return SwingUtilities.isEventDispatchThread();
    }
}
