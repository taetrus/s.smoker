package smoker.agent;

import javax.swing.*;
import java.awt.Component;
import java.util.Locale;

/** Proposes real component names {@code <view>.<type>.<field>} (WP-09). Pure functions. */
final class NameProposer {

    private static final String[] VIEW_SUFFIXES = {
            "Panel", "View", "Dialog", "Frame", "Window", "Pane", "Form", "Page", "Tab"};

    /** Longest first. */
    private static final String[] FIELD_SUFFIXES = {
            "ComboBox", "CheckBox", "Spinner", "Button", "Slider", "Table", "Radio", "Check", "Field", "Label",
            "Combo", "Area", "List", "Tree", "Item", "Menu", "Pane", "Btn", "Fld", "Box"};

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

    static String textStem(String text) {
        if (text == null) return "";
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

    static String source(String field, String text) {
        if (field != null) return "field";
        return textStem(text).isEmpty() ? null : "text";
    }

    static String propose(String classSimpleName, Component c, String field, String text) {
        String src = source(field, text);
        if (src == null) return null;
        String stem = "field".equals(src) ? fieldStem(field) : textStem(text);
        return view(classSimpleName) + "." + type(c) + "." + stem;
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
