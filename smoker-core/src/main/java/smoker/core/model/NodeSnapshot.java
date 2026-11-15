package smoker.core.model;

/** Semantic snapshot of one component (R-23). Pixel-free, LAF/DPI independent. */
public class NodeSnapshot {
    /** Path used in diffs, e.g. {@code [Orders]/#orders.table} or {@code [#orders.frame]/#orders.panel/JButton[0]}. */
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
}
