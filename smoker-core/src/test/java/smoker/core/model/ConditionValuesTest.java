package smoker.core.model;

import org.junit.Test;

import java.util.Locale;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/** Run also with -Plocale-tr (F-16). */
public class ConditionValuesTest {

    @Test
    public void localeAcceptsDashAndUnderscore() {
        Locale a = ConditionValues.locale("en-US");
        assertEquals("en", a.getLanguage());
        assertEquals("US", a.getCountry());
        Locale b = ConditionValues.locale("en_US");
        assertEquals("en", b.getLanguage());
        assertEquals("US", b.getCountry());
        assertEquals("tr", ConditionValues.locale("tr").getLanguage());
        assertEquals("en", ConditionValues.locale(" en-US ").getLanguage());
    }

    @Test
    public void localeWithoutLanguageThrows() {
        for (String v : new String[] {"", "-", "_US"}) {
            assertEquals("locale: '" + v + "' is not a language tag (example: en-US)", localeMessage(v));
        }
    }

    @Test
    public void windowSize() {
        assertArrayEquals(new int[] {1280, 800}, ConditionValues.windowSize("1280x800"));
        assertArrayEquals(new int[] {1280, 800}, ConditionValues.windowSize("1280X800"));
        assertArrayEquals(new int[] {1, 100000}, ConditionValues.windowSize("1x100000"));
    }

    @Test
    public void wrongWindowSizeThrows() {
        for (String v : new String[] {"0x10", "1280", "axb", "1280x", "-1x5", "100001x5", "", "99999999999x5"}) {
            try {
                ConditionValues.windowSize(v);
                fail(v);
            } catch (IllegalArgumentException e) {
                assertEquals("windowSize: '" + v + "' is not <width>x<height> (example: 1280x800)", e.getMessage());
            }
        }
    }

    private static String localeMessage(String v) {
        try {
            ConditionValues.locale(v);
            fail(v);
            return null;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }
}
