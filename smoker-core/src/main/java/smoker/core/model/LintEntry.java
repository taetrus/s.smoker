package smoker.core.model;

/** One component that needs a real name, with a proposal (WP-09). Class names are fully qualified. */
public class LintEntry {
    public String window;
    public String runtimeClass;
    public String declaringClass;
    public String field;
    /** Simple component class name. */
    public String type;
    public String text;
    public String autoName;
    public String proposedName;
    /** {@code field} or {@code text}. */
    public String source;
}
