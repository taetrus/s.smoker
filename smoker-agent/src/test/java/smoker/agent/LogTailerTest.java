package smoker.agent;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import smoker.core.model.RunResult;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** WP-04, F-08, R-10a: a log file is read to its end before a step closes. */
public class LogTailerTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Observations obs;
    private Path file;

    @Before
    public void setUp() throws Exception {
        obs = new Observations(new AgentConfig());
        file = tmp.getRoot().toPath().resolve("app.log");
    }

    @After
    public void tearDown() {
        obs.stop();
    }

    private void append(String text, Charset cs) throws Exception {
        Files.write(file, text.getBytes(cs), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private List<RunResult.LogHit> all() {
        return obs.takeHits(Long.MIN_VALUE, Long.MAX_VALUE);
    }

    @Test(timeout = 20000)
    public void r10aHundredRepetitions() throws Exception {
        append("", StandardCharsets.UTF_8);
        obs.tail(file, StandardCharsets.UTF_8);
        SimpleDateFormat hms = new SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT);
        for (int i = 0; i < 100; i++) {
            long from = System.currentTimeMillis();
            long t;
            do {
                t = System.currentTimeMillis();
            } while (t <= from); // window (from, to]: the line time must be after from
            append(hms.format(new Date(t)) + " [main] ERROR t - step " + i + "\n", StandardCharsets.UTF_8);
            List<RunResult.LogHit> hits = obs.takeHits(from, System.currentTimeMillis());
            assertEquals("repetition " + i, 1, hits.size());
            assertTrue(hits.get(0).line, hits.get(0).line.endsWith(" - step " + i));
            assertEquals("file:app.log", hits.get(0).source);
        }
    }

    @Test
    public void fileCreatedAfterStartIsReadFromZero() throws Exception {
        obs.tail(file, StandardCharsets.UTF_8);
        append("ERROR one\nERROR two\n", StandardCharsets.UTF_8);
        assertEquals(2, all().size());
    }

    @Test
    public void existingFileIsReadFromItsEnd() throws Exception {
        append("ERROR one\nERROR two\nERROR three\n", StandardCharsets.UTF_8);
        obs.tail(file, StandardCharsets.UTF_8);
        assertTrue(all().isEmpty());
        append("ERROR four\n", StandardCharsets.UTF_8);
        List<RunResult.LogHit> hits = all();
        assertEquals(1, hits.size());
        assertEquals("ERROR four", hits.get(0).line);
    }

    @Test
    public void rotatedFileIsReadFromTheStart() throws Exception {
        obs.tail(file, StandardCharsets.UTF_8);
        append("ERROR one, a long line\nERROR two, a long line\n", StandardCharsets.UTF_8);
        assertEquals(2, all().size());
        Files.write(file, "ERROR new\n".getBytes(StandardCharsets.UTF_8), StandardOpenOption.TRUNCATE_EXISTING);
        List<RunResult.LogHit> hits = all();
        assertEquals(1, hits.size());
        assertEquals("ERROR new", hits.get(0).line);
        assertTrue(all().isEmpty());
    }

    @Test
    public void charset() throws Exception {
        // Windows-1254 (Turkish ANSI code page) holds the letter; windows-1252 does not.
        Charset cs = Charset.forName("windows-1254");
        obs.tail(file, cs);
        append("ERROR ğ\r\n", cs);
        List<RunResult.LogHit> hits = all();
        assertEquals(1, hits.size());
        assertEquals("ERROR ğ", hits.get(0).line);
        assertFalse(hits.get(0).source.startsWith("late:"));
    }
}
