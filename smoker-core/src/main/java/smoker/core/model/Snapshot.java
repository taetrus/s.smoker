package smoker.core.model;

import java.util.ArrayList;
import java.util.List;

/** All visible windows at one instant, as a flat list of nodes sorted by path (F-05). */
public class Snapshot {
    public List<NodeSnapshot> nodes = new ArrayList<NodeSnapshot>();
}
