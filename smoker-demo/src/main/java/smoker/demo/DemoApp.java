package smoker.demo;

import demo.notes.NotesPanel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.IOException;
import java.util.Locale;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * Minimal Swing app for exercising smoker. Every interactive component has a stable name
 * (convention: {@code <view>.<widget>}). Logs to {@code demo.log} (file) so the log tailer has something to read.
 */
public class DemoApp {

    private static final Logger LOG = Logger.getLogger("demo");

    private static final boolean BAD_CELL = Boolean.getBoolean("demo.badCell");
    private static final boolean EXTRA_PANEL = Boolean.getBoolean("demo.extraPanel");

    public static void main(String[] args) throws IOException {
        FileHandler fh = new FileHandler(System.getProperty("demo.log", "demo.log"), true);
        fh.setFormatter(new SimpleFormatter());
        LOG.addHandler(fh);
        LOG.setUseParentHandlers(false);
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                build();
            }
        });
    }

    static void build() {
        final JFrame f = new JFrame("Smoker Demo");
        f.setName("demo.frame");
        f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        JMenuBar mb = new JMenuBar();
        JMenu help = new JMenu("Help");
        help.setName("demo.menu.help");
        JMenuItem about = new JMenuItem("About");
        about.setName("demo.menu.about");
        about.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                JOptionPane.showMessageDialog(f, "Smoker Demo 0.1", "About", JOptionPane.INFORMATION_MESSAGE);
            }
        });
        help.add(about);
        mb.add(help);
        f.setJMenuBar(mb);

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        final JTextField name = new JTextField(12);
        name.setName("demo.name");
        JButton greet = new JButton("Greet");
        greet.setName("demo.greet");
        final JLabel result = new JLabel("…");
        result.setName("demo.result");
        final JComboBox<String> lang = new JComboBox<String>(new String[] {"en", "tr", "de"});
        lang.setName("demo.lang");
        top.add(new JLabel("Name:"));
        top.add(name);
        top.add(lang);
        top.add(greet);
        top.add(result);

        final DefaultTableModel model = new DefaultTableModel(new Object[] {"Name", "Lang"}, 0);
        JTable table = new JTable(model);
        table.setName("demo.table");

        greet.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                String n = name.getText().trim();
                if (n.isEmpty()) {
                    LOG.log(Level.SEVERE, "greet called with empty name");
                    result.setText("name required");
                    return;
                }
                String greeting = "tr".equals(lang.getSelectedItem()) ? "Merhaba " : "de".equals(lang.getSelectedItem()) ? "Hallo " : "Hello ";
                result.setText(greeting + n);
                String shown = String.valueOf(lang.getSelectedItem());
                // -Ddemo.badCell=true: the language in the first row is upper case (test of cell comparison).
                if (BAD_CELL && model.getRowCount() == 0) shown = shown.toUpperCase(Locale.ROOT);
                model.addRow(new Object[] {n, shown});
                LOG.info("greeted " + n);
            }
        });

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton boom = new JButton("Throw on EDT");
        boom.setName("demo.boom");
        boom.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                throw new IllegalStateException("demo exception on EDT");
            }
        });
        JButton hang = new JButton("Hang EDT 8s");
        hang.setName("demo.hang");
        hang.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                try { Thread.sleep(8000); } catch (InterruptedException ignored) {}
            }
        });
        JButton unnamed = new JButton("I have no name");
        bottom.add(boom);
        bottom.add(hang);
        bottom.add(unnamed);

        f.getContentPane().setLayout(new BorderLayout());
        // -Ddemo.extraPanel=true: one more unnamed panel around the top row (test of anchored paths).
        JPanel topRow = top;
        if (EXTRA_PANEL) {
            LOG.info("extra panel");
            topRow = new JPanel(new BorderLayout());
            topRow.add(top, BorderLayout.CENTER);
        }
        f.getContentPane().add(topRow, BorderLayout.NORTH);
        f.getContentPane().add(new JScrollPane(table), BorderLayout.CENTER);
        // The notes row is below the bottom row: the paths of the named components stay as they are.
        JPanel south = new JPanel(new BorderLayout());
        south.add(bottom, BorderLayout.CENTER);
        south.add(new NotesPanel(), BorderLayout.SOUTH);
        f.getContentPane().add(south, BorderLayout.SOUTH);
        f.setSize(640, 400);
        f.setLocation(100, 100);
        f.setVisible(true);
        LOG.info("demo started");
    }
}
