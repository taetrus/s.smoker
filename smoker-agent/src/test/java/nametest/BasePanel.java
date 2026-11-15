package nametest;

import javax.swing.JButton;
import javax.swing.JPanel;

/** Declares okButton for its subclasses (WP-09 test). */
public class BasePanel extends JPanel {
    protected final JButton okButton = new JButton("OK");

    public BasePanel() {
        add(okButton);
    }

    public JButton okButton() {
        return okButton;
    }
}
