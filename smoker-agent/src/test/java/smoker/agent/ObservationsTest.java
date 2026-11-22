package smoker.agent;

import org.junit.Test;
import smoker.core.model.RunResult;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** WP-04, F-08: hits go to contiguous step windows; the console tee reads lines without a deadlock. */
public class ObservationsTest {

    private static LogEvent event(long time, int precisionMs, String line) {
        return new LogEvent("t", LogEvent.Kind.PLAIN, time, precisionMs, null,
                new ArrayList<String>(Collections.singletonList(line)));
    }

    private static List<String> describe(List<RunResult.LogHit> hits) {
        List<String> out = new ArrayList<String>();
        for (RunResult.LogHit h : hits) out.add(h.source + "@" + h.timestamp);
        return out;
    }

    @Test
    public void takeHitsUsesContiguousWindows() {
        Observations obs = new Observations(new AgentConfig());
        obs.accept(event(100, 1, "ERROR a"));
        obs.accept(event(200, 1, "ERROR b"));
        obs.accept(event(300, 1, "ERROR c"));
        assertEquals(Arrays.asList("t@100"), describe(obs.takeHits(Long.MIN_VALUE, 150)));
        assertEquals(Arrays.asList("t@200"), describe(obs.takeHits(150, 250)));
        obs.accept(event(120, 1, "ERROR late"));
        assertEquals(Arrays.asList("late:t@120", "t@300"), describe(obs.takeHits(250, 400)));
        assertTrue(obs.takeHits(400, 1000).isEmpty());
    }

    @Test
    public void secondPrecisionIsNotLate() {
        Observations obs = new Observations(new AgentConfig());
        obs.accept(event(5000, 1000, "ERROR a"));
        assertEquals(Arrays.asList("t@5000"), describe(obs.takeHits(5300, 6000)));
    }

    @Test
    public void hitKeepsFirstLineAndFullText() {
        Observations obs = new Observations(new AgentConfig());
        obs.accept(new LogEvent("t", LogEvent.Kind.PLAIN, 10, 1, null,
                new ArrayList<String>(Arrays.asList("ERROR a", "\tat x.y(Z.java:1)"))));
        obs.accept(event(20, 1, "INFO no hit"));
        List<RunResult.LogHit> hits = obs.takeHits(Long.MIN_VALUE, 100);
        assertEquals(1, hits.size());
        assertEquals("ERROR a", hits.get(0).line);
        assertEquals("ERROR a\n\tat x.y(Z.java:1)", hits.get(0).text);
    }

    @Test
    public void smokerLinesAreIgnored() throws Exception {
        Observations obs = new Observations(new AgentConfig());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream tee = obs.tee(new PrintStream(bytes, true, "UTF-8"), StandardCharsets.UTF_8, "stdout");
        tee.println("[smoker] step s001 ERROR");
        assertTrue(obs.takeHits(Long.MIN_VALUE, Long.MAX_VALUE).isEmpty());
        assertEquals("[smoker] step s001 ERROR" + System.getProperty("line.separator"),
                new String(bytes.toByteArray(), StandardCharsets.UTF_8));
    }

    @Test
    public void teeDecodesTheCharset() throws Exception {
        // Windows-1254 (Turkish ANSI code page) holds the Turkish letters; windows-1252 does not.
        Charset cs = Charset.forName("windows-1254");
        Observations obs = new Observations(new AgentConfig());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream tee = obs.tee(new PrintStream(bytes, true, cs.name()), cs, "stdout");
        byte[] line = "ERROR şık\n".getBytes(cs);
        tee.write(line, 0, line.length);
        List<RunResult.LogHit> hits = obs.takeHits(Long.MIN_VALUE, Long.MAX_VALUE);
        assertEquals(1, hits.size());
        assertEquals("ERROR şık", hits.get(0).line);
        assertEquals("stdout", hits.get(0).source);
        assertArrayEquals(line, bytes.toByteArray());
    }

    @Test
    public void teeSeesSingleBytesAndCarriageReturns() throws Exception {
        Observations obs = new Observations(new AgentConfig());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream tee = obs.tee(new PrintStream(bytes, true, "UTF-8"), StandardCharsets.UTF_8, "stderr");
        byte[] line = "SEVERE: x\r\n".getBytes(StandardCharsets.UTF_8);
        for (byte b : line) tee.write(b);
        List<RunResult.LogHit> hits = obs.takeHits(Long.MIN_VALUE, Long.MAX_VALUE);
        assertEquals(1, hits.size());
        assertEquals("SEVERE: x", hits.get(0).line);
        assertArrayEquals(line, bytes.toByteArray());
    }

    private static String repeat(char c, int n) {
        StringBuilder b = new StringBuilder(n);
        for (int i = 0; i < n; i++) b.append(c);
        return b.toString();
    }

    @Test
    public void aThrowingSinkNeverReachesThePrintCall() throws Exception {
        for (final Error error : new Error[] {null, new StackOverflowError("user regex")}) {
            Consumer<LogEvent> sink = new Consumer<LogEvent>() {
                @Override
                public void accept(LogEvent e) {
                    if (error != null) throw error;
                    throw new IllegalStateException("fail rule");
                }
            };
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ConsoleTee tee = new ConsoleTee(new PrintStream(bytes, true, "UTF-8"), StandardCharsets.UTF_8,
                    new EventJoiner("stdout", LogPattern.compile(LogPattern.DEFAULT), false, sink));
            tee.println("ERROR one");
            tee.println("ERROR two"); // closes "ERROR one": the sink throws
            tee.drain();              // closes "ERROR two": the sink throws
            String nl = System.getProperty("line.separator");
            assertEquals("ERROR one" + nl + "ERROR two" + nl, new String(bytes.toByteArray(), StandardCharsets.UTF_8));
        }
    }

    @Test
    public void teeGivesALongLineInParts() throws Exception {
        Observations obs = new Observations(new AgentConfig());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream tee = obs.tee(new PrintStream(bytes, true, "UTF-8"), StandardCharsets.UTF_8, "stdout");
        String text = "ERROR " + repeat('a', 100 * 1024); // no line feed
        tee.print(text);
        List<RunResult.LogHit> hits = obs.takeHits(Long.MIN_VALUE, Long.MAX_VALUE);
        assertEquals(1, hits.size());
        assertTrue(hits.get(0).line.startsWith("ERROR aaa"));
        assertEquals(2001, hits.get(0).line.length());
        assertEquals(text, new String(bytes.toByteArray(), StandardCharsets.UTF_8));
    }

    @Test(timeout = 5000)
    public void printWhileDrainingDoesNotDeadlock() throws Exception {
        final Observations obs = new Observations(new AgentConfig());
        final PrintStream tee = obs.tee(new PrintStream(new ByteArrayOutputStream(), true, "UTF-8"),
                StandardCharsets.UTF_8, "stdout");
        final AtomicInteger total = new AtomicInteger();
        final boolean[] printed = {false};
        Thread printer = new Thread(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < 1000; i++) tee.println("ERROR line " + i);
                synchronized (printed) {
                    printed[0] = true;
                }
            }
        });
        Thread taker = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    boolean done;
                    synchronized (printed) {
                        done = printed[0];
                    }
                    total.addAndGet(obs.takeHits(Long.MIN_VALUE, Long.MAX_VALUE).size());
                    if (done) return;
                }
            }
        });
        printer.start();
        taker.start();
        printer.join();
        taker.join();
        assertFalse(printer.isAlive() || taker.isAlive());
        assertEquals(1000, total.get());
    }
}
