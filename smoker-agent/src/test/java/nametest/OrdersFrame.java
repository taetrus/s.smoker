package nametest;

import javax.swing.JButton;
import javax.swing.JFrame;

/** Application window class that holds its button in a field (WP-09 test). */
public class OrdersFrame extends JFrame {
    private final JButton okButton = new JButton("OK");

    public OrdersFrame() {
        super("Orders");
        getContentPane().add(okButton);
    }

    public JButton okButton() {
        return okButton;
    }
}
