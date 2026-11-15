package smoker.core.golden;

import smoker.core.diff.SnapshotDiff;
import smoker.core.io.Json;
import smoker.core.model.Golden;
import smoker.core.model.NodeSnapshot;
import smoker.core.model.Snapshot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Converts snapshots per step to and from the golden file model (F-06). */
public final class GoldenCodec {

    public static final String SET = "set";
    public static final String REMOVE = "remove";

    private GoldenCodec() {}

    public static Golden encode(String scenario, LinkedHashMap<String, Snapshot> byStep) {
        Golden g = new Golden();
        g.scenario = scenario;
        Map<String, NodeSnapshot> previous = null;
        for (Map.Entry<String, Snapshot> e : byStep.entrySet()) {
            Map<String, NodeSnapshot> current = nodes(e.getValue());
            Golden.GoldenStep step = new Golden.GoldenStep();
            step.id = e.getKey();
            if (previous == null) {
                step.full = new ArrayList<NodeSnapshot>(current.values());
            } else {
                step.changes = changes(previous, current);
            }
            g.steps.add(step);
            previous = current;
        }
        return g;
    }

    public static LinkedHashMap<String, Snapshot> decode(Golden g) {
        LinkedHashMap<String, Snapshot> out = new LinkedHashMap<String, Snapshot>();
        TreeMap<String, NodeSnapshot> state = new TreeMap<String, NodeSnapshot>();
        for (Golden.GoldenStep step : g.steps) {
            if (step.full != null) {
                state.clear();
                for (NodeSnapshot n : step.full) state.put(n.path, n);
            }
            if (step.changes != null) {
                for (Golden.Change c : step.changes) {
                    if (REMOVE.equals(c.op)) state.remove(c.path);
                    else state.put(c.path, c.node);
                }
            }
            Snapshot s = new Snapshot();
            s.nodes.addAll(state.values());
            out.put(step.id, s);
        }
        return out;
    }

    private static List<Golden.Change> changes(Map<String, NodeSnapshot> previous, Map<String, NodeSnapshot> current) {
        TreeMap<String, Golden.Change> sorted = new TreeMap<String, Golden.Change>();
        for (Map.Entry<String, NodeSnapshot> e : current.entrySet()) {
            NodeSnapshot old = previous.get(e.getKey());
            if (old == null || !Json.toString(old).equals(Json.toString(e.getValue()))) {
                sorted.put(e.getKey(), change(e.getKey(), SET, e.getValue()));
            }
        }
        for (String path : previous.keySet()) {
            if (!current.containsKey(path)) sorted.put(path, change(path, REMOVE, null));
        }
        return new ArrayList<Golden.Change>(sorted.values());
    }

    private static Golden.Change change(String path, String op, NodeSnapshot node) {
        Golden.Change c = new Golden.Change();
        c.path = path;
        c.op = op;
        c.node = node;
        return c;
    }

    /** Copies of all nodes of a snapshot, sorted by path. */
    private static TreeMap<String, NodeSnapshot> nodes(Snapshot s) {
        TreeMap<String, NodeSnapshot> m = new TreeMap<String, NodeSnapshot>();
        for (NodeSnapshot n : SnapshotDiff.byPath(s).values()) m.put(n.path, copy(n));
        return m;
    }

    private static NodeSnapshot copy(NodeSnapshot n) {
        NodeSnapshot c = new NodeSnapshot();
        c.path = n.path;
        c.type = n.type;
        c.name = n.name;
        c.text = n.text;
        c.title = n.title;
        c.visible = n.visible;
        c.enabled = n.enabled;
        c.selected = n.selected;
        c.rowCount = n.rowCount;
        c.itemCount = n.itemCount;
        c.selectedIndex = n.selectedIndex;
        if (n.items != null) c.items = new ArrayList<String>(n.items);
        if (n.cells != null) {
            c.cells = new ArrayList<List<String>>();
            for (List<String> row : n.cells) c.cells.add(row == null ? null : new ArrayList<String>(row));
        }
        return c;
    }
}
