package nametest;

import javax.swing.JPanel;
import javax.swing.JSpinner;

/** Holds only the spinner in a field; the editor text field of the spinner has no application field (WP-09 test). */
public class SpinnerPanel extends JPanel {
    private final JSpinner spinner = new JSpinner();

    public SpinnerPanel() {
        add(spinner);
    }

    public JSpinner spinner() {
        return spinner;
    }
}
