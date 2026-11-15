package nametest;

import javax.swing.JPanel;
import javax.swing.JPasswordField;

/** Application panel that holds a password field in a field (WP-09 test). */
public class LoginPanel extends JPanel {
    private final JPasswordField secret = new JPasswordField("sk-secret", 10);

    public LoginPanel() {
        add(secret);
    }
}
