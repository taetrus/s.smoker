package nametest;

import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JSpinner;

/** A spinner and a slider in fields, without names (WP-09 test: both need a real name). */
public class QuantityPanel extends JPanel {
    private final JSpinner amountSpinner = new JSpinner();
    private final JSlider volumeSlider = new JSlider();

    public QuantityPanel() {
        add(amountSpinner);
        add(volumeSlider);
    }
}
