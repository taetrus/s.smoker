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
import javax.swing.JToolTip;
import javax.swing.JTree;
import javax.swing.text.JTextComponent;
import javax.swing.tree.TreePath;
import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.util.ArrayList;
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
    private final Set<String> duplicates = new TreeSet<String>();
    private final int maxRows;
    private final boolean namedOnly;

    /** @param maxRows the content of at most this many rows of a table, list or tree is taken (F-07) */
    SnapshotTaker(int maxRows) {
        this(maxRows, false);
    }

    /**
     * @param maxRows   the content of at most this many rows of a table, list or tree is taken (F-07)
     * @param namedOnly compare mode {@code named}: only windows and named components become nodes (R-30a)
     */
    SnapshotTaker(int maxRows, boolean namedOnly) {
        this.maxRows = maxRows;
        this.namedOnly = namedOnly;
    }

    Snapshot take() {
        Snapshot s = new Snapshot();
        Set<String> taken = new HashSet<String>();
        for (Window w : Window.getWindows()) {
            if (!w.isShowing()) continue;
            if (w.getName() != null && w.getName().startsWith(SMOKER_WINDOW_PREFIX)) continue;
            if (onlyToolTips(w)) continue;   // a tool tip across the frame edge has its own window
            String title = Locators.titleOf(w);
            String name = Locators.realName(w);   // a window has no automatic name
            String key = Locators.keyOf(w);
            String part = unique(taken, name != null ? "[#" + name + "]" : "[" + (key == null ? "" : key) + "]");
            NodeSnapshot n = node(w, part, name);
            n.title = title;
            s.nodes.add(n);
            Map<String, Integer> names = new HashMap<String, Integer>();
            walk(w, part, part, new HashMap<String, Integer>(), names, taken, s.nodes);
            for (Map.Entry<String, Integer> e : names.entrySet()) {
                if (e.getValue() > 1) duplicates.add(part + ": #" + e.getKey() + " (" + e.getValue() + "x)");
            }
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

    /** One entry per window and name that more than one visible component has: {@code "[Orders]: #x (2x)"} (F-17). */
    Set<String> duplicateNames() {
        return duplicates;
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
        // A real name only: an automatically named component is never a layout container (AutoNames gives none).
        if (Locators.realName(c) != null) return false;
        return c instanceof javax.swing.JPanel || c instanceof javax.swing.JScrollPane || c instanceof javax.swing.JViewport
                || c instanceof javax.swing.JRootPane || c instanceof javax.swing.JLayeredPane || c instanceof javax.swing.Box
                || c instanceof javax.swing.Box.Filler || c instanceof javax.swing.JSplitPane || c instanceof javax.swing.JToolBar
                || c instanceof javax.swing.JScrollBar || c instanceof javax.swing.CellRendererPane || lafInternal(c);
    }

    /**
     * True when {@code c} shows at least one tool tip and nothing else that a snapshot compares.
     * A tool tip shows only while the mouse hovers, and replay does not hover: it gives no node (F-21).
     */
    static boolean onlyToolTips(Container c) {
        int[] tips = {0};
        return !otherContent(c, tips) && tips[0] > 0;
    }

    /** True when a visible descendant of {@code p} outside a tool tip gives a node. Counts the tool tips in {@code tips[0]}. */
    private static boolean otherContent(Container p, int[] tips) {
        for (Component k : p.getComponents()) {
            if (!k.isVisible() || lafInternal(k)) continue;
            if (k instanceof JToolTip) {
                tips[0]++;
                continue;
            }
            if (!isLayoutContainer(k)) return true;
            if (k instanceof Container && otherContent((Container) k, tips)) return true;
        }
        return false;
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
     * @param names    the number of visible components per name (real or automatic) in this window
     * @param taken    the paths of this snapshot so far
     */
    private void walk(Container parent, String windowPart, String anchor, Map<String, Integer> counters,
                      Map<String, Integer> names, Set<String> taken, List<NodeSnapshot> out) {
        for (Component k : parent.getComponents()) {
            if (!k.isVisible() || lafInternal(k) || k instanceof JToolTip) continue;
            String childAnchor = anchor;
            String name = Locators.nameOf(k);
            // An unnamed holder of a tool tip (the panel of a medium-weight popup) gives no node either.
            if (name == null && !isLayoutContainer(k) && k instanceof Container && onlyToolTips((Container) k)) continue;
            if (name != null || !isLayoutContainer(k)) {
                String cls = Locators.classNameOf(k);
                int idx = next(counters, anchor + "/" + cls);
                String path = unique(taken, name != null ? windowPart + "/#" + name : anchor + "/" + cls + "[" + idx + "]");
                NodeSnapshot n = node(k, path, name);   // also collects the lint, in both modes
                if (name != null) {
                    out.add(n);
                    childAnchor = path;
                    Integer c = names.get(name);
                    names.put(name, c == null ? 1 : c + 1);
                } else if (!namedOnly) {
                    out.add(n);
                }
            }
            if (k instanceof Container) walk((Container) k, windowPart, childAnchor, counters, names, taken, out);
        }
    }

    /** JOptionPane generates its buttons; the app cannot name them, so they are not lint findings. */
    private static boolean generatedByOptionPane(Component c) {
        return javax.swing.SwingUtilities.getAncestorOfClass(javax.swing.JOptionPane.class, c) != null;
    }

    /**
     * The text of a cell as the user sees it: the text of a label renderer, the text of a text component renderer
     * (a password field gives {@link #MASK}, F-19), else the value.
     */
    private static String cellText(Component renderer, Object value) {
        if (renderer instanceof JLabel) return ((JLabel) renderer).getText();
        if (renderer instanceof JTextComponent) return textOf((JTextComponent) renderer);
        return String.valueOf(value);
    }

    /** The text of the first rows of a table as the user sees it ({@link #cellText}). */
    private List<List<String>> tableCells(JTable t) {
        List<List<String>> rows = new ArrayList<List<String>>();
        int nr = Math.min(maxRows, t.getRowCount());
        for (int r = 0; r < nr; r++) {
            List<String> row = new ArrayList<String>();
            for (int c = 0; c < t.getColumnCount(); c++) {
                Component rc = t.prepareRenderer(t.getCellRenderer(r, c), r, c);
                row.add(cellText(rc, t.getValueAt(r, c)));
            }
            rows.add(row);
        }
        return rows;
    }

    private <E> List<String> listItems(JList<E> l) {
        List<String> items = new ArrayList<String>();
        int n = Math.min(maxRows, l.getModel().getSize());
        for (int i = 0; i < n; i++) {
            E v = l.getModel().getElementAt(i);
            Component rc = l.getCellRenderer().getListCellRendererComponent(l, v, i, l.isSelectedIndex(i), false);
            items.add(cellText(rc, v));
        }
        return items;
    }

    /** The labels of the first visible rows, two spaces per depth. A hidden root does not count as a level. */
    private List<String> treeRows(JTree t) {
        List<String> rows = new ArrayList<String>();
        int n = Math.min(maxRows, t.getRowCount());
        int rootLevel = t.isRootVisible() ? 0 : 1;
        for (int i = 0; i < n; i++) {
            TreePath p = t.getPathForRow(i);
            if (p == null) continue;
            StringBuilder sb = new StringBuilder();
            for (int d = p.getPathCount() - 1 - rootLevel; d > 0; d--) sb.append("  ");
            Object last = p.getLastPathComponent();
            sb.append(t.convertValueToText(last, t.isRowSelected(i), t.isExpanded(i), t.getModel().isLeaf(last), i, false));
            rows.add(sb.toString());
        }
        return rows;
    }

    /**
     * One node with the attributes of {@code c}. {@code name} is {@link Locators#nameOf}.
     * Records an interactive component without a real name for the lint, also when it has an automatic name (spec 5.2).
     */
    private NodeSnapshot node(Component c, String path, String name) {
        NodeSnapshot n = new NodeSnapshot();
        n.path = path;
        n.type = Locators.classNameOf(c);
        n.name = name;
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
            n.items = listItems(l);
        } else if (c instanceof JTable) {
            JTable t = (JTable) c;
            n.rowCount = t.getRowCount();
            n.selectedIndex = t.getSelectedRow();
            n.cells = tableCells(t);
        } else if (c instanceof JTabbedPane) {
            JTabbedPane tp = (JTabbedPane) c;
            n.itemCount = tp.getTabCount();
            n.selectedIndex = tp.getSelectedIndex();
            n.text = tp.getSelectedIndex() >= 0 ? tp.getTitleAt(tp.getSelectedIndex()) : null;
        } else if (c instanceof JTree) {
            n.rowCount = ((JTree) c).getRowCount();
            n.items = treeRows((JTree) c);
        }

        if (Locators.isInteractive(c) && Locators.realName(c) == null && c.isShowing() && !lafInternal(c) && !generatedByOptionPane(c)) unnamed.add(path);

        return n;
    }
}
