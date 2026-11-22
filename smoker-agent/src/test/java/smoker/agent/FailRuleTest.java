package smoker.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.junit.Test;

public class FailRuleTest {

    private static final Pattern FAIL = Pattern.compile("\\b(ERROR|SEVERE|FATAL)\\b");
    private final FailRule rule = new FailRule(LogLevel.ERROR, FAIL, null);

    private List<LogEvent> join(String... lines) {
        final List<LogEvent> events = new ArrayList<LogEvent>();
        EventJoiner joiner = new EventJoiner("app", LogPattern.compile(LogPattern.DEFAULT), true,
                new Consumer<LogEvent>() {
                    @Override
                    public void accept(LogEvent e) {
                        events.add(e);
                    }
                });
        for (String line : lines) {
            joiner.line(line, System.currentTimeMillis());
        }
        joiner.close();
        return events;
    }

    private int hits(FailRule r, List<String> lines) {
        int n = 0;
        for (LogEvent e : join(lines.toArray(new String[0]))) {
            if (r.isHit(e)) {
                n++;
            }
        }
        return n;
    }

    @Test
    public void sampleFilesGiveTheExpectedHits() throws IOException {
        assertEquals(29, hits(rule, EventJoinerTest.sample("spike-crash-app-output.log")));
        assertEquals(28, hits(rule, EventJoinerTest.sample("spike-hang-app-output.log")));
        assertEquals(28, hits(rule, EventJoinerTest.sample("spike-configuration.log")));
        assertEquals(28, hits(rule, EventJoinerTest.sample("spike-crash1-configuration.log")));
        assertEquals(0, hits(rule, EventJoinerTest.sample("p2-app-output.log")));
        int equinox = 0;
        int logback = 0;
        for (LogEvent e : join(EventJoinerTest.sample("spike-crash-app-output.log").toArray(new String[0]))) {
            if (rule.isHit(e)) {
                if (e.kind == LogEvent.Kind.EQUINOX) {
                    equinox++;
                } else if (e.kind == LogEvent.Kind.LOGBACK && e.firstLine().contains("crashing on purpose")) {
                    logback++;
                }
            }
        }
        assertEquals(28, equinox);
        assertEquals(1, logback);
    }

    @Test
    public void normalTextIsNoHit() {
        assertFalse(rule.isHit(join("10:00:00.001 [main] INFO  a.B - Error count 0 of 3").get(0)));
        assertFalse(rule.isHit(join("Exception handler ready").get(0)));
        assertFalse(rule.isHit(join("No Error found").get(0)));
        assertTrue(rule.isHit(join("FATAL disk").get(0)));
    }

    @Test
    public void warnWithTraceFollowsFailLevel() {
        LogEvent e = join("10:00:00.001 [main] WARN  a.B - slow", "java.lang.Exception: x", "\tat a.B.m(B.java:1)").get(0);
        assertFalse(rule.isHit(e));
        assertTrue(new FailRule(LogLevel.WARN, FAIL, null).isHit(e));
    }

    @Test
    public void equinoxWarningHitsOnlyWhenFailLevelIsWarn() {
        LogEvent e = join("!ENTRY a.b 2 0 2026-11-14 22:51:28.788", "!MESSAGE warn").get(0);
        assertFalse(rule.isHit(e));
        assertTrue(new FailRule(LogLevel.WARN, FAIL, null).isHit(e));
        LogEvent info = join("!ENTRY a.b 1 0 2026-11-14 22:51:28.788", "!MESSAGE info").get(0);
        assertFalse(new FailRule(LogLevel.WARN, FAIL, null).isHit(info));
    }

    @Test
    public void julAndUncaught() {
        LogEvent severe = new LogEvent("jul", LogEvent.Kind.JUL, 0, 1, LogLevel.ERROR, Arrays.asList("SEVERE x"));
        LogEvent info = new LogEvent("jul", LogEvent.Kind.JUL, 0, 1, LogLevel.INFO, Arrays.asList("INFO x"));
        LogEvent thrown = new LogEvent("jul", LogEvent.Kind.JUL, 0, 1, LogLevel.INFO, Arrays.asList("INFO x"));
        thrown.hasStackTrace = true;
        LogEvent dead = new LogEvent("thread", LogEvent.Kind.UNCAUGHT, 0, 1, null, Arrays.asList("died"));
        assertTrue(rule.isHit(severe));
        assertFalse(rule.isHit(info));
        assertTrue(rule.isHit(thrown));
        assertTrue(rule.isHit(dead));
    }

    @Test
    public void allowRegexWins() {
        FailRule allow = new FailRule(LogLevel.ERROR, FAIL, Pattern.compile("known issue"));
        List<LogEvent> events = join("10:00:00.001 [main] ERROR a.B - failed", "java.lang.Exception: known issue",
                "\tat a.B.m(B.java:1)");
        assertTrue(rule.isHit(events.get(0)));
        assertFalse(allow.isHit(events.get(0)));
    }

    @Test
    public void textIsLimited() {
        List<String> lines = new ArrayList<String>();
        for (int i = 0; i < 200; i++) {
            lines.add("line " + i);
        }
        String text = new LogEvent("a", LogEvent.Kind.PLAIN, 0, 1, null, lines).text();
        assertEquals(50, text.split("\n").length);
        char[] big = new char[20000];
        Arrays.fill(big, 'x');
        String longText = new LogEvent("a", LogEvent.Kind.PLAIN, 0, 1, null,
                Arrays.asList(new String(big))).text();
        assertTrue(longText.length() <= 8000);
    }
}
