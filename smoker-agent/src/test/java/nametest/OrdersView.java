package nametest;

import javax.swing.JButton;
import javax.swing.JPanel;

/** Holds okButton in a field, but the button is a child of a {@link HelperPanel} (WP-09 test). */
public class OrdersView extends JPanel {
    private final JButton okButton = new JButton("OK");

    public OrdersView() {
        HelperPanel helper = new HelperPanel();
        helper.add(okButton);
        add(helper);
    }

    public JButton okButton() {
        return okButton;
    }
}
