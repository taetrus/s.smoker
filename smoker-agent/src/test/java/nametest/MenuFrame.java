package nametest;

import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;

/** Application window class that holds a menu item in a field. The menu stays closed (WP-09 test). */
public class MenuFrame extends JFrame {
    private final JMenu fileMenu = new JMenu("File");
    private final JMenuItem exitItem = new JMenuItem("Exit");

    public MenuFrame() {
        super("Menu");
        fileMenu.add(exitItem);
        JMenuBar bar = new JMenuBar();
        bar.add(fileMenu);
        setJMenuBar(bar);
    }

    public JMenuItem exitItem() {
        return exitItem;
    }
}
