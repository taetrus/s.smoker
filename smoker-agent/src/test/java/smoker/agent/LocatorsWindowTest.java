package smoker.agent;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import smoker.core.model.Locator;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

/**
 * Locator logic that needs a shown window. Skipped without a display (Linux: use xvfb-run).
 * All Swing access goes through the EDT.
 */
public class LocatorsWindowTest {

    private JFrame frame;
    private JButton named;
    private JButton ok0;
    private JButton ok1;
    private JMenuItem popupItem;

    @Before
    public void showFrame() throws Exception {
        Assume.assumeFalse("needs a display", GraphicsEnvironment.isHeadless());
        onEdt(new Callable<Void>() {
            public Void call() {
                frame = new JFrame("Orders");
                JPanel p = new JPanel();
                named = new JButton("Save");
                named.setName("orders.save");
                ok0 = new JButton("OK");
                ok1 = new JButton("OK");
                p.add(named);
                p.add(ok0);
                p.add(ok1);
                JPopupMenu popup = new JPopupMenu();
                popupItem = new JMenuItem("Copy");
                popup.add(popupItem);
                popup.setInvoker(ok1);
                frame.getContentPane().add(p);
                frame.pack();
                frame.setVisible(true);
                return null;
            }
        });
    }

    @After
    public void disposeFrame() throws Exception {
        if (frame == null) return;
        onEdt(new Callable<Void>() {
            public Void call() {
                frame.dispose();
                return null;
            }
        });
    }

    @Test
    public void secondButtonWithTheSameTextGetsIndexOne() throws Exception {
        Locator l = onEdt(new Callable<Locator>() {
            public Locator call() {
                return Locators.resolve(ok1);
            }
        });
        assertEquals("Orders", l.windowTitle);
        assertEquals("OK", l.text);
        assertEquals(Integer.valueOf(1), l.index);
        assertEquals("[Orders] JButton('OK')[1]", l.toString());
        assertSame(ok1, find(l));
    }

    @Test
    public void firstButtonHasNoIndex() throws Exception {
        Locator l = onEdt(new Callable<Locator>() {
            public Locator call() {
                return Locators.resolve(ok0);
            }
        });
        assertNull(l.index);
        assertSame(ok0, find(l));
    }

    @Test
    public void namedLocatorIgnoresTheWindowTitle() throws Exception {
        Locator l = new Locator();
        l.name = "orders.save";
        l.windowTitle = "Other";
        assertSame(named, find(l));
    }

    @Test
    public void unnamedLocatorInAnotherWindowIsNotFound() throws Exception {
        Locator l = new Locator();
        l.windowTitle = "Other";
        l.className = "JButton";
        l.text = "OK";
        assertNull(find(l));
    }

    @Test
    public void indexOutOfRangeIsNotFound() throws Exception {
        Locator l = new Locator();
        l.windowTitle = "Orders";
        l.className = "JButton";
        l.text = "OK";
        l.index = 2;
        assertNull(find(l));
    }

    @Test
    public void popupItemBelongsToTheWindowOfItsInvoker() throws Exception {
        Window w = onEdt(new Callable<Window>() {
            public Window call() {
                return Locators.logicalWindowOf(popupItem);
            }
        });
        assertSame(frame, w);
    }

    private static Component find(final Locator l) throws Exception {
        return onEdt(new Callable<Component>() {
            public Component call() {
                return Locators.find(l);
            }
        });
    }

    private static <T> T onEdt(final Callable<T> c) throws Exception {
        final AtomicReference<T> result = new AtomicReference<T>();
        final AtomicReference<Exception> error = new AtomicReference<Exception>();
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                try {
                    result.set(c.call());
                } catch (Exception e) {
                    error.set(e);
                }
            }
        });
        if (error.get() != null) throw error.get();
        return result.get();
    }
}
