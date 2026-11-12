package smoker.core.model;

import java.util.ArrayList;
import java.util.List;

/** Semantic snapshot of one component (R-23). Pixel-free, LAF/DPI independent. */
public class NodeSnapshot {
    /** Path used in diffs, e.g. {@code [Orders]/JPanel[0]/#orders.table}. */
    public String path;
    public String type;
    public String name;
    public String text;
    public String title;
    public Boolean visible;
    public Boolean enabled;
    public Boolean selected;
    public Integer rowCount;
    public Integer itemCount;
    public Integer selectedIndex;
    public List<NodeSnapshot> children;

    public List<NodeSnapshot> children() {
        if (children == null) children = new ArrayList<NodeSnapshot>();
        return children;
    }
}
