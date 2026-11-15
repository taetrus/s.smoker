package smoker.agent;

import org.junit.Test;
import smoker.core.model.Locator;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Locator logic that needs no window. {@link LocatorsWindowTest} examines the parts that need a display.
 * The components are not shown, so access from the test thread is permitted, except for
 * {@link Locators#resolve} of a component without a real name: automatic names run on the EDT only.
 */
public class LocatorsTest {

    private static Locator resolveOnEdt(final Component c) throws Exception {
        final Locator[] out = new Locator[1];
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                out[0] = Locators.resolve(c);
            }
        });
        return out[0];
    }

    @Test
    public void swingGeneratedNamesAreAutoNames() {
        assertTrue(Locators.isAutoName("null.contentPane"));
        assertTrue(Locators.isAutoName("OptionPane.button"));
        assertTrue(Locators.isAutoName("frame0"));
        assertTrue(Locators.isAutoName("dialog12"));
        assertTrue(Locators.isAutoName("win1.glassPane"));
        assertTrue(Locators.isAutoName("x.layeredPane"));
    }

    @Test
    public void uiDelegateNamesAreAutoNames() {
        assertTrue(Locators.isAutoName("Spinner.formattedTextField"));
        assertTrue(Locators.isAutoName("ComboBox.textField"));
        assertTrue(Locators.isAutoName("Table.editor"));
        assertTrue(Locators.isAutoName("Tree.cellEditor"));
        assertTrue(Locators.isAutoName("List.cellRenderer"));
        assertTrue(Locators.isAutoName("FileChooser.fileNameTextField"));
        assertTrue(Locators.isAutoName("ColorChooser.swatches"));
        assertFalse(Locators.isAutoName("orders.save"));
        assertFalse(Locators.isAutoName("Spinner"));
    }

    @Test
    public void applicationNamesAreNotAutoNames() {
        assertFalse(Locators.isAutoName("demo.greet"));
        assertFalse(Locators.isAutoName("frame"));
        assertFalse(Locators.isAutoName("myframe0"));
        assertFalse(Locators.isAutoName("orders.window1"));
    }

    @Test
    public void textOfUsesVisibleLabelsOnly() {
        assertEquals("Greet", Locators.textOf(new JButton("Greet")));
        assertEquals("Name:", Locators.textOf(new JLabel("Name:")));
        assertNull("empty text", Locators.textOf(new JButton("")));
        assertNull("editable text is not a key", Locators.textOf(new JTextField("Kerem")));
        assertNull(Locators.textOf(new JPanel()));
    }

    @Test
    public void namedComponentResolvesToItsNameOnly() {
        JButton b = new JButton("Greet");
        b.setName("demo.greet");
        Locator l = Locators.resolve(b);
        assertEquals("demo.greet", l.name);
        assertEquals("JButton", l.className);
        assertNull("no text for a named component", l.text);
        assertNull(l.windowTitle);
        assertEquals("#demo.greet", l.toString());
    }

    @Test
    public void autoNamedComponentResolvesToItsText() throws Exception {
        JButton b = new JButton("OK");
        b.setName("OptionPane.button");
        Locator l = resolveOnEdt(b);
        assertNull(l.name);
        assertEquals("OK", l.text);
        assertNull("no window: no index", l.index);
        assertNull("not shown: no coordinates", l.x);
    }

    @Test
    public void anonymousSubclassResolvesToItsSuperclass() throws Exception {
        JButton b = new JButton("Go") {
        };
        assertEquals("JButton", resolveOnEdt(b).className);
    }

    @Test
    public void allComponentsIncludesItemsOfClosedMenus() {
        JMenuBar bar = new JMenuBar();
        JMenu help = new JMenu("Help");
        JMenuItem about = new JMenuItem("About");
        help.add(about);
        bar.add(help);
        JPanel root = new JPanel();
        root.add(bar);
        List<Component> all = Locators.allComponents(root);
        assertTrue(all.contains(help));
        assertTrue(all.contains(about));
        assertEquals("depth-first: root first", root, all.get(0));
    }

    @Test
    public void interactiveComponentsAreRecognised() {
        assertTrue(Locators.isInteractive(new JButton()));
        assertTrue(Locators.isInteractive(new JTextField()));
        assertTrue(Locators.isInteractive(new JTable()));
        assertFalse(Locators.isInteractive(new JLabel()));
        assertFalse(Locators.isInteractive(new JPanel()));
    }

    @Test
    public void titleOfNullWindowIsNull() {
        assertNull(Locators.titleOf(null));
        assertNull(Locators.windowOf(new JButton()));
    }
}
