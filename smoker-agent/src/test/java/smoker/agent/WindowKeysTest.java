package smoker.agent;

import org.junit.Test;
import smoker.core.model.WindowKey;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

public class WindowKeysTest {

    private static WindowKeys rules() {
        return new WindowKeys(Arrays.asList(new WindowKey("Editor - .*", "Editor")));
    }

    @Test
    public void firstMatchingPatternGivesTheKey() {
        WindowKeys k = new WindowKeys(Arrays.asList(new WindowKey("Editor - .*", "Editor"), new WindowKey(".*", "Any")));
        assertEquals("Editor", k.keyOf("Editor - a.txt"));
        assertEquals("Any", k.keyOf("Other"));
    }

    @Test
    public void patternMustMatchTheWholeTitle() {
        assertEquals("My Editor - a", rules().keyOf("My Editor - a"));
    }

    @Test
    public void noRulesGiveTheTitle() {
        assertEquals("Orders", new WindowKeys(Collections.<WindowKey>emptyList()).keyOf("Orders"));
        assertEquals("Orders", new WindowKeys(null).keyOf("Orders"));
        assertNull(rules().keyOf((String) null));
    }

    @Test
    public void invalidPatternNamesThePattern() {
        try {
            new WindowKeys(Arrays.asList(new WindowKey("Edit(or", "E")));
            fail();
        } catch (IllegalArgumentException e) {
            assertEquals(true, e.getMessage().startsWith("windowKeys: invalid pattern 'Edit(or': "));
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void nullKeyIsRefused() {
        new WindowKeys(Arrays.asList(new WindowKey("x", null)));
    }
}
