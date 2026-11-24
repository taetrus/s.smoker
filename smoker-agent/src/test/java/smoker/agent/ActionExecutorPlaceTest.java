package smoker.agent;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import smoker.core.model.Action;
import smoker.core.model.Locator;

import javax.swing.DefaultListModel;
import javax.swing.JFrame;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Replay of a click with a place, a count or a button, and of a tree selection (WP-06, spec 6). Needs a display. */
public class ActionExecutorPlaceTest {

    private JFrame frame;
    private JTree tree;
    private JTable table;
    private JList<String> list;
    private JPopupMenu menu;
    private final List<MouseEvent> clicks = new ArrayList<MouseEvent>();
    private final List<MouseEvent> triggers = new ArrayList<MouseEvent>();
    private int popupShows;

    @Before
    public void show() throws Exception {
        Assume.assumeFalse("needs a display", GraphicsEnvironment.isHeadless());
        onEdt(new Callable<Object>() {
            public Object call() {
                DefaultMutableTreeNode root = new DefaultMutableTreeNode("Root");
                DefaultMutableTreeNode src = new DefaultMutableTreeNode("src");
                src.add(new DefaultMutableTreeNode("Main.java"));
                src.add(new DefaultMutableTreeNode("Util.java"));
                DefaultMutableTreeNode docs = new DefaultMutableTreeNode("docs");
                docs.add(new DefaultMutableTreeNode("readme.md"));
                root.add(src);
                root.add(docs);
                tree = new JTree(new DefaultTreeModel(root));
                tree.expandRow(0);
                table = new JTable(new DefaultTableModel(new Object[][] {{"a", "1"}, {"b", "2"}}, new Object[] {"Name", "Qty"}) {
                    @Override public boolean isCellEditable(int r, int c) { return false; }
                });
                DefaultListModel<String> lm = new DefaultListModel<String>();
                for (String s : new String[] {"x", "y", "z"}) lm.addElement(s);
                list = new JList<String>(lm);
                menu = new JPopupMenu();
                menu.add(new JMenuItem("Open"));
                MouseAdapter record = new MouseAdapter() {
                    @Override public void mouseClicked(MouseEvent e) { clicks.add(e); }
                    @Override public void mousePressed(MouseEvent e) { trigger(e); }
                    @Override public void mouseReleased(MouseEvent e) { trigger(e); }
                };
                tree.addMouseListener(record);
                table.addMouseListener(record);
                list.addMouseListener(record);
                frame = new JFrame("ActionExecutorPlaceTest");
                JPanel p = new JPanel(new java.awt.GridLayout(1, 3));
                p.add(new JScrollPane(tree));
                p.add(new JScrollPane(table));
                p.add(new JScrollPane(list));
                frame.getContentPane().add(p);
                frame.setSize(600, 250);
                frame.setVisible(true);
                return null;
            }
        });
    }

    private void trigger(MouseEvent e) {
        if (e.isPopupTrigger()) triggers.add(e);
    }

    @After
    public void dispose() throws Exception {
        if (frame == null) return;
        onEdt(new Callable<Object>() {
            public Object call() {
                menu.setVisible(false);
                table.setComponentPopupMenu(null);
                frame.dispose();
                return null;
            }
        });
    }

    private static <T> T onEdt(Callable<T> c) throws Exception {
        final Object[] box = new Object[2];
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                try {
                    box[0] = c.call();
                } catch (Exception e) {
                    box[1] = e;
                }
            }
        });
        if (box[1] != null) throw (Exception) box[1];
        @SuppressWarnings("unchecked") T t = (T) box[0];
        return t;
    }

    private void run(final Action a) throws Exception {
        onEdt(new Callable<Object>() {
            public Object call() throws Exception {
                ActionExecutor.execute(a);
                return null;
            }
        });
    }

    /** Runs the action on the EDT with the component set by name through a stub of the locator lookup. */
    private Action on(Action.Type type, String name) {
        Action a = new Action();
        a.type = type;
        a.locator = new Locator();
        a.locator.name = name;
        return a;
    }

    private void name() throws Exception {
        onEdt(new Callable<Object>() {
            public Object call() {
                tree.setName("t.tree");
                table.setName("t.table");
                list.setName("t.list");
                return null;
            }
        });
    }

    private List<String> path(String... s) {
        return Arrays.asList(s);
    }

    @Test
    public void treeSelectExpandsAndSelects() throws Exception {
        name();
        Action a = on(Action.Type.SELECT, "t.tree");
        a.path = path("Root", "docs", "readme.md");
        run(a);
        onEdt(new Callable<Object>() {
            public Object call() {
                assertEquals(path("Root", "docs", "readme.md"), Places.labels(tree, tree.getSelectionPath()));
                return null;
            }
        });
    }

    @Test(expected = IllegalStateException.class)
    public void treeSelectWithoutAPathFails() throws Exception {
        name();
        try {
            run(on(Action.Type.SELECT, "t.tree"));
        } catch (IllegalStateException e) {
            assertEquals("JTree SELECT needs a path", e.getMessage());
            throw e;
        }
    }

    @Test
    public void doubleClickOnATreeNodeReachesTheListener() throws Exception {
        name();
        Action a = on(Action.Type.CLICK, "t.tree");
        a.path = path("Root", "src", "Main.java");
        a.count = 2;
        run(a);
        onEdt(new Callable<Object>() {
            public Object call() {
                Rectangle r = tree.getPathBounds(Places.findPath(tree, path("Root", "src", "Main.java")));
                assertEquals(2, clicks.size());
                assertEquals(1, clicks.get(0).getClickCount());
                assertEquals(2, clicks.get(1).getClickCount());
                for (MouseEvent e : clicks) assertTrue(r.contains(e.getPoint()));
                return null;
            }
        });
    }

    @Test
    public void doubleClickOnATableCell() throws Exception {
        name();
        Action a = on(Action.Type.CLICK, "t.table");
        a.row = 1;
        a.column = "Name";
        a.count = 2;
        run(a);
        onEdt(new Callable<Object>() {
            public Object call() {
                MouseEvent last = clicks.get(clicks.size() - 1);
                assertEquals(1, table.rowAtPoint(last.getPoint()));
                assertEquals(0, table.columnAtPoint(last.getPoint()));
                assertEquals(2, last.getClickCount());
                return null;
            }
        });
    }

    @Test
    public void rightClickOpensAListenerMenu() throws Exception {
        name();
        onEdt(new Callable<Object>() {
            public Object call() {
                tree.addMouseListener(new MouseAdapter() {
                    @Override public void mousePressed(MouseEvent e) { show(e); }
                    @Override public void mouseReleased(MouseEvent e) { show(e); }
                    private void show(MouseEvent e) {
                        if (e.isPopupTrigger()) {
                            popupShows++;
                            menu.show(tree, e.getX(), e.getY());
                        }
                    }
                });
                return null;
            }
        });
        Action a = on(Action.Type.CLICK, "t.tree");
        a.path = path("Root", "src");
        a.button = 3;
        run(a);
        onEdt(new Callable<Object>() {
            public Object call() {
                assertEquals(1, popupShows);
                assertTrue(menu.isVisible());
                return null;
            }
        });
    }

    @Test
    public void rightClickOpensAComponentPopupMenu() throws Exception {
        name();
        onEdt(new Callable<Object>() {
            public Object call() {
                table.setComponentPopupMenu(menu);
                return null;
            }
        });
        Action a = on(Action.Type.CLICK, "t.table");
        a.row = 0;
        a.column = "Name";
        a.button = 3;
        run(a);
        onEdt(new Callable<Object>() {
            public Object call() {
                assertTrue(menu.isVisible());
                return null;
            }
        });
    }

    @Test
    public void rightClickHasOnePopupTrigger() throws Exception {
        name();
        Action a = on(Action.Type.CLICK, "t.tree");
        a.path = path("Root", "src");
        a.button = 3;
        a.count = 2;
        run(a);
        onEdt(new Callable<Object>() {
            public Object call() {
                assertEquals(1, triggers.size());
                assertEquals(ActionExecutor.popupOnRelease() ? MouseEvent.MOUSE_RELEASED : MouseEvent.MOUSE_PRESSED, triggers.get(0).getID());
                assertEquals(2, triggers.get(0).getClickCount());
                return null;
            }
        });
    }

    @Test
    public void clickWithoutAPlaceIsAsBefore() throws Exception {
        name();
        run(on(Action.Type.CLICK, "t.tree"));
        onEdt(new Callable<Object>() {
            public Object call() {
                assertEquals(1, clicks.size());
                assertEquals(new Point(tree.getWidth() / 2, tree.getHeight() / 2), clicks.get(0).getPoint());
                return null;
            }
        });
    }

    @Test
    public void listRowClick() throws Exception {
        name();
        Action a = on(Action.Type.CLICK, "t.list");
        a.row = 2;
        a.count = 2;
        run(a);
        onEdt(new Callable<Object>() {
            public Object call() {
                MouseEvent last = clicks.get(clicks.size() - 1);
                assertEquals(2, list.locationToIndex(last.getPoint()));
                assertEquals(2, last.getClickCount());
                return null;
            }
        });
    }
}
