package smoker.agent;

import org.junit.Test;
import smoker.core.model.Locator;

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
        assertEquals("", RecorderToolbar.snapshotSuffix(true, false));
        assertEquals("", RecorderToolbar.snapshotSuffix(true, true));
        assertEquals("  (snapshot pending\u2026)", RecorderToolbar.snapshotSuffix(false, false));
        assertEquals("  (no snapshot: WAIT_FOR follows)", RecorderToolbar.snapshotSuffix(false, true));
    }
}
