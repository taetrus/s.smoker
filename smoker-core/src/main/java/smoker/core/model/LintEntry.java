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
    /** The text of the label of the component ({@code JLabel.setLabelFor}), or null. */
    public String label;
    public String autoName;
    public String proposedName;
    /** The source of the last part of the proposed name: {@code field}, {@code text} or {@code label}. */
    public String source;
}
