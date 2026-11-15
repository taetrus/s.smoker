package smoker.agent;

import smoker.core.model.NodeSnapshot;
import smoker.core.model.Snapshot;

import javax.swing.AbstractButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPasswordField;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTree;
import javax.swing.text.JTextComponent;
import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Captures the semantic UI state (R-23). Must run on the EDT. Only visible components are included,
 * so a component that disappears shows up as MISSING and one that appears as UNEXPECTED.
 * The snapshot is a flat list of nodes sorted by path (F-05). Path rules: design WP-02, section 4.2.
 */
final class SnapshotTaker {

    /** Windows whose name starts with this are smoker's own UI and are never captured. */
    static final String SMOKER_WINDOW_PREFIX = "smoker.";

    private final Set<String> unnamed = new TreeSet<String>();

    Snapshot take() {
        Snapshot s = new Snapshot();
        Map<String, Integer> windowParts = new HashMap<String, Integer>();
        for (Window w : Window.getWindows()) {
            if (!w.isShowing()) continue;
            if (w.getName() != null && w.getName().startsWith(SMOKER_WINDOW_PREFIX)) continue;
            String title = Locators.titleOf(w);
            String name = applicationName(w);
            String part = indexed(windowParts, name != null ? "[#" + name + "]" : "[" + (title == null ? "" : title) + "]");
            NodeSnapshot n = node(w, part);
            n.title = title;
            s.nodes.add(n);
            walk(w, part, part, new HashMap<String, Integer>(), new HashMap<String, Integer>(), s.nodes);
        }
        Collections.sort(s.nodes, new Comparator<NodeSnapshot>() {
            public int compare(NodeSnapshot a, NodeSnapshot b) {
                return a.path.compareTo(b.path);
            }
        });
        return s;
    }

    Set<String> unnamedInteractive() {
        return unnamed;
    }

    /** The snapshot text of a password field that is not empty. */
    static final String MASK = "***";

    /**
     * The text of a text component for the snapshot. A password field gives {@link #MASK} or "",
     * never its value or its length: snapshots go into scenario files and reports (F-19). Must run on the EDT.
     */
    static String textOf(JTextComponent c) {
        if (c instanceof JPasswordField) return c.getDocument().getLength() == 0 ? "" : MASK;
        return c.getText();
    }

    /**
     * Look-and-feel internals (combo arrow buttons, scroll bar buttons, JOptionPane's generated buttons) differ
     * between Metal on a Linux runner and the Windows LAF on a dev box; keeping them out makes goldens LAF-independent.
     */
    static boolean lafInternal(Component c) {
        String pkg = c.getClass().getName();
        if (pkg.startsWith("javax.swing.plaf.") || pkg.startsWith("com.sun.java.swing.plaf.") || pkg.startsWith("sun.swing.")
                || pkg.startsWith("com.apple.laf.")) return true;
        Component p = c.getParent();
        return p instanceof javax.swing.JComboBox || p instanceof javax.swing.JScrollBar || p instanceof javax.swing.JSpinner
                || p instanceof javax.swing.table.JTableHeader;
    }

    /**
     * An unnamed layout container shows no state the user can see: it gives no node and no path segment,
     * but its children are visited. A named container is compared and is an anchor.
     */
    static boolean isLayoutContainer(Component c) {
        if (applicationName(c) != null) return false;
        return c instanceof javax.swing.JPanel || c instanceof javax.swing.JScrollPane || c instanceof javax.swing.JViewport
                || c instanceof javax.swing.JRootPane || c instanceof javax.swing.JLayeredPane || c instanceof javax.swing.Box
                || c instanceof javax.swing.JSplitPane || c instanceof javax.swing.JToolBar || lafInternal(c);
    }

    /** The name the application gave the component, or null when it has none (no name or a generated name). */
    private static String applicationName(Component c) {
        String n = c.getName();
        return n != null && !Locators.isAutoName(n) ? n : null;
    }

    /** {@code key} on first use, then {@code key[1]}, {@code key[2]}, ... */
    private static String indexed(Map<String, Integer> seen, String key) {
        Integer i = seen.get(key);
        seen.put(key, i == null ? 1 : i + 1);
        return i == null ? key : key + "[" + i + "]";
    }

    /** {@code 0} on first use of the key, then 1, 2, ... */
    private static int next(Map<String, Integer> counters, String key) {
        Integer i = counters.get(key);
        int idx = i == null ? 0 : i;
        counters.put(key, idx + 1);
        return idx;
    }

    /**
     * Adds a node for each compared descendant of {@code parent}, in tree order.
     *
     * @param names    occurrences of each application name in this window
     * @param counters next index of each (anchor, class) in this window
     */
    private void walk(Container parent, String windowPart, String anchor, Map<String, Integer> names,
                      Map<String, Integer> counters, List<NodeSnapshot> out) {
        for (Component k : parent.getComponents()) {
            if (!k.isVisible() || lafInternal(k)) continue;
            String childAnchor = anchor;
            String name = applicationName(k);
            if (name != null) {
                childAnchor = windowPart + "/" + indexed(names, "#" + name);
                out.add(node(k, childAnchor));
            } else if (!isLayoutContainer(k)) {
                String cls = Locators.classNameOf(k);
                out.add(node(k, anchor + "/" + cls + "[" + next(counters, anchor + "/" + cls) + "]"));
            }
            if (k instanceof Container) walk((Container) k, windowPart, childAnchor, names, counters, out);
        }
    }

    /** JOptionPane generates its buttons; the app cannot name them, so they are not lint findings. */
    private static boolean generatedByOptionPane(Component c) {
        return javax.swing.SwingUtilities.getAncestorOfClass(javax.swing.JOptionPane.class, c) != null;
    }

    /** One node with the attributes of {@code c}. Records an unnamed interactive component for the lint. */
    private NodeSnapshot node(Component c, String path) {
        NodeSnapshot n = new NodeSnapshot();
        n.path = path;
        n.type = Locators.classNameOf(c);
        n.name = applicationName(c);
        n.visible = c.isVisible() ? null : Boolean.FALSE;   // visible is the default; only record the exception
        n.enabled = c.isEnabled() ? null : Boolean.FALSE;

        if (c instanceof AbstractButton) {
            AbstractButton b = (AbstractButton) c;
            n.text = b.getText();
            if (b instanceof javax.swing.JToggleButton || b instanceof javax.swing.JCheckBoxMenuItem
                    || b instanceof javax.swing.JRadioButtonMenuItem) n.selected = b.isSelected();
        } else if (c instanceof JLabel) {
            n.text = ((JLabel) c).getText();
        } else if (c instanceof JTextComponent) {
            n.text = textOf((JTextComponent) c);
        } else if (c instanceof JComboBox) {
            JComboBox<?> cb = (JComboBox<?>) c;
            n.itemCount = cb.getItemCount();
            n.selectedIndex = cb.getSelectedIndex();
            n.text = cb.getSelectedItem() == null ? null : String.valueOf(cb.getSelectedItem());
        } else if (c instanceof JList) {
            JList<?> l = (JList<?>) c;
            n.itemCount = l.getModel().getSize();
            n.selectedIndex = l.getSelectedIndex();
        } else if (c instanceof JTable) {
            JTable t = (JTable) c;
            n.rowCount = t.getRowCount();
            n.selectedIndex = t.getSelectedRow();
        } else if (c instanceof JTabbedPane) {
            JTabbedPane tp = (JTabbedPane) c;
            n.itemCount = tp.getTabCount();
            n.selectedIndex = tp.getSelectedIndex();
            n.text = tp.getSelectedIndex() >= 0 ? tp.getTitleAt(tp.getSelectedIndex()) : null;
        } else if (c instanceof JTree) {
            n.rowCount = ((JTree) c).getRowCount();
        }

        if (Locators.isInteractive(c) && n.name == null && c.isShowing() && !lafInternal(c) && !generatedByOptionPane(c)) unnamed.add(path);

        return n;
    }
}
