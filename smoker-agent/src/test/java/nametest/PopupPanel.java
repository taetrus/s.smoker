package nametest;

import javax.swing.JButton;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;

/** Application panel with a context menu. The invoker of the popup is a button of the panel (WP-09 test). */
public class PopupPanel extends JPanel {
    private final JButton target = new JButton("Target");
    private final JMenuItem copyItem = new JMenuItem("Copy");

    public PopupPanel() {
        add(target);
        JPopupMenu popup = new JPopupMenu();
        popup.add(copyItem);
        popup.setInvoker(target);
    }

    public JMenuItem copyItem() {
        return copyItem;
    }
}
