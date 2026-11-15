package smoker.core.model;

/** A title rule: a window title that matches {@code pattern} (whole title) has the fixed key {@code key}. */
public class WindowKey {
    public String pattern;
    public String key;

    public WindowKey() {}

    public WindowKey(String pattern, String key) {
        this.pattern = pattern;
        this.key = key;
    }
}
