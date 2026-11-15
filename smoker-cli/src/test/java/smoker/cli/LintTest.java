package smoker.cli;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import smoker.core.io.Json;
import smoker.core.model.LintEntry;
import smoker.core.model.RunResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** The lint command: read, table, summary and patch (WP-09). */
public class LintTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static LintEntry entry(String window, String runtime, String declaring, String field, String type,
                                   String text, String auto, String proposed, String source) {
        LintEntry e = new LintEntry();
        e.window = window;
        e.runtimeClass = runtime;
        e.declaringClass = declaring;
        e.field = field;
        e.type = type;
        e.text = text;
        e.autoName = auto;
        e.proposedName = proposed;
        e.source = source;
        return e;
    }

    private static LintEntry fieldEntry(String field, String proposed) {
        return entry("orders", "nametest.OrdersPanel", "nametest.OrdersPanel", field, "JButton", "Save", null, proposed, "field");
    }

    private static LintEntry textEntry(String window, String proposed) {
        return entry(window, "nametest.SettingsDialogPanel", null, null, "JButton", "OK", null, proposed, "text");
    }

    private void result(String dir, LintEntry... entries) throws Exception {
        Path d = tmp.getRoot().toPath().resolve(dir);
        Files.createDirectories(d);
        RunResult r = new RunResult();
        r.lint.addAll(Arrays.asList(entries));
        Json.write(d.resolve("run-result.json"), r);
    }

    @Test
    public void readJoinsEntriesOfAllRuns() throws Exception {
        LintEntry shared = fieldEntry("saveButton", "orders.btn.save");
        result("a", shared, fieldEntry("a1", "orders.btn.a1"));
        result("b", fieldEntry("saveButton", "orders.btn.save"), fieldEntry("b1", "orders.btn.b1"));
        List<LintEntry> all = Lint.read(tmp.getRoot().toPath());
        assertEquals(3, all.size());
    }

    @Test
    public void summaryCountsFieldAndTextProposals() {
        List<LintEntry> l = new ArrayList<LintEntry>();
        l.add(entry("w", "p.A", "p.A", "a", "JButton", "A", "x", "w.btn.a", "field"));
        l.add(entry("w", "p.A", "p.A", "b", "JButton", "B", null, "w.btn.b", "field"));
        l.add(entry("w", "p.A", null, null, "JButton", "C", "y", "w.btn.c", "text"));
        l.add(entry("w", "p.A", null, null, "JButton", "D", null, null, null));
        assertEquals("4 unnamed interactive component(s), 3 with a proposed name (75 %: 2 from fields, 1 from text),"
                + " 2 with an automatic name", Lint.summary(l));
    }

    @Test
    public void patchWritesSetNameLinesPerClass() {
        String p = Lint.patch(Arrays.asList(fieldEntry("saveButton", "orders.btn.save")));
        assertTrue(p, p.contains("// nametest.OrdersPanel\n"));
        assertTrue(p, p.contains("saveButton.setName(\"orders.btn.save\");\n"));
    }

    @Test
    public void superclassFieldGivesAComment() {
        String p = Lint.patch(Arrays.asList(entry("orders2", "nametest.OrdersPanel2", "nametest.BasePanel", "okButton",
                "JButton", "OK", null, "orders2.btn.ok", "field")));
        assertTrue(p, p.contains("// okButton is declared in BasePanel: set \"orders2.btn.ok\" in OrdersPanel2\n"));
    }

    @Test
    public void textProposalGivesAComment() {
        String p = Lint.patch(Arrays.asList(entry("settings", "nametest.SettingsPanel", null, null, "JButton", "Cancel",
                null, "settings.btn.cancel", "text")));
        assertTrue(p, p.contains("// JButton \"Cancel\" has no field: setName(\"settings.btn.cancel\") where it is created\n"));
    }

    /** One field is one line of code: the same field in two windows is one row; the first one wins. */
    @Test
    public void readJoinsAFieldOfTwoWindowsToOneRow() throws Exception {
        result("a", entry("[Main]", "p.BarPanel", "p.BarPanel", "okButton", "JButton", "OK", null, "bar.btn.ok", "field"),
                entry("[Bar]", "p.BarPanel", "p.BarPanel", "okButton", "JButton", "OK", null, "bar.btn.ok2", "field"));
        List<LintEntry> all = Lint.read(tmp.getRoot().toPath());
        assertEquals(1, all.size());
        assertEquals("bar.btn.ok", all.get(0).proposedName);
    }

    /** Three equal buttons without a field that the agent suffixed stay three rows. */
    @Test
    public void readKeepsTextEntriesWithDistinctProposals() throws Exception {
        result("a", textEntry("[S]", "settingsDialog.btn.ok"), textEntry("[S]", "settingsDialog.btn.ok2"),
                textEntry("[S]", "settingsDialog.btn.ok3"));
        result("b", textEntry("[S]", "settingsDialog.btn.ok"));
        List<LintEntry> all = Lint.read(tmp.getRoot().toPath());
        assertEquals(3, all.size());
        assertTrue(Lint.summary(all), Lint.summary(all).startsWith("3 unnamed interactive component(s), 3 with"));
    }

    @Test
    public void patchWritesOneLinePerFieldAndOneCommentPerTextEntry() {
        String p = Lint.patch(Arrays.asList(
                entry("[Main]", "p.BarPanel", "p.BarPanel", "okButton", "JButton", "OK", null, "bar.btn.ok", "field"),
                entry("[Bar]", "p.BarPanel", "p.BarPanel", "okButton", "JButton", "OK", null, "bar.btn.ok2", "field"),
                entry("[Main]", "p.BarPanel", null, null, "JButton", "Go", null, "bar.btn.go", "text"),
                entry("[Bar]", "p.BarPanel", null, null, "JButton", "Go", null, "bar.btn.go", "text"),
                entry("[Bar]", "p.BarPanel", null, null, "JButton", "Go", null, "bar.btn.go2", "text")));
        assertEquals("// p.BarPanel\n"
                + "okButton.setName(\"bar.btn.ok\");\n"
                + "// JButton \"Go\" has no field: setName(\"bar.btn.go\") where it is created\n"
                + "// JButton \"Go\" has no field: setName(\"bar.btn.go2\") where it is created\n", p);
    }

    @Test
    public void litOfNullIsEmpty() {
        assertEquals("", Lint.lit(null));
    }

    @Test
    public void lintWithoutRunResultsExitsTwo() throws Exception {
        Map<String, String> o = new LinkedHashMap<String, String>();
        o.put("run", tmp.getRoot().toPath().toString());
        assertEquals(2, Main.lint(o));
    }

    @Test
    public void lintWritesPatchFile() throws Exception {
        result("a", fieldEntry("saveButton", "orders.btn.save"));
        Path patch = tmp.getRoot().toPath().resolve("sub/p.txt");
        Map<String, String> o = new LinkedHashMap<String, String>();
        o.put("run", tmp.getRoot().toPath().toString());
        o.put("emit-patch", patch.toString());
        assertEquals(0, Main.lint(o));
        assertTrue(new String(Files.readAllBytes(patch), StandardCharsets.UTF_8).contains("saveButton.setName"));
    }

    @Test
    public void patchEscapesTextAndStaysOnOneLine() {
        String p = Lint.patch(Arrays.asList(entry("settings", "nametest.SettingsPanel", null, null, "JButton",
                "say \"hi\" \\u00zz\nline2\r", null, "a\"b\\c", "text")));
        assertEquals("// nametest.SettingsPanel\n"
                + "// JButton \"say \\\"hi\\\" \\\\u00zz\\nline2\\r\" has no field: setName(\"a\\\"b\\\\c\") where it is created\n", p);
        assertEquals("a\\\"b\\\\c", Lint.lit("a\"b\\c"));
    }

    @Test
    public void tableFlattensLineBreaks() {
        String t = Lint.table(Arrays.asList(entry("w", "p.A", null, null, "JButton", "a\nb", null, null, null)));
        assertEquals(2, t.split("\n").length);
    }

    @Test
    public void unwritablePatchTargetExitsTwo() throws Exception {
        result("a", fieldEntry("saveButton", "orders.btn.save"));
        Path blocker = tmp.newFile("blocker").toPath();
        Map<String, String> o = new LinkedHashMap<String, String>();
        o.put("run", tmp.getRoot().toPath().resolve("a").toString());
        o.put("emit-patch", blocker.resolve("p.txt").toString());
        assertEquals(2, Main.lint(o));
    }
}
