package nametest;

import javax.swing.JButton;
import javax.swing.JPanel;

/** Application panel with local buttons that no field refers to (WP-09 test). */
public class SettingsDialogPanel extends JPanel {
    public SettingsDialogPanel(String... texts) {
        for (String t : texts) add(new JButton(t));
    }
}
