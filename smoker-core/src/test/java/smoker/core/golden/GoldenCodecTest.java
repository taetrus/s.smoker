package smoker.core.golden;

import org.junit.Test;
import smoker.core.diff.SnapshotDiff;
import smoker.core.io.Json;
import smoker.core.model.Golden;
import smoker.core.model.NodeSnapshot;
import smoker.core.model.Snapshot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class GoldenCodecTest {

    private static NodeSnapshot node(String path, String text) {
        NodeSnapshot n = new NodeSnapshot();
        n.path = path;
        n.type = "JLabel";
        n.text = text;
        n.enabled = Boolean.TRUE;
        return n;
    }

    private static Snapshot snap(NodeSnapshot... nodes) {
        Snapshot s = new Snapshot();
        for (NodeSnapshot n : nodes) s.windows.add(n);
        return s;
    }

    private static LinkedHashMap<String, Snapshot> threeSteps() {
        LinkedHashMap<String, Snapshot> m = new LinkedHashMap<String, Snapshot>();
        m.put("s001", snap(node("[W]/#a", "one"), node("[W]/#b", "x")));
        m.put("s002", snap(node("[W]/#a", "two"), node("[W]/#b", "x")));
        m.put("s003", snap(node("[W]/#a", "two"), node("[W]/#b", "x")));
        return m;
    }

    private static List<String> flatJson(Snapshot s) {
        List<String> out = new ArrayList<String>();
        for (Map.Entry<String, NodeSnapshot> e : SnapshotDiff.flatten(s).entrySet()) {
            out.add(e.getKey() + "=" + Json.toString(e.getValue()));
        }
        return out;
    }

    @Test
    public void firstStepIsFullLaterStepsAreChanges() {
        Golden g = GoldenCodec.encode("demo", threeSteps());
        assertEquals(2, g.schemaVersion);
        assertEquals("demo", g.scenario);
        assertEquals(3, g.steps.size());
        assertEquals(2, g.steps.get(0).full.size());
        assertNull(g.steps.get(0).changes);
        assertNull(g.steps.get(1).full);
        assertEquals(1, g.steps.get(1).changes.size());
        assertEquals("set", g.steps.get(1).changes.get(0).op);
        assertEquals("[W]/#a", g.steps.get(1).changes.get(0).path);
        assertTrue(g.steps.get(2).changes.isEmpty());
    }

    @Test
    public void decodeGivesBackTheSameSnapshots() {
        LinkedHashMap<String, Snapshot> in = threeSteps();
        LinkedHashMap<String, Snapshot> out = GoldenCodec.decode(GoldenCodec.encode("demo", in));
        assertEquals(in.keySet(), out.keySet());
        for (String id : in.keySet()) {
            assertEquals(id, flatJson(in.get(id)), flatJson(out.get(id)));
        }
    }

    @Test
    public void removedAndAddedComponentsAreChanges() {
        LinkedHashMap<String, Snapshot> m = new LinkedHashMap<String, Snapshot>();
        m.put("s001", snap(node("[W]/#a", "a")));
        m.put("s002", snap(node("[W]/#b", "b")));
        Golden g = GoldenCodec.encode("demo", m);
        List<Golden.Change> ch = g.steps.get(1).changes;
        assertEquals(2, ch.size());
        assertEquals("[W]/#a", ch.get(0).path);
        assertEquals("remove", ch.get(0).op);
        assertNull(ch.get(0).node);
        assertEquals("[W]/#b", ch.get(1).path);
        assertEquals("set", ch.get(1).op);
        assertEquals("b", ch.get(1).node.text);
        LinkedHashMap<String, Snapshot> back = GoldenCodec.decode(g);
        assertEquals(flatJson(m.get("s002")), flatJson(back.get("s002")));
    }

    @Test
    public void changesAreSortedByPath() {
        LinkedHashMap<String, Snapshot> m = new LinkedHashMap<String, Snapshot>();
        m.put("s001", snap(node("[W]/#c", "1"), node("[W]/#a", "1"), node("[W]/#b", "1")));
        m.put("s002", snap(node("[W]/#b", "2"), node("[W]/#c", "2"), node("[W]/#a", "2")));
        Golden g = GoldenCodec.encode("demo", m);
        assertEquals("[W]/#a", g.steps.get(0).full.get(0).path);
        List<Golden.Change> ch = g.steps.get(1).changes;
        assertEquals(3, ch.size());
        for (int i = 1; i < ch.size(); i++) {
            assertTrue(ch.get(i - 1).path.compareTo(ch.get(i).path) < 0);
        }
    }

    @Test
    public void emptyMapGivesNoSteps() {
        Golden g = GoldenCodec.encode("demo", new LinkedHashMap<String, Snapshot>());
        assertTrue(g.steps.isEmpty());
        assertTrue(GoldenCodec.decode(g).isEmpty());
    }
}
