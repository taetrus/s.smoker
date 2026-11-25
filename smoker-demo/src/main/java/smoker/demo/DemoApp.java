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
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTree;
import javax.swing.SpinnerNumberModel;
import javax.swing.event.TableModelEvent;
import javax.swing.event.TableModelListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Minimal Swing app for exercising smoker. Every interactive component has a stable name
 * (convention: {@code <view>.<widget>}). Logs to {@code demo.log} (file) so the log tailer has something to read.
 */
public class DemoApp {

    private static final Logger LOG = Logger.getLogger("demo");

    private static final boolean BAD_CELL = Boolean.getBoolean("demo.badCell");
    private static final boolean EXTRA_PANEL = Boolean.getBoolean("demo.extraPanel");
    // -Ddemo.logPanel=true: the "Log" panel with the buttons of the log scenarios (off: the other goldens stay as they are).
    private static final boolean LOG_PANEL = Boolean.getBoolean("demo.logPanel");
    // -Ddemo.recorderPanel=true: a tree, an editable table and a spinner for the recorder scenarios (WP-06, R-20a).
    private static final boolean RECORDER_PANEL = Boolean.getBoolean("demo.recorderPanel");
    // -Ddemo.localePanel=true: a label with a fixed instant and a fixed number in the default locale and time zone (WP-07, R-34).
    private static final boolean LOCALE_PANEL = Boolean.getBoolean("demo.localePanel");
    // -Ddemo.equinoxError=true: the application prints an Equinox start error to System.out before the UI (F-22).
    private static final boolean EQUINOX_ERROR = Boolean.getBoolean("demo.equinoxError");

    // -Ddemo.lockFile=<file>: created at start, deleted when the main frame closes (test of the clean stop, WP-05).
    private static final String LOCK_FILE = System.getProperty("demo.lockFile");
    // -Ddemo.ignoreClose=true: the main frame ignores WINDOW_CLOSING and deletes no lock file.
    private static final boolean IGNORE_CLOSE = Boolean.getBoolean("demo.ignoreClose");

    // -Ddemo.simPort=<port>: a row with the button demo.sim.count (sends INC to the simulator) and the label demo.sim.value (WP-05, R-37).
    private static final String SIM_PORT = System.getProperty("demo.simPort");

    public static void main(String[] args) throws IOException {
        FileHandler fh = new FileHandler(System.getProperty("demo.log", "demo.log"), true);
        fh.setFormatter(new PilotFormatter());
        LOG.addHandler(fh);
        LOG.setUseParentHandlers(false);
        if (EQUINOX_ERROR) printEquinoxError();
        if (LOCK_FILE != null) java.nio.file.Files.write(java.nio.file.Paths.get(LOCK_FILE), new byte[0]);
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                build();
            }
        });
    }

    private static void printEquinoxError() throws IOException {
        InputStream in = DemoApp.class.getResourceAsStream("/equinox-error.txt");
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "US-ASCII"));
        try {
            String line;
            while ((line = r.readLine()) != null) System.out.print(line + "\n");
        } finally {
            r.close();
        }
        System.out.flush();
    }

    /** The label of -Ddemo.localePanel=true: the text depends on the default locale and the default time zone. */
    private static JLabel localeLabel() {
        String text = new SimpleDateFormat("d MMMM yyyy HH:mm").format(new Date(14L * 3600 * 1000))
                + "  " + String.format("%,d", 1234567);
        JLabel l = new JLabel(text);
        l.setName("demo.localeText");
        return l;
    }

    /** The panel of -Ddemo.recorderPanel=true: tree with popup, table with popup, spinner (spec 9). */
    private static JPanel recorderPanel() {
        JPanel p = new JPanel(new BorderLayout());
        p.setName("demo.recorderPanel");
        final JLabel picked = new JLabel("-");
        picked.setName("demo.picked");

        DefaultMutableTreeNode root = new DefaultMutableTreeNode("Root");
        DefaultMutableTreeNode src = new DefaultMutableTreeNode("src");
        src.add(new DefaultMutableTreeNode("Main.java"));
        src.add(new DefaultMutableTreeNode("Util.java"));
        root.add(src);
        root.add(new DefaultMutableTreeNode("docs"));
        final JTree tree = new JTree(root);
        tree.setName("demo.tree");
        tree.setVisibleRowCount(4);
        final JPopupMenu treeMenu = new JPopupMenu();
        treeMenu.setName("demo.tree.menu");
        JMenuItem open = new JMenuItem("Open");
        open.setName("demo.tree.open");
        open.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                TreePath sel = tree.getSelectionPath();
                if (sel != null) picked.setText("menu " + sel.getLastPathComponent());
            }
        });
        treeMenu.add(open);
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                popup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                popup(e);
            }

            private void popup(MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                TreePath path = tree.getPathForLocation(e.getX(), e.getY());
                if (path != null) tree.setSelectionPath(path);
                treeMenu.show(tree, e.getX(), e.getY());
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() != 2 || e.getButton() != MouseEvent.BUTTON1) return;
                TreePath path = tree.getPathForLocation(e.getX(), e.getY());
                if (path == null) return;
                DefaultMutableTreeNode n = (DefaultMutableTreeNode) path.getLastPathComponent();
                if (n.isLeaf()) picked.setText("opened " + n);
            }
        });

        final DefaultTableModel items = new DefaultTableModel(new Object[][] {{"bolt", 2, false}, {"nut", 3, false}},
                new Object[] {"Name", "Qty", "Done"}) {
            @Override
            public Class<?> getColumnClass(int c) {
                return c == 1 ? Integer.class : c == 2 ? Boolean.class : String.class;
            }

            @Override
            public boolean isCellEditable(int r, int c) {
                return c != 0;
            }
        };
        JTable table = new JTable(items);
        table.setName("demo.items");
        final JLabel total = new JLabel("total 5");
        total.setName("demo.total");
        items.addTableModelListener(new TableModelListener() {
            @Override
            public void tableChanged(TableModelEvent e) {
                int sum = 0;
                for (int r = 0; r < items.getRowCount(); r++) sum += ((Integer) items.getValueAt(r, 1)).intValue();
                total.setText("total " + sum);
            }
        });
        JPopupMenu itemsMenu = new JPopupMenu();
        itemsMenu.setName("demo.items.menu");
        JMenuItem clear = new JMenuItem("Clear quantities");
        clear.setName("demo.items.clear");
        clear.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                for (int r = 0; r < items.getRowCount(); r++) items.setValueAt(Integer.valueOf(0), r, 1);
            }
        });
        itemsMenu.add(clear);
        table.setComponentPopupMenu(itemsMenu);

        JSpinner count = new JSpinner(new SpinnerNumberModel(1, 0, 10, 1));
        count.setName("demo.count");

        JScrollPane treeScroll = new JScrollPane(tree);
        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setPreferredSize(new java.awt.Dimension(200, 70));
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT));
        row.add(picked);
        row.add(total);
        row.add(count);
        p.add(treeScroll, BorderLayout.WEST);
        p.add(tableScroll, BorderLayout.CENTER);
        p.add(row, BorderLayout.SOUTH);
        return p;
    }

    private static JPanel logPanel() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT));
        p.setName("demo.logPanel");
        JButton trace = new JButton("Log trace");
        trace.setName("demo.logTrace");
        trace.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                IllegalStateException ex = new IllegalStateException("trace demo");
                StackTraceElement[] st = new StackTraceElement[40];
                for (int i = 0; i < st.length; i++) st[i] = new StackTraceElement("demo.Deep", "frame" + i, "Deep.java", 100 + i);
                ex.setStackTrace(st);
                LOG.log(Level.SEVERE, "trace demo", ex);
            }
        });
        JButton infoError = new JButton("Log info with Error");
        infoError.setName("demo.logInfoError");
        infoError.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                LOG.info("Error count 0 of 3");
                System.out.println("Exception handler ready");
            }
        });
        JButton print = new JButton("Print trace");
        print.setName("demo.printTrace");
        print.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                new IllegalStateException("print demo", new IOException("cause")).printStackTrace();
            }
        });
        JButton exit = new JButton("Exit 7");
        exit.setName("demo.exit");
        exit.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                LOG.severe("exit on purpose");
                System.exit(7);
            }
        });
        p.add(trace);
        p.add(infoError);
        p.add(print);
        p.add(exit);
        return p;
    }

    private static JPanel simRow(final int port) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton count = new JButton("Count");
        count.setName("demo.sim.count");
        final JLabel value = new JLabel("-");
        value.setName("demo.sim.value");
        count.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                // A local socket answers at once; the call has a time limit of 5 s.
                try {
                    value.setText(DemoSimulator.ask(port, "INC"));
                } catch (IOException ex) {
                    value.setText("error");
                    LOG.info("simulator: " + ex);
                }
            }
        });
        row.add(count);
        row.add(value);
        return row;
    }

    static void build() {
        final JFrame f = new JFrame("Smoker Demo");
        f.setName("demo.frame");
        f.setDefaultCloseOperation(IGNORE_CLOSE ? JFrame.DO_NOTHING_ON_CLOSE : JFrame.EXIT_ON_CLOSE);
        if (LOCK_FILE != null && !IGNORE_CLOSE) {
            f.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override
                public void windowClosing(java.awt.event.WindowEvent e) {
                    new java.io.File(LOCK_FILE).delete();
                }
            });
        }

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
        if (LOG_PANEL) {
            JPanel notesAndLog = new JPanel(new BorderLayout());
            notesAndLog.add(new NotesPanel(), BorderLayout.NORTH);
            notesAndLog.add(logPanel(), BorderLayout.SOUTH);
            south.add(notesAndLog, BorderLayout.SOUTH);
        } else {
            south.add(new NotesPanel(), BorderLayout.SOUTH);
        }
        // The query row is below the notes row: the delayed answer stands in for a hardware simulator (WAIT_FOR).
        JPanel queryRow = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton query = new JButton("Query");
        query.setName("demo.query");
        final JLabel answer = new JLabel("-");
        answer.setName("demo.answer");
        query.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                answer.setText("waiting\u2026");
                Thread t = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try { Thread.sleep(3000); } catch (InterruptedException ignored) { return; }
                        SwingUtilities.invokeLater(new Runnable() {
                            @Override
                            public void run() {
                                answer.setText("ready 42");
                            }
                        });
                        LOG.info("answer ready");
                    }
                }, "demo-query");
                t.setDaemon(true);
                t.start();
            }
        });
        queryRow.add(query);
        queryRow.add(answer);
        JPanel southAll = new JPanel(new BorderLayout());
        southAll.add(south, BorderLayout.CENTER);
        if (SIM_PORT != null) {
            JPanel both = new JPanel(new BorderLayout());
            both.add(queryRow, BorderLayout.CENTER);
            both.add(simRow(Integer.parseInt(SIM_PORT)), BorderLayout.SOUTH);
            southAll.add(both, BorderLayout.SOUTH);
        } else {
            southAll.add(queryRow, BorderLayout.SOUTH);
        }
        f.getContentPane().add(southAll, BorderLayout.SOUTH);
        if (RECORDER_PANEL) f.getContentPane().add(recorderPanel(), BorderLayout.EAST);
        if (LOCALE_PANEL) f.getContentPane().add(localeLabel(), BorderLayout.WEST);
        f.setSize(640, 400);
        f.setLocation(100, 100);
        f.setVisible(true);
        LOG.info("demo started");
    }
}
