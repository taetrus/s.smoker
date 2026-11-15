package smoker.core.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Golden file {@code <scenario>.golden.json}: the first step in full,
 * every later step as a change list against the step before it (F-06).
 */
public class Golden {
    public int schemaVersion = 2;
    public String scenario;
    public List<GoldenStep> steps = new ArrayList<GoldenStep>();

    /** Exactly one of {@code full} (first step only) and {@code changes} is non-null. */
    public static class GoldenStep {
        public String id;
        public List<NodeSnapshot> full;
        public List<Change> changes;
    }

    /** One component per change entry. {@code node.path} is not written: the path is {@link #path}. */
    public static class Change {
        public String path;
        /**
         * {@code "add"}: {@code node} is the full new component.
         * {@code "set"}: {@code node} holds only the attributes that changed to a non-null value (null when there
         * are none), {@code unset} the names of the attributes that changed to null (null when there are none).
         * {@code "remove"}: no {@code node} and no {@code unset}.
         */
        public String op;
        public NodeSnapshot node;
        public List<String> unset;
    }
}
