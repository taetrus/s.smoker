package smoker.agent;

import org.junit.Test;

import javax.swing.*;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class NameProposerTest {

    @Test
    public void viewDropsOneSuffixAndLowersTheFirstLetter() {
        assertEquals("orders", NameProposer.view("OrdersPanel"));
        assertEquals("customerDetails", NameProposer.view("CustomerDetailsDialog"));
        assertEquals("panel", NameProposer.view("Panel"));
        assertEquals("settings", NameProposer.view("Settings"));
    }

    @Test
    public void typeFollowsTheTable() {
        assertEquals("pwd", NameProposer.type(new JPasswordField()));
        assertEquals("txt", NameProposer.type(new JTextField()));
        assertEquals("txt", NameProposer.type(new JTextArea()));
        assertEquals("chk", NameProposer.type(new JCheckBox()));
        assertEquals("rad", NameProposer.type(new JRadioButton()));
        assertEquals("tgl", NameProposer.type(new JToggleButton()));
        assertEquals("menu", NameProposer.type(new JMenuItem()));
        assertEquals("btn", NameProposer.type(new JButton()));
        assertEquals("cmb", NameProposer.type(new JComboBox<String>()));
        assertEquals("lst", NameProposer.type(new JList<String>()));
        assertEquals("tbl", NameProposer.type(new JTable()));
        assertEquals("tree", NameProposer.type(new JTree()));
        assertEquals("tabs", NameProposer.type(new JTabbedPane()));
        assertEquals("spn", NameProposer.type(new JSpinner()));
        assertEquals("sld", NameProposer.type(new JSlider()));
        assertEquals("progressbar", NameProposer.type(new JProgressBar()));
    }

    @Test
    public void typeOfAnAnonymousSubclassUsesTheSuperclass() {
        assertEquals("btn", NameProposer.type(new JButton() { }));
        assertEquals("progressbar", NameProposer.type(new JProgressBar() { }));
    }

    @Test
    public void fieldStemDropsTheLongestTypeSuffix() {
        assertEquals("save", NameProposer.fieldStem("saveButton"));
        assertEquals("customerName", NameProposer.fieldStem("customerNameField"));
        assertEquals("model", NameProposer.fieldStem("modelComboBox"));
        assertEquals("button", NameProposer.fieldStem("button"));
        assertEquals("save", NameProposer.fieldStem("SaveBtn"));
    }

    @Test
    public void textStemIsLowerCamelCase() {
        assertEquals("baseUrl", NameProposer.textStem("Base URL:"));
        assertEquals("save", NameProposer.textStem("Save"));
        assertEquals("hangEdt8s", NameProposer.textStem("Hang EDT 8s"));
        assertEquals("", NameProposer.textStem("…"));
        assertEquals("", NameProposer.textStem(null));
    }

    private static String propose(String view, javax.swing.JComponent c, String field, String text, String label) {
        NameProposer.Proposal p = NameProposer.propose(view, c, field, text, label);
        return p == null ? null : p.name + " " + p.source;
    }

    @Test
    public void proposeUsesTheFieldFirst() {
        assertEquals("orders.btn.save field", propose("OrdersPanel", new JButton(), "saveButton", "Save", null));
        assertEquals("settings.txt.baseUrl field",
                propose("SettingsDialog", new javax.swing.JTextField(), "baseUrlField", null, "Server:"));
    }

    @Test
    public void proposeFallsBackToTheText() {
        assertEquals("settings.btn.cancel text", propose("SettingsDialog", new JButton(), null, "Cancel", null));
    }

    @Test
    public void noFieldNoTextAndNoLabelGivesNoProposal() {
        assertNull(propose("X", new JButton(), null, null, null));
        assertNull(propose("X", new JButton(), null, "\u2026", null));
    }

    /** WP-09 follow-up, item 5: a text field in a local variable gets the text of its label. */
    @Test
    public void proposeFallsBackToTheLabel() {
        assertEquals("settings.txt.baseUrl label",
                propose("SettingsDialog", new javax.swing.JTextField(), null, null, "Base URL:"));
        assertEquals("settings.btn.ok text", propose("SettingsDialog", new JButton(), null, "OK", "Server:"));
    }

    @Test
    public void generatedFieldNamesAreRecognised() {
        for (String f : new String[] {"jButton1", "jTextField12", "jComboBox1", "btnNewButton", "btnNewButton_1",
                "chckbxNewCheckBox", "mntmNewMenuItem_2", "textField", "textField_1", "comboBox", "list", "table_1",
                "tabs", "textPane", "textArea", "passwordField", "formattedTextField", "tabbedPane", "button2"}) {
            assertTrue(f, NameProposer.isGeneratedField(f));
        }
        for (String f : new String[] {"saveButton", "apiKeyField", "modelCombo", "customerNameField", "jSaveButton",
                "refreshModelsButton", "okButton2", "filterText"}) {
            assertFalse(f, NameProposer.isGeneratedField(f));
        }
    }

    /** WP-09 follow-up, item 4: a generated field gives way to the text and the label, but is the last resort. */
    @Test
    public void generatedFieldGivesWayToTextAndLabel() {
        assertEquals("orders.btn.save text", propose("OrdersPanel", new JButton(), "jButton1", "Save", null));
        assertEquals("orders.txt.customer label",
                propose("OrdersPanel", new javax.swing.JTextField(), "jTextField2", null, "Customer"));
        assertEquals("orders.txt.jTextField2 field",
                propose("OrdersPanel", new javax.swing.JTextField(), "jTextField2", null, null));
        assertEquals("chat.txt.text field", propose("ChatPanel", new javax.swing.JTextPane(), "textPane", null, null));
    }

    @Test
    public void textStemIgnoresHtml() {
        assertEquals("saveAll", NameProposer.textStem("<html><b>Save</b>&nbsp;all</html>"));
        assertEquals("aB", NameProposer.textStem("a&b"));
    }
}
