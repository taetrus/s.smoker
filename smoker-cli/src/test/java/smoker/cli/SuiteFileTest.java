package smoker.cli;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** The suite file of {@code run --suite} (WP-05, F-03, F-04). */
public class SuiteFileTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Path file(String rel, String json) throws Exception {
        Path f = tmp.getRoot().toPath().resolve(rel);
        Files.createDirectories(f.getParent());
        Files.write(f, json.replace('\'', '"').getBytes(StandardCharsets.UTF_8));
        return f;
    }

    private Path scenario(String rel) throws Exception {
        String stem = rel.substring(rel.lastIndexOf('/') + 1).replace(".json", "");
        return file(rel, "{'schemaVersion': 2, 'name': '" + stem + "', 'steps': []}");
    }

    private Path suite(String json) throws Exception {
        return file("suite.json", json);
    }

    private void refused(Path suite, String message) throws Exception {
        try {
            SuiteFile.read(suite);
            fail("accepted, expected: " + message);
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().startsWith("suite: "));
            assertTrue(e.getMessage(), e.getMessage().contains(message));
        }
    }

    @Test
    public void readsTheExampleOfTheDesign() throws Exception {
        Path login = scenario("scenarios/login.json");
        scenario("scenarios/orders/b.json");
        Path a = scenario("scenarios/orders/a.json");
        smoker.core.io.ScenarioFiles.writeGolden(a, "a", new java.util.LinkedHashMap<String, smoker.core.model.Snapshot>());
        Path reset = scenario("scenarios/reset.json");
        Path s = suite("{'app': 'java {agent} -jar x.jar', 'workDir': 'work',"
                + " 'agentConfig': {'log': 'logs/app.log', 'failLevel': 'ERROR', 'edtHangMs': 5000},"
                + " 'before': [{'command': 'sim.bat', 'background': true, 'readyPort': 5020, 'readyTimeoutS': 30}],"
                + " 'reset': [{'command': 'load.bat', 'timeoutS': 10}],"
                + " 'resetScenario': 'scenarios/reset.json',"
                + " 'after': [{'command': 'stop.bat'}],"
                + " 'scenarios': ['scenarios/login.json', 'scenarios/orders']}");
        SuiteFile f = SuiteFile.read(s);
        assertEquals("java {agent} -jar x.jar", f.app);
        assertEquals(tmp.getRoot().toPath().resolve("work").toAbsolutePath().normalize(), f.workDir);
        assertEquals(2, f.agentConfig.size());
        assertEquals("ERROR", f.agentConfig.get("failLevel"));
        assertEquals("5000", f.agentConfig.get("edtHangMs"));
        assertEquals(1, f.logFiles.size());
        assertEquals("logs/app.log", f.logFiles.get(0));
        assertEquals(1, f.before.size());
        assertEquals("sim.bat", f.before.get(0).command);
        assertTrue(f.before.get(0).background);
        assertEquals(Integer.valueOf(5020), f.before.get(0).readyPort);
        assertEquals(30, f.before.get(0).readyTimeoutS);
        assertEquals(60, f.before.get(0).timeoutS);
        assertEquals(10, f.reset.get(0).timeoutS);
        assertFalse(f.reset.get(0).background);
        assertNull(f.reset.get(0).readyPort);
        assertEquals("stop.bat", f.after.get(0).command);
        assertEquals(reset.toAbsolutePath().normalize(), f.resetScenario);
        assertEquals("reset", f.parsedReset.name);
        assertEquals(3, f.scenarios.size());
        assertEquals(login.toAbsolutePath().normalize(), f.scenarios.get(0));
        assertEquals("a.json", f.scenarios.get(1).getFileName().toString());
        assertEquals("b.json", f.scenarios.get(2).getFileName().toString());
        assertEquals(3, f.parsed.size());
        assertEquals("login", f.parsed.get(f.scenarios.get(0)).name);
    }

    @Test
    public void defaultsWithoutOptionalFields() throws Exception {
        scenario("a.json");
        SuiteFile f = SuiteFile.read(suite("{'app': 'x', 'scenarios': ['a.json']}"));
        assertEquals(java.nio.file.Paths.get("").toAbsolutePath().normalize(), f.workDir);
        assertTrue(f.agentConfig.isEmpty());
        assertTrue(f.logFiles.isEmpty());
        assertTrue(f.before.isEmpty() && f.reset.isEmpty() && f.after.isEmpty());
        assertNull(f.resetScenario);
        assertNull(f.parsedReset);
    }

    @Test
    public void unknownFieldIsRefused() throws Exception {
        scenario("a.json");
        refused(suite("{'app': 'x', 'scenarios': ['a.json'], 'aftr': []}"), "suite: unknown field 'aftr'");
    }

    @Test
    public void unknownAgentKeyIsRefused() throws Exception {
        scenario("a.json");
        refused(suite("{'app': 'x', 'scenarios': ['a.json'], 'agentConfig': {'failLevl': 'ERROR'}}"),
                "unknown agentConfig key 'failLevl'");
    }

    @Test
    public void unknownCommandFieldIsRefused() throws Exception {
        scenario("a.json");
        refused(suite("{'app': 'x', 'scenarios': ['a.json'], 'after': [{'command': 'c', 'timeout': 5}]}"),
                "unknown field 'timeout' in after[0]");
    }

    @Test
    public void logAsStringOrArray() throws Exception {
        scenario("a.json");
        SuiteFile one = SuiteFile.read(suite("{'app': 'x', 'scenarios': ['a.json'], 'agentConfig': {'log': 'a,b.log'}}"));
        assertEquals(1, one.logFiles.size());
        assertEquals("a,b.log", one.logFiles.get(0));
        assertFalse(one.agentConfig.containsKey("log"));
        SuiteFile two = SuiteFile.read(suite("{'app': 'x', 'scenarios': ['a.json'], 'agentConfig': {'log': ['l1', 'l2']}}"));
        assertEquals(2, two.logFiles.size());
        assertEquals("l2", two.logFiles.get(1));
    }

    @Test
    public void backgroundOnlyInBefore() throws Exception {
        scenario("a.json");
        refused(suite("{'app': 'x', 'scenarios': ['a.json'], 'after': [{'command': 'c', 'background': true}]}"),
                "background is permitted only in before");
        refused(suite("{'app': 'x', 'scenarios': ['a.json'], 'reset': [{'command': 'c', 'background': true}]}"),
                "background is permitted only in before");
    }

    @Test
    public void readyPortOnlyWithBackground() throws Exception {
        scenario("a.json");
        refused(suite("{'app': 'x', 'scenarios': ['a.json'], 'before': [{'command': 'c', 'readyPort': 80}]}"),
                "readyPort needs background");
    }

    @Test
    public void appAndScenariosAreRequired() throws Exception {
        scenario("a.json");
        refused(suite("{'scenarios': ['a.json']}"), "missing field 'app'");
        refused(suite("{'app': 'x'}"), "missing field 'scenarios'");
        refused(suite("{'app': 'x', 'scenarios': []}"), "'scenarios' has no scenario");
    }

    @Test
    public void sameScenarioNameInTwoFoldersIsRefused() throws Exception {
        scenario("a/x.json");
        scenario("b/x.json");
        refused(suite("{'app': 'x', 'scenarios': ['a', 'b']}"), "suite: two scenarios have the name 'x'");
    }

    @Test
    public void missingScenarioFileIsRefused() throws Exception {
        refused(suite("{'app': 'x', 'scenarios': ['nope.json']}"), "nope.json");
    }

    @Test
    public void wrongScenarioFileIsRefused() throws Exception {
        file("old.json", "{'name': 'old', 'steps': []}");
        refused(suite("{'app': 'x', 'scenarios': ['old.json']}"), "old scenario format");
    }

    @Test
    public void malformedSuiteFileIsRefused() throws Exception {
        refused(suite("{'app': "), "suite:");
        refused(tmp.getRoot().toPath().resolve("absent.json"), "suite:");
    }

    @Test
    public void malformedScenarioAndGoldenFilesAreRefusedWithThePrefix() throws Exception {
        Path bad = file("bad.json", "{'schemaVersion': 2, 'name': ");
        refused(suite("{'app': 'x', 'scenarios': ['bad.json']}"), bad.getFileName().toString());
        Path ok = scenario("ok.json");
        file("ok.golden.json", "{'schemaVersion': 2, ");
        refused(suite("{'app': 'x', 'scenarios': ['ok.json']}"), "ok");
    }

    @Test
    public void readyPortIsRangeChecked() throws Exception {
        scenario("a.json");
        for (String port : new String[] {"0", "65536", "4294967297"}) {
            refused(suite("{'app': 'x', 'scenarios': ['a.json'],"
                    + " 'before': [{'command': 'c', 'background': true, 'readyPort': " + port + "}]}"),
                    "before[0]: readyPort must be 1..65535");
        }
        assertEquals(Integer.valueOf(65535), SuiteFile.read(suite("{'app': 'x', 'scenarios': ['a.json'],"
                + " 'before': [{'command': 'c', 'background': true, 'readyPort': 65535}]}")).before.get(0).readyPort);
    }
}
