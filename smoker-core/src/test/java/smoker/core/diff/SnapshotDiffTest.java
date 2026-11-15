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

    private static NodeSnapshot withCells(String path, String[]... rows) {
        NodeSnapshot n = node(path, null);
        n.cells = new ArrayList<List<String>>();
        for (String[] r : rows) n.cells.add(new ArrayList<String>(Arrays.asList(r)));
        return n;
    }

    private static NodeSnapshot withItems(String path, String... items) {
        NodeSnapshot n = node(path, null);
        n.items = new ArrayList<String>(Arrays.asList(items));
        return n;
    }

    @Test
    public void wrongCellInRowOneGivesOneLine() {
        List<String> d = SnapshotDiff.diff(
                snapshot(withCells("p", new String[] {"Kerem", "tr"}, new String[] {"Ana", "en"})),
                snapshot(withCells("p", new String[] {"Kerem", "de"}, new String[] {"Ana", "en"})), new IgnoreRules());
        assertEquals(Arrays.asList("CHANGED p .cells[0][1]: expected=\"tr\" actual=\"de\""), d);
    }

    @Test
    public void fewerRowsGiveALengthLineAndLinesForTheCommonPart() {
        List<String> d = SnapshotDiff.diff(
                snapshot(withCells("p", new String[] {"a"}, new String[] {"b"})),
                snapshot(withCells("p", new String[] {"x"})), new IgnoreRules());
        assertEquals(Arrays.asList("CHANGED p .cells: expected=2 rows actual=1 rows",
                "CHANGED p .cells[0][0]: expected=\"a\" actual=\"x\""), d);
    }

    @Test
    public void differentColumnCountGivesOneLine() {
        List<String> d = SnapshotDiff.diff(
                snapshot(withCells("p", new String[] {"a", "b"})),
                snapshot(withCells("p", new String[] {"a"})), new IgnoreRules());
        assertEquals(Arrays.asList("CHANGED p .cells[0]: expected=2 columns actual=1 columns"), d);
    }

    @Test
    public void shorterListGivesALengthLine() {
        List<String> d = SnapshotDiff.diff(
                snapshot(withItems("p", "a", "b", "c")),
                snapshot(withItems("p", "a", "x")), new IgnoreRules());
        assertEquals(Arrays.asList("CHANGED p .items: expected=3 rows actual=2 rows",
                "CHANGED p .items[1]: expected=\"b\" actual=\"x\""), d);
    }

    @Test
    public void nullItemsAgainstItemsGivesLengthLineWithZero() {
        List<String> d = SnapshotDiff.diff(snapshot(node("p", null)), snapshot(withItems("p", "a")), new IgnoreRules());
        assertEquals(Arrays.asList("CHANGED p .items: expected=0 rows actual=1 rows"), d);
        assertEquals(new ArrayList<String>(), SnapshotDiff.diff(snapshot(node("p", null)), snapshot(node("p", null)), new IgnoreRules()));
    }

    @Test
    public void ignoredAttributesAndValuePatternsApplyToItemsAndCells() {
        IgnoreRules ignore = new IgnoreRules();
        ignore.attributes.add("items");
        ignore.attributes.add("cells");
        assertEquals(new ArrayList<String>(), SnapshotDiff.diff(
                snapshot(withItems("p", "a"), withCells("q", new String[] {"a"})),
                snapshot(withItems("p", "b"), withCells("q", new String[] {"b"})), ignore));

        IgnoreRules pat = new IgnoreRules();
        pat.valuePatterns.add("\\d{2}:\\d{2}");
        assertEquals(new ArrayList<String>(), SnapshotDiff.diff(
                snapshot(withItems("p", "at 10:00"), withCells("q", new String[] {"at 10:00"})),
                snapshot(withItems("p", "at 11:30"), withCells("q", new String[] {"at 11:30"})), pat));
    }
}
