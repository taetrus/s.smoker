package smoker.agent;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import smoker.core.model.Action;
import smoker.core.model.Locator;

import javax.swing.JFrame;
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
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Table columns, tree paths, reveal and fill (WP-06, spec 5.2, 5.3, 6). Needs a display. */
public class PlacesTest {

    private JFrame frame;
    private JTable table;
    private JTree tree;

    @Before
    public void show() throws Exception {
        Assume.assumeFalse("needs a display", GraphicsEnvironment.isHeadless());
        onEdt(new Callable<Object>() {
            public Object call() {
                DefaultTableModel m = new DefaultTableModel(new Object[] {"Name", "Qty", "Qty", ""}, 0);
                for (int i = 0; i < 40; i++) m.addRow(new Object[] {"n" + i, "1", "2", "3"});
                table = new JTable(m);
                DefaultMutableTreeNode root = new DefaultMutableTreeNode("Root");
                DefaultMutableTreeNode src = new DefaultMutableTreeNode("src");
                src.add(new DefaultMutableTreeNode("Main.java"));
                src.add(new DefaultMutableTreeNode("Util.java"));
                src.add(new DefaultMutableTreeNode("Util.java"));
                DefaultMutableTreeNode docs = new DefaultMutableTreeNode("docs");
                docs.add(new DefaultMutableTreeNode("readme.md"));
                root.add(src);
                root.add(docs);
                tree = new JTree(new DefaultTreeModel(root));
                frame = new JFrame("PlacesTest");
                frame.getContentPane().setLayout(new java.awt.GridLayout(1, 2));
                JScrollPane ts = new JScrollPane(table);
                ts.setPreferredSize(new java.awt.Dimension(300, 150));
                frame.getContentPane().add(ts);
                JScrollPane trs = new JScrollPane(tree);
                trs.setPreferredSize(new java.awt.Dimension(300, 150));
                frame.getContentPane().add(trs);
                frame.pack();
                frame.setVisible(true);
                return null;
            }
        });
    }

    @After
    public void dispose() throws Exception {
        if (frame == null) return;
        onEdt(new Callable<Object>() {
            public Object call() {
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

    private static List<String> p(String... s) {
        return Arrays.asList(s);
    }

    private Action treeSelect(String... path) {
        return Action.select(new Locator(), p(path));
    }

    @Test
    public void columnKeyIsTheHeaderOrTheIndex() throws Exception {
        assertEquals("Name", onEdt(new Callable<String>() { public String call() { return Places.columnKey(table, 0); } }));
        assertEquals("1", onEdt(new Callable<String>() { public String call() { return Places.columnKey(table, 1); } }));
        assertEquals("3", onEdt(new Callable<String>() { public String call() { return Places.columnKey(table, 3); } }));
    }

    @Test
    public void columnOfFindsHeaderThenIndex() throws Exception {
        assertEquals(0, col("Name"));
        assertEquals(1, col("Qty"));
        assertEquals(2, col("2"));
        try {
            col("Price");
            fail();
        } catch (IllegalStateException e) {
            assertEquals("column not found: Price", e.getMessage());
        }
    }

    private int col(final String name) throws Exception {
        return onEdt(new Callable<Integer>() { public Integer call() { return Places.columnOf(table, name); } });
    }

    @Test
    public void labelsAndFindPath() throws Exception {
        onEdt(new Callable<Object>() {
            public Object call() {
                TreePath tp = Places.findPath(tree, p("Root", "src", "Main.java"));
                assertEquals(p("Root", "src", "Main.java"), Places.labels(tree, tp));
                TreePath dup = Places.findPath(tree, p("Root", "src", "Util.java"));
                assertEquals(1, tree.getModel().getIndexOfChild(tp.getParentPath().getLastPathComponent(), dup.getLastPathComponent()));
                return null;
            }
        });
    }

    @Test
    public void findPathFailsWithTheJoinedPath() throws Exception {
        assertNotFound("Root/src/X.java", "Root", "src", "X.java");
        assertNotFound("Other", "Other");
    }

    private void assertNotFound(final String msg, final String... path) throws Exception {
        try {
            onEdt(new Callable<Object>() { public Object call() { return Places.findPath(tree, p(path)); } });
            fail();
        } catch (IllegalStateException e) {
            assertEquals("tree node not found: " + msg, e.getMessage());
        }
    }

    @Test
    public void hiddenRootStillStartsAtTheRoot() throws Exception {
        onEdt(new Callable<Object>() {
            public Object call() {
                tree.setRootVisible(false);
                TreePath tp = Places.findPath(tree, p("Root", "docs"));
                assertEquals(p("Root", "docs"), Places.labels(tree, tp));
                return null;
            }
        });
    }

    @Test
    public void revealExpandsATreePath() throws Exception {
        onEdt(new Callable<Object>() {
            public Object call() {
                tree.collapseRow(1);
                tree.collapseRow(0);
                Action a = treeSelect("Root", "docs", "readme.md");
                Rectangle r = Places.reveal(tree, a);
                TreePath tp = Places.findPath(tree, a.path);
                assertTrue(tree.isVisible(tp));
                assertEquals(tree.getPathBounds(tp), r);
                return null;
            }
        });
    }

    @Test
    public void revealWithoutAPlaceGivesTheWholeComponent() throws Exception {
        onEdt(new Callable<Object>() {
            public Object call() {
                assertEquals(new Rectangle(0, 0, tree.getWidth(), tree.getHeight()), Places.reveal(tree, Action.click(new Locator())));
                return null;
            }
        });
    }

    @Test
    public void revealOfATableRowAndCell() throws Exception {
        onEdt(new Callable<Object>() {
            public Object call() {
                Action a = Action.click(new Locator());
                a.row = 30;
                Rectangle row = Places.reveal(table, a);
                assertEquals(table.getCellRect(30, 0, true).y, row.y);
                assertEquals(table.getWidth(), row.width);
                assertTrue(table.getVisibleRect().intersects(row));
                a.column = "Name";
                assertEquals(table.getCellRect(30, 0, true), Places.reveal(table, a));
                return null;
            }
        });
    }

    @Test
    public void revealOfAMissingRowFails() throws Exception {
        try {
            onEdt(new Callable<Object>() {
                public Object call() {
                    Action a = Action.click(new Locator());
                    a.row = 99;
                    return Places.reveal(table, a);
                }
            });
            fail();
        } catch (IllegalStateException e) {
            assertEquals("row not found: 99", e.getMessage());
        }
    }

    @Test
    public void fillReadsTheCellUnderThePoint() throws Exception {
        onEdt(new Callable<Object>() {
            public Object call() {
                Rectangle r = table.getCellRect(1, 0, true);
                Action a = Action.click(new Locator());
                assertTrue(Places.fill(a, table, new Point(r.x + r.width / 2, r.y + r.height / 2)));
                assertEquals(Integer.valueOf(1), a.row);
                assertEquals("Name", a.column);
                Action none = Action.click(new Locator());
                assertFalse(Places.fill(none, table, new Point(5, table.getHeight() + 50)));
                assertNull(none.row);
                return null;
            }
        });
    }

    @Test
    public void fillReadsTheTreeNodeUnderThePoint() throws Exception {
        onEdt(new Callable<Object>() {
            public Object call() {
                Rectangle r = tree.getRowBounds(1);
                Action a = Action.click(new Locator());
                assertTrue(Places.fill(a, tree, new Point(r.x + r.width / 2, r.y + r.height / 2)));
                assertEquals(p("Root", "src"), a.path);
                assertFalse(Places.fill(Action.click(new Locator()), frame.getContentPane(), new Point(1, 1)));
                return null;
            }
        });
    }
}
