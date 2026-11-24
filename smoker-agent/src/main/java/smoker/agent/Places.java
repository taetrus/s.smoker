package smoker.agent;

import smoker.core.model.Action;

import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JTable;
import javax.swing.JTree;
import javax.swing.tree.TreeModel;
import javax.swing.tree.TreePath;
import java.awt.Component;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

/** The place of a mouse event inside a table, a list or a tree (WP-06, spec 5 and 6). All methods run on the EDT. */
final class Places {

    private Places() {}

    /** The view column for {@code column}: the first header text that equals it, else the index (spec 5.3). */
    static int columnOf(JTable t, String column) {
        for (int i = 0; i < t.getColumnCount(); i++) {
            if (t.getColumnName(i).equals(column)) return i;
        }
        try {
            int i = Integer.parseInt(column.trim());
            if (i >= 0 && i < t.getColumnCount()) return i;
        } catch (NumberFormatException e) {
            // not an index
        }
        throw new IllegalStateException("column not found: " + column);
    }

    /** The header text when it is not empty and unique, else the view index as a string. */
    static String columnKey(JTable t, int viewColumn) {
        String name = t.getColumnName(viewColumn);
        if (name != null && !name.isEmpty()) {
            boolean unique = true;
            for (int i = 0; i < t.getColumnCount(); i++) {
                if (i != viewColumn && name.equals(t.getColumnName(i))) unique = false;
            }
            if (unique) return name;
        }
        return String.valueOf(viewColumn);
    }

    /** The text of each node of the path, root first. */
    static List<String> labels(JTree t, TreePath p) {
        List<String> out = new ArrayList<String>();
        for (Object node : p.getPath()) out.add(label(t, node));
        return out;
    }

    private static String label(JTree t, Object node) {
        return t.convertValueToText(node, false, false, t.getModel().isLeaf(node), 0, false);
    }

    /** The node whose labels from the root are {@code path} (spec 5.2). */
    static TreePath findPath(JTree t, List<String> path) {
        TreeModel m = t.getModel();
        Object node = m.getRoot();
        if (node == null || path.isEmpty() || !path.get(0).equals(label(t, node))) throw notFound(path);
        TreePath tp = new TreePath(node);
        for (int i = 1; i < path.size(); i++) {
            Object next = null;
            for (int k = 0; k < m.getChildCount(node); k++) {
                Object child = m.getChild(node, k);
                if (path.get(i).equals(label(t, child))) {
                    next = child;
                    break;
                }
            }
            if (next == null) throw notFound(path);
            node = next;
            tp = tp.pathByAddingChild(node);
        }
        return tp;
    }

    private static IllegalStateException notFound(List<String> path) {
        StringBuilder sb = new StringBuilder();
        for (String s : path) sb.append(sb.length() == 0 ? "" : "/").append(s);
        return new IllegalStateException("tree node not found: " + sb);
    }

    /** Makes the place of {@code a} visible and returns its bounds in the coordinates of {@code c}. */
    static Rectangle reveal(Component c, Action a) {
        Rectangle whole = new Rectangle(0, 0, c.getWidth(), c.getHeight());
        if (c instanceof JTable && a.row != null) {
            JTable t = (JTable) c;
            if (a.row < 0 || a.row >= t.getRowCount()) throw new IllegalStateException("row not found: " + a.row);
            Rectangle r;
            if (a.column != null) {
                r = t.getCellRect(a.row, columnOf(t, a.column), true);
            } else {
                r = t.getCellRect(a.row, 0, true);
                if (t.getColumnCount() > 0) r = r.union(t.getCellRect(a.row, t.getColumnCount() - 1, true));
            }
            t.scrollRectToVisible(r);
            return r;
        }
        if (c instanceof JList && a.row != null) {
            JList<?> l = (JList<?>) c;
            if (a.row < 0 || a.row >= l.getModel().getSize()) throw new IllegalStateException("row not found: " + a.row);
            Rectangle r = l.getCellBounds(a.row, a.row);
            l.scrollRectToVisible(r);
            return r;
        }
        if (c instanceof JTree && a.path != null) {
            JTree t = (JTree) c;
            TreePath p = findPath(t, a.path);
            t.scrollPathToVisible(p);   // expands the parents
            Rectangle r = t.getPathBounds(p);
            if (r == null) throw new IllegalStateException("tree node not visible: " + a.path);
            return r;
        }
        return whole;
    }

    /** Recording side: sets the place of {@code a} from the point {@code p} in {@code c}. False when there is none. */
    static boolean fill(Action a, Component c, Point p) {
        if (c instanceof JTable) {
            JTable t = (JTable) c;
            int row = t.rowAtPoint(p), col = t.columnAtPoint(p);
            if (row < 0 || col < 0) return false;
            a.row = row;
            a.column = columnKey(t, col);
            return true;
        }
        if (c instanceof JList) {
            JList<?> l = (JList<?>) c;
            int row = l.locationToIndex(p);
            if (row < 0) return false;
            Rectangle b = l.getCellBounds(row, row);
            if (b == null || !b.contains(p)) return false;
            a.row = row;
            return true;
        }
        if (c instanceof JTree) {
            JTree t = (JTree) c;
            TreePath tp = t.getPathForLocation(p.x, p.y);
            if (tp == null) return false;
            a.path = labels(t, tp);
            return true;
        }
        return false;
    }
}
