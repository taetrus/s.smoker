package smoker.core;

import org.junit.Test;

import java.util.Locale;

import static org.junit.Assert.assertEquals;

/** Makes sure that profile locale-tr really changes the default locale of the test JVM (F-16). */
public class TestLocaleTest {

    @Test
    public void defaultLocaleAgreesWithTheProfile() {
        String expected = System.getProperty("smoker.expectLocale", "");
        if (expected.isEmpty()) return;
        assertEquals(expected, Locale.getDefault().toLanguageTag());
    }
}
