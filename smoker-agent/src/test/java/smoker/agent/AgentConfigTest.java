package smoker.agent;

import org.junit.Test;

import java.nio.charset.Charset;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Run also with -Plocale-tr: values must parse the same in the Turkish locale (F-16). */
public class AgentConfigTest {

    @Test
    public void modeIsCaseInsensitive() {
        assertEquals(AgentConfig.Mode.PLAY, AgentConfig.parse("mode=play;scenario=a.json").mode);
        assertEquals(AgentConfig.Mode.RECORD, AgentConfig.parse("mode=Record;scenario=a.json").mode);
    }

    @Test
    public void screenshotsOffIsCaseInsensitive() {
        assertFalse(AgentConfig.parse("scenario=a.json;screenshots=OFF").screenshots);
        assertTrue(AgentConfig.parse("scenario=a.json;screenshots=evidence").screenshots);
    }

    @Test
    public void allKeysAreParsed() {
        AgentConfig c = AgentConfig.parse("mode=play;scenario=s.json;out=o;log=a.log, b.log;failRegex=FATAL;"
                + "allowRegex=noise;edtHangMs=900;exitWhenDone=false");
        assertEquals(Paths.get("s.json"), c.scenario);
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
        AgentConfig d = AgentConfig.parse("scenario=a.json");
        assertEquals("%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n", d.logPattern);
        assertEquals(LogLevel.ERROR, d.failLevel);
        assertEquals("\\b(ERROR|SEVERE|FATAL)\\b", d.failRegex);
        assertEquals(Charset.defaultCharset(), d.logCharset);

        AgentConfig c = AgentConfig.parse("scenario=a.json;logPattern=%d %msg%n;failLevel=warn;logCharset=windows-1252");
        assertEquals("%d %msg%n", c.logPattern);
        assertEquals(LogLevel.WARN, c.failLevel);
        assertEquals(Charset.forName("windows-1252"), c.logCharset);
    }

    @Test(expected = IllegalArgumentException.class)
    public void unknownFailLevelIsRefused() {
        AgentConfig.parse("scenario=a.json;failLevel=LOUD");
    }

    @Test(expected = IllegalArgumentException.class)
    public void unsupportedLogPatternIsRefused() {
        AgentConfig.parse("scenario=a.json;logPattern=%xyz");
    }

    @Test(expected = IllegalArgumentException.class)
    public void scenarioIsRequired() {
        AgentConfig.parse("mode=play");
    }

    @Test(expected = IllegalArgumentException.class)
    public void emptyArgumentsAreRefused() {
        AgentConfig.parse(" ");
    }
}
