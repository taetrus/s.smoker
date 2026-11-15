package smoker.agent;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import nametest.OrdersPanel;
import smoker.core.model.Locator;
import smoker.core.model.WindowKey;
import java.util.Arrays;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JPopupMenu;
import javax.swing.JTextField;
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
    private JPasswordField apiKey;
    private JTextField model;

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
                // F-23: the password field comes first. JPasswordField extends JTextField.
                apiKey = new JPasswordField(10);
                model = new JTextField(10);
                p.add(apiKey);
                p.add(model);
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
        Locators.useWindowKeys(null);
        AutoNames.enable(true);
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
    public void textFieldLocatorDoesNotFindThePasswordFieldInFrontOfIt() throws Exception {
        Locator l = onEdt(new Callable<Locator>() {
            public Locator call() {
                return Locators.resolve(model);
            }
        });
        assertEquals("JTextField", l.className);
        assertNull(l.index);
        assertSame(model, find(l));
    }

    @Test
    public void passwordFieldLocatorFindsThePasswordField() throws Exception {
        Locator l = onEdt(new Callable<Locator>() {
            public Locator call() {
                return Locators.resolve(apiKey);
            }
        });
        assertEquals("JPasswordField", l.className);
        assertSame(apiKey, find(l));
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

    @Test
    public void unnamedLocatorFindsTheWindowAfterATitleChange() throws Exception {
        Locators.useWindowKeys(new WindowKeys(Arrays.asList(new WindowKey("Editor - .*", "Editor"))));
        onEdt(new Callable<Void>() {
            public Void call() {
                frame.setTitle("Editor - a.txt");
                return null;
            }
        });
        Locator l = onEdt(new Callable<Locator>() {
            public Locator call() {
                return Locators.resolve(ok1);
            }
        });
        assertEquals("Editor", l.windowTitle);
        onEdt(new Callable<Void>() {
            public Void call() {
                frame.setTitle("Editor - b.txt");
                return null;
            }
        });
        assertSame(ok1, find(l));
    }

    @Test
    public void resolveUsesTheAutomaticName() throws Exception {
        final OrdersPanel orders = addOrdersPanel();
        Locator l = onEdt(new Callable<Locator>() {
            public Locator call() {
                return Locators.resolve(orders.saveButton());
            }
        });
        assertEquals("@OrdersPanel.saveButton", l.name);
        assertEquals("#@OrdersPanel.saveButton", l.toString());
        assertSame(orders.saveButton(), find(l));
    }

    /** Spec goal 5. */
    @Test
    public void oldAutomaticLocatorFindsTheComponentAfterARealName() throws Exception {
        final OrdersPanel orders = addOrdersPanel();
        Locator l = onEdt(new Callable<Locator>() {
            public Locator call() {
                Locator r = Locators.resolve(orders.saveButton());
                orders.saveButton().setName("orders.btn.save");
                return r;
            }
        });
        assertEquals("@OrdersPanel.saveButton", l.name);
        assertSame(orders.saveButton(), find(l));
    }

    @Test
    public void automaticLocatorIsNotFoundWhenAutoNamesAreOff() throws Exception {
        addOrdersPanel();
        AutoNames.enable(false);
        Locator l = new Locator();
        l.name = "@OrdersPanel.saveButton";
        assertNull(find(l));
    }

    /** Adds an application panel with an unnamed button in a field to the frame. */
    private OrdersPanel addOrdersPanel() throws Exception {
        return onEdt(new Callable<OrdersPanel>() {
            public OrdersPanel call() {
                OrdersPanel o = new OrdersPanel();
                frame.getContentPane().add(o, java.awt.BorderLayout.SOUTH);
                frame.pack();
                return o;
            }
        });
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
