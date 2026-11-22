package smoker.agent;

import org.junit.After;
import org.junit.Test;
import smoker.core.model.RunResult;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** WP-04, F-22: the tee assembles the console lines of each writing thread apart. */
public class ConsoleTeeTest {

    private final ExecutorService a = Executors.newSingleThreadExecutor();
    private final ExecutorService b = Executors.newSingleThreadExecutor();

    @After
    public void stop() throws Exception {
        a.shutdownNow();
        b.shutdownNow();
        a.awaitTermination(5, TimeUnit.SECONDS);
        b.awaitTermination(5, TimeUnit.SECONDS);
    }

    /** Writes {@code text} in one write call on the thread of {@code on}, and waits for the call to end. */
    private static void write(ExecutorService on, final PrintStream tee, final String text) throws Exception {
        on.submit(new Runnable() {
            @Override
            public void run() {
                byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
                tee.write(bytes, 0, bytes.length);
            }
        }).get(5, TimeUnit.SECONDS);
    }

    @Test(timeout = 10000)
    public void aLineOfAnotherThreadDoesNotSplitAnEquinoxEntry() throws Exception {
        Observations obs = new Observations(new AgentConfig());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream tee = obs.tee(new PrintStream(bytes, true, "UTF-8"), StandardCharsets.UTF_8, "stdout");
        String entry = "!ENTRY org.eclipse.osgi 4 0 ";
        String info = "15:58:44.333 [AWT-EventQueue-0] INFO  com.kk.pde.ds.spike.master.MasterApp - App-1 building\n";
        String time = "2026-11-22 15:58:44.333\n";
        String message = "!MESSAGE Bundle initial@reference:file:org.eclipse.core.jobs_3.15.600.v20250513-1234.jar"
                + " was not resolved.\n";
        write(a, tee, entry);
        write(b, tee, info);
        write(a, tee, time);
        write(a, tee, message);
        write(a, tee, "\n");
        List<RunResult.LogHit> hits = obs.takeHits(Long.MIN_VALUE, Long.MAX_VALUE);
        assertEquals(hits.toString(), 1, hits.size());
        assertEquals("stdout", hits.get(0).source);
        assertEquals("!ENTRY org.eclipse.osgi 4 0 2026-11-22 15:58:44.333", hits.get(0).line);
        assertTrue(hits.get(0).text, hits.get(0).text.contains("was not resolved"));
        // the real stream gets the bytes in the original order
        assertEquals(entry + info + time + message + "\n", new String(bytes.toByteArray(), StandardCharsets.UTF_8));
    }

    @Test(timeout = 10000)
    public void interleavedLogbackEventsStayApart() throws Exception {
        Observations obs = new Observations(new AgentConfig());
        PrintStream tee = obs.tee(new PrintStream(new ByteArrayOutputStream(), true, "UTF-8"),
                StandardCharsets.UTF_8, "stdout");
        String[][] parts = new String[2][];
        for (int t = 0; t < 2; t++) {
            parts[t] = new String[] {
                "10:00:00.00" + t + " [t" + t + "] ERROR a.B - fa",
                "iled " + t + "\njava.lang.IllegalStateException: x" + t + "\n\tat a.B.m" + t + "(B.java:1)\n",
                "\tat a.B.n" + t + "(B.java:2)\n",
                "\tat a.B.o" + t + "(B.java:3)\n",
            };
        }
        for (int i = 0; i < parts[0].length; i++) {
            write(a, tee, parts[0][i]);
            write(b, tee, parts[1][i]);
        }
        List<RunResult.LogHit> hits = obs.takeHits(Long.MIN_VALUE, Long.MAX_VALUE);
        assertEquals(hits.toString(), 2, hits.size());
        for (RunResult.LogHit h : hits) {
            int t = h.line.contains("[t0]") ? 0 : 1;
            assertEquals("10:00:00.00" + t + " [t" + t + "] ERROR a.B - failed " + t, h.line);
            assertEquals(h.line + "\njava.lang.IllegalStateException: x" + t + "\n\tat a.B.m" + t + "(B.java:1)\n"
                    + "\tat a.B.n" + t + "(B.java:2)\n\tat a.B.o" + t + "(B.java:3)", h.text);
        }
        assertTrue(hits.get(0).line.contains("[t0]") != hits.get(1).line.contains("[t0]"));
    }

    @Test(timeout = 10000)
    public void drainReleasesTheStateOfAnIdleThread() throws Exception {
        Observations obs = new Observations(new AgentConfig());
        ConsoleTee tee = obs.tee(new PrintStream(new ByteArrayOutputStream(), true, "UTF-8"),
                StandardCharsets.UTF_8, "stdout");
        write(a, tee, "INFO full line\n");
        write(b, tee, "ERROR partial"); // no line feed: thread b stays alive
        assertEquals(2, tee.threads());
        tee.drain();
        assertEquals(1, tee.threads());
        assertTrue(obs.takeHits(Long.MIN_VALUE, Long.MAX_VALUE).isEmpty());
        write(b, tee, " line\n");
        tee.drain();
        assertEquals(0, tee.threads());
        List<RunResult.LogHit> hits = obs.takeHits(Long.MIN_VALUE, Long.MAX_VALUE);
        assertEquals(1, hits.size());
        assertEquals("ERROR partial line", hits.get(0).line);
    }

    @Test(timeout = 10000)
    public void drainGivesThePartialLineOfAThreadThatEnded() throws Exception {
        Observations obs = new Observations(new AgentConfig());
        final ConsoleTee tee = obs.tee(new PrintStream(new ByteArrayOutputStream(), true, "UTF-8"),
                StandardCharsets.UTF_8, "stdout");
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                tee.print("ERROR last words");
            }
        });
        t.start();
        t.join();
        assertEquals(1, tee.threads());
        List<RunResult.LogHit> hits = obs.takeHits(Long.MIN_VALUE, Long.MAX_VALUE);
        assertEquals(0, tee.threads());
        assertEquals(1, hits.size());
        assertEquals("ERROR last words", hits.get(0).line);
    }
}
