package nametest;

import javax.swing.JButton;
import javax.swing.JPanel;

/** Two fields refer to the same button: the first in declaration order wins (WP-09 test). */
public class Twice extends JPanel {
    private JButton a;
    private JButton b;

    public Twice() {
        a = new JButton("A");
        b = a;
        add(a);
    }

    public JButton button() {
        return b;
    }
}
