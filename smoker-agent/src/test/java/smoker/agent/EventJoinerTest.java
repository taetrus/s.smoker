package smoker.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import org.junit.Test;

public class EventJoinerTest {

    static final String ERROR_LINE = "10:00:00.001 [main] ERROR a.B - failed";
    static final String INFO_LINE = "10:00:00.002 [main] INFO  a.B - fine";

    private final List<LogEvent> events = new ArrayList<LogEvent>();
    private final EventJoiner joiner = new EventJoiner("app", LogPattern.compile(LogPattern.DEFAULT), true,
            new Consumer<LogEvent>() {
                @Override
                public void accept(LogEvent e) {
                    events.add(e);
                }
            });

    private void feed(String... lines) {
        for (String line : lines) {
            joiner.line(line, System.currentTimeMillis());
        }
    }

    static List<String> sample(String name) throws IOException {
        return Files.readAllLines(Paths.get("src/test/resources/wp04/" + name), Charset.forName("windows-1252"));
    }

    @Test
    public void logbackErrorWith40TraceLinesIsOneEvent() {
        feed(ERROR_LINE, "java.lang.IllegalStateException: boom");
        for (int i = 0; i < 40; i++) {
            feed("\tat a.B.m(B.java:" + i + ")");
        }
        feed(INFO_LINE);
        joiner.close();
        assertEquals(2, events.size());
        assertEquals(42, events.get(0).lines.size());
        assertTrue(events.get(0).hasStackTrace);
        assertEquals(LogLevel.ERROR, events.get(0).level);
        assertEquals(LogEvent.Kind.LOGBACK, events.get(0).kind);
        assertEquals(LogLevel.INFO, events.get(1).level);
    }

    @Test
    public void causedByAndMoreJoin() {
        feed(ERROR_LINE, "java.lang.RuntimeException: x", "\tat a.B.m(B.java:1)",
                "Caused by: java.io.IOException: x", "\tat a.B.n(B.java:2)", "\t... 12 more",
                "\tSuppressed: java.lang.Exception: s", "\t... 3 more");
        joiner.close();
        assertEquals(1, events.size());
        assertEquals(8, events.get(0).lines.size());
    }

    @Test
    public void equinoxEntryWithEmptyLineInsideIsOneEvent() throws IOException {
        List<String> lines = sample("spike-crash-app-output.log");
        for (String line : lines.subList(0, 31)) {
            joiner.line(line, 0);
        }
        joiner.close();
        assertEquals(2, events.size());
        LogEvent session = events.get(0);
        assertEquals(LogEvent.Kind.EQUINOX, session.kind);
        assertNull(session.level);
        LogEvent entry = events.get(1);
        assertEquals(LogEvent.Kind.EQUINOX, entry.kind);
        assertEquals(LogLevel.ERROR, entry.level);
        assertEquals(22, entry.lines.size());
        assertTrue(entry.text().contains("Unresolved requirement"));
        assertEquals(1, entry.precisionMs);
        assertEquals(time("2026-11-14 22:51:28.788"), entry.time);
    }

    @Test
    public void shortEquinoxEntries() {
        feed("!ENTRY org.eclipse.osgi 4 0 2026-11-14 22:51:28.927",
                "!MESSAGE Bundle x was not resolved.", "",
                "!ENTRY org.eclipse.osgi 4 0 2026-11-14 22:51:28.928",
                "!MESSAGE Bundle y was not resolved.");
        joiner.close();
        assertEquals(2, events.size());
        assertEquals(2, events.get(0).lines.size());
    }

    @Test
    public void plainPrintlnLines() {
        feed("Exception handler ready", "hello");
        joiner.close();
        assertEquals(2, events.size());
        assertEquals(LogEvent.Kind.PLAIN, events.get(0).kind);
        assertEquals(LogEvent.Kind.PLAIN, events.get(1).kind);
        assertFalse(events.get(0).hasStackTrace);
    }

    @Test
    public void printStackTraceIsOnePlainEventWithTrace() {
        feed("java.lang.IllegalStateException: print demo", "\tat a.B.m(B.java:1)", "\tat a.B.n(B.java:2)",
                "\tat a.B.o(B.java:3)", "Caused by: java.io.IOException: cause", "\t... 3 more");
        joiner.close();
        assertEquals(1, events.size());
        assertEquals(LogEvent.Kind.PLAIN, events.get(0).kind);
        assertTrue(events.get(0).hasStackTrace);
    }

    @Test
    public void plainTextAfterLogbackLineStartsANewEvent() {
        feed(INFO_LINE, "java.util.Date loaded");
        joiner.close();
        assertEquals(2, events.size());
        assertEquals(LogEvent.Kind.PLAIN, events.get(1).kind);
    }

    @Test
    public void continuationAfterCloseStartsAPlainHit() {
        feed(ERROR_LINE);
        joiner.close();
        feed("\tat a.B.m(B.java:1)", "\tat a.B.n(B.java:2)", "\tat a.B.o(B.java:3)");
        joiner.close();
        assertEquals(2, events.size());
        assertEquals(LogEvent.Kind.PLAIN, events.get(1).kind);
        assertTrue(events.get(1).hasStackTrace);
    }

    @Test
    public void smokerLineClosesTheEventAndIsNoEvent() {
        feed(INFO_LINE, "[smoker] step s001", "\tat a.B.m(B.java:1)");
        joiner.close();
        assertEquals(2, events.size());
        assertEquals(LogEvent.Kind.LOGBACK, events.get(0).kind);
        assertEquals(LogEvent.Kind.PLAIN, events.get(1).kind);
    }

    @Test
    public void emptyLineBeforeNewEventIsDropped() {
        feed(INFO_LINE, "", ERROR_LINE);
        joiner.close();
        assertEquals(2, events.size());
        assertEquals(1, events.get(0).lines.size());
    }

    @Test
    public void fileEventsUseLineTimeStreamsUseArrival() {
        LogPattern dated = LogPattern.compile("%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level %msg%n");
        String line = "2026-11-14 22:51:28.788 ERROR boom";
        List<LogEvent> file = new ArrayList<LogEvent>();
        List<LogEvent> stream = new ArrayList<LogEvent>();
        EventJoiner a = new EventJoiner("f", dated, true, collect(file));
        EventJoiner b = new EventJoiner("s", dated, false, collect(stream));
        a.line(line, 5L);
        b.line(line, 5L);
        a.close();
        b.close();
        assertEquals(time("2026-11-14 22:51:28.788"), file.get(0).time);
        assertEquals(5L, stream.get(0).time);
        assertEquals(1, file.get(0).precisionMs);
    }

    private static Consumer<LogEvent> collect(final List<LogEvent> into) {
        return new Consumer<LogEvent>() {
            @Override
            public void accept(LogEvent e) {
                into.add(e);
            }
        };
    }

    static long time(String text) {
        try {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).parse(text).getTime();
        } catch (java.text.ParseException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
