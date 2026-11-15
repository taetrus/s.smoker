package smoker.core.io;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import smoker.core.model.NodeSnapshot;
import smoker.core.model.Scenario;
import smoker.core.model.Snapshot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ScenarioFilesTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Path file(String name, String json) throws IOException {
        Path f = tmp.getRoot().toPath().resolve(name);
        Files.write(f, json.replace('\'', '"').getBytes(StandardCharsets.UTF_8));
        return f;
    }

    private static String refusal(Path scenario, boolean golden) throws IOException {
        try {
            if (golden) ScenarioFiles.readGolden(scenario);
            else ScenarioFiles.readScenario(scenario);
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
        fail("no IllegalArgumentException for " + scenario);
        return null;
    }

    private static NodeSnapshot node(String path, String text) {
        NodeSnapshot n = new NodeSnapshot();
        n.path = path;
        n.type = "JLabel";
        n.text = text;
        return n;
    }

    private static Snapshot snap(NodeSnapshot... nodes) {
        Snapshot s = new Snapshot();
        s.nodes.addAll(Arrays.asList(nodes));
        return s;
    }

    @Test
    public void newFormatIsRead() throws Exception {
        Path f = file("greet.json", "{'schemaVersion': 2, 'name': 'greet', 'steps': [{'id': 's001', 'name': 'x'}]}");
        Scenario sc = ScenarioFiles.readScenario(f);
        assertEquals("greet", sc.name);
        assertEquals(2, sc.schemaVersion);
        assertEquals("all", sc.compare);
        assertEquals(1, sc.steps.size());
    }

    @Test
    public void oldFormatIsRefused() throws Exception {
        Path f = file("greet.json", "{'schemaVersion': 2, 'name': 'greet', 'steps': ["
                + "{'id': 's001', 'name': 'x', 'expected': {'nodes': []}}]}");
        assertEquals(f + ": old scenario format (schemaVersion 1). Convert it: see README, section Scenario file.",
                refusal(f, false));
    }

    @Test
    public void missingSchemaVersionIsRefused() throws Exception {
        Path f = file("greet.json", "{'name': 'greet', 'steps': []}");
        assertTrue(refusal(f, false), refusal(f, false).contains("old scenario format"));
        Path one = file("one.json", "{'schemaVersion': 1, 'name': 'greet', 'steps': []}");
        assertTrue(refusal(one, false), refusal(one, false).contains("old scenario format"));
    }

    @Test
    public void newerSchemaVersionIsRefused() throws Exception {
        Path f = file("greet.json", "{'schemaVersion': 3, 'name': 'greet', 'steps': []}");
        assertEquals(f + ": schemaVersion 3 is newer than this s.smoker (2).", refusal(f, false));
    }

    @Test
    public void missingGoldenFileGivesAnEmptyMap() throws Exception {
        Path f = file("greet.json", "{'schemaVersion': 2, 'name': 'greet', 'steps': []}");
        assertTrue(ScenarioFiles.readGolden(f).isEmpty());
    }

    @Test
    public void goldenSurvivesWriteAndRead() throws Exception {
        Path f = tmp.getRoot().toPath().resolve("greet.json");
        LinkedHashMap<String, Snapshot> m = new LinkedHashMap<String, Snapshot>();
        m.put("s001", snap(node("[W]/#a", "one"), node("[W]/#b", "İ")));
        m.put("s002", snap(node("[W]/#a", "two")));
        ScenarioFiles.writeGolden(f, "greet", m);

        LinkedHashMap<String, Snapshot> back = ScenarioFiles.readGolden(f);
        assertEquals(Arrays.asList("s001", "s002"), Arrays.asList(back.keySet().toArray()));
        assertEquals(Json.toString(m.get("s001")), Json.toString(back.get("s001")));
        assertEquals(Json.toString(m.get("s002")), Json.toString(back.get("s002")));
        String text = new String(Files.readAllBytes(ScenarioFiles.goldenPath(f)), StandardCharsets.UTF_8);
        assertTrue(text, text.startsWith("{\n  \"schemaVersion\": 2,"));
    }

    @Test
    public void goldenPathIsBesideTheScenario() {
        assertEquals(Paths.get("dir", "greet.golden.json"), ScenarioFiles.goldenPath(Paths.get("dir", "greet.json")));
        assertEquals(Paths.get("greet.golden.json"), ScenarioFiles.goldenPath(Paths.get("greet.json")));
    }

    @Test
    public void goldenFileIsNotAScenario() {
        assertTrue(ScenarioFiles.isGoldenFile(Paths.get("dir", "greet.golden.json")));
        assertFalse(ScenarioFiles.isGoldenFile(Paths.get("dir", "greet.json")));
    }

    @Test
    public void goldenWithOldOrNewerSchemaVersionIsRefused() throws Exception {
        Path f = tmp.getRoot().toPath().resolve("greet.json");
        Path g = file("greet.golden.json", "{'scenario': 'greet', 'steps': []}");
        assertTrue(refusal(f, true), refusal(f, true).startsWith(g + ": old scenario format"));
        file("greet.golden.json", "{'schemaVersion': 3, 'scenario': 'greet', 'steps': []}");
        assertEquals(g + ": schemaVersion 3 is newer than this s.smoker (2).", refusal(f, true));
    }

    @Test
    public void goldenWithAWrongStructureIsRefused() throws Exception {
        Path f = tmp.getRoot().toPath().resolve("greet.json");
        Path g = file("greet.golden.json", "{'schemaVersion': 2, 'scenario': 'greet', 'steps': ["
                + "{'id': 's001', 'changes': []}]}");
        String m = refusal(f, true);
        assertTrue(m, m.startsWith(g + ":") && m.contains("s001"));

        file("greet.golden.json", "{'schemaVersion': 2, 'scenario': 'greet', 'steps': ["
                + "{'id': 's001', 'full': []}, {'id': 's002', 'full': []}]}");
        m = refusal(f, true);
        assertTrue(m, m.startsWith(g + ":") && m.contains("s002"));

        file("greet.golden.json", "{'schemaVersion': 2, 'scenario': 'greet', 'steps': ["
                + "{'id': 's001', 'full': []}, {'id': 's002'}]}");
        m = refusal(f, true);
        assertTrue(m, m.startsWith(g + ":") && m.contains("s002"));
    }

    @Test
    public void goldenWithAWrongChangeIsRefused() throws Exception {
        Path f = tmp.getRoot().toPath().resolve("greet.json");
        String head = "{'schemaVersion': 2, 'scenario': 'greet', 'steps': [{'id': 's001', 'full': []}, "
                + "{'id': 's002', 'changes': [";
        String[] wrong = {
            "{'path': '[W]/#a', 'op': 'add'}",
            "{'path': '[W]/#a', 'op': 'set'}",
            "{'path': '[W]/#a', 'op': 'remove', 'node': {'text': 'a'}}",
            "{'path': '[W]/#a', 'op': 'remove', 'unset': ['text']}",
            "{'path': '[W]/#a', 'op': 'move'}",
            "{'op': 'remove'}",
        };
        for (String c : wrong) {
            Path g = file("greet.golden.json", head + c + "]}]}");
            String m = refusal(f, true);
            assertTrue(c + ": " + m, m.startsWith(g + ":") && m.contains("s002"));
        }
        file("greet.golden.json", head + "{'path': '[W]/#a', 'op': 'add', 'node': {'text': 'a'}}, "
                + "{'path': '[W]/#a', 'op': 'set', 'unset': ['text']}, "
                + "{'path': '[W]/#a', 'op': 'remove'}]}]}");
        assertTrue(ScenarioFiles.readGolden(f).get("s002").nodes.isEmpty());
    }

    @Test
    public void compareOtherThanAllOrNamedIsRefused() throws Exception {
        Path f = file("c.json", "{'schemaVersion':2,'name':'c','compare':'some','steps':[]}");
        assertEquals(f + ": compare must be \"all\" or \"named\", not \"some\".", refusal(f, false));
        assertEquals("named", ScenarioFiles.readScenario(file("d.json", "{'schemaVersion':2,'name':'d','compare':'named','steps':[]}")).compare);
    }

    @Test
    public void invalidWindowKeyIsRefused() throws Exception {
        Path f = file("w.json", "{'schemaVersion': 2, 'name': 'w', 'windowKeys': [{'pattern': 'Edit(or', 'key': 'E'}], 'steps': []}");
        String m = refusal(f, false);
        assertTrue(m, m.startsWith(f + ": windowKeys: invalid pattern 'Edit(or': "));
        Path noKey = file("k.json", "{'schemaVersion': 2, 'name': 'k', 'windowKeys': [{'pattern': 'x'}], 'steps': []}");
        assertEquals(noKey + ": windowKeys: invalid pattern 'x': pattern and key are required", refusal(noKey, false));
        Path noPattern = file("p.json", "{'schemaVersion': 2, 'name': 'p', 'windowKeys': [{'key': 'x'}], 'steps': []}");
        assertEquals(noPattern + ": windowKeys: invalid pattern 'null': pattern and key are required", refusal(noPattern, false));
        Path nullRule = file("n.json", "{'schemaVersion': 2, 'name': 'n', 'windowKeys': [null], 'steps': []}");
        assertEquals(nullRule + ": windowKeys: invalid pattern 'null': pattern and key are required", refusal(nullRule, false));
        Path ok = file("o.json", "{'schemaVersion': 2, 'name': 'o', 'windowKeys': [{'pattern': 'Editor - .*', 'key': 'Editor'}], 'steps': []}");
        assertEquals("Editor", ScenarioFiles.readScenario(ok).windowKeys.get(0).key);
    }

    @Test
    public void goldenWithoutStepsOrWithANullStepIsRefused() throws Exception {
        Path f = tmp.getRoot().toPath().resolve("greet.json");
        Path g = file("greet.golden.json", "{'schemaVersion': 2, 'scenario': 'greet', 'steps': null}");
        assertEquals(g + ": \"steps\" is missing.", refusal(f, true));
        file("greet.golden.json", "{'schemaVersion': 2, 'scenario': 'greet', 'steps': [{'id': 's001', 'full': []}, null]}");
        assertEquals(g + ": step 2 of \"steps\" is null.", refusal(f, true));
    }
}
