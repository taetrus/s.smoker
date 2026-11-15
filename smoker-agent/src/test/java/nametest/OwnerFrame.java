package nametest;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;

/** Holds a button of its dialog in a field. The owner of a window is not an ancestor (WP-09 test). */
public class OwnerFrame extends JFrame {
    private final JDialog dialog;
    private final JButton dialogButton = new JButton("Close");

    public OwnerFrame() {
        super("Owner");
        dialog = new JDialog(this, "Details");
        dialog.getContentPane().add(dialogButton);
    }

    public JDialog dialog() {
        return dialog;
    }

    public JButton dialogButton() {
        return dialogButton;
    }
}
