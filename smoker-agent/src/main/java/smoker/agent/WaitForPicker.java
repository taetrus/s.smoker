package smoker.agent;

import smoker.core.model.Action;
import smoker.core.model.Locator;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.RootPaneContainer;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The select mode and the dialog of the toolbar button {@code Wait for…} (WP-03, F-02, spec 6.5).
 * In select mode a transparent glass pane covers each application window: the click that selects a component does not
 * reach the application, and the recorder pauses. Only a component with a name (real or unique automatic) is accepted.
 * All methods run on the EDT.
 */
final class WaitForPicker {

    private static final String CANCEL = "smoker.waitfor.cancel";

    private final RecorderToolbar toolbar;
    private final Recorder recorder;
    /** Called with the accepted locator after select mode ends. Tests replace it. */
    Consumer<Locator> opener = new Consumer<Locator>() {
        @Override
        public void accept(Locator l) {
            showDialog(l);
        }
    };

    private final List<Covered> covered = new ArrayList<Covered>();
    private boolean on;
    private boolean wasRecording;

    /** An application window under a select-mode glass pane, with its own glass pane to restore. */
    private static final class Covered {
        final RootPaneContainer window;
        final Component oldGlass;
        final boolean oldVisible;

        Covered(RootPaneContainer window) {
            this.window = window;
            this.oldGlass = window.getGlassPane();
            this.oldVisible = oldGlass.isVisible();
        }
    }

    WaitForPicker(RecorderToolbar toolbar, Recorder recorder) {
        this.toolbar = toolbar;
        this.recorder = recorder;
        // After a click on the button the toolbar has the focus: ESC must cancel there too.
        bindEscape(toolbar.getRootPane());
    }

    boolean selectModeOn() {
        return on;
    }

    /** The button action: turns select mode on, or cancels it. */
    void toggle() {
        if (on) cancel();
        else start();
    }

    private void start() {
        on = true;
        wasRecording = recorder.isRecording();
        if (wasRecording) recorder.pause();
        for (Window w : Window.getWindows()) {
            if (!w.isShowing() || !(w instanceof RootPaneContainer) || Recorder.isSmokerUi(w)) continue;
            RootPaneContainer rpc = (RootPaneContainer) w;
            covered.add(new Covered(rpc));
            rpc.setGlassPane(glassPane(rpc));
            rpc.getGlassPane().setVisible(true);
        }
        toolbar.status("Wait for: click a named component (ESC cancels)");
    }

    void cancel() {
        stop();
        toolbar.status("Wait for: cancelled");
    }

    private void stop() {
        on = false;
        for (Covered c : covered) {
            c.window.setGlassPane(c.oldGlass);
            c.oldGlass.setVisible(c.oldVisible);
        }
        covered.clear();
        toolbar.unhover();
        if (wasRecording) recorder.start();
    }

    private JComponent glassPane(final RootPaneContainer rpc) {
        final JPanel glass = new JPanel();
        glass.setOpaque(false);
        MouseAdapter m = new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                Component c = componentAt(rpc, glass, e.getPoint());
                if (c != null) toolbar.hover(c);
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getButton() != MouseEvent.BUTTON1) return;
                Component c = componentAt(rpc, glass, e.getPoint());
                if (c != null) select(c);
            }
        };
        glass.addMouseListener(m);
        glass.addMouseMotionListener(m);
        bindEscape(glass);
        return glass;
    }

    private static Component componentAt(RootPaneContainer rpc, Component glass, Point p) {
        Point q = SwingUtilities.convertPoint(glass, p, rpc.getContentPane());
        return SwingUtilities.getDeepestComponentAt(rpc.getContentPane(), q.x, q.y);
    }

    private void bindEscape(JComponent c) {
        c.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), CANCEL);
        c.getActionMap().put(CANCEL, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (on) cancel();
            }
        });
    }

    private void select(Component c) {
        Locator l = Locators.resolve(c);
        String refused = RecorderToolbar.refusal(l);
        if (refused != null) {
            String proposal = proposalFor(c);
            toolbar.status(refused + ": " + (proposal != null ? proposal : l + " (give it a name with setName)"));
            return;
        }
        stop();
        opener.accept(l);
    }

    /** The name that {@link NameProposer} proposes for {@code c}, as in the lint entry of the snapshot (WP-09). */
    static String proposalFor(Component c) {
        AutoNames.Hit hit = AutoNames.of(c);
        String viewClass;
        if (hit != null) {
            viewClass = hit.name.substring(1, hit.name.length() - hit.field.length() - 1);
        } else {
            Class<?> app = SnapshotTaker.applicationAncestor(c);
            if (app == null) return null;
            viewClass = AutoNames.simpleName(app);
        }
        return NameProposer.propose(viewClass, c, hit == null ? null : hit.field, Locators.textOf(c));
    }

    // ---- dialog ------------------------------------------------------------------------------

    /** The dialog {@code smoker.waitfor}: attribute, regex and time limit of the WAIT_FOR step. */
    private void showDialog(final Locator l) {
        final JDialog d = new JDialog(toolbar, "Wait for", true);
        d.setName("smoker.waitfor");
        final JComboBox<String> attribute = new JComboBox<String>(WaitCondition.ATTRIBUTES.toArray(new String[0]));
        attribute.setSelectedItem("text");
        final JTextField regex = new JTextField(currentValueRegex(l, "text"), 24);
        final JTextField timeout = new JTextField(String.valueOf(Action.DEFAULT_WAIT_TIMEOUT_MS), 8);
        final JLabel error = new JLabel(" ");
        error.setForeground(Color.RED.darker());
        attribute.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                regex.setText(currentValueRegex(l, (String) attribute.getSelectedItem()));
            }
        });

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));
        row(form, 0, "Component", new JLabel(l.toString()));
        row(form, 1, "Attribute", attribute);
        row(form, 2, "Matches (regex)", regex);
        row(form, 3, "Time limit (ms)", timeout);

        JButton ok = new JButton("OK");
        JButton cancel = new JButton("Cancel");
        ok.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                try {
                    Action a = Action.waitFor(l, (String) attribute.getSelectedItem(), regex.getText(),
                            timeoutOf(timeout.getText()));
                    WaitCondition.validate(a);
                    recorder.addWaitFor(a);
                    d.dispose();
                } catch (IllegalArgumentException ex) {
                    error.setText(ex.getMessage());
                    d.pack();
                }
            }
        });
        cancel.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                d.dispose();
            }
        });
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(ok);
        buttons.add(cancel);
        JPanel south = new JPanel(new BorderLayout());
        error.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
        south.add(error, BorderLayout.NORTH);
        south.add(buttons, BorderLayout.SOUTH);

        d.getContentPane().add(form, BorderLayout.CENTER);
        d.getContentPane().add(south, BorderLayout.SOUTH);
        d.getRootPane().setDefaultButton(ok);
        d.getRootPane().registerKeyboardAction(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                d.dispose();
            }
        }, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        d.pack();
        d.setLocationRelativeTo(toolbar);
        d.setVisible(true);   // modal: returns after OK or Cancel
    }

    private static void row(JPanel form, int y, String label, Component field) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = y;
        c.insets = new Insets(2, 2, 2, 6);
        c.anchor = GridBagConstraints.WEST;
        form.add(new JLabel(label), c);
        c.gridx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        form.add(field, c);
    }

    /** A regex that matches the current value at once. A missing component or value gives {@code ""}. */
    private static String currentValueRegex(Locator l, String attribute) {
        String v = WaitCondition.read(l, attribute);
        return WaitCondition.NOT_FOUND.equals(v) ? "" : RecorderToolbar.literalRegex(v);
    }

    /** The time limit in ms; empty or the default gives null (the scenario then omits it). */
    static Long timeoutOf(String text) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) return null;
        long ms;
        try {
            ms = Long.parseLong(t);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("time limit \"" + t + "\" is not a number of ms");
        }
        if (ms <= 0) throw new IllegalArgumentException("time limit must be more than 0 ms");
        return ms == Action.DEFAULT_WAIT_TIMEOUT_MS ? null : ms;
    }
}
