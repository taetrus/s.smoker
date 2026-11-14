package smoker.agent;

import org.junit.Test;

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

    @Test(expected = IllegalArgumentException.class)
    public void scenarioIsRequired() {
        AgentConfig.parse("mode=play");
    }

    @Test(expected = IllegalArgumentException.class)
    public void emptyArgumentsAreRefused() {
        AgentConfig.parse(" ");
    }
}
