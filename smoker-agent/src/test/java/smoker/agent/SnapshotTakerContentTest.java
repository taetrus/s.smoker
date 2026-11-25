package smoker.agent;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import smoker.core.model.NodeSnapshot;
import smoker.core.model.Snapshot;

import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JList;
import javax.swing.JPasswordField;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.JTable;
import javax.swing.JToolTip;
import javax.swing.JTree;
import javax.swing.ListCellRenderer;
import javax.swing.Popup;
import javax.swing.PopupFactory;
import javax.swing.SwingUtilities;
import javax.swing.table.TableCellRenderer;
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
    private final List<Popup> popups = new ArrayList<Popup>();

    @Before
    public void needsADisplay() {
        Assume.assumeFalse("needs a display", GraphicsEnvironment.isHeadless());
    }

    @After
    public void disposeWindows() throws Exception {
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                for (Popup p : popups) p.hide();
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
    public void spinnerGivesTheTextOfItsEditorField() throws Exception {
        NodeSnapshot n = take(20, "s", new Callable<java.awt.Component>() {
            public java.awt.Component call() {
                return new JSpinner(new SpinnerNumberModel(3, 0, 10, 1));
            }
        });
        assertEquals("3", n.text);
    }

    @Test
    public void spinnerWithACustomEditorGivesNoText() throws Exception {
        NodeSnapshot custom = take(20, "s", new Callable<java.awt.Component>() {
            public java.awt.Component call() {
                JSpinner sp = new JSpinner(new SpinnerNumberModel(3, 0, 10, 1));
                sp.setEditor(new JLabel("x"));
                return sp;
            }
        });
        assertNull(custom.text);
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
    public void treeGivesTheSelectedRow() throws Exception {
        NodeSnapshot n = take(20, "tr", new Callable<java.awt.Component>() {
            public java.awt.Component call() {
                DefaultMutableTreeNode root = new DefaultMutableTreeNode("r");
                root.add(new DefaultMutableTreeNode("a"));
                root.add(new DefaultMutableTreeNode("b"));
                JTree t = new JTree(new DefaultTreeModel(root));
                t.expandRow(0);
                t.setSelectionRow(2);
                return t;
            }
        });
        assertEquals(Integer.valueOf(2), n.selectedIndex);
    }

    @Test
    public void treeWithoutASelectionGivesNoSelectedIndex() throws Exception {
        NodeSnapshot n = take(20, "tr", new Callable<java.awt.Component>() {
            public java.awt.Component call() {
                return new JTree(new DefaultTreeModel(new DefaultMutableTreeNode("r")));
            }
        });
        assertNull(n.selectedIndex);
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

    /** A renderer that shows the secret in a password field (F-19). */
    private static JPasswordField passwordRenderer() {
        JPasswordField p = new JPasswordField();
        p.setText("geheim");
        return p;
    }

    @Test
    public void tableCellWithAPasswordFieldRendererIsMasked() throws Exception {
        NodeSnapshot n = take(20, "t", new Callable<java.awt.Component>() {
            public java.awt.Component call() {
                JTable t = new JTable(new Object[][] {{"Kerem", "geheim"}}, new Object[] {"Name", "Password"});
                t.getColumnModel().getColumn(1).setCellRenderer(new TableCellRenderer() {
                    public java.awt.Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                                                                            boolean focus, int row, int column) {
                        return passwordRenderer();
                    }
                });
                return t;
            }
        });
        assertEquals(Arrays.asList(Arrays.asList("Kerem", SnapshotTaker.MASK)), n.cells);
    }

    @Test
    public void tableCellWithAnotherRendererGivesTheValue() throws Exception {
        NodeSnapshot n = take(20, "t", new Callable<java.awt.Component>() {
            public java.awt.Component call() {
                JTable t = new JTable(new Object[][] {{"Kerem", Boolean.TRUE}}, new Object[] {"Name", "Active"});
                t.getColumnModel().getColumn(1).setCellRenderer(new TableCellRenderer() {
                    public java.awt.Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                                                                            boolean focus, int row, int column) {
                        return new JCheckBox("not the value", Boolean.TRUE.equals(value));
                    }
                });
                return t;
            }
        });
        assertEquals(Arrays.asList(Arrays.asList("Kerem", "true")), n.cells);
    }

    @Test
    public void listItemWithAPasswordFieldRendererIsMasked() throws Exception {
        NodeSnapshot n = take(20, "l", new Callable<java.awt.Component>() {
            public java.awt.Component call() {
                JList<String> l = new JList<String>(new String[] {"geheim"});
                l.setCellRenderer(new ListCellRenderer<String>() {
                    public java.awt.Component getListCellRendererComponent(JList<? extends String> list, String value,
                                                                           int index, boolean selected, boolean focus) {
                        return passwordRenderer();
                    }
                });
                return l;
            }
        });
        assertEquals(Arrays.asList(SnapshotTaker.MASK), n.items);
    }

    @Test
    public void onlyMaxRowsRowsOfATreeAreTaken() throws Exception {
        NodeSnapshot n = take(2, "tr", new Callable<java.awt.Component>() {
            public java.awt.Component call() {
                DefaultMutableTreeNode root = new DefaultMutableTreeNode("r");
                root.add(new DefaultMutableTreeNode("a"));
                root.add(new DefaultMutableTreeNode("b"));
                JTree t = new JTree(new DefaultTreeModel(root));
                t.expandRow(0);
                return t;
            }
        });
        assertEquals(Arrays.asList("r", "  a"), n.items);
        assertEquals(Integer.valueOf(3), n.rowCount);
    }

    /**
     * A tool tip shows only while the mouse hovers. Replay does not hover, so a tool tip in a golden gives a false
     * MISSING (F-21). A tool tip inside the frame and a tool tip in its own window give no node.
     */
    @Test
    public void toolTipsGiveNoNode() throws Exception {
        final AtomicReference<Snapshot> ref = new AtomicReference<Snapshot>();
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                JFrame f = new JFrame("Content");
                windows.add(f);
                JLabel label = new JLabel("x");
                label.setName("l");
                f.getContentPane().add(label);
                f.setBounds(100, 100, 300, 200);
                f.setVisible(true);
                // In the frame: the tool tip is in the layered pane, as a light-weight popup puts it.
                JToolTip inside = new JToolTip();
                inside.setTipText("inside");
                inside.setBounds(10, 10, 80, 20);
                f.getLayeredPane().add(inside, JLayeredPane.POPUP_LAYER);
                // Across the frame edge: the tool tip gets its own heavy-weight window.
                JToolTip outside = new JToolTip();
                outside.setTipText("outside");
                Popup p = PopupFactory.getSharedInstance().getPopup(f, outside, 350, 120);
                popups.add(p);
                p.show();
                ref.set(new SnapshotTaker(20).take());
            }
        });
        List<String> paths = new ArrayList<String>();
        for (NodeSnapshot n : ref.get().nodes) paths.add(n.path + " " + n.type);
        assertEquals(Arrays.asList("[Content] JFrame", "[Content]/#l JLabel"), paths);
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
