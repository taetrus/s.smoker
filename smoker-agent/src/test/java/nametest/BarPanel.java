package nametest;

import javax.swing.JButton;
import javax.swing.JPanel;

/** Reusable panel: one instance in the main frame, one in a dialog (WP-09 test). */
public class BarPanel extends JPanel {
    private final JButton okButton = new JButton("OK");

    public BarPanel() {
        add(okButton);
    }

    public JButton okButton() {
        return okButton;
    }
}
