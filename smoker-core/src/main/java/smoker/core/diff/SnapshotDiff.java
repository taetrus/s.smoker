package smoker.core.diff;

import smoker.core.model.IgnoreRules;
import smoker.core.model.NodeSnapshot;
import smoker.core.model.Snapshot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Structural comparison of two snapshots (R-30, R-32).
 * Nodes are matched by path; attributes compared one by one.
 */
public final class SnapshotDiff {

    private SnapshotDiff() {}

    public static List<String> diff(Snapshot expected, Snapshot actual, IgnoreRules rules) {
        List<String> out = new ArrayList<String>();
        if (rules == null) rules = new IgnoreRules();
        Map<String, NodeSnapshot> exp = byPath(expected);
        Map<String, NodeSnapshot> act = byPath(actual);

        for (Map.Entry<String, NodeSnapshot> e : exp.entrySet()) {
            String path = e.getKey();
            if (rules.ignoresPath(path)) continue;
            NodeSnapshot a = act.get(path);
            if (a == null) {
                out.add("MISSING " + path);
                continue;
            }
            compareAttr(out, path, "type", e.getValue().type, a.type, rules);
            compareAttr(out, path, "text", e.getValue().text, a.text, rules);
            compareAttr(out, path, "title", e.getValue().title, a.title, rules);
            compareAttr(out, path, "visible", e.getValue().visible, a.visible, rules);
            compareAttr(out, path, "enabled", e.getValue().enabled, a.enabled, rules);
            compareAttr(out, path, "selected", e.getValue().selected, a.selected, rules);
            compareAttr(out, path, "rowCount", e.getValue().rowCount, a.rowCount, rules);
            compareAttr(out, path, "itemCount", e.getValue().itemCount, a.itemCount, rules);
            compareAttr(out, path, "selectedIndex", e.getValue().selectedIndex, a.selectedIndex, rules);
        }
        for (String path : act.keySet()) {
            if (!exp.containsKey(path) && !rules.ignoresPath(path)) out.add("UNEXPECTED " + path);
        }
        return out;
    }

    private static void compareAttr(List<String> out, String path, String attr, Object e, Object a, IgnoreRules rules) {
        if (rules.ignoresAttribute(attr)) return;
        if (e == null && a == null) return;
        if (e != null && e.equals(a)) return;
        if (e instanceof String && a instanceof String && rules.valuesEquivalent((String) e, (String) a)) return;
        out.add("CHANGED " + path + " ." + attr + ": expected=" + fmt(e) + " actual=" + fmt(a));
    }

    private static String fmt(Object o) {
        return o == null ? "<null>" : (o instanceof String ? "\"" + o + "\"" : o.toString());
    }

    /** The nodes of a snapshot by path, in the order of {@link Snapshot#nodes}. */
    public static LinkedHashMap<String, NodeSnapshot> byPath(Snapshot s) {
        LinkedHashMap<String, NodeSnapshot> m = new LinkedHashMap<String, NodeSnapshot>();
        if (s != null) for (NodeSnapshot n : s.nodes) m.put(n.path, n);
        return m;
    }
}
