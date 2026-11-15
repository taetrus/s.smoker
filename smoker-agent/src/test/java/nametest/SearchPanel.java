package nametest;

import javax.swing.JPanel;
import javax.swing.JTextField;

/** Application panel that holds its text field in a private field (WP-09 test). */
public class SearchPanel extends JPanel {
    private final JTextField queryField = new JTextField(10);

    public SearchPanel() {
        add(queryField);
    }

    public JTextField queryField() {
        return queryField;
    }
}
