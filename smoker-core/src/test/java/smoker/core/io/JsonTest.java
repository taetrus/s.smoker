package smoker.core.io;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import smoker.core.model.Action;
import smoker.core.model.IgnoreRules;
import smoker.core.model.Locator;
import smoker.core.model.NodeSnapshot;
import smoker.core.model.Scenario;
import smoker.core.model.Snapshot;
import smoker.core.model.Step;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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
        NodeSnapshot n = new NodeSnapshot();
        n.path = "[Demo]/#demo.name";
        n.text = "İsmail ğüşçö";
        n.enabled = Boolean.TRUE;
        sc.steps.get(0).expected = new Snapshot();
        sc.steps.get(0).expected.nodes.add(n);
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
        assertEquals("İsmail ğüşçö", s1.expected.nodes.get(0).text);
        assertEquals(Boolean.TRUE, s1.expected.nodes.get(0).enabled);
        assertNull(s1.expected.nodes.get(0).visible);
        assertEquals(Action.Type.KEY, back.steps.get(1).action.type);
        assertNull("modifier 0 is not written", back.steps.get(1).action.modifiers);
        assertNull(back.steps.get(1).expected);
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
}
