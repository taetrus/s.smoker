package smoker.agent;

import nametest.BarPanel;
import nametest.BuilderPanel;
import nametest.LoginPanel;
import nametest.OrdersFrame;
import nametest.OrdersPanel;
import nametest.OrdersView;
import nametest.QuantityPanel;
import nametest.SettingsDialogPanel;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import smoker.core.io.Json;
import smoker.core.model.LintEntry;
import smoker.core.model.RunResult;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Lint entries with proposed names (WP-09, spec 5.3 and 5.5). Needs a display. */
public class SnapshotTakerLintTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final List<java.awt.Window> frames = new ArrayList<java.awt.Window>();

    @Before
    public void needsADisplay() {
        Assume.assumeFalse("needs a display", GraphicsEnvironment.isHeadless());
    }

    @After
    public void disposeWindows() throws Exception {
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                for (java.awt.Window f : frames) f.dispose();
            }
        });
    }

    private SnapshotTaker lintOf(final JPanel panel) throws Exception {
        final SnapshotTaker taker = new SnapshotTaker(20);
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                JFrame f = new JFrame("Orders");
                frames.add(f);
                f.getContentPane().add(panel);
                f.pack();
                f.setVisible(true);
                taker.take();
            }
        });
        return taker;
    }

    private static JPanel onEdt(Callable<JPanel> c) throws Exception {
        final AtomicReference<JPanel> r = new AtomicReference<JPanel>();
        final Callable<JPanel> cc = c;
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                try {
                    r.set(cc.call());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });
        return r.get();
    }

    @Test
    public void unnamedButtonInAFieldGivesAFullEntry() throws Exception {
        List<LintEntry> lint = lintOf(onEdt(new Callable<JPanel>() {
            public JPanel call() {
                return new OrdersPanel();
            }
        })).lintEntries();
        assertEquals(1, lint.size());
        LintEntry e = lint.get(0);
        assertEquals("[Orders]", e.window);
        assertEquals("nametest.OrdersPanel", e.runtimeClass);
        assertEquals("nametest.OrdersPanel", e.declaringClass);
        assertEquals("saveButton", e.field);
        assertEquals("JButton", e.type);
        assertEquals("Save", e.text);
        assertEquals("@OrdersPanel.saveButton", e.autoName);
        assertEquals("orders.btn.save", e.proposedName);
        assertEquals("field", e.source);
    }

    @Test
    public void localButtonGivesATextProposal() throws Exception {
        List<LintEntry> lint = lintOf(onEdt(new Callable<JPanel>() {
            public JPanel call() {
                return new SettingsDialogPanel("Cancel");
            }
        })).lintEntries();
        assertEquals(1, lint.size());
        LintEntry e = lint.get(0);
        assertEquals("nametest.SettingsDialogPanel", e.runtimeClass);
        assertNull(e.declaringClass);
        assertNull(e.field);
        assertNull(e.autoName);
        assertEquals("settingsDialog.btn.cancel", e.proposedName);
        assertEquals("text", e.source);
    }

    @Test
    public void equalProposalsGetASuffix() throws Exception {
        List<LintEntry> lint = lintOf(onEdt(new Callable<JPanel>() {
            public JPanel call() {
                return new SettingsDialogPanel("OK", "OK", "OK");
            }
        })).lintEntries();
        assertEquals(3, lint.size());
        assertEquals("settingsDialog.btn.ok", lint.get(0).proposedName);
        assertEquals("settingsDialog.btn.ok2", lint.get(1).proposedName);
        assertEquals("settingsDialog.btn.ok3", lint.get(2).proposedName);
    }

    @Test
    public void passwordFieldValueReachesNoFile() throws Exception {
        SnapshotTaker taker = lintOf(onEdt(new Callable<JPanel>() {
            public JPanel call() {
                return new LoginPanel();
            }
        }));
        List<LintEntry> lint = taker.lintEntries();
        assertEquals(1, lint.size());
        assertNotNull(lint.get(0).autoName);
        assertNull(lint.get(0).text);
        RunResult r = new RunResult();
        r.lint.addAll(lint);
        r.autoNames.addAll(taker.autoNamesUsed());
        Path f = tmp.getRoot().toPath().resolve("run-result.json");
        Json.write(f, r);
        String json = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
        assertFalse(json, json.contains("sk-secret"));
        assertTrue(json, json.contains("@LoginPanel.secret"));
    }

    @Test
    public void namedComponentIsNotInTheLint() throws Exception {
        SnapshotTaker taker = lintOf(onEdt(new Callable<JPanel>() {
            public JPanel call() {
                OrdersPanel p = new OrdersPanel();
                p.saveButton().setName("orders.btn.save");
                return p;
            }
        }));
        assertTrue(taker.lintEntries().isEmpty());
        assertTrue(taker.autoNamesUsed().isEmpty());
    }

    @Test
    public void automaticNamesUsedAreListed() throws Exception {
        SnapshotTaker taker = lintOf(onEdt(new Callable<JPanel>() {
            public JPanel call() {
                return new OrdersPanel();
            }
        }));
        assertEquals("[@OrdersPanel.saveButton]", taker.autoNamesUsed().toString());
    }

    @Test
    public void secondSnapshotKeepsTheSuffixes() throws Exception {
        final SnapshotTaker taker = lintOf(onEdt(new Callable<JPanel>() {
            public JPanel call() {
                return new SettingsDialogPanel("OK", "OK", "OK");
            }
        }));
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                taker.take();
            }
        });
        List<LintEntry> lint = taker.lintEntries();
        assertEquals(3, lint.size());
        assertEquals("settingsDialog.btn.ok", lint.get(0).proposedName);
        assertEquals("settingsDialog.btn.ok2", lint.get(1).proposedName);
        assertEquals("settingsDialog.btn.ok3", lint.get(2).proposedName);
    }

    /** One field is one line of code: the same field in two windows and two instances keeps one proposal (spec 5.3). */
    @Test
    public void reusablePanelInTwoWindowsGivesOneProposal() throws Exception {
        final SnapshotTaker taker = new SnapshotTaker(20);
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                JFrame f = new JFrame("Main");
                frames.add(f);
                JPanel both = new JPanel();
                both.add(new BarPanel());
                both.add(new BarPanel());
                f.getContentPane().add(both);
                f.pack();
                f.setVisible(true);
                JDialog d = new JDialog(f, "Bar");
                frames.add(d);
                d.getContentPane().add(new BarPanel());
                d.pack();
                d.setVisible(true);
                taker.take();
            }
        });
        List<LintEntry> lint = taker.lintEntries();
        assertEquals(3, lint.size());
        for (LintEntry e : lint) assertEquals(e.window, "bar.btn.ok", e.proposedName);
    }

    /** Two classes with one view stem in one window: the second equal proposal gets the suffix 2 (spec 5.3). */
    @Test
    public void twoClassesWithOneViewStemInOneWindowGetASuffix() throws Exception {
        final SnapshotTaker taker = new SnapshotTaker(20);
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                OrdersFrame f = new OrdersFrame();
                frames.add(f);
                f.getContentPane().add(new OrdersView(), java.awt.BorderLayout.SOUTH);
                f.pack();
                f.setVisible(true);
                taker.take();
            }
        });
        List<LintEntry> lint = taker.lintEntries();
        assertEquals(2, lint.size());
        assertEquals("nametest.OrdersFrame", lint.get(0).runtimeClass);
        assertEquals("orders.btn.ok", lint.get(0).proposedName);
        assertEquals("nametest.OrdersView", lint.get(1).runtimeClass);
        assertEquals("orders.btn.ok2", lint.get(1).proposedName);
    }

    /**
     * A spinner and a slider need a real name. The editor text field inside the spinner is part of the spinner:
     * it gives no entry of its own.
     */
    @Test
    public void spinnerAndSliderGiveEntries() throws Exception {
        List<LintEntry> lint = lintOf(onEdt(new Callable<JPanel>() {
            public JPanel call() {
                return new QuantityPanel();
            }
        })).lintEntries();
        List<String> proposals = new ArrayList<String>();
        for (LintEntry e : lint) proposals.add(e.type + " " + e.autoName + " " + e.proposedName);
        assertEquals(proposals.toString(), 2, lint.size());
        assertTrue(proposals.toString(), proposals.contains("JSpinner @QuantityPanel.amountSpinner quantity.spn.amount"));
        assertTrue(proposals.toString(), proposals.contains("JSlider @QuantityPanel.volumeSlider quantity.sld.volume"));
    }

    /**
     * WP-09 follow-up: a field name of a GUI builder gives way to the text (item 4), and a labeled text field
     * gets the text of its label, also without a field (item 5).
     */
    @Test
    public void builderFieldsAndLabelsGiveReadableProposals() throws Exception {
        List<LintEntry> lint = lintOf(onEdt(new Callable<JPanel>() {
            public JPanel call() {
                return new BuilderPanel();
            }
        })).lintEntries();
        List<String> rows = new ArrayList<String>();
        for (LintEntry e : lint) rows.add(e.field + " " + e.label + " " + e.proposedName + " " + e.source);
        assertEquals(rows.toString(), 3, lint.size());
        assertTrue(rows.toString(), rows.contains("jButton1 null builder.btn.save text"));
        assertTrue(rows.toString(), rows.contains("jTextField1 Customer: builder.txt.customer label"));
        assertTrue(rows.toString(), rows.contains("null Base URL: builder.txt.baseUrl label"));
    }

    @Test
    public void accessorsReturnIndependentCopies() throws Exception {
        final SnapshotTaker taker = lintOf(onEdt(new Callable<JPanel>() {
            public JPanel call() {
                return new OrdersPanel();
            }
        }));
        taker.autoNamesUsed().clear();
        taker.unnamedInteractive().clear();
        taker.duplicateNames().clear();
        taker.lintEntries().clear();
        assertEquals(1, taker.autoNamesUsed().size());
        assertEquals(1, taker.unnamedInteractive().size());
        assertEquals(1, taker.lintEntries().size());
    }
}
