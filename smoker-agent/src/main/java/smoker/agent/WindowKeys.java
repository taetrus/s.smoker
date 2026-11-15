package smoker.agent;

import smoker.core.model.WindowKey;

import java.awt.Window;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Title rules: a window title that changes while the application runs gets a fixed key (WP-02, F-05).
 * The first pattern that matches the whole title gives the key. No match gives the title.
 */
final class WindowKeys {

    private final List<Pattern> patterns = new ArrayList<Pattern>();
    private final List<String> keys = new ArrayList<String>();

    /** @param rules the rules in order; null means no rules */
    WindowKeys(List<WindowKey> rules) {
        if (rules == null) return;
        for (WindowKey r : rules) {
            if (r == null || r.pattern == null || r.key == null) {
                throw new IllegalArgumentException("windowKeys: invalid pattern '" + (r == null ? null : r.pattern)
                        + "': pattern and key are required");
            }
            try {
                patterns.add(Pattern.compile(r.pattern));
            } catch (PatternSyntaxException e) {
                throw new IllegalArgumentException("windowKeys: invalid pattern '" + r.pattern + "': " + e.getDescription(), e);
            }
            keys.add(r.key);
        }
    }

    String keyOf(String title) {
        if (title == null) return null;
        for (int i = 0; i < patterns.size(); i++) {
            Matcher m = patterns.get(i).matcher(title);
            if (m.matches()) return keys.get(i);
        }
        return title;
    }

    String keyOf(Window w) {
        return keyOf(Locators.titleOf(w));
    }
}
