package nametest;

import javax.swing.JButton;
import javax.swing.JPanel;

/** Refers to its button only through a static field (WP-09 test). */
public class StaticHolder extends JPanel {
    private static JButton shared;

    public StaticHolder() {
        shared = new JButton("Shared");
        add(shared);
    }

    public JButton shared() {
        return shared;
    }
}
