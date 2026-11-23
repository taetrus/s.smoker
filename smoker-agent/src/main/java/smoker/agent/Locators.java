package smoker.agent;

import smoker.core.model.Locator;

import javax.swing.AbstractButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JSlider;
import javax.swing.JSpinner;
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
        l.name = realName(c);
        if (l.name != null) return l;
        // An automatic name is a locator only when it is unique in the window. Two panels of one class give
        // the same name, and find would give the first one: such a component gets the locator of an unnamed one.
        AutoNames.Hit auto = AutoNames.of(c);
        if (auto != null && uniqueIn(w, c, auto.name)) {
            l.name = auto.name;
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

    /** True when no other component of {@code w} has the name {@code name} ({@link #nameOf}). */
    private static boolean uniqueIn(Window w, Component c, String name) {
        if (w == null) return true;
        for (Component o : allComponents(w)) {
            if (o != c && name.equals(nameOf(o))) return false;
        }
        return true;
    }

    /**
     * Name prefixes that Swing UI delegates give to the parts they make, such as "Spinner.formattedTextField".
     * The check is case-sensitive. An application name that starts with one of these prefixes, such as
     * "Table.orders", also counts as a generated name. This is accepted: application names follow the
     * lower-case {@code <view>.<widget>} rule.
     */
    private static final String[] UI_DELEGATE_PREFIXES = {
            "Spinner.", "ComboBox.", "Table.", "Tree.", "List.", "FileChooser.", "ColorChooser."};

    /**
     * Swing generates names like "null.contentPane", "OptionPane.button", "frame0", "Spinner.formattedTextField"
     * — not user-chosen, so not stable.
     */
    static boolean isAutoName(String n) {
        if (n.startsWith("null.") || n.startsWith("OptionPane") || n.matches("(frame|dialog|window)\\d+")
                || n.endsWith(".glassPane") || n.endsWith(".layeredPane") || n.endsWith(".contentPane")) return true;
        for (String p : UI_DELEGATE_PREFIXES) {
            if (n.startsWith(p)) return true;
        }
        return false;
    }

    /** The name the application gave the component, or null when it has none (no name, empty or generated). */
    static String realName(Component c) {
        String n = c.getName();
        return n != null && !n.isEmpty() && !isAutoName(n) ? n : null;
    }

    /**
     * The name of a component for locators and snapshot paths: the real name, else the automatic name
     * ({@link AutoNames}), else null. Must run on the EDT.
     */
    static String nameOf(Component c) {
        String n = realName(c);
        if (n != null) return n;
        AutoNames.Hit h = AutoNames.of(c);
        return h == null ? null : h.name;
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

    /** The component of the locator, or null. Must run on the EDT. */
    static Component find(Locator l) {
        List<Component> matches = new ArrayList<Component>();
        boolean automatic = l.isNamed() && l.name.startsWith("@");
        for (Window w : Window.getWindows()) {
            if (!w.isShowing()) continue;
            if (l.windowTitle != null && !l.isNamed() && !eq(keyOf(w), l.windowTitle)) continue;
            for (Component c : allComponents(w)) {
                if (l.isNamed()) {
                    // An automatic name also matches after the component got a real name (WP-09, spec goal 5).
                    // Only an automatic locator pays for the field walk: find visits every component.
                    if (l.name.equals(c.getName()) || (automatic && l.name.equals(AutoNames.nameIgnoringRealName(c)))) {
                        matches.add(c);
                    }
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

    /** A component that the user changes or acts on. It needs a real name (lint, WP-09). */
    static boolean isInteractive(Component c) {
        return c instanceof AbstractButton || c instanceof JTextComponent || c instanceof JComboBox
                || c instanceof JList || c instanceof JTable || c instanceof JTree || c instanceof JTabbedPane
                || c instanceof JSpinner || c instanceof JSlider;
    }

    private static String nz(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    static boolean eq(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }
}
