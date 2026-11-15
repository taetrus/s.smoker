package smoker.core.diff;

import org.junit.Test;
import smoker.core.model.IgnoreRules;
import smoker.core.model.NodeSnapshot;
import smoker.core.model.Snapshot;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SnapshotDiffTest {

    private static NodeSnapshot node(String path, String text) {
        NodeSnapshot n = new NodeSnapshot();
        n.path = path;
        n.type = "JLabel";
        n.text = text;
        n.visible = Boolean.TRUE;
        return n;
    }

    private static Snapshot snapshot(NodeSnapshot... nodes) {
        Snapshot s = new Snapshot();
        s.nodes.addAll(Arrays.asList(nodes));
        return s;
    }

    private static Snapshot demo(String resultText) {
        return snapshot(node("[Demo]", null), node("[Demo]/#demo.name", "Kerem"), node("[Demo]/#demo.result", resultText));
    }

    @Test
    public void equalSnapshotsGiveNoDifference() {
        assertEquals(new ArrayList<String>(), SnapshotDiff.diff(demo("Hello"), demo("Hello"), new IgnoreRules()));
    }

    @Test
    public void changedTextGivesOneChangedLine() {
        List<String> d = SnapshotDiff.diff(demo("Hello"), demo("Merhaba"), new IgnoreRules());
        assertEquals(Arrays.asList("CHANGED [Demo]/#demo.result .text: expected=\"Hello\" actual=\"Merhaba\""), d);
    }

    @Test
    public void nonStringAttributesAndNullAreFormatted() {
        Snapshot e = demo("x");
        Snapshot a = demo("x");
        e.nodes.get(2).selectedIndex = 1;
        a.nodes.get(2).selectedIndex = 2;
        a.nodes.get(1).enabled = Boolean.FALSE;
        List<String> d = SnapshotDiff.diff(e, a, new IgnoreRules());
        assertEquals(Arrays.asList(
                "CHANGED [Demo]/#demo.name .enabled: expected=<null> actual=false",
                "CHANGED [Demo]/#demo.result .selectedIndex: expected=1 actual=2"), d);
    }

    @Test
    public void missingAndUnexpectedNodesAreReported() {
        Snapshot e = snapshot(node("[Demo]", null), node("[Demo]/#old", "a"));
        Snapshot a = snapshot(node("[Demo]", null), node("[Demo]/#new", "a"));
        assertEquals(Arrays.asList("MISSING [Demo]/#old", "UNEXPECTED [Demo]/#new"),
                SnapshotDiff.diff(e, a, new IgnoreRules()));
    }

    @Test
    public void nullExpectedSnapshotReportsEachActualNodeAsUnexpected() {
        assertEquals(Arrays.asList("UNEXPECTED [Demo]", "UNEXPECTED [Demo]/#demo.name", "UNEXPECTED [Demo]/#demo.result"),
                SnapshotDiff.diff(null, demo("x"), new IgnoreRules()));
    }

    @Test
    public void nullRulesAreTheSameAsEmptyRules() {
        assertEquals(1, SnapshotDiff.diff(demo("a"), demo("b"), null).size());
    }

    @Test
    public void ignoredPathSkipsChangedMissingAndUnexpectedNodes() {
        IgnoreRules r = new IgnoreRules();
        r.paths.add("#demo\\.result$");
        r.paths.add("#(old|new)");
        Snapshot e = snapshot(node("[Demo]", null), node("[Demo]/#demo.result", "a"), node("[Demo]/#old", "a"));
        Snapshot a = snapshot(node("[Demo]", null), node("[Demo]/#demo.result", "b"), node("[Demo]/#new", "a"));
        assertEquals(new ArrayList<String>(), SnapshotDiff.diff(e, a, r));
    }

    @Test
    public void ignoredAttributeIsNotCompared() {
        IgnoreRules r = new IgnoreRules();
        r.attributes.add("text");
        assertEquals(new ArrayList<String>(), SnapshotDiff.diff(demo("a"), demo("b"), r));
    }

    @Test
    public void valuePatternMakesDynamicTextEqual() {
        IgnoreRules r = new IgnoreRules();
        r.valuePatterns.add("^\\d{2}:\\d{2}:\\d{2}$");
        assertEquals(new ArrayList<String>(), SnapshotDiff.diff(demo("10:00:01"), demo("11:22:33"), r));
        assertEquals(1, SnapshotDiff.diff(demo("10:00:01"), demo("later"), r).size());
    }

    @Test
    public void byPathKeepsTheOrderOfTheNodes() {
        Map<String, NodeSnapshot> m = SnapshotDiff.byPath(snapshot(
                node("[A]", null), node("[A]/#p", null), node("[A]/#p/JLabel[0]", "1"), node("[B]", null)));
        assertEquals(Arrays.asList("[A]", "[A]/#p", "[A]/#p/JLabel[0]", "[B]"), new ArrayList<String>(m.keySet()));
        assertTrue(SnapshotDiff.byPath(null).isEmpty());
    }
}
