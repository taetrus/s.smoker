package smoker.core.model;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks the values of the configuration keys {@code locale} and {@code windowSize} (WP-07, R-34). The agent and
 * the CLI use the same rules. Uses no Swing or AWT class: the agent calls it in {@code premain}.
 */
public final class ConditionValues {

    private static final Pattern SIZE = Pattern.compile("([0-9]{1,6})[xX]([0-9]{1,6})");
    private static final int MAX_SIZE = 100000;

    private ConditionValues() {}

    /** @param value a language tag such as {@code en-US} or {@code en_US} */
    public static Locale locale(String value) {
        Locale l = Locale.forLanguageTag(value.trim().replace('_', '-'));
        if (l.getLanguage().isEmpty()) {
            throw new IllegalArgumentException("locale: '" + value + "' is not a language tag (example: en-US)");
        }
        return l;
    }

    /** @return {width, height} of a value {@code <w>x<h>}; both from 1 to 100000 */
    public static int[] windowSize(String value) {
        Matcher m = SIZE.matcher(value);
        if (m.matches()) {
            int w = Integer.parseInt(m.group(1));
            int h = Integer.parseInt(m.group(2));
            if (w >= 1 && w <= MAX_SIZE && h >= 1 && h <= MAX_SIZE) return new int[] {w, h};
        }
        throw new IllegalArgumentException("windowSize: '" + value + "' is not <width>x<height> (example: 1280x800)");
    }
}
