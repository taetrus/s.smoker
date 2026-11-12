package smoker.agent;

import smoker.core.model.Locator;
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
import javax.swing.JToggleButton;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.border.Border;
import java.awt.AWTEvent;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.FlowLayout;
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
    private final JLabel pickerLabel = new JLabel(" ");
    private final JToggleButton rec = new JToggleButton("● Record");
    private final JToggleButton pick = new JToggleButton("Pick");

    private JComponent highlighted;
    private Border highlightedBorder;
    private final AWTEventListener pickerListener = new AWTEventListener() {
        @Override
        public void eventDispatched(AWTEvent e) {
            if (e.getID() == MouseEvent.MOUSE_MOVED && e.getSource() instanceof Component) onHover((Component) e.getSource(), (MouseEvent) e);
        }
    };

    RecorderToolbar(Recorder recorder) {
        super("s.smoker recorder — " + recorder.scenario().name);
        this.recorder = recorder;
        setName("smoker.toolbar");
        setAlwaysOnTop(true);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setFocusableWindowState(true);

        for (Step s : recorder.scenario().steps) model.addElement(s);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        buttons.add(rec);
        buttons.add(pick);
        JButton rename = new JButton("Rename");
        JButton delete = new JButton("Delete");
        JButton save = new JButton("Save");
        buttons.add(rename);
        buttons.add(delete);
        buttons.add(save);

        rec.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (rec.isSelected()) { RecorderToolbar.this.recorder.start(); rec.setText("❚❚ Pause"); }
                else { RecorderToolbar.this.recorder.pause(); rec.setText("● Record"); }
            }
        });
        pick.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (pick.isSelected()) Toolkit.getDefaultToolkit().addAWTEventListener(pickerListener, AWTEvent.MOUSE_MOTION_EVENT_MASK);
                else { Toolkit.getDefaultToolkit().removeAWTEventListener(pickerListener); unhighlight(); pickerLabel.setText(" "); }
            }
        });
        rename.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                renameSelected();
            }
        });
        delete.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                Step s = list.getSelectedValue();
                if (s != null) { RecorderToolbar.this.recorder.remove(s); model.removeElement(s); }
            }
        });
        save.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                try {
                    RecorderToolbar.this.recorder.save();
                } catch (Exception ex) {
                    status("save failed: " + ex);
                }
            }
        });
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel, boolean foc) {
                Step s = (Step) v;
                String txt = s.id + "  " + s.name + (s.expected == null ? "  (snapshot pending…)" : "");
                return super.getListCellRendererComponent(l, txt, i, sel, foc);
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
        cp.add(new JScrollPane(list), BorderLayout.CENTER);
        cp.add(south, BorderLayout.SOUTH);
        setSize(520, 300);
        setLocation(20, 20);
        recorder.setListener(this);
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

    private void onHover(Component c, MouseEvent e) {
        if (Recorder.isSmokerUi(c)) return;
        Component deepest = SwingUtilities.getDeepestComponentAt(c, e.getX(), e.getY());
        if (deepest == null) deepest = c;
        if (deepest == highlighted) return;
        unhighlight();
        if (deepest instanceof JComponent) {
            highlighted = (JComponent) deepest;
            highlightedBorder = highlighted.getBorder();
            highlighted.setBorder(BorderFactory.createLineBorder(Color.RED, 2));
        }
        Locator l = Locators.resolve(deepest);
        pickerLabel.setText((l.isNamed() ? "" : "⚠ unnamed  ") + l + "   [" + deepest.getClass().getSimpleName() + "]");
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
                model.addElement(s);
                list.ensureIndexIsVisible(model.size() - 1);
            }
        });
    }

    @Override
    public void stepUpdated(Step s) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                list.repaint();
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
