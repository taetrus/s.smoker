package smoker.core.model;

import java.util.ArrayList;
import java.util.List;

/** All visible windows at one instant, as a semantic tree. */
public class Snapshot {
    public List<NodeSnapshot> windows = new ArrayList<NodeSnapshot>();
}
