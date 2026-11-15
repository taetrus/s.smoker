package smoker.core.io;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import smoker.core.golden.GoldenCodec;
import smoker.core.model.Action;
import smoker.core.model.Golden;
import smoker.core.model.IgnoreRules;
import smoker.core.model.Locator;
import smoker.core.model.NodeSnapshot;
import smoker.core.model.Scenario;
import smoker.core.model.Snapshot;
import smoker.core.model.Step;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class JsonTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static Scenario sample() {
        Scenario sc = new Scenario();
        sc.name = "greet";
        sc.ignore.valuePatterns.add("a<b&c=d");
        Locator l = new Locator();
        l.name = "demo.name";
        sc.steps.add(new Step("s001", "enter name", Action.setText(l, "İsmail ğüşçö")));
        sc.steps.add(new Step("s002", "press enter", Action.key("ENTER", 0)));
        return sc;
    }

    @Test
    public void scenarioSurvivesWriteAndRead() throws Exception {
        Path f = tmp.getRoot().toPath().resolve("greet.json");
        Json.write(f, sample());
        Scenario back = Json.read(f, Scenario.class);

        assertEquals("greet", back.name);
        assertEquals(2, back.steps.size());
        Step s1 = back.steps.get(0);
        assertEquals(Action.Type.SET_TEXT, s1.action.type);
        assertEquals("demo.name", s1.action.locator.name);
        assertEquals("İsmail ğüşçö", s1.action.value);
        assertEquals(Action.Type.KEY, back.steps.get(1).action.type);
        assertNull("modifier 0 is not written", back.steps.get(1).action.modifiers);
        assertEquals("a<b&c=d", back.ignore.valuePatterns.get(0));
    }

    @Test
    public void outputIsPrettyUtf8WithoutHtmlEscapes() throws Exception {
        Path f = tmp.getRoot().toPath().resolve("greet.json");
        Json.write(f, sample());
        String text = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);

        assertTrue("pretty printed", text.contains("\n  \"name\": \"greet\""));
        assertTrue("UTF-8, not \\u escapes", text.contains("İsmail ğüşçö"));
        assertTrue("no HTML escapes", text.contains("a<b&c=d"));
        assertTrue("enum by name", text.contains("\"type\": \"SET_TEXT\""));
        assertTrue("ends with a newline", text.endsWith("}\n"));
    }

    @Test
    public void transientFieldsAreNotWritten() {
        IgnoreRules r = new IgnoreRules();
        r.paths.add("#x");
        r.ignoresPath("[W]/#x"); // fills the transient pattern cache
        String text = Json.toString(r);
        assertFalse(text, text.contains("pathPatterns"));
        assertFalse(text, text.contains("valuePats"));
    }

    @Test
    public void writeCreatesMissingParentDirectories() throws Exception {
        Path f = tmp.getRoot().toPath().resolve("a/b/c.json");
        Json.write(f, sample());
        assertTrue(Files.isRegularFile(f));
    }

    @Test
    public void missingFieldsKeepTheirDefaults() throws Exception {
        Path f = tmp.getRoot().toPath().resolve("min.json");
        Files.write(f, "{\"name\":\"min\",\"steps\":[]}".getBytes(StandardCharsets.UTF_8));
        Scenario sc = Json.read(f, Scenario.class);
        assertEquals(60000L, sc.startupTimeoutMs);
        assertEquals(400L, sc.quietMs);
        assertTrue(sc.ignore.paths.isEmpty());
    }

    private String written(Path f) throws Exception {
        return new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
    }

    @Test
    public void shortObjectIsWrittenOnOneLine() throws Exception {
        Path f = tmp.getRoot().toPath().resolve("greet.json");
        Json.write(f, sample());
        String text = written(f);
        assertTrue(text, text.contains("{\"name\": \"demo.name\"}"));
        assertTrue(text, text.contains("\"paths\": []"));
        assertTrue("ends with a newline", text.endsWith("}\n"));
    }

    @Test
    public void longObjectIsWrittenOnSeveralLines() throws Exception {
        Locator l = new Locator();
        StringBuilder name = new StringBuilder();
        while (name.length() < 100) name.append("demo.long");
        l.name = name.toString();
        Path f = tmp.getRoot().toPath().resolve("l.json");
        Json.write(f, l);
        assertEquals("{\n  \"name\": \"" + l.name + "\"\n}\n", written(f));
    }

    @Test
    public void goldenLayoutPutsEachAttributeOnItsOwnLine() throws Exception {
        NodeSnapshot n = new NodeSnapshot();
        n.path = "[W]/#a";
        n.type = "JLabel";
        n.text = "a";
        Path f = tmp.getRoot().toPath().resolve("g.golden.json");
        Json.writeGolden(f, n);
        assertEquals("{\n  \"path\": \"[W]/#a\",\n  \"type\": \"JLabel\",\n  \"text\": \"a\"\n}\n", written(f));
    }

    @Test
    public void goldenLayoutPutsAPrimitiveArrayOnOneLine() throws Exception {
        NodeSnapshot n = new NodeSnapshot();
        n.items = Arrays.asList("a", "b");
        n.cells = new ArrayList<List<String>>();
        n.cells.add(Arrays.asList("a", "b"));
        n.cells.add(Arrays.asList("c", "d"));
        Path f = tmp.getRoot().toPath().resolve("g.golden.json");
        Json.writeGolden(f, n);
        assertEquals("{\n  \"items\": [\"a\", \"b\"],\n  \"cells\": [\n    [\"a\", \"b\"],\n    [\"c\", \"d\"]\n  ]\n}\n",
                written(f));
    }

    @Test
    public void compactOutputReadsBackEqual() throws Exception {
        Path f = tmp.getRoot().toPath().resolve("greet.json");
        Json.write(f, sample());
        assertEquals(Json.toString(sample()), Json.toString(Json.read(f, Scenario.class)));

        NodeSnapshot a = new NodeSnapshot();
        a.path = "[W]/#t";
        a.type = "JTable";
        a.rowCount = 2;
        a.cells = new ArrayList<List<String>>();
        a.cells.add(Arrays.asList("x", null));
        NodeSnapshot b = new NodeSnapshot();
        b.path = "[W]/#t";
        b.type = "JTable";
        b.rowCount = 3;
        Snapshot s1 = new Snapshot();
        s1.nodes.add(a);
        Snapshot s2 = new Snapshot();
        s2.nodes.add(b);
        LinkedHashMap<String, Snapshot> m = new LinkedHashMap<String, Snapshot>();
        m.put("s001", s1);
        m.put("s002", s2);
        Golden g = GoldenCodec.encode("demo", m);
        Path gf = tmp.getRoot().toPath().resolve("greet.golden.json");
        Json.writeGolden(gf, g);
        assertEquals(Json.toString(g), Json.toString(Json.read(gf, Golden.class)));
        Json.write(gf, g);
        assertEquals(Json.toString(g), Json.toString(Json.read(gf, Golden.class)));
    }
}
