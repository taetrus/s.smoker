package nametest;

import javax.swing.JButton;
import javax.swing.JPanel;

/** Makes an anonymous application panel: its automatic name uses this enclosing class (WP-09 test). */
public class AnonFactory {
    public AnonFactory() {
    }

    public JPanel create() {
        return new JPanel() {
            private final JButton okButton = new JButton("OK");

            {
                add(okButton);
            }
        };
    }
}
