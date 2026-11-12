package smoker.core.model;

/** One scenario step: an action plus the golden state expected afterwards (R-30). */
public class Step {
    public String id;
    public String name;
    public Action action;
    /** Golden snapshot. {@code null} until approved (R-33). */
    public Snapshot expected;

    public Step() {}

    public Step(String id, String name, Action action) {
        this.id = id;
        this.name = name;
        this.action = action;
    }
}
