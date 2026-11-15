package smoker.core.model;

import java.util.ArrayList;
import java.util.List;

/** One functionality = one scenario file (R-25). */
public class Scenario {
    /** Format version of the scenario file. The old format with goldens in the steps is version 1 (F-17). */
    public int schemaVersion = 2;
    public String name;
    public String description;
    /** Main window must appear within this time (R-14). */
    public long startupTimeoutMs = 60_000;
    /** Snapshot is taken after the EDT has been idle this long (R-23). */
    public long quietMs = 400;
    /** Upper bound for waiting for quiescence per step. */
    public long stepTimeoutMs = 15_000;
    /** Components to compare: {@code all} or {@code named}. */
    public String compare = "all";
    /** A snapshot takes the content of at most this many rows of a table, list or tree (F-07). */
    public int maxRows = 20;
    /** Title rules: a changing window title gets a fixed key in paths and locators. */
    public List<WindowKey> windowKeys = new ArrayList<WindowKey>();
    public IgnoreRules ignore = new IgnoreRules();
    public List<Step> steps = new ArrayList<Step>();

    public Step findStep(String id) {
        for (Step s : steps) if (id.equals(s.id)) return s;
        return null;
    }
}
