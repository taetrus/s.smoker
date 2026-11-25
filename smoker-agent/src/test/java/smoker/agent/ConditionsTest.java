package smoker.agent;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import smoker.core.model.RunResult;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The conditions of premain and the warnings at the start of a scenario (WP-07, R-34). */
public class ConditionsTest {

    private static final String[] PROPERTIES = {"user.language", "user.country", "user.variant", "user.timezone",
            "swing.defaultlaf"};

    private Locale locale;
    private Locale formatLocale;
    private Locale displayLocale;
    private TimeZone timeZone;
    private final Map<String, String> properties = new HashMap<String, String>();

    @Before
    public void save() {
        locale = Locale.getDefault();
        formatLocale = Locale.getDefault(Locale.Category.FORMAT);
        displayLocale = Locale.getDefault(Locale.Category.DISPLAY);
        timeZone = TimeZone.getDefault();
        for (String p : PROPERTIES) properties.put(p, System.getProperty(p));
    }

    @After
    public void restore() {
        Locale.setDefault(locale); // also sets FORMAT and DISPLAY: set them after it
        Locale.setDefault(Locale.Category.FORMAT, formatLocale);
        Locale.setDefault(Locale.Category.DISPLAY, displayLocale);
        TimeZone.setDefault(timeZone);
        for (String p : PROPERTIES) {
            if (properties.get(p) == null) System.clearProperty(p);
            else System.setProperty(p, properties.get(p));
        }
    }

    private static AgentConfig cfg(String... kv) {
        Map<String, String> m = new HashMap<String, String>();
        m.put("scenario.1", "a.json");
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return AgentConfig.fromMap(m, "test");
    }

    @Test
    public void applyEarlySetsDefaultsAndProperties() {
        Conditions.applyEarly(cfg("locale", "en-US", "timeZone", "UTC", "lookAndFeel", "x.Y"));
        assertEquals("en-US", Locale.getDefault().toLanguageTag());
        assertEquals("en", System.getProperty("user.language"));
        assertEquals("US", System.getProperty("user.country"));
        assertEquals("UTC", TimeZone.getDefault().getID());
        assertEquals("UTC", System.getProperty("user.timezone"));
        assertEquals("x.Y", System.getProperty("swing.defaultlaf"));
    }

    /** A locale without a country or variant removes the old values (final review I-3). */
    @Test
    public void localeWithoutCountryClearsCountryAndVariant() {
        System.setProperty("user.country", "TR");
        System.setProperty("user.variant", "X");
        Conditions.applyEarly(cfg("locale", "en"));
        assertEquals("en", Locale.getDefault().toLanguageTag());
        assertEquals("en", System.getProperty("user.language"));
        assertNull(System.getProperty("user.country"));
        assertNull(System.getProperty("user.variant"));
    }

    @Test
    public void applyEarlyWithoutKeysChangesNothing() {
        Map<String, String> before = new HashMap<String, String>();
        for (String p : PROPERTIES) before.put(p, System.getProperty(p));
        Conditions.applyEarly(cfg());
        assertEquals(locale, Locale.getDefault());
        assertEquals(timeZone, TimeZone.getDefault());
        for (String p : PROPERTIES) assertEquals(p, before.get(p), System.getProperty(p));
    }

    @Test
    public void describeGivesWarningsForReplacedValues() {
        AgentConfig c = cfg("locale", "en-US", "timeZone", "UTC");
        Conditions.applyEarly(c);
        Locale.setDefault(Locale.forLanguageTag("tr-TR")); // as an application does after premain
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Istanbul"));
        RunResult r = new RunResult();
        Conditions.describe(c, r);
        assertEquals("tr-TR", r.locale);
        assertEquals("Europe/Istanbul", r.timeZone);
        assertEquals(Arrays.asList("locale is tr-TR, not the configured en-US",
                "time zone is Europe/Istanbul, not the configured UTC"), r.warnings);
    }

    @Test
    public void describeWithEqualValuesGivesNoWarning() {
        AgentConfig c = cfg("locale", "en_US", "timeZone", "UTC");
        Conditions.applyEarly(c);
        RunResult r = new RunResult();
        Conditions.describe(c, r);
        assertEquals("en-US", r.locale);
        assertEquals("UTC", r.timeZone);
        assertEquals(Collections.<String>emptyList(), r.warnings);
    }

    @Test
    public void describeWithoutKeysGivesValuesAndNoWarning() {
        RunResult r = new RunResult();
        Conditions.describe(cfg(), r);
        assertEquals(Locale.getDefault().toLanguageTag(), r.locale);
        assertEquals(TimeZone.getDefault().getID(), r.timeZone);
        assertTrue(r.warnings.isEmpty());
    }

    /** No answer of the EDT: no value, and no warning for the look and feel and the window size (final review m-7). */
    @Test
    public void describeWithoutAnswerOfTheEdtGivesNoUiWarning() {
        AgentConfig c = cfg("lookAndFeel", "x.Y", "windowSize", "1280x800", "timeZone", "UTC");
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Istanbul"));
        RunResult r = new RunResult();
        Conditions.describe(c, r, null);
        assertNull(r.lookAndFeel);
        assertNull(r.windowSize);
        assertEquals(Arrays.asList("time zone is Europe/Istanbul, not the configured UTC"), r.warnings);
    }

    @Test
    public void lookAndFeelWarning() throws Exception {
        String before = javax.swing.UIManager.getLookAndFeel() == null ? null
                : javax.swing.UIManager.getLookAndFeel().getClass().getName();
        setLookAndFeel("javax.swing.plaf.metal.MetalLookAndFeel");
        try {
            RunResult r = new RunResult();
            Conditions.describe(cfg("lookAndFeel", "x.Y"), r);
            assertEquals("javax.swing.plaf.metal.MetalLookAndFeel", r.lookAndFeel);
            assertEquals(1, r.warnings.size());
            assertTrue(r.warnings.get(0), r.warnings.get(0).startsWith(
                    "look and feel is javax.swing.plaf.metal.MetalLookAndFeel, not the configured x.Y"));
            RunResult same = new RunResult();
            Conditions.describe(cfg("lookAndFeel", "crossplatform"), same);
            assertTrue(same.warnings.isEmpty());
        } finally {
            if (before != null) setLookAndFeel(before);
        }
    }

    private static void setLookAndFeel(final String name) throws Exception {
        javax.swing.SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                try {
                    javax.swing.UIManager.setLookAndFeel(name);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });
    }
}
