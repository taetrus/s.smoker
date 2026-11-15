package nametest;

import javax.swing.JLabel;
import javax.swing.JPanel;

/** Holds an unnamed layout panel in a field (WP-09 test). */
public class FormHolder extends JPanel {
    private final JPanel form = new JPanel();

    public FormHolder() {
        form.add(new JLabel("Form"));
        add(form);
    }

    public JPanel form() {
        return form;
    }
}
