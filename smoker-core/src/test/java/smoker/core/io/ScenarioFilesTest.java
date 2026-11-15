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
}
