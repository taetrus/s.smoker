package smoker.agent;

import javax.swing.*;
import java.awt.Component;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Proposes real component names {@code <view>.<type>.<field>} (WP-09). Pure functions. */
final class NameProposer {

    private static final String[] VIEW_SUFFIXES = {
            "Panel", "View", "Dialog", "Frame", "Window", "Pane", "Form", "Page", "Tab"};

    /** Longest first. */
    private static final String[] FIELD_SUFFIXES = {
            "ComboBox", "CheckBox", "Spinner", "Button", "Slider", "Table", "Radio", "Check", "Field", "Label",
            "Combo", "Area", "List", "Tree", "Item", "Menu", "Pane", "Btn", "Fld", "Box"};

    /** NetBeans GUI builder: {@code jButton1}, {@code jTextField2}. */
    private static final Pattern NETBEANS = Pattern.compile("j[A-Z][A-Za-z]*\\d+");

    /** Eclipse WindowBuilder: {@code btnNewButton}, {@code chckbxNewCheckBox_1}, {@code mntmNewMenuItem}. */
    private static final Pattern WINDOW_BUILDER =
            Pattern.compile("(btn|chckbx|rdbtn|tglbtn|mntm|mn|lbl)New[A-Z][A-Za-z]*(_\\d+)?");

    /** A field stem (lower case) that only names the type: {@code textField_1}, {@code comboBox}, {@code tabs}. */
    private static final Set<String> TYPE_WORDS = new HashSet<String>(Arrays.asList(
            "text", "password", "formattedtext", "editor", "button", "btn", "combo", "check", "radio", "toggle",
            "list", "table", "tree", "tab", "tabs", "tabbed", "spinner", "slider", "menu", "item", "field", "area",
            "pane", "box", "label"));

    /** A proposed name and the source of its last part: {@code field}, {@code text} or {@code label}. */
    static final class Proposal {
        final String name;
        final String source;

        Proposal(String name, String source) {
            this.name = name;
            this.source = source;
        }
    }

    private NameProposer() {
    }

    static String view(String classSimpleName) {
        return lowerFirst(dropSuffix(classSimpleName, VIEW_SUFFIXES));
    }

    static String type(Component c) {
        if (c instanceof JPasswordField) return "pwd";
        if (c instanceof JTextField || c instanceof JTextArea || c instanceof JTextPane || c instanceof JEditorPane) {
            return "txt";
        }
        if (c instanceof JCheckBox || c instanceof JCheckBoxMenuItem) return "chk";
        if (c instanceof JRadioButton || c instanceof JRadioButtonMenuItem) return "rad";
        if (c instanceof JToggleButton) return "tgl";
        if (c instanceof JMenuItem || c instanceof JMenu) return "menu";
        if (c instanceof AbstractButton) return "btn";
        if (c instanceof JComboBox) return "cmb";
        if (c instanceof JList) return "lst";
        if (c instanceof JTable) return "tbl";
        if (c instanceof JTree) return "tree";
        if (c instanceof JTabbedPane) return "tabs";
        if (c instanceof JSpinner) return "spn";
        if (c instanceof JSlider) return "sld";
        String n = Locators.classNameOf(c);
        if (n.length() > 1 && n.charAt(0) == 'J') n = n.substring(1);
        return n.toLowerCase(Locale.ROOT);
    }

    static String fieldStem(String field) {
        return lowerFirst(dropSuffix(field, FIELD_SUFFIXES));
    }

    /** Lower camel case of the letters and digits of {@code text}. HTML tags and entities do not count. */
    static String textStem(String text) {
        if (text == null) return "";
        if (text.trim().toLowerCase(Locale.ROOT).startsWith("<html")) {
            text = text.replaceAll("<[^>]*>", " ").replaceAll("&#?[A-Za-z0-9]+;", " ");
        }
        StringBuilder sb = new StringBuilder();
        StringBuilder run = new StringBuilder();
        boolean first = true;
        for (int i = 0; i <= text.length(); i++) {
            char ch = i < text.length() ? text.charAt(i) : ' ';
            if (Character.isLetterOrDigit(ch)) {
                run.append(ch);
            } else if (run.length() > 0) {
                String r = run.toString().toLowerCase(Locale.ROOT);
                if (!first) r = r.substring(0, 1).toUpperCase(Locale.ROOT) + r.substring(1);
                sb.append(r);
                first = false;
                run.setLength(0);
            }
        }
        return sb.toString();
    }

    /**
     * True for a field name that a GUI builder made, or that only names the type. Such a name gives a poor proposal
     * ({@code orders.btn.jButton1}), so the text and the label come first.
     */
    static boolean isGeneratedField(String field) {
        if (NETBEANS.matcher(field).matches() || WINDOW_BUILDER.matcher(field).matches()) return true;
        String base = field.replaceAll("(_\\d+|\\d+)$", "");
        if (base.isEmpty()) return true;
        String stem = fieldStem(base).toLowerCase(Locale.ROOT);
        return stem.isEmpty() || TYPE_WORDS.contains(stem);
    }

    /**
     * The proposed name {@code <view>.<type>.<stem>}, or null. The stem comes from the first that gives one:
     * the field (when it is not {@link #isGeneratedField generated}), the text of the component, the text of its
     * label ({@code JLabel.setLabelFor}), a generated field.
     *
     * @param field the field that refers to the component, or null
     * @param text  the visible text of the component, or null
     * @param label the text of the label of the component, or null
     */
    static Proposal propose(String classSimpleName, Component c, String field, String text, String label) {
        String stem;
        String src;
        if (field != null && !isGeneratedField(field)) {
            stem = fieldStem(field);
            src = "field";
        } else if (!textStem(text).isEmpty()) {
            stem = textStem(text);
            src = "text";
        } else if (!textStem(label).isEmpty()) {
            stem = textStem(label);
            src = "label";
        } else if (field != null) {
            stem = fieldStem(field);
            src = "field";
        } else {
            return null;
        }
        return new Proposal(view(classSimpleName) + "." + type(c) + "." + stem, src);
    }

    private static String dropSuffix(String s, String[] suffixes) {
        for (String suf : suffixes) {
            if (s.endsWith(suf) && s.length() > suf.length()) return s.substring(0, s.length() - suf.length());
        }
        return s;
    }

    private static String lowerFirst(String s) {
        if (s.isEmpty()) return s;
        return s.substring(0, 1).toLowerCase(Locale.ROOT) + s.substring(1);
    }
}
