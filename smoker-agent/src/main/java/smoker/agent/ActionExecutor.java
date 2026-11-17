package smoker.agent;

import smoker.core.model.Action;
import smoker.core.model.Locator;

import javax.swing.AbstractButton;
import javax.swing.JComboBox;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;
import java.awt.Component;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.WindowEvent;

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
            case CLICK: click(require(a.locator)); break;
            case SET_TEXT: setText(require(a.locator), a.value == null ? "" : a.value); break;
            case SELECT: select(require(a.locator), a.value); break;
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

    private static void click(Component c) {
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
        throw new IllegalStateException("SET_TEXT not supported on " + c.getClass().getSimpleName());
    }

    private static void select(Component c, String value) {
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
