package smoker.agent;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import smoker.core.model.Action;
import smoker.core.model.Scenario;
import smoker.core.model.Snapshot;
import smoker.core.model.Step;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTable;
import javax.swing.JTree;
import javax.swing.MenuElement;
import javax.swing.MenuSelectionManager;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * WP-06 spec 6, 6.1, 6.2, 6.3: the recorder with tree selection, the double-click and the right-click.
 * Real Swing components get synthetic mouse events. Skipped without a display (Linux: use xvfb-run).
 */
public class RecorderPlaceTest {

    private JFrame frame;
    private JTree tree;
    private TreePath src;
    private TreePath main;
    private JTable table;
    private JList<String> list;
    private JPanel panel;
    private JButton button;
    private JPopupMenu popup;
    private JMenuItem open;
    private Recorder recorder;

    private static AgentConfig recordConfig() {
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put("mode", "record");
        m.put("scenario.1", "target/recorder-place-test.json");
        m.put("screenshots", "off");
        return AgentConfig.fromMap(m, "test");
    }

    @Before
    public void showFrame() throws Exception {
        Assume.assumeFalse("needs a display", GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                frame = new JFrame("Places");
                DefaultMutableTreeNode root = new DefaultMutableTreeNode("Root");
                DefaultMutableTreeNode s = new DefaultMutableTreeNode("src");
                DefaultMutableTreeNode m = new DefaultMutableTreeNode("Main.java");
                s.add(m);
                s.add(new DefaultMutableTreeNode("Util.java"));
                root.add(s);
                tree = new JTree(root);
                tree.setName("t.tree");
                src = new TreePath(new Object[] {root, s});
                main = src.pathByAddingChild(m);
                tree.expandPath(src);
                popup = new JPopupMenu();
                open = new JMenuItem("Open");
                open.setName("t.open");
                popup.add(open);
                tree.setComponentPopupMenu(popup);
                table = new JTable(new DefaultTableModel(new Object[][] {{"a", 1}, {"b", 2}, {"c", 3}},
                        new Object[] {"Name", "Qty"}) {
                    @Override
                    public boolean isCellEditable(int row, int column) {
                        return false;
                    }
                });
                table.setName("t.table");
                list = new JList<String>(new String[] {"one", "two", "three"});
                list.setName("t.list");
                panel = new JPanel();
                panel.setName("t.panel");
                panel.setPreferredSize(new Dimension(100, 40));
                button = new JButton("Button");
                button.setName("t.button");
                JPanel p = new JPanel(new GridLayout(0, 1));
                p.add(tree);
                p.add(table);
                p.add(list);
                p.add(panel);
                p.add(button);
                frame.getContentPane().add(p);
                frame.pack();
                frame.setVisible(true);
            }
        });
        recorder = new Recorder(recordConfig(), new Scenario(), new HashMap<String, Snapshot>());
        recorder.start();
    }

    @After
    public void disposeFrame() throws Exception {
        if (recorder != null) recorder.pause();
        if (frame == null) return;
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                MenuSelectionManager.defaultManager().clearSelectedPath();
                popup.setVisible(false);
                frame.dispose();
            }
        });
    }

    // ---- 6.1 tree selection -------------------------------------------------------------------

    @Test
    public void treeClickRecordsSelectWithThePath() throws Exception {
        clickAt(tree, center(pathBounds(main)), 1, 1);
        List<Step> steps = steps();
        assertEquals(describe(steps), 1, steps.size());
        Action a = steps.get(0).action;
        assertEquals(Action.Type.SELECT, a.type);
        assertEquals("t.tree", a.locator.name);
        assertEquals(Arrays.asList("Root", "src", "Main.java"), a.path);
        assertNull(a.value);
    }

    @Test
    public void treeClickOutsideARowRecordsNothing() throws Exception {
        Rectangle r = pathBounds(src);
        clickAt(tree, new Point(r.x - 8, r.y + r.height / 2), 1, 1);   // the expand handle of src
        assertEquals(describe(steps()), 0, steps().size());
    }

    // ---- 6.2 double-click ---------------------------------------------------------------------

    @Test
    public void doubleClickOnATreeNode() throws Exception {
        Point p = center(pathBounds(main));
        clickAt(tree, p, 1, 1);
        clickAt(tree, p, 1, 2);
        List<Step> steps = steps();
        assertEquals(describe(steps), 2, steps.size());
        assertEquals(Action.Type.SELECT, steps.get(0).action.type);
        assertEquals(Arrays.asList("Root", "src", "Main.java"), steps.get(0).action.path);
        Action a = steps.get(1).action;
        assertEquals(Action.Type.CLICK, a.type);
        assertEquals("t.tree", a.locator.name);
        assertEquals(Arrays.asList("Root", "src", "Main.java"), a.path);
        assertEquals(Integer.valueOf(2), a.count);
        assertNull(a.button);
    }

    @Test
    public void doubleClickOnATableRow() throws Exception {
        Point p = center(cellRect(1, 0));
        clickAt(table, p, 1, 1);
        clickAt(table, p, 1, 2);
        List<Step> steps = steps();
        assertEquals(describe(steps), 2, steps.size());
        assertEquals(Action.Type.SELECT, steps.get(0).action.type);
        assertEquals("1", steps.get(0).action.value);
        Action a = steps.get(1).action;
        assertEquals(Action.Type.CLICK, a.type);
        assertEquals("t.table", a.locator.name);
        assertEquals(Integer.valueOf(1), a.row);
        assertEquals("Name", a.column);
        assertEquals(Integer.valueOf(2), a.count);
    }

    @Test
    public void doubleClickOnAListRow() throws Exception {
        Point p = center(onEdt(new Callable<Rectangle>() {
            public Rectangle call() {
                return list.getCellBounds(2, 2);
            }
        }));
        clickAt(list, p, 1, 1);
        clickAt(list, p, 1, 2);
        List<Step> steps = steps();
        assertEquals(describe(steps), 2, steps.size());
        assertEquals(Action.Type.SELECT, steps.get(0).action.type);
        assertEquals("2", steps.get(0).action.value);
        Action a = steps.get(1).action;
        assertEquals(Action.Type.CLICK, a.type);
        assertEquals("t.list", a.locator.name);
        assertEquals(Integer.valueOf(2), a.row);
        assertNull(a.column);
        assertEquals(Integer.valueOf(2), a.count);
    }

    @Test
    public void doubleClickOnAPlainPanel() throws Exception {
        Point p = new Point(10, 10);
        clickAt(panel, p, 1, 1);
        clickAt(panel, p, 1, 2);
        List<Step> steps = steps();
        assertEquals(describe(steps), 1, steps.size());
        Step s = steps.get(0);
        assertEquals(Action.Type.CLICK, s.action.type);
        assertEquals("t.panel", s.action.locator.name);
        assertEquals(Integer.valueOf(2), s.action.count);
        assertFalse("the snapshot between the two clicks is gone", recorder.hasSnapshot(s));
        awaitSnapshot(s, 5000);
    }

    @Test
    public void doubleClickOnAButtonIsTwoClicks() throws Exception {
        Point p = new Point(5, 5);
        clickAt(button, p, 1, 1);
        clickAt(button, p, 1, 2);
        List<Step> steps = steps();
        assertEquals(describe(steps), 2, steps.size());
        for (Step s : steps) {
            assertEquals(Action.Type.CLICK, s.action.type);
            assertEquals("t.button", s.action.locator.name);
            assertNull(s.action.count);
        }
    }

    /** Review Focus 5: a second click that is not a double-click of the last step adds a step. */
    @Test
    public void secondClickAfterAnotherStepAddsAStep() throws Exception {
        Point p = new Point(10, 10);
        clickAt(panel, p, 1, 1);
        clickAt(button, new Point(5, 5), 1, 1);
        dispatch(mouse(panel, MouseEvent.MOUSE_CLICKED, p, 1, 2));
        flush();
        List<Step> steps = steps();
        assertEquals(describe(steps), 3, steps.size());
        assertNull(steps.get(0).action.count);
        Action a = steps.get(2).action;
        assertEquals(Action.Type.CLICK, a.type);
        assertEquals("t.panel", a.locator.name);
        assertEquals(Integer.valueOf(2), a.count);
    }

    // ---- 6.3 right-click ----------------------------------------------------------------------

    @Test
    public void rightClickOnATreeNode() throws Exception {
        Point p = center(pathBounds(src));
        dispatch(mouse(tree, MouseEvent.MOUSE_PRESSED, p, 3, 1));
        flush();
        List<Step> steps = steps();
        assertEquals("recorded at the press: " + describe(steps), 1, steps.size());
        Action a = steps.get(0).action;
        assertEquals(Action.Type.CLICK, a.type);
        assertEquals("t.tree", a.locator.name);
        assertEquals(Arrays.asList("Root", "src"), a.path);
        assertEquals(Integer.valueOf(3), a.button);
        assertNull(a.count);
        dispatch(mouse(tree, MouseEvent.MOUSE_RELEASED, p, 3, 1));
        dispatch(mouse(tree, MouseEvent.MOUSE_CLICKED, p, 3, 1));
        flush();
        assertEquals(describe(steps()), 1, steps().size());
    }

    @Test
    public void rightClickOnATableCell() throws Exception {
        clickAt(table, center(cellRect(0, 1)), 3, 1);
        List<Step> steps = steps();
        assertEquals(describe(steps), 1, steps.size());
        Action a = steps.get(0).action;
        assertEquals(Action.Type.CLICK, a.type);
        assertEquals("t.table", a.locator.name);
        assertEquals(Integer.valueOf(0), a.row);
        assertEquals("Qty", a.column);
        assertEquals(Integer.valueOf(3), a.button);
    }

    @Test
    public void rightClickOnAPlainPanel() throws Exception {
        clickAt(panel, new Point(10, 10), 3, 1);
        List<Step> steps = steps();
        assertEquals(describe(steps), 1, steps.size());
        Action a = steps.get(0).action;
        assertEquals(Action.Type.CLICK, a.type);
        assertEquals("t.panel", a.locator.name);
        assertEquals(Integer.valueOf(3), a.button);
        assertFalse(a.hasPlace());
    }

    @Test
    public void rightClickThenMenuItem() throws Exception {
        final Point p = center(pathBounds(src));
        clickAt(tree, p, 3, 1);
        onEdt(new Runnable() {
            public void run() {
                if (!popup.isShowing()) popup.show(tree, p.x, p.y);
                MenuSelectionManager.defaultManager().setSelectedPath(new MenuElement[] {popup, open});   // arms the item
            }
        });
        dispatch(mouse(open, MouseEvent.MOUSE_PRESSED, new Point(5, 5), 1, 1));
        dispatch(mouse(open, MouseEvent.MOUSE_RELEASED, new Point(5, 5), 1, 1));
        flush();
        final List<Step> steps = steps();
        assertEquals(describe(steps), 2, steps.size());
        assertEquals(Integer.valueOf(3), steps.get(0).action.button);
        Action a = steps.get(1).action;
        assertEquals(Action.Type.CLICK, a.type);
        assertEquals("t.open", a.locator.name);
        // The locator of the item resolves while the popup shows (spec 6.3).
        Component found = onEdt(new Callable<Component>() {
            public Component call() {
                if (!popup.isShowing()) popup.show(tree, p.x, p.y);
                return Locators.find(steps.get(1).action.locator);
            }
        });
        assertSame(open, found);
    }

    @Test
    public void middleButtonRecordsNothing() throws Exception {
        clickAt(panel, new Point(10, 10), 2, 1);
        clickAt(tree, center(pathBounds(main)), 2, 1);
        assertEquals(describe(steps()), 0, steps().size());
    }

    @Test
    public void rightClickTakesTheSnapshotOfTheStepBefore() throws Exception {
        clickAt(button, new Point(5, 5), 1, 1);
        dispatch(mouse(tree, MouseEvent.MOUSE_PRESSED, center(pathBounds(main)), 3, 1));
        List<Step> steps = steps();
        assertEquals(describe(steps), 2, steps.size());
        assertTrue("the press takes the snapshot of the step before", recorder.hasSnapshot(steps.get(0)));
        assertFalse("the right-click step waits for its own snapshot", recorder.hasSnapshot(steps.get(1)));
        dispatch(mouse(tree, MouseEvent.MOUSE_RELEASED, center(pathBounds(main)), 3, 1));
    }

    // ---- helpers ------------------------------------------------------------------------------

    /** Press, release and clicked at {@code p}, each through the AWT event listeners; then the EDT is flushed. */
    private static void clickAt(Component c, Point p, int button, int count) throws Exception {
        dispatch(mouse(c, MouseEvent.MOUSE_PRESSED, p, button, count));
        dispatch(mouse(c, MouseEvent.MOUSE_RELEASED, p, button, count));
        dispatch(mouse(c, MouseEvent.MOUSE_CLICKED, p, button, count));
        flush();
    }

    private static MouseEvent mouse(Component c, int id, Point p, int button, int count) {
        int down = button == 1 ? InputEvent.BUTTON1_DOWN_MASK : button == 2 ? InputEvent.BUTTON2_DOWN_MASK
                : InputEvent.BUTTON3_DOWN_MASK;
        int mods = id == MouseEvent.MOUSE_PRESSED ? down : 0;
        return new MouseEvent(c, id, System.currentTimeMillis(), mods, p.x, p.y, count, false, button);
    }

    private static void dispatch(final MouseEvent e) throws Exception {
        onEdt(new Runnable() {
            public void run() {
                e.getComponent().dispatchEvent(e);
            }
        });
    }

    /** Two EDT round trips: the recorder reads the state of a click in a later EDT task. */
    private static void flush() throws Exception {
        for (int i = 0; i < 2; i++) {
            onEdt(new Runnable() {
                public void run() {
                }
            });
        }
    }

    private Rectangle pathBounds(final TreePath p) throws Exception {
        return onEdt(new Callable<Rectangle>() {
            public Rectangle call() {
                return tree.getPathBounds(p);
            }
        });
    }

    private Rectangle cellRect(final int row, final int column) throws Exception {
        return onEdt(new Callable<Rectangle>() {
            public Rectangle call() {
                return table.getCellRect(row, column, false);
            }
        });
    }

    private static Point center(Rectangle r) {
        return new Point(r.x + r.width / 2, r.y + r.height / 2);
    }

    private List<Step> steps() throws Exception {
        return onEdt(new Callable<List<Step>>() {
            public List<Step> call() {
                return new ArrayList<Step>(recorder.scenario().steps);
            }
        });
    }

    private void awaitSnapshot(Step s, long ms) throws Exception {
        long deadline = System.currentTimeMillis() + ms;
        while (!recorder.hasSnapshot(s)) {
            if (System.currentTimeMillis() > deadline) fail("no snapshot for " + s.id + " within " + ms + " ms");
            Thread.sleep(50);
        }
    }

    private static String describe(List<Step> steps) {
        List<String> out = new ArrayList<String>();
        for (Step s : steps) out.add(s.id + " " + s.action);
        return out.toString();
    }

    private static <T> T onEdt(Callable<T> c) throws Exception {
        return Quiescence.onEdt(c);
    }

    private static void onEdt(Runnable r) throws Exception {
        SwingUtilities.invokeAndWait(r);
    }
}
