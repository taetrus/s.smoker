package smoker.core.model;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** A title rule: a window title that matches {@code pattern} (whole title) has the fixed key {@code key}. */
public class WindowKey {
    public String pattern;
    public String key;

    public WindowKey() {}

    public WindowKey(String pattern, String key) {
        this.pattern = pattern;
        this.key = key;
    }

    /**
     * Compiles the pattern of each rule, in order. The CLI and the agent use it, so both refuse the same rules.
     *
     * @param rules the rules; null means no rules
     * @throws IllegalArgumentException {@code windowKeys: invalid pattern '<p>': <reason>} for a null rule,
     *                                  a null pattern or key, or a pattern that does not compile
     */
    public static List<Pattern> compile(List<WindowKey> rules) {
        List<Pattern> out = new ArrayList<Pattern>();
        if (rules == null) return out;
        for (WindowKey r : rules) {
            if (r == null || r.pattern == null || r.key == null) {
                throw new IllegalArgumentException("windowKeys: invalid pattern '" + (r == null ? null : r.pattern)
                        + "': pattern and key are required");
            }
            try {
                out.add(Pattern.compile(r.pattern));
            } catch (PatternSyntaxException e) {
                throw new IllegalArgumentException("windowKeys: invalid pattern '" + r.pattern + "': " + e.getDescription(), e);
            }
        }
        return out;
    }
}
