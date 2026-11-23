package nametest;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;

/**
 * Fields with the names of a GUI builder, labels with {@code setLabelFor}, and a labeled text field in a local
 * variable (WP-09 follow-up test).
 */
public class BuilderPanel extends JPanel {
    private final JButton jButton1 = new JButton("Save");
    private final JTextField jTextField1 = new JTextField(8);

    public BuilderPanel() {
        JLabel customer = new JLabel("Customer:");
        customer.setLabelFor(jTextField1);
        add(customer);
        add(jTextField1);
        add(jButton1);
        JLabel url = new JLabel("Base URL:");
        JTextField local = new JTextField(8);
        url.setLabelFor(local);
        add(url);
        add(local);
    }
}
