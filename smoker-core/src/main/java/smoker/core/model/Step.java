package smoker.core.model;

/** One scenario step: an action (R-30). The golden state is in the golden file (F-06). */
public class Step {
    public String id;
    public String name;
    public Action action;
    /** Optional. A step with a group starts a group; the report shows it as a header row. Replay ignores it. */
    public String group;

    public Step() {}

    public Step(String id, String name, Action action) {
        this.id = id;
        this.name = name;
        this.action = action;
    }
}
