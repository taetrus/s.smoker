package smoker.agent;

import smoker.core.diff.SnapshotDiff;
import smoker.core.model.Action;
import smoker.core.model.IgnoreRules;
import smoker.core.model.Locator;
import smoker.core.model.Snapshot;
import smoker.core.model.Step;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JToggleButton;
import javax.swing.JViewport;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.border.Border;
import java.awt.AWTEvent;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Floating always-on-top recorder window (R-20, R-26): Record/Pause, step list with rename/delete,
 * Save, and a component picker that highlights the hovered component and shows its locator.
 * Everything smoker-owned is named {@code smoker.*} so it is excluded from snapshots and recording.
 */
final class RecorderToolbar extends JFrame implements Recorder.Listener {

    private final Recorder recorder;
    private final DefaultListModel<Step> model = new DefaultListModel<Step>();
    private final JList<Step> list = new JList<Step>(model);
    private final JLabel status = new JLabel(" ");
    private final String baseTitle;
    private final JLabel pickerLabel = new JLabel(" ");
    private final JToggleButton rec = new JToggleButton("● Record");
    private final JToggleButton pick = new JToggleButton("Pick");
    private final WaitForPicker waitFor;
    private final JTextArea preview = new JTextArea();
    private final JScrollPane previewScroll = new JScrollPane(preview);
    private final JButton up = new JButton("Up");
    private final JButton down = new JButton("Down");

    private JComponent highlighted;
    private Border highlightedBorder;
    /** The last logged text of each row of the step list. EDT only. */
    private final java.util.Map<Step, String> loggedRows = new java.util.IdentityHashMap<Step, String>();
    private final AWTEventListener pickerListener = new AWTEventListener() {
        @Override
        public void eventDispatched(AWTEvent e) {
            if (e.getID() == MouseEvent.MOUSE_MOVED && e.getSource() instanceof Component) onHover((Component) e.getSource(), (MouseEvent) e);
        }
    };

    RecorderToolbar(Recorder recorder) {
        super("s.smoker recorder — " + recorder.scenario().name);
        this.recorder = recorder;
        this.baseTitle = getTitle();
        setName("smoker.toolbar");
        setAlwaysOnTop(true);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setFocusableWindowState(true);
        waitFor = new WaitForPicker(this, recorder);

        for (Step s : recorder.scenario().steps) model.addElement(s);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        buttons.add(rec);
        buttons.add(pick);
        JButton waitForButton = new JButton("Wait for…");
        buttons.add(waitForButton);
        JButton rename = new JButton("Rename");
        JButton group = new JButton("Group...");
        group.setName("smoker.toolbar.group");
        up.setName("smoker.toolbar.up");
        down.setName("smoker.toolbar.down");
        JButton delete = new JButton("Delete");
        JButton save = new JButton("Save");
        buttons.add(rename);
        buttons.add(group);
        buttons.add(up);
        buttons.add(down);
        buttons.add(delete);
        buttons.add(save);

        rec.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (rec.isSelected()) RecorderToolbar.this.recorder.start();
                else RecorderToolbar.this.recorder.pause();
                showRecordState();
            }
        });
        pick.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (pick.isSelected()) Toolkit.getDefaultToolkit().addAWTEventListener(pickerListener, AWTEvent.MOUSE_MOTION_EVENT_MASK);
                else { Toolkit.getDefaultToolkit().removeAWTEventListener(pickerListener); unhover(); }
            }
        });
        waitForButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                waitFor.toggle();
            }
        });
        rename.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                renameSelected();
            }
        });
        group.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                groupSelected();
            }
        });
        up.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                moveSelected(-1);
            }
        });
        down.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                moveSelected(+1);
            }
        });
        delete.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                Step s = list.getSelectedValue();
                if (s != null) {
                    RecorderToolbar.this.recorder.remove(s);
                    model.removeElement(s);
                    loggedRows.remove(s);
                    refreshPreview();
                    SmokerAgent.log("step list: deleted " + s.id);
                    logChangedRows();
                }
            }
        });
        save.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                try {
                    RecorderToolbar.this.recorder.save();
                } catch (Exception ex) {
                    showStatus("save failed: " + ex);
                }
            }
        });
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new ListCellRenderer<Step>() {
            private final javax.swing.DefaultListCellRenderer row = new javax.swing.DefaultListCellRenderer();
            private final JLabel header = new JLabel();
            private final JPanel cell = new JPanel(new BorderLayout());

            @Override
            public Component getListCellRendererComponent(JList<? extends Step> l, Step v, int i, boolean sel, boolean foc) {
                Component r = row.getListCellRendererComponent(l, listText(i), i, sel, foc);
                String g = headerOf(i);
                if (g == null) return r;
                // A header is part of the cell of its step, not a row of the model (spec 7.2).
                header.setText("-- " + g + " --");
                header.setFont(l.getFont().deriveFont(java.awt.Font.BOLD));
                header.setForeground(new Color(0x1a, 0x5f, 0xb4));
                header.setOpaque(true);
                header.setBackground(l.getBackground());
                cell.removeAll();
                cell.add(header, BorderLayout.NORTH);
                cell.add(r, BorderLayout.CENTER);
                return cell;
            }
        });
        list.addListSelectionListener(new javax.swing.event.ListSelectionListener() {
            @Override
            public void valueChanged(javax.swing.event.ListSelectionEvent e) {
                if (e.getValueIsAdjusting()) return;
                refreshPreview();
            }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) renameSelected();
            }
        });

        JPanel south = new JPanel(new BorderLayout());
        pickerLabel.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        pickerLabel.setForeground(new Color(0x1a, 0x5f, 0xb4));
        status.setBorder(BorderFactory.createEmptyBorder(2, 6, 4, 6));
        south.add(pickerLabel, BorderLayout.NORTH);
        south.add(status, BorderLayout.SOUTH);

        Container cp = getContentPane();
        cp.add(buttons, BorderLayout.NORTH);
        preview.setEditable(false);
        preview.setName("smoker.toolbar.preview");
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(list), previewScroll);
        split.setResizeWeight(0.5);
        cp.add(split, BorderLayout.CENTER);
        cp.add(south, BorderLayout.SOUTH);
        setSize(980, 300);
        split.setDividerLocation(480);
        setLocation(20, 20);
        recorder.setListener(this);
        showRecordState();
        refreshPreview();
        if (!recorder.isRecording()) showStatus("paused: click Record to record the clicks");
    }

    /**
     * Shows in the title and on the toggle whether the recorder records. A click on Record that did not arrive left a
     * record session without steps (WP-09 Windows check, 2026-11-23). Must run on the EDT.
     */
    private void showRecordState() {
        boolean on = recorder.isRecording();
        rec.setSelected(on);
        rec.setText(on ? "❚❚ Pause" : "● Record");
        setTitle(baseTitle + (on ? " — RECORDING" : " — paused"));
    }

    /** The Record/Pause toggle, for tests. */
    JToggleButton recordToggle() {
        return rec;
    }

    private void renameSelected() {
        Step s = list.getSelectedValue();
        if (s == null) return;
        JOptionPane pane = new JOptionPane("Step name", JOptionPane.PLAIN_MESSAGE, JOptionPane.OK_CANCEL_OPTION);
        pane.setWantsInput(true);
        pane.setInitialSelectionValue(s.name);
        javax.swing.JDialog d = pane.createDialog(this, "Rename step");
        d.setName("smoker.rename"); // keep smoker's own dialogs out of snapshots and recordings
        d.setVisible(true);
        d.dispose();
        Object v = pane.getInputValue();
        String n = v == JOptionPane.UNINITIALIZED_VALUE || v == null ? null : v.toString();
        if (n != null && !n.trim().isEmpty()) {
            s.name = n.trim();
            list.repaint();
        }
    }

    /**
     * The list text after a step name. A step before a WAIT_FOR never gets a snapshot (WP-03). A move removes a golden
     * (spec 7.3); the move wins over pending.
     */
    static String snapshotSuffix(boolean hasSnapshot, boolean waitForFollows, boolean removedByMove) {
        if (hasSnapshot) return "";
        if (waitForFollows) return "  (no snapshot: WAIT_FOR follows)";
        return removedByMove ? "  (no golden: run and approve)" : "  (snapshot pending...)";
    }

    private String suffix(int i) {
        Step s = model.get(i);
        int next = i + 1;
        boolean waitForFollows = next < model.size() && model.get(next).action.type == Action.Type.WAIT_FOR;
        return snapshotSuffix(recorder.hasSnapshot(s), waitForFollows, recorder.goldenRemoved(s));
    }

    /** The text of row {@code i} of the step list. Must run on the EDT. */
    String listText(int i) {
        Step s = model.get(i);
        return s.id + "  " + s.name + suffix(i);
    }

    /** The group header above row {@code i}, or null. Must run on the EDT. */
    String headerOf(int i) {
        return model.get(i).group;
    }

    int modelSize() {
        return model.size();
    }

    int selectedIndex() {
        return list.getSelectedIndex();
    }

    void selectStep(int index) {
        list.setSelectedIndex(index);
        refreshPreview();
    }

    String previewText() {
        return preview.getText();
    }

    /**
     * The lines of the preview. {@code current} is the golden of the step, {@code previous} the golden before it.
     * {@code noGoldenReason} is shown when the step has no golden.
     */
    static List<String> previewLines(Snapshot previous, Snapshot current, IgnoreRules rules, String noGoldenReason) {
        List<String> out = new ArrayList<String>();
        if (current == null) {
            out.add(noGoldenReason);
        } else if (previous == null) {
            out.add("first golden: no earlier step to compare");
        } else {
            out.addAll(SnapshotDiff.diff(previous, current, rules));
            if (out.isEmpty()) out.add("no changes");
        }
        return out;
    }

    /** Shows the changes of the selected step and sets the Up and Down buttons. Must run on the EDT. */
    private void refreshPreview() {
        int i = list.getSelectedIndex();
        up.setEnabled(i > 0);
        down.setEnabled(i >= 0 && i < model.size() - 1);
        if (i < 0 || i >= model.size()) {
            preview.setText("");
            previewScroll.setBorder(BorderFactory.createTitledBorder("changes"));
            return;
        }
        Step s = model.get(i);
        String reason = suffix(i).replaceAll("^\\s*\\(|\\)$", "");
        List<String> lines = previewLines(recorder.previousSnapshot(s), recorder.snapshot(s), recorder.scenario().ignore, reason);
        StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append(sb.length() == 0 ? "" : "\n").append(l);
        String text = sb.toString();
        if (!text.equals(preview.getText())) preview.setText(text);
        preview.setCaretPosition(0);
        previewScroll.setBorder(BorderFactory.createTitledBorder("changes of " + s.id));
    }

    /** Moves the selected step one row and keeps it selected. Must run on the EDT. */
    void moveSelected(int delta) {
        int i = list.getSelectedIndex();
        if (i < 0) return;
        int j = i + delta;
        if (j < 0 || j >= model.size()) return;
        Step s = model.get(i);
        recorder.move(s, delta);
        model.remove(i);
        model.add(j, s);
        list.setSelectedIndex(j);
        list.ensureIndexIsVisible(j);
        SmokerAgent.log("step list: moved " + s.id + (delta < 0 ? " up" : " down"));
        logChangedRows();
        refreshPreview();
    }

    /** Sets the group of the selected step; an empty text removes it. Must run on the EDT. */
    void setGroupOfSelected(String group) {
        Step s = list.getSelectedValue();
        if (s == null) return;
        recorder.setGroup(s, group);
        list.repaint();
    }

    private void groupSelected() {
        Step s = list.getSelectedValue();
        if (s == null) return;
        JOptionPane pane = new JOptionPane("Group name (empty: no group)", JOptionPane.PLAIN_MESSAGE, JOptionPane.OK_CANCEL_OPTION);
        pane.setWantsInput(true);
        pane.setInitialSelectionValue(s.group == null ? "" : s.group);
        javax.swing.JDialog d = pane.createDialog(this, "Group of step");
        d.setName("smoker.group"); // keep smoker's own dialogs out of snapshots and recordings
        d.setVisible(true);
        d.dispose();
        Object v = pane.getInputValue();
        Object choice = pane.getValue();
        if (v == JOptionPane.UNINITIALIZED_VALUE || v == null || !Integer.valueOf(JOptionPane.OK_OPTION).equals(choice)) return;
        setGroupOfSelected(v.toString());
    }

    /**
     * Writes each row of the step list whose text changed to the agent log. The person who records cannot always
     * remember the list; the record log shows it. Must run on the EDT.
     */
    private void logChangedRows() {
        for (int i = 0; i < model.size(); i++) {
            String text = listText(i);
            if (!text.equals(loggedRows.put(model.get(i), text))) SmokerAgent.log("step list: " + text);
        }
    }

    /** Enables or disables the Record toggle, and writes a change to the agent log. Must run on the EDT. */
    void setRecEnabled(boolean enabled) {
        if (rec.isEnabled() != enabled) SmokerAgent.log("Record button " + (enabled ? "enabled" : "disabled"));
        rec.setEnabled(enabled);
    }

    /**
     * Shows {@code text} in the status line and writes it to the agent log. For messages of the toolbar itself:
     * the recorder writes its own messages to the log before it calls {@link #status}. Callable from any thread.
     */
    void showStatus(String text) {
        SmokerAgent.log("status: " + text);
        status(text);
    }

    boolean recEnabled() {
        return rec.isEnabled();
    }

    WaitForPicker waitForPicker() {
        return waitFor;
    }

    /** The text of the picker line. Must run on the EDT. */
    String pickerLabelText() {
        return pickerLabel.getText();
    }

    /** The text of the status line. Must run on the EDT. */
    String statusText() {
        return status.getText();
    }

    private void onHover(Component c, MouseEvent e) {
        // In select mode the glass pane gets the mouse: the picker of "Wait for…" calls hover itself.
        if (Recorder.isSmokerUi(c) || waitFor.selectModeOn()) return;
        Component deepest = SwingUtilities.getDeepestComponentAt(c, e.getX(), e.getY());
        hover(deepest == null ? c : deepest);
    }

    /**
     * Highlights {@code deepest} and shows its locator. Must run on the EDT.
     * A {@link JViewport} refuses a border ({@code setBorder} throws): it gets the locator text but no highlight.
     */
    void hover(Component deepest) {
        if (deepest == highlighted) return;
        unhighlight();
        if (deepest instanceof JComponent && !(deepest instanceof JViewport)) {
            highlighted = (JComponent) deepest;
            highlightedBorder = highlighted.getBorder();
            highlighted.setBorder(BorderFactory.createLineBorder(Color.RED, 2));
        }
        pickerLabel.setText(pickerText(Locators.resolve(deepest), deepest.getClass().getSimpleName()));
    }

    /** The picker text of a locator. An automatic name still needs a real name (WP-09, spec 5.2). */
    static String pickerText(Locator l, String simpleClassName) {
        String auto = l.isNamed() && l.name.startsWith("@") ? " (automatic name - needs a real name)" : "";
        return (l.isNamed() ? "" : "⚠ unnamed  ") + l + auto + "   [" + simpleClassName + "]";
    }

    /** Removes the highlight and the locator text. Must run on the EDT. */
    void unhover() {
        unhighlight();
        pickerLabel.setText(" ");
    }

    /** Null when the locator has a name (real or unique automatic); else the reason for the status line (spec 6.5). */
    static String refusal(Locator l) {
        return l.isNamed() ? null : "needs a name";
    }

    /** A regex that matches exactly {@code value}: a backslash before each regex metacharacter. Null gives {@code ""}. */
    static String literalRegex(String value) {
        if (value == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if ("\\.[]{}()*+?^$|".indexOf(ch) >= 0) sb.append('\\');
            sb.append(ch);
        }
        return sb.toString();
    }

    private void unhighlight() {
        if (highlighted != null) {
            highlighted.setBorder(highlightedBorder);
            highlighted = null;
        }
    }

    // ---- Recorder.Listener (called from any thread) ----

    @Override
    public void stepAdded(final Step s) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                // A SET_CELL can come before the last step (spec 6.4): the row goes to the place of the step.
                int i = recorder.scenario().steps.indexOf(s);
                if (i < 0 || i > model.size()) i = model.size();
                model.add(i, s);
                list.ensureIndexIsVisible(i);
                logChangedRows();
                refreshPreview();
            }
        });
    }

    @Override
    public void stepUpdated(Step s) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                list.repaint();
                logChangedRows();
                refreshPreview();
            }
        });
    }

    @Override
    public void status(final String text) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                status.setText(text);
            }
        });
    }
}
