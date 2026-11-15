package smoker.agent;

import nametest.AnonFactory;
import nametest.FormHolder;
import nametest.OrdersPanel;
import nametest.OrdersPanel2;
import nametest.OrdersView;
import nametest.SpinnerPanel;
import nametest.StaticHolder;
import nametest.Twice;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.GraphicsEnvironment;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Automatic names {@code @<Class>.<field>} by reflection (WP-09, spec 5.1). Needs a display (Linux: xvfb-run).
 * All Swing access and all calls of {@link AutoNames#of} go through the EDT.
 */
public class AutoNamesTest {

    private JFrame frame;

    @Before
    public void needsDisplay() {
        Assume.assumeFalse("needs a display", GraphicsEnvironment.isHeadless());
    }

    @After
    public void dispose() throws Exception {
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
    public void applicationClassRule() {
        assertTrue(AutoNames.isApplicationClass(OrdersPanel.class));
        assertFalse(AutoNames.isApplicationClass(JPanel.class));
        assertFalse(AutoNames.isApplicationClass(AutoNames.class));
        assertFalse(AutoNames.isApplicationClass(String.class));
    }

    @Test
    public void fieldOfTheNearestHolderGivesTheName() throws Exception {
        final OrdersPanel p = show(new Callable<OrdersPanel>() {
            public OrdersPanel call() {
                return new OrdersPanel();
            }
        });
        AutoNames.Hit h = of(new Callable<Component>() {
            public Component call() {
                return p.saveButton();
            }
        });
        assertNotNull(h);
        assertEquals("@OrdersPanel.saveButton", h.name);
        assertEquals("saveButton", h.field);
        assertEquals("nametest.OrdersPanel", h.runtimeClass);
        assertEquals("nametest.OrdersPanel", h.declaringClass);
    }

    @Test
    public void realNameWins() throws Exception {
        final OrdersPanel p = show(new Callable<OrdersPanel>() {
            public OrdersPanel call() {
                OrdersPanel o = new OrdersPanel();
                o.saveButton().setName("orders.btn.save");
                return o;
            }
        });
        assertNull(of(new Callable<Component>() {
            public Component call() {
                return p.saveButton();
            }
        }));
    }

    @Test
    public void generatedNameCountsAsNoName() throws Exception {
        final OrdersPanel p = show(new Callable<OrdersPanel>() {
            public OrdersPanel call() {
                OrdersPanel o = new OrdersPanel();
                o.saveButton().setName("frame0");
                return o;
            }
        });
        AutoNames.Hit h = of(new Callable<Component>() {
            public Component call() {
                return p.saveButton();
            }
        });
        assertEquals("@OrdersPanel.saveButton", h.name);
    }

    @Test
    public void helperPanelWithoutTheFieldIsPassedOver() throws Exception {
        final OrdersView v = show(new Callable<OrdersView>() {
            public OrdersView call() {
                return new OrdersView();
            }
        });
        AutoNames.Hit h = of(new Callable<Component>() {
            public Component call() {
                return v.okButton();
            }
        });
        assertEquals("@OrdersView.okButton", h.name);
    }

    /** Review Focus 1. */
    @Test
    public void firstFieldInDeclarationOrderWins() throws Exception {
        final Twice t = show(new Callable<Twice>() {
            public Twice call() {
                return new Twice();
            }
        });
        AutoNames.Hit h = of(new Callable<Component>() {
            public Component call() {
                return t.button();
            }
        });
        assertEquals("@Twice.a", h.name);
    }

    /** Review Focus 2. */
    @Test
    public void superclassFieldUsesTheRuntimeClass() throws Exception {
        final OrdersPanel2 p = show(new Callable<OrdersPanel2>() {
            public OrdersPanel2 call() {
                return new OrdersPanel2();
            }
        });
        AutoNames.Hit h = of(new Callable<Component>() {
            public Component call() {
                return p.okButton();
            }
        });
        assertEquals("@OrdersPanel2.okButton", h.name);
        assertEquals("nametest.OrdersPanel2", h.runtimeClass);
        assertEquals("nametest.BasePanel", h.declaringClass);
        assertEquals("okButton", h.field);
    }

    /** Review Focus 4: the editor text field of a spinner is held only by JDK fields. */
    @Test
    public void componentHeldOnlyByAJdkFieldHasNoName() throws Exception {
        final SpinnerPanel p = show(new Callable<SpinnerPanel>() {
            public SpinnerPanel call() {
                return new SpinnerPanel();
            }
        });
        assertNull(of(new Callable<Component>() {
            public Component call() {
                Component text = ((JSpinner.DefaultEditor) p.spinner().getEditor()).getTextField();
                // Swing names it "Spinner.formattedTextField": clear it so that the real-name rule does not decide.
                text.setName(null);
                return text;
            }
        }));
        AutoNames.Hit h = of(new Callable<Component>() {
            public Component call() {
                return p.spinner();
            }
        });
        assertEquals("@SpinnerPanel.spinner", h.name);
    }

    @Test
    public void layoutContainerHeldInAFieldHasNoName() throws Exception {
        final FormHolder f = show(new Callable<FormHolder>() {
            public FormHolder call() {
                return new FormHolder();
            }
        });
        assertNull(of(new Callable<Component>() {
            public Component call() {
                return f.form();
            }
        }));
    }

    @Test
    public void staticFieldDoesNotCount() throws Exception {
        final StaticHolder s = show(new Callable<StaticHolder>() {
            public StaticHolder call() {
                return new StaticHolder();
            }
        });
        assertNull(of(new Callable<Component>() {
            public Component call() {
                return s.shared();
            }
        }));
    }

    @Test
    public void anonymousClassUsesTheEnclosingClassName() throws Exception {
        final JPanel p = show(new Callable<JPanel>() {
            public JPanel call() {
                return new AnonFactory().create();
            }
        });
        AutoNames.Hit h = of(new Callable<Component>() {
            public Component call() {
                return p.getComponent(0);
            }
        });
        assertEquals("@AnonFactory.okButton", h.name);
    }

    @Test
    public void disabledGivesNoName() throws Exception {
        final OrdersPanel p = show(new Callable<OrdersPanel>() {
            public OrdersPanel call() {
                return new OrdersPanel();
            }
        });
        AutoNames.enable(false);
        assertFalse(AutoNames.enabled());
        assertNull(of(new Callable<Component>() {
            public Component call() {
                return p.saveButton();
            }
        }));
    }

    /** Builds the panel on the EDT and shows it in a frame. */
    private <T extends JComponent> T show(final Callable<T> make) throws Exception {
        return onEdt(new Callable<T>() {
            public T call() throws Exception {
                T panel = make.call();
                frame = new JFrame("AutoNames");
                frame.getContentPane().add(panel);
                frame.pack();
                frame.setVisible(true);
                return panel;
            }
        });
    }

    /** Calls {@link AutoNames#of} on the EDT. */
    private static AutoNames.Hit of(final Callable<Component> target) throws Exception {
        return onEdt(new Callable<AutoNames.Hit>() {
            public AutoNames.Hit call() throws Exception {
                return AutoNames.of(target.call());
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
