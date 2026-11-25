package smoker.agent;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Run also with -Plocale-tr: values must parse the same in the Turkish locale (F-16). */
public class AgentConfigTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static Map<String, String> kv(String... kv) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    private static AgentConfig cfg(String... kv) {
        Map<String, String> m = kv("scenario.1", "a.json");
        m.putAll(kv(kv));
        return AgentConfig.fromMap(m, "test");
    }

    private static String message(String... kv) {
        try {
            cfg(kv);
            fail();
            return null;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    @Test
    public void labelKey() {
        assertEquals("jre8", cfg("label", "jre8").label);
        assertNull(cfg().label);
    }

    @Test
    public void modeIsCaseInsensitive() {
        assertEquals(AgentConfig.Mode.PLAY, cfg("mode", "play").mode);
        assertEquals(AgentConfig.Mode.RECORD, cfg("mode", "Record").mode);
    }

    @Test
    public void screenshotsOffIsCaseInsensitive() {
        assertFalse(cfg("screenshots", "OFF").screenshots);
        assertTrue(cfg("screenshots", "evidence").screenshots);
    }

    @Test
    public void allKeysAreParsed() {
        AgentConfig c = cfg("mode", "play", "scenario.2", "b.json", "out", "o", "log.1", "a.log", "log.2", "b.log",
                "failRegex", "FATAL", "allowRegex", "noise", "edtHangMs", "900", "exitWhenDone", "false");
        assertEquals(Arrays.asList(Paths.get("a.json"), Paths.get("b.json")), c.scenarios);
        assertEquals(Paths.get("o"), c.out);
        assertEquals(2, c.logFiles.size());
        assertEquals(Paths.get("b.log"), c.logFiles.get(1));
        assertEquals("FATAL", c.failRegex);
        assertEquals("noise", c.allowRegex);
        assertEquals(900L, c.edtHangMs);
        assertFalse(c.exitWhenDone);
    }

    @Test
    public void newKeysAndDefaults() {
        AgentConfig d = cfg();
        assertEquals("%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n", d.logPattern);
        assertEquals(LogLevel.ERROR, d.failLevel);
        assertEquals("\\b(ERROR|SEVERE|FATAL)\\b", d.failRegex);
        assertEquals(Charset.defaultCharset(), d.logCharset);
        assertEquals(10_000L, d.stopTimeoutMs);
        assertTrue(d.resetCommands.isEmpty());

        AgentConfig c = cfg("logPattern", "%d %msg%n", "failLevel", "warn", "logCharset", "windows-1252");
        assertEquals("%d %msg%n", c.logPattern);
        assertEquals(LogLevel.WARN, c.failLevel);
        assertEquals(Charset.forName("windows-1252"), c.logCharset);
    }

    @Test
    public void fromArgsRefusesTheOldForm() {
        for (String old : new String[] {"mode=play;scenario=a.json", "", null}) {
            try {
                AgentConfig.fromArgs(old);
                fail();
            } catch (IllegalArgumentException e) {
                assertEquals("use config=<file> (see README)", e.getMessage());
            }
        }
    }

    @Test
    public void fromArgsTakesAPathWithSemicolonsAndSpaces() throws Exception {
        Path dir = tmp.newFolder("out;1 a=b").toPath();
        Path f = dir.resolve("agent.cfg");
        Files.write(f, Arrays.asList("scenario.1=x.json", "out=" + dir), StandardCharsets.UTF_8);
        AgentConfig c = AgentConfig.fromArgs("config=" + f);
        assertEquals(Paths.get("x.json"), c.scenarios.get(0));
        assertEquals(dir, c.out);
    }

    @Test
    public void unknownKeyIsRefused() {
        assertEquals("unknown key 'scenaro.1' in test", message("scenaro.1", "a.json"));
    }

    @Test
    public void numberedKeysHaveNoGap() {
        assertEquals("missing key 'scenario.2' in test", message("scenario.3", "c.json"));
        assertEquals("missing key 'log.1' in test", message("log.2", "b.log"));
    }

    @Test
    public void scenarioOneIsRequired() {
        try {
            AgentConfig.fromMap(kv("mode", "play"), "test");
            fail();
        } catch (IllegalArgumentException e) {
            assertEquals("missing key 'scenario.1' in test", e.getMessage());
        }
    }

    @Test
    public void logFilesAreNumbered() {
        AgentConfig c = cfg("log.1", "a,b.log");
        assertEquals(Arrays.asList(Paths.get("a,b.log")), c.logFiles);
    }

    @Test
    public void resetCommandsGetTheirTimeouts() {
        AgentConfig c = cfg("resetCommand.1", "x y", "resetCommand.2", "z", "resetTimeoutS.2", "5");
        assertEquals(Arrays.asList("x y", "z"), c.resetCommands);
        assertEquals(Arrays.asList(60L, 5L), c.resetTimeoutS);
        assertEquals("unknown key 'resetTimeoutS.3' in test", message("resetCommand.1", "x", "resetTimeoutS.3", "5"));
    }

    @Test
    public void stopTimeoutAndAliveFile() {
        AgentConfig c = cfg("stopTimeoutMs", "2500", "aliveFile", "alive.txt", "resetScenario", "r.json");
        assertEquals(2500L, c.stopTimeoutMs);
        assertEquals(Paths.get("alive.txt"), c.aliveFile);
        assertEquals(Paths.get("r.json"), c.resetScenario);
    }

    @Test(expected = IllegalArgumentException.class)
    public void unknownFailLevelIsRefused() {
        cfg("failLevel", "LOUD");
    }

    @Test(expected = IllegalArgumentException.class)
    public void unsupportedLogPatternIsRefused() {
        cfg("logPattern", "%xyz");
    }

    @Test
    public void unknownLogCharsetHasAClearMessage() {
        assertEquals("smoker: logCharset: 'no-such-cs' is not supported", message("logCharset", "no-such-cs"));
    }

    @Test
    public void badConfigPrintsOneFatalLine() throws Exception {
        Path f = tmp.newFile("bad.cfg").toPath();
        Files.write(f, Arrays.asList("scenario.1=a.json", "logPattern=%xyz"), StandardCharsets.UTF_8);
        PrintStream before = System.err;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        System.setErr(new PrintStream(bytes, true, "UTF-8"));
        try {
            SmokerAgent.parseConfig("config=" + f);
            fail();
        } catch (IllegalArgumentException e) {
            // expected
        } finally {
            System.setErr(before);
        }
        assertEquals("[smoker] fatal: smoker: logPattern: conversion word %xyz is not supported"
                + System.getProperty("line.separator"), new String(bytes.toByteArray(), StandardCharsets.UTF_8));
    }
}
