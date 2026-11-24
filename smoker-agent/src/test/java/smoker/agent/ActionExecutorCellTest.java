package smoker.agent;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import smoker.core.model.Action;
import smoker.core.model.Locator;

import javax.swing.DefaultCellEditor;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.RowSorter;
import javax.swing.SortOrder;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import java.awt.GraphicsEnvironment;
import java.util.Collections;
import java.util.concurrent.Callable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Replay of SET_CELL and of a spinner value (WP-06, spec 6.4 and 6.5). Needs a display. */
public class ActionExecutorCellTest {

    private JFrame frame;
    private JTable table;
    private DefaultTableModel model;
    private JSpinner spinner;

    @Before
    public void show() throws Exception {
        Assume.assumeFalse("needs a display", GraphicsEnvironment.isHeadless());
        onEdt(new Callable<Object>() {
            public Object call() {
                model = new DefaultTableModel(new Object[][] {
                        {"a", 1, false, "a", "f"}, {"b", 2, false, "a", "f"}, {"c", 3, false, "a", "f"}},
                        new Object[] {"Name", "Qty", "Done", "Kind", "Fixed"}) {
                    @Override public Class<?> getColumnClass(int c) {
                        return c == 1 ? Integer.class : c == 2 ? Boolean.class : String.class;
                    }
                    @Override public boolean isCellEditable(int r, int c) { return c != 4; }
                };
                table = new JTable(model);
                table.setName("c.table");
                table.getColumnModel().getColumn(3).setCellEditor(
                        new DefaultCellEditor(new JComboBox<String>(new String[] {"a", "b", "c"})));
                spinner = new JSpinner(new SpinnerNumberModel(1, 0, 10, 1));
                spinner.setName("c.spinner");
                frame = new JFrame("ActionExecutorCellTest");
                JPanel p = new JPanel();
                p.add(new JScrollPane(table));
                p.add(spinner);
                frame.getContentPane().add(p);
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

    private void run(final Action a) throws Exception {
        onEdt(new Callable<Object>() {
            public Object call() throws Exception {
                ActionExecutor.execute(a);
                return null;
            }
        });
    }

    private Locator loc(String name) {
        Locator l = new Locator();
        l.name = name;
        return l;
    }

    private Action cell(int row, String column, String value) {
        return Action.setCell(loc("c.table"), row, column, value);
    }

    private Action spin(String value) {
        return Action.setText(loc("c.spinner"), value);
    }

    private Object valueAt(final int row, final int col) throws Exception {
        return onEdt(new Callable<Object>() {
            public Object call() { return model.getValueAt(row, col); }
        });
    }

    private void assertNotEditing() throws Exception {
        assertFalse(onEdt(new Callable<Boolean>() {
            public Boolean call() { return table.isEditing(); }
        }));
    }

    private void expectFailure(Action a, String contains) throws Exception {
        try {
            run(a);
            fail("expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(contains));
        }
    }

    @Test
    public void textCell() throws Exception {
        run(cell(1, "Name", "Ana"));
        assertEquals("Ana", valueAt(1, 0));
        assertNotEditing();
    }

    /** Ruling 7: a real edit selects the cell, so the replay selects it too. */
    @Test
    public void setCellSelectsTheCell() throws Exception {
        run(cell(1, "Name", "Ana"));
        assertEquals(Integer.valueOf(1), onEdt(new Callable<Integer>() {
            public Integer call() { return table.getSelectedRow(); }
        }));
        assertEquals(Integer.valueOf(0), onEdt(new Callable<Integer>() {
            public Integer call() { return table.getSelectedColumn(); }
        }));
    }

    /** Plan Review Focus 1: after a column move, the header text finds the moved column. */
    @Test
    public void movedColumns() throws Exception {
        onEdt(new Callable<Object>() {
            public Object call() {
                table.moveColumn(0, 1);   // view: Qty, Name, Done, Kind, Fixed
                return null;
            }
        });
        run(cell(1, "Name", "Ana"));
        assertEquals("Ana", valueAt(1, 0));
        assertEquals(Integer.valueOf(2), valueAt(1, 1));
        run(cell(0, "Qty", "7"));
        assertEquals(Integer.valueOf(7), valueAt(0, 1));
        assertEquals("a", valueAt(0, 0));
    }

    @Test
    public void integerCellGoesThroughTheEditor() throws Exception {
        run(cell(0, "Qty", "5"));
        assertEquals(Integer.valueOf(5), valueAt(0, 1));
    }

    @Test
    public void integerEditorRefusesText() throws Exception {
        expectFailure(cell(0, "Qty", "abc"), "the cell editor refused the value \"abc\"");
        assertNotEditing();
        assertEquals(Integer.valueOf(1), valueAt(0, 1));
    }

    @Test
    public void booleanCell() throws Exception {
        run(cell(2, "Done", "true"));
        assertEquals(Boolean.TRUE, valueAt(2, 2));
    }

    @Test
    public void comboCell() throws Exception {
        run(cell(0, "Kind", "b"));
        assertEquals("b", valueAt(0, 3));
        expectFailure(cell(0, "Kind", "zzz"), "the cell editor refused the value \"zzz\"");
        assertNotEditing();
        assertEquals("b", valueAt(0, 3));
    }

    @Test
    public void notEditable() throws Exception {
        expectFailure(cell(0, "Fixed", "x"), "not editable");
        assertEquals("f", valueAt(0, 4));
    }

    @Test
    public void rowOutside() throws Exception {
        expectFailure(cell(9, "Name", "x"), "row not found: 9");
    }

    @Test
    public void unknownColumn() throws Exception {
        expectFailure(cell(0, "Nope", "x"), "column not found: Nope");
    }

    /** Review M1: a SET_CELL without a column gives a clear failure. */
    @Test
    public void nullColumn() throws Exception {
        expectFailure(cell(0, null, "x"), "column not found: null");
    }

    @Test
    public void sortedTable() throws Exception {
        onEdt(new Callable<Object>() {
            public Object call() {
                table.setAutoCreateRowSorter(true);
                table.getRowSorter().setSortKeys(Collections.singletonList(new RowSorter.SortKey(0, SortOrder.DESCENDING)));
                return null;
            }
        });
        run(cell(0, "Name", "Zed2"));
        assertEquals("Zed2", valueAt(2, 0));
        assertEquals("a", valueAt(0, 0));
    }

    @Test
    public void spinner() throws Exception {
        run(spin("7"));
        assertEquals(Integer.valueOf(7), spinnerValue());
    }

    @Test
    public void spinnerRefusesText() throws Exception {
        expectFailure(spin("abc"), "abc");
        assertEquals(Integer.valueOf(1), spinnerValue());
    }

    @Test
    public void spinnerWithoutDefaultEditor() throws Exception {
        onEdt(new Callable<Object>() {
            public Object call() {
                spinner.setEditor(new JLabel());
                return null;
            }
        });
        expectFailure(spin("3"), "editor");
    }

    private Object spinnerValue() throws Exception {
        return onEdt(new Callable<Object>() {
            public Object call() { return spinner.getValue(); }
        });
    }
}
