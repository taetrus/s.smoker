package smoker.core.golden;

import smoker.core.diff.SnapshotDiff;
import smoker.core.io.Json;
import smoker.core.model.Golden;
import smoker.core.model.NodeSnapshot;
import smoker.core.model.Snapshot;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Converts snapshots per step to and from the golden file model (F-06). */
public final class GoldenCodec {

    public static final String ADD = "add";
    public static final String SET = "set";
    public static final String REMOVE = "remove";

    /** The attributes of a component: all fields of {@link NodeSnapshot} but {@code path}. */
    private static final String[] ATTRIBUTES = {
        "type", "name", "text", "title", "visible", "enabled", "selected",
        "rowCount", "itemCount", "selectedIndex", "items", "cells",
    };
    private static final Field[] FIELDS = new Field[ATTRIBUTES.length];

    static {
        try {
            for (int i = 0; i < ATTRIBUTES.length; i++) FIELDS[i] = NodeSnapshot.class.getField(ATTRIBUTES[i]);
        } catch (NoSuchFieldException e) {
            throw new ExceptionInInitializerError(e);
        }
        // A new NodeSnapshot field must be added to ATTRIBUTES, or set changes would lose it.
        if (NodeSnapshot.class.getFields().length != ATTRIBUTES.length + 1) {
            throw new ExceptionInInitializerError("ATTRIBUTES does not list all fields of NodeSnapshot");
        }
    }

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
                for (Golden.Change c : step.changes) apply(state, c);
            }
            Snapshot s = new Snapshot();
            s.nodes.addAll(state.values());
            out.put(step.id, s);
        }
        return out;
    }

    /**
     * Applies one change entry. A changed node is a new copy: the snapshots of earlier steps share the old one.
     * Unknown ops are refused when the file is read ({@code ScenarioFiles.readGolden}).
     */
    private static void apply(TreeMap<String, NodeSnapshot> state, Golden.Change c) {
        if (REMOVE.equals(c.op)) {
            state.remove(c.path);
            return;
        }
        NodeSnapshot n;
        if (ADD.equals(c.op)) {
            n = copy(c.node);
        } else {
            NodeSnapshot old = state.get(c.path);
            n = old == null ? new NodeSnapshot() : copy(old);
            NodeSnapshot set = c.node == null ? null : copy(c.node);
            for (Field f : FIELDS) {
                if (set != null && get(f, set) != null) put(f, n, get(f, set));
                if (c.unset != null && c.unset.contains(f.getName())) put(f, n, null);
            }
        }
        n.path = c.path;
        state.put(c.path, n);
    }

    private static List<Golden.Change> changes(Map<String, NodeSnapshot> previous, Map<String, NodeSnapshot> current) {
        TreeMap<String, Golden.Change> sorted = new TreeMap<String, Golden.Change>();
        for (Map.Entry<String, NodeSnapshot> e : current.entrySet()) {
            NodeSnapshot old = previous.get(e.getKey());
            Golden.Change c = old == null ? added(e.getKey(), e.getValue()) : set(e.getKey(), old, e.getValue());
            if (c != null) sorted.put(e.getKey(), c);
        }
        for (String path : previous.keySet()) {
            if (!current.containsKey(path)) sorted.put(path, change(path, REMOVE, null));
        }
        return new ArrayList<Golden.Change>(sorted.values());
    }

    private static Golden.Change added(String path, NodeSnapshot node) {
        NodeSnapshot n = copy(node);
        n.path = null;
        return change(path, ADD, n);
    }

    /** @return the attributes that changed, or null when the component did not change */
    private static Golden.Change set(String path, NodeSnapshot old, NodeSnapshot now) {
        NodeSnapshot node = new NodeSnapshot();
        boolean anySet = false;
        List<String> unset = new ArrayList<String>();
        for (Field f : FIELDS) {
            Object a = get(f, old);
            Object b = get(f, now);
            if (Json.toString(a).equals(Json.toString(b))) continue;
            if (b == null) {
                unset.add(f.getName());
            } else {
                put(f, node, b);
                anySet = true;
            }
        }
        if (!anySet && unset.isEmpty()) return null;
        Golden.Change c = change(path, SET, anySet ? node : null);
        if (!unset.isEmpty()) c.unset = unset;
        return c;
    }

    private static Object get(Field f, NodeSnapshot n) {
        try {
            return f.get(n);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void put(Field f, NodeSnapshot n, Object value) {
        try {
            f.set(n, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
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
