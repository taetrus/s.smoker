package smoker.agent;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import smoker.core.model.NodeSnapshot;
import smoker.core.model.Snapshot;

import javax.swing.JFrame;
import javax.swing.JList;
import javax.swing.JTable;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/** Content of tables, lists and trees in snapshots (WP-02, F-07, spec section 4.3). Needs a display. */
public class SnapshotTakerContentTest {

    private final List<Window> windows = new ArrayList<Window>();

    @Before
    public void needsADisplay() {
        Assume.assumeFalse("needs a display", GraphicsEnvironment.isHeadless());
    }

    @After
    public void disposeWindows() throws Exception {
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                for (Window w : windows) w.dispose();
            }
        });
    }

    @Test
    public void tableGivesCellTextOfTheFirstRows() throws Exception {
        NodeSnapshot n = take(20, "t", new Callable<java.awt.Component>() {
            public java.awt.Component call() {
                return new JTable(new Object[][] {{"Kerem", "tr"}, {"Ana", "en"}}, new Object[] {"Name", "Lang"});
            }
        });
        assertEquals(Arrays.asList(Arrays.asList("Kerem", "tr"), Arrays.asList("Ana", "en")), n.cells);
        assertEquals(Integer.valueOf(2), n.rowCount);
        assertNull(n.items);
    }

    @Test
    public void onlyMaxRowsRowsAreTaken() throws Exception {
        NodeSnapshot n = take(20, "t", new Callable<java.awt.Component>() {
            public java.awt.Component call() {
                Object[][] data = new Object[25][1];
                for (int i = 0; i < 25; i++) data[i][0] = "r" + i;
                return new JTable(data, new Object[] {"A"});
            }
        });
        assertEquals(20, n.cells.size());
        assertEquals("r19", n.cells.get(19).get(0));
        assertEquals(Integer.valueOf(25), n.rowCount);
    }

    @Test
    public void listGivesItems() throws Exception {
        NodeSnapshot n = take(20, "l", new Callable<java.awt.Component>() {
            public java.awt.Component call() {
                return new JList<String>(new String[] {"a", "b"});
            }
        });
        assertEquals(Arrays.asList("a", "b"), n.items);
        assertEquals(Integer.valueOf(2), n.itemCount);
        assertNull(n.cells);
    }

    @Test
    public void onlyMaxRowsItemsOfAListAreTaken() throws Exception {
        NodeSnapshot n = take(2, "l", new Callable<java.awt.Component>() {
            public java.awt.Component call() {
                return new JList<String>(new String[] {"a", "b", "c"});
            }
        });
        assertEquals(Arrays.asList("a", "b"), n.items);
        assertEquals(Integer.valueOf(3), n.itemCount);
    }

    @Test
    public void treeGivesVisibleRowsWithDepth() throws Exception {
        NodeSnapshot n = take(20, "tr", new Callable<java.awt.Component>() {
            public java.awt.Component call() {
                DefaultMutableTreeNode root = new DefaultMutableTreeNode("r");
                root.add(new DefaultMutableTreeNode("c"));
                JTree t = new JTree(new DefaultTreeModel(root));
                t.expandRow(0);
                return t;
            }
        });
        assertEquals(Arrays.asList("r", "  c"), n.items);
        assertEquals(Integer.valueOf(2), n.rowCount);
    }

    @Test
    public void treeWithHiddenRootStartsAtDepthZero() throws Exception {
        NodeSnapshot n = take(20, "tr", new Callable<java.awt.Component>() {
            public java.awt.Component call() {
                DefaultMutableTreeNode root = new DefaultMutableTreeNode("r");
                DefaultMutableTreeNode c = new DefaultMutableTreeNode("c");
                c.add(new DefaultMutableTreeNode("d"));
                root.add(c);
                JTree t = new JTree(new DefaultTreeModel(root));
                t.setRootVisible(false);
                t.expandRow(0);
                return t;
            }
        });
        assertEquals(Arrays.asList("c", "  d"), n.items);
    }

    /** Shows {@code c} named {@code name} in a frame and returns its node. */
    private NodeSnapshot take(final int maxRows, final String name, final Callable<java.awt.Component> make) throws Exception {
        final AtomicReference<Snapshot> ref = new AtomicReference<Snapshot>();
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                try {
                    JFrame f = new JFrame("Content");
                    windows.add(f);
                    java.awt.Component c = make.call();
                    c.setName(name);
                    f.getContentPane().add(c);
                    f.pack();
                    f.setVisible(true);
                    ref.set(new SnapshotTaker(maxRows).take());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });
        for (NodeSnapshot n : ref.get().nodes) if (("[Content]/#" + name).equals(n.path)) return n;
        assertNotNull("node #" + name + " in " + ref.get().nodes.size() + " nodes", null);
        return null;
    }
}
