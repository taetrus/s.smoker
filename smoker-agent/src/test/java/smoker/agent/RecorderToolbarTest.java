package smoker.agent;

import org.junit.Test;
import smoker.core.model.IgnoreRules;
import smoker.core.model.Locator;
import smoker.core.model.NodeSnapshot;
import smoker.core.model.Snapshot;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The picker text of the recorder toolbar. Needs no window. */
public class RecorderToolbarTest {

    @Test
    public void automaticNameNeedsARealName() {
        Locator l = new Locator();
        l.name = "@OrdersPanel.saveButton";
        assertEquals("#@OrdersPanel.saveButton (automatic name - needs a real name)   [JButton]",
                RecorderToolbar.pickerText(l, "JButton"));
    }

    @Test
    public void realNameHasNoNote() {
        Locator l = new Locator();
        l.name = "orders.save";
        assertEquals("#orders.save   [JButton]", RecorderToolbar.pickerText(l, "JButton"));
    }

    @Test
    public void literalRegexMatchesTheValueExactly() {
        for (String v : new String[] {"ready (42)", "1.5 V", "a|b", "x\\y", "[*]"}) {
            assertTrue(v, Pattern.compile(RecorderToolbar.literalRegex(v)).matcher(v).matches());
        }
        assertEquals("1\\.5 V", RecorderToolbar.literalRegex("1.5 V"));
        assertEquals("", RecorderToolbar.literalRegex(null));
    }

    @Test
    public void refusesAComponentWithoutAName() {
        Locator unnamed = new Locator();
        unnamed.className = "JButton";
        unnamed.text = "I have no name";
        assertTrue(RecorderToolbar.refusal(unnamed), RecorderToolbar.refusal(unnamed).startsWith("needs a name"));
        Locator named = new Locator();
        named.name = "demo.answer";
        assertNull(RecorderToolbar.refusal(named));
        Locator automatic = new Locator();
        automatic.name = "@Foo.bar";
        assertNull(RecorderToolbar.refusal(automatic));
    }

    @Test
    public void snapshotSuffixDependsOnTheNextStep() {
        assertEquals("", RecorderToolbar.snapshotSuffix(true, false, false));
        assertEquals("", RecorderToolbar.snapshotSuffix(true, true, false));
        assertEquals("  (snapshot pending...)", RecorderToolbar.snapshotSuffix(false, false, false));
        assertEquals("  (no snapshot: WAIT_FOR follows)", RecorderToolbar.snapshotSuffix(false, true, false));
        assertEquals("  (no golden: run and approve)", RecorderToolbar.snapshotSuffix(false, false, true));
    }

    private static Snapshot labelSnapshot(String text) {
        NodeSnapshot n = new NodeSnapshot();
        n.path = "[F]/#l";
        n.type = "JLabel";
        n.name = "l";
        n.text = text;
        Snapshot s = new Snapshot();
        s.nodes.add(n);
        return s;
    }

    @Test
    public void previewLinesTellTheReason() {
        IgnoreRules rules = new IgnoreRules();
        assertEquals(Arrays.asList("first golden: no earlier step to compare"),
                RecorderToolbar.previewLines(null, labelSnapshot("x"), rules, "x"));
        assertEquals(Arrays.asList("snapshot pending..."),
                RecorderToolbar.previewLines(labelSnapshot("x"), null, rules, "snapshot pending..."));
        Snapshot s = labelSnapshot("x");
        assertEquals(Arrays.asList("no changes"), RecorderToolbar.previewLines(s, s, rules, "x"));
        List<String> d = RecorderToolbar.previewLines(labelSnapshot(""), labelSnapshot("Draft"), rules, "x");
        assertEquals(d.toString(), 1, d.size());
        assertTrue(d.get(0), d.get(0).startsWith("CHANGED ") && d.get(0).contains(".text"));
        IgnoreRules ignoring = new IgnoreRules();
        ignoring.paths.add("\\[F\\]/#l");
        assertEquals(Arrays.asList("no changes"), RecorderToolbar.previewLines(labelSnapshot(""), labelSnapshot("Draft"), ignoring, "x"));
    }

    /**
     * The step list rows go to the record log, which is in the console charset of the application (Cp1252 on Windows).
     * The fixed texts of s.smoker are ASCII, so they read the same in every charset (Windows walkthrough, 2026-11-18).
     */
    @Test
    public void snapshotSuffixIsAscii() {
        for (boolean has : new boolean[] {true, false}) {
            for (boolean follows : new boolean[] {true, false}) {
                for (boolean removed : new boolean[] {true, false}) {
                    String s = RecorderToolbar.snapshotSuffix(has, follows, removed);
                    assertTrue(s, s.matches("\\p{ASCII}*"));
                }
            }
        }
    }
}
