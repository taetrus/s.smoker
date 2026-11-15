package smoker.agent;

import smoker.core.model.Locator;

import javax.swing.AbstractButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTree;
import javax.swing.text.JTextComponent;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.Frame;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;

/** Resolves components to stable locators and back (R-21). */
final class Locators {

    private static volatile WindowKeys windowKeys = new WindowKeys(null);

    private Locators() {}

    /** Sets the title rules of the scenario. {@code null} means no rules. */
    static void useWindowKeys(WindowKeys k) {
        windowKeys = k == null ? new WindowKeys(null) : k;
    }

    /** The key of a window: its title, or the fixed key of a matching title rule. */
    static String keyOf(Window w) {
        return windowKeys.keyOf(w);
    }

    /**
     * The window a user would say the component belongs to: for items inside popup menus
     * (menus, combo popups) that is the invoker's window, not the heavyweight popup.
     */
    static Window logicalWindowOf(Component c) {
        for (int guard = 0; guard < 8 && c != null; guard++) {
            javax.swing.JPopupMenu popup = c instanceof javax.swing.JPopupMenu ? (javax.swing.JPopupMenu) c
                    : (javax.swing.JPopupMenu) javax.swing.SwingUtilities.getAncestorOfClass(javax.swing.JPopupMenu.class, c);
            if (popup == null || popup.getInvoker() == null) break;
            c = popup.getInvoker();
        }
        return windowOf(c);
    }

    static Locator resolve(Component c) {
        Locator l = new Locator();
        Window w = logicalWindowOf(c);
        l.windowTitle = keyOf(w);
        l.className = classNameOf(c);
        if (c.getName() != null && !c.getName().isEmpty() && !isAutoName(c.getName())) {
            l.name = c.getName();
            return l;
        }
        l.text = textOf(c);
        // index among same class + text within the window
        int idx = 0;
        if (w != null) {
            for (Component o : allComponents(w)) {
                if (o == c) break;
                if (classNameOf(o).equals(l.className) && eq(textOf(o), l.text)) idx++;
            }
        }
        l.index = idx == 0 ? null : idx;
        if (w != null && c.isShowing() && w.isShowing()) {
            java.awt.Point p = c.getLocationOnScreen();
            java.awt.Point wp = w.getLocationOnScreen();
            l.x = p.x - wp.x + c.getWidth() / 2;
            l.y = p.y - wp.y + c.getHeight() / 2;
        }
        return l;
    }

    /** Swing generates names like "null.contentPane", "OptionPane.button", "frame0" — not user-chosen, so not stable. */
    static boolean isAutoName(String n) {
        return n.startsWith("null.") || n.startsWith("OptionPane") || n.matches("(frame|dialog|window)\\d+")
                || n.endsWith(".glassPane") || n.endsWith(".layeredPane") || n.endsWith(".contentPane");
    }

    /**
     * The class name of a locator: the simple class name, or the superclass name for an anonymous class.
     * {@link #resolve} and {@link #find} must use this same rule. A subclass such as {@code JPasswordField}
     * must not match the locator {@code JTextField} (F-23).
     */
    static String classNameOf(Component c) {
        String n = c.getClass().getSimpleName();
        return n.isEmpty() ? c.getClass().getSuperclass().getSimpleName() : n;
    }

    static Component find(Locator l) {
        List<Component> matches = new ArrayList<Component>();
        for (Window w : Window.getWindows()) {
            if (!w.isShowing()) continue;
            if (l.windowTitle != null && !l.isNamed() && !eq(keyOf(w), l.windowTitle)) continue;
            for (Component c : allComponents(w)) {
                if (l.isNamed()) {
                    if (l.name.equals(c.getName())) matches.add(c);
                } else {
                    if (!classNameOf(c).equals(l.className)) continue;
                    if (!eq(textOf(c), l.text)) continue;
                    matches.add(c);
                }
            }
        }
        if (matches.isEmpty()) return null;
        if (l.isNamed()) {
            // prefer the one in the front-most window
            Window front = frontWindow();
            for (Component c : matches) if (windowOf(c) == front) return c;
            return matches.get(0);
        }
        int want = l.index == null ? 0 : l.index;
        return want < matches.size() ? matches.get(want) : null;
    }

    static Window frontWindow() {
        Window[] ws = Window.getWindows();
        Window best = null;
        for (Window w : ws) {
            if (!w.isShowing()) continue;
            if (w.isFocused()) return w;
            if (w instanceof Dialog && ((Dialog) w).isModal()) best = w;
            else if (best == null) best = w;
        }
        return best;
    }

    static List<Component> allComponents(Container root) {
        List<Component> out = new ArrayList<Component>();
        collect(root, out);
        return out;
    }

    private static void collect(Component c, List<Component> out) {
        out.add(c);
        if (c instanceof javax.swing.JMenu) {
            // menu items live in a popup that is not part of the frame hierarchy while closed
            for (Component k : ((javax.swing.JMenu) c).getMenuComponents()) collect(k, out);
            return;
        }
        if (c instanceof Container) for (Component k : ((Container) c).getComponents()) collect(k, out);
    }

    static Window windowOf(Component c) {
        while (c != null && !(c instanceof Window)) c = c.getParent();
        return (Window) c;
    }

    static String titleOf(Window w) {
        if (w instanceof Frame) return ((Frame) w).getTitle();
        if (w instanceof Dialog) return ((Dialog) w).getTitle();
        if (w == null) return null;
        return "<" + w.getClass().getSimpleName() + ">";
    }

    /** Visible, user-facing text used as a locator key. Editable text is deliberately excluded. */
    static String textOf(Component c) {
        if (c instanceof AbstractButton) return nz(((AbstractButton) c).getText());
        if (c instanceof JLabel) return nz(((JLabel) c).getText());
        if (c instanceof JFrame) return nz(((JFrame) c).getTitle());
        if (c instanceof JDialog) return nz(((JDialog) c).getTitle());
        return null;
    }

    static boolean isInteractive(Component c) {
        return c instanceof AbstractButton || c instanceof JTextComponent || c instanceof JComboBox
                || c instanceof JList || c instanceof JTable || c instanceof JTree || c instanceof JTabbedPane;
    }

    private static String nz(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    static boolean eq(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }
}
