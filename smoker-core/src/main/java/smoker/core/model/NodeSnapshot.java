package smoker.core.model;

import java.util.List;

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
    /** Texts of the first rows of a list, or of the visible rows of a tree (two spaces per depth). Null for other types. */
    public List<String> items;
    /** Cell texts of the first rows of a table, in view order. Null for other types. */
    public List<List<String>> cells;
}
