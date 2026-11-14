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
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Captures the semantic UI state (R-23). Must run on the EDT. Only visible components are included,
 * so a component that disappears shows up as MISSING and one that appears as UNEXPECTED.
 */
final class SnapshotTaker {

    /** Windows whose name starts with this are smoker's own UI and are never captured. */
    static final String SMOKER_WINDOW_PREFIX = "smoker.";

    private final Set<String> unnamed = new TreeSet<String>();

    Snapshot take() {
        Snapshot s = new Snapshot();
        for (Window w : Window.getWindows()) {
            if (!w.isShowing()) continue;
            if (w.getName() != null && w.getName().startsWith(SMOKER_WINDOW_PREFIX)) continue;
            String title = Locators.titleOf(w);
            NodeSnapshot n = node(w, "[" + (title == null ? "" : title) + "]");
            n.title = title;
            s.windows.add(n);
        }
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

    /** JOptionPane generates its buttons; the app cannot name them, so they are not lint findings. */
    private static boolean generatedByOptionPane(Component c) {
        return javax.swing.SwingUtilities.getAncestorOfClass(javax.swing.JOptionPane.class, c) != null;
    }

    private NodeSnapshot node(Component c, String path) {
        NodeSnapshot n = new NodeSnapshot();
        n.path = path;
        n.type = Locators.classNameOf(c);
        if (c.getName() != null && !Locators.isAutoName(c.getName())) n.name = c.getName();
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

        if (c instanceof Container) {
            Map<String, Integer> counters = new HashMap<String, Integer>();
            for (Component k : ((Container) c).getComponents()) {
                if (!k.isVisible() || lafInternal(k)) continue;
                String seg;
                if (k.getName() != null && !Locators.isAutoName(k.getName())) {
                    seg = "#" + k.getName();
                } else {
                    String t = Locators.classNameOf(k);
                    Integer i = counters.get(t);
                    int idx = i == null ? 0 : i;
                    counters.put(t, idx + 1);
                    seg = t + "[" + idx + "]";
                }
                n.children().add(node(k, path + "/" + seg));
            }
            if (n.children != null && n.children.isEmpty()) n.children = null;
        }
        return n;
    }
}
