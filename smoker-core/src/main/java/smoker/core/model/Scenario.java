package smoker.core.model;

import java.util.ArrayList;
import java.util.List;

/** One functionality = one scenario file (R-25). */
public class Scenario {
    public String name;
    public String description;
    /** Main window must appear within this time (R-14). */
    public long startupTimeoutMs = 60_000;
    /** Snapshot is taken after the EDT has been idle this long (R-23). */
    public long quietMs = 400;
    /** Upper bound for waiting for quiescence per step. */
    public long stepTimeoutMs = 15_000;
    public IgnoreRules ignore = new IgnoreRules();
    public List<Step> steps = new ArrayList<Step>();

    public Step findStep(String id) {
        for (Step s : steps) if (id.equals(s.id)) return s;
        return null;
    }
}
