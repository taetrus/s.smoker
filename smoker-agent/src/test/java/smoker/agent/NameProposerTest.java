package smoker.agent;

import org.junit.Test;

import javax.swing.*;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

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

    @Test
    public void proposeUsesTheFieldFirst() {
        assertEquals("orders.btn.save", NameProposer.propose("OrdersPanel", new JButton(), "saveButton", "Save"));
        assertEquals("field", NameProposer.source("saveButton", "Save"));
    }

    @Test
    public void proposeFallsBackToTheText() {
        assertEquals("settings.btn.cancel", NameProposer.propose("SettingsDialog", new JButton(), null, "Cancel"));
        assertEquals("text", NameProposer.source(null, "Cancel"));
    }

    @Test
    public void noFieldAndNoTextGivesNoProposal() {
        assertNull(NameProposer.propose("X", new JButton(), null, null));
        assertNull(NameProposer.source(null, null));
        assertNull(NameProposer.source(null, "…"));
    }
}
