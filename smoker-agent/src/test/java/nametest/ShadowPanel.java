package nametest;

import javax.swing.JButton;

/** Its own field and the inherited okButton refer to the same button: the subclass field wins (WP-09 test). */
public class ShadowPanel extends BasePanel {
    private final JButton primary;

    public ShadowPanel() {
        primary = okButton;
    }

    public JButton primary() {
        return primary;
    }
}
