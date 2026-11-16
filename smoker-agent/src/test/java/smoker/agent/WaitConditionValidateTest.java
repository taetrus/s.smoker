package smoker.agent;

import org.junit.Test;
import smoker.core.model.Action;
import smoker.core.model.Locator;

import java.util.regex.Pattern;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Validation and matching of WAIT_FOR (WP-03, F-02). Needs no display. */
public class WaitConditionValidateTest {

    private static Locator named(String name) {
        Locator l = new Locator();
        l.name = name;
        return l;
    }

    private static void rejects(Action a, String expectedPart) {
        try {
            WaitCondition.validate(a);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(expectedPart));
        }
    }

    @Test
    public void validateAcceptsAGoodAction() {
        Pattern p = WaitCondition.validate(Action.waitFor(named("t.answer"), "text", "ready \\d+", null));
        assertTrue(p.matcher("ready 42").matches());
    }

    @Test
    public void validateRejectsUnknownAttribute() {
        rejects(Action.waitFor(named("x"), "items", "a", null), "text, title, visible, enabled, selected, rowCount, itemCount, selectedIndex");
    }

    @Test
    public void validateRejectsInvalidRegex() {
        rejects(Action.waitFor(named("x"), "text", "(", null), "matches");
    }

    @Test
    public void validateRejectsNullMatches() {
        rejects(Action.waitFor(named("x"), "text", null, null), "matches");
    }

    @Test
    public void validateRejectsMissingLocator() {
        rejects(Action.waitFor(null, "text", "a", null), "locator");
    }

    @Test
    public void matchesNeedsTheWholeValue() {
        assertFalse(WaitCondition.matches(Pattern.compile("ready"), "ready 42"));
        assertTrue(WaitCondition.matches(Pattern.compile("ready \\d+"), "ready 42"));
        assertFalse(WaitCondition.matches(Pattern.compile(".*"), null));
        assertFalse(WaitCondition.matches(Pattern.compile(".*"), WaitCondition.NOT_FOUND));
    }
}
