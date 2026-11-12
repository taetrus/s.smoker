package smoker.core.model;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Mask rules for dynamic content (R-32). */
public class IgnoreRules {
    /** Regexes matched against the node path; matching nodes are skipped entirely. */
    public List<String> paths = new ArrayList<String>();
    /** Attribute names ignored everywhere, e.g. {@code title}, {@code rowCount}. */
    public List<String> attributes = new ArrayList<String>();
    /** Regexes applied to text/title values; a match on both sides is treated as equal (timestamps, counters). */
    public List<String> valuePatterns = new ArrayList<String>();

    private transient List<Pattern> pathPatterns;
    private transient List<Pattern> valuePats;

    public boolean ignoresPath(String path) {
        if (pathPatterns == null) {
            pathPatterns = new ArrayList<Pattern>();
            for (String p : paths) pathPatterns.add(Pattern.compile(p));
        }
        for (Pattern p : pathPatterns) if (p.matcher(path).find()) return true;
        return false;
    }

    public boolean ignoresAttribute(String attr) {
        return attributes.contains(attr);
    }

    public boolean valuesEquivalent(String a, String b) {
        if (a == null || b == null) return false;
        if (valuePats == null) {
            valuePats = new ArrayList<Pattern>();
            for (String p : valuePatterns) valuePats.add(Pattern.compile(p));
        }
        for (Pattern p : valuePats) if (p.matcher(a).find() && p.matcher(b).find()) return true;
        return false;
    }
}
