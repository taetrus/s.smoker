package smoker.core.io;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ConfigFileTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Path file(String... lines) throws Exception {
        Path f = tmp.getRoot().toPath().resolve("agent.cfg");
        Files.write(f, Arrays.asList(lines), StandardCharsets.UTF_8);
        return f;
    }

    @Test
    public void readKeepsBackslashesAndSeparators() throws Exception {
        String out = "C:\\tools\\new dir;a=b #1";
        String re = "\\b(ERROR|SEVERE|FATAL)\\b";
        Path f = file("out=" + out, "failRegex=" + re);
        Map<String, String> m = ConfigFile.read(f);
        assertEquals(out, m.get("out"));
        assertEquals(out.length(), m.get("out").length());
        assertEquals(re, m.get("failRegex"));
    }

    @Test
    public void readStripsAUtf8Bom() throws Exception {
        Path f = tmp.getRoot().toPath().resolve("bom.cfg");
        Files.write(f, "\uFEFFa=1\nb=2\n".getBytes(StandardCharsets.UTF_8));
        Map<String, String> m = ConfigFile.read(f);
        assertEquals("1", m.get("a"));
        assertEquals("2", m.get("b"));
    }

    @Test
    public void readSkipsCommentsAndEmptyLines() throws Exception {
        Map<String, String> m = ConfigFile.read(file("# comment", "", "  ", "a=1", "  # other", "b = 2 "));
        assertEquals(2, m.size());
        assertEquals("1", m.get("a"));
        assertEquals("2", m.get("b"));
    }

    @Test
    public void readFirstEqualsEndsTheKey() throws Exception {
        Map<String, String> m = ConfigFile.read(file("logPattern=%d %msg%n=x"));
        assertEquals("%d %msg%n=x", m.get("logPattern"));
    }

    @Test
    public void readRefusesALineWithoutEquals() throws Exception {
        Path f = file("a=1", "oops");
        try {
            ConfigFile.read(f);
            fail();
        } catch (IllegalArgumentException e) {
            assertEquals(f + ":2: no '=' in the line", e.getMessage());
        }
    }

    @Test
    public void readRefusesADuplicateKey() throws Exception {
        try {
            ConfigFile.read(file("a=1", "a=2"));
            fail();
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("a"));
        }
    }

    @Test
    public void writeRefusesALineBreak() throws Exception {
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put("k", "a\nb");
        try {
            ConfigFile.write(tmp.getRoot().toPath().resolve("x.cfg"), m);
            fail();
        } catch (IllegalArgumentException e) {
            assertEquals("k: a value cannot contain a line break", e.getMessage());
        }
    }

    @Test
    public void writeThenReadRoundTripsInUtf8() throws Exception {
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put("name", "\u00c7a\u011fr\u0131");
        m.put("out", "C:\\a b;c=d");
        Path f = tmp.getRoot().toPath().resolve("x.cfg");
        ConfigFile.write(f, m);
        assertEquals(m, ConfigFile.read(f));
    }
}
