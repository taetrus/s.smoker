package nametest;

import javax.swing.JButton;
import javax.swing.JPanel;

/** Application panel that holds its button in a private field (WP-09 test). */
public class OrdersPanel extends JPanel {
    private final JButton saveButton = new JButton("Save");

    public OrdersPanel() {
        add(saveButton);
    }

    public JButton saveButton() {
        return saveButton;
    }
}
