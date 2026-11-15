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
import java.util.HashSet;
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
        Set<String> taken = new HashSet<String>();
        for (Window w : Window.getWindows()) {
            if (!w.isShowing()) continue;
            if (w.getName() != null && w.getName().startsWith(SMOKER_WINDOW_PREFIX)) continue;
            String title = Locators.titleOf(w);
            String name = applicationName(w);
            String part = unique(taken, name != null ? "[#" + name + "]" : "[" + (title == null ? "" : title) + "]");
            NodeSnapshot n = node(w, part);
            n.title = title;
            s.nodes.add(n);
            walk(w, part, part, new HashMap<String, Integer>(), taken, s.nodes);
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
     * A {@link #lafInternal} component also gives true, but the walk skips it together with its subtree
     * (its children are not visited).
     */
    static boolean isLayoutContainer(Component c) {
        if (applicationName(c) != null) return false;
        return c instanceof javax.swing.JPanel || c instanceof javax.swing.JScrollPane || c instanceof javax.swing.JViewport
                || c instanceof javax.swing.JRootPane || c instanceof javax.swing.JLayeredPane || c instanceof javax.swing.Box
                || c instanceof javax.swing.Box.Filler || c instanceof javax.swing.JSplitPane || c instanceof javax.swing.JToolBar
                || c instanceof javax.swing.JScrollBar || c instanceof javax.swing.CellRendererPane || lafInternal(c);
    }

    /** The name the application gave the component, or null when it has none (no name or a generated name). */
    private static String applicationName(Component c) {
        String n = c.getName();
        return n != null && !Locators.isAutoName(n) ? n : null;
    }

    /**
     * {@code path} when no node of this snapshot has it yet, else the first free {@code path[1]}, {@code path[2]}, ...
     * This gives a repeated window part or name its index, and keeps every path unique (spec 4.2).
     */
    private static String unique(Set<String> taken, String path) {
        if (taken.add(path)) return path;
        for (int i = 1; ; i++) {
            String p = path + "[" + i + "]";
            if (taken.add(p)) return p;
        }
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
     * A named component also takes an index of its class below its anchor, so naming one component
     * does not change the paths of its unnamed siblings.
     *
     * @param counters next index of each (anchor, class) in this window
     * @param taken    the paths of this snapshot so far
     */
    private void walk(Container parent, String windowPart, String anchor, Map<String, Integer> counters,
                      Set<String> taken, List<NodeSnapshot> out) {
        for (Component k : parent.getComponents()) {
            if (!k.isVisible() || lafInternal(k)) continue;
            String childAnchor = anchor;
            String name = applicationName(k);
            if (name != null || !isLayoutContainer(k)) {
                String cls = Locators.classNameOf(k);
                int idx = next(counters, anchor + "/" + cls);
                String path = unique(taken, name != null ? windowPart + "/#" + name : anchor + "/" + cls + "[" + idx + "]");
                out.add(node(k, path));
                if (name != null) childAnchor = path;
            }
            if (k instanceof Container) walk((Container) k, windowPart, childAnchor, counters, taken, out);
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
