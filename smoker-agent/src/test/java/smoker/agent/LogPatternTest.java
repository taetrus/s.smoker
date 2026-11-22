package smoker.agent;

import org.junit.Test;

import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

public class LogPatternTest {

    private static long local(int y, int mo, int d, int h, int mi, int s, int ms) {
        Calendar c = new GregorianCalendar(y, mo - 1, d, h, mi, s);
        c.set(Calendar.MILLISECOND, ms);
        return c.getTimeInMillis();
    }

    private static final long NOW = local(2026, 11, 14, 22, 51, 30, 0);

    private static LogPattern.Match parse(String pattern, String line, long now) {
        LogPattern.Match m = LogPattern.compile(pattern).match(line, now);
        assertNotNull("line must agree: " + line, m);
        return m;
    }

    @Test
    public void defaultPatternParsesAPilotLine() {
        LogPattern.Match m = parse(LogPattern.DEFAULT,
                "22:51:29.827 [AWT-EventQueue-0] ERROR com.kk.pde.ds.spike.master.MasterApp - App-1 crashing on purpose", NOW);
        assertEquals(LogLevel.ERROR, m.level);
        assertEquals("AWT-EventQueue-0", m.thread);
        assertEquals("com.kk.pde.ds.spike.master.MasterApp", m.logger);
        assertEquals("App-1 crashing on purpose", m.msg);
        assertEquals(local(2026, 11, 14, 22, 51, 29, 827), m.time);
    }

    @Test
    public void threadWithSpacesAndColons() {
        LogPattern.Match m = parse(LogPattern.DEFAULT,
                "22:48:04.573 [Start Level: Equinox Container: f41427cf-3b9c] INFO  com.kk.pde.ds.imp.Greet - Greet.start()", NOW);
        assertEquals("Start Level: Equinox Container: f41427cf-3b9c", m.thread);
        assertEquals(LogLevel.INFO, m.level);
        assertEquals("Greet.start()", m.msg);
    }

    @Test
    public void iso8601DefaultDate() {
        long expected = local(2026, 11, 20, 10, 0, 1, 234);
        long now = local(2026, 11, 20, 10, 0, 2, 0);
        LogPattern.Match m = parse("%d %-5level %logger - %msg%n", "2026-11-20 10:00:01,234 WARN  a.b - x", now);
        assertEquals(expected, m.time);
        assertEquals(LogLevel.WARN, m.level);
        m = parse("%date{ISO8601} %-5level %logger - %msg%n", "2026-11-20 10:00:01,234 WARN  a.b - x", now);
        assertEquals(expected, m.time);
    }

    @Test
    public void colorWordsUseTheInnerPattern() {
        LogPattern.Match m = parse("%d{HH:mm:ss.SSS} %highlight(%-5level) %cyan(%logger{15}) - %msg%n",
                "10:00:00.000 ERROR a.B - boom", local(2026, 11, 14, 10, 0, 1, 0));
        assertEquals(LogLevel.ERROR, m.level);
        assertEquals("a.B", m.logger);
        assertEquals("boom", m.msg);
    }

    @Test
    public void exceptionWordsMatchNothing() {
        LogPattern.Match m = parse("%d{HH:mm:ss.SSS} %level %msg%n%ex", "10:00:00.000 INFO hi",
                local(2026, 11, 14, 10, 0, 1, 0));
        assertEquals(LogLevel.INFO, m.level);
        assertEquals("hi", m.msg);
    }

    @Test
    public void unknownWordIsRefused() {
        try {
            LogPattern.compile("%d %xyz %msg");
            fail();
        } catch (IllegalArgumentException e) {
            assertEquals("smoker: logPattern: conversion word %xyz is not supported", e.getMessage());
        }
        try {
            LogPattern.compile("%d{dd MMM yyyy} %msg");
            fail();
        } catch (IllegalArgumentException expected) {
            // date letter MMM
        }
    }

    @Test
    public void lineThatDoesNotAgreeGivesNull() {
        LogPattern p = LogPattern.compile(LogPattern.DEFAULT);
        assertNull(p.match("!ENTRY org.eclipse.osgi 4 0 2026-11-14 22:51:28.927", NOW));
        assertNull(p.match("Exception handler ready", NOW));
        assertNull(p.match("\tat a.B.c(B.java:1)", NOW));
    }

    @Test
    public void timeAfterNowIsFromTheDayBefore() {
        long now = local(2026, 11, 15, 0, 0, 10, 0);
        String pattern = "%d{HH:mm:ss.SSS} %msg%n";
        assertEquals(local(2026, 11, 14, 23, 59, 59, 0), parse(pattern, "23:59:59.000 x", now).time);
        assertEquals(local(2026, 11, 15, 0, 0, 5, 0), parse(pattern, "00:00:05.000 x", now).time);
    }

    @Test
    public void precision() {
        assertEquals(1, LogPattern.compile(LogPattern.DEFAULT).precisionMs());
        assertEquals(1000, LogPattern.compile("%d{HH:mm:ss} %msg%n").precisionMs());
    }

    @Test
    public void levelParseUsesLocaleRoot() {
        Locale old = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            assertEquals(LogLevel.INFO, LogLevel.parse("info"));
        } finally {
            Locale.setDefault(old);
        }
    }

    @Test
    public void quotedLiteralInDateFormatKeepsItsQuotes() {
        LogPattern.Match m = parse("%d{'T'HH:mm:ss.SSS} %msg%n", "T10:00:00.000 hi", local(2026, 11, 14, 10, 0, 1, 0));
        assertEquals("hi", m.msg);
        assertEquals(local(2026, 11, 14, 10, 0, 0, 0), m.time);
    }

    @Test
    public void dateTimeZoneIsRefused() {
        for (String p : new String[] {"%d{HH:mm:ss.SSS, UTC} %msg%n", "%d{HH:mm:ss.SSS}{UTC} %msg%n"}) {
            try {
                LogPattern.compile(p);
                fail(p);
            } catch (IllegalArgumentException e) {
                assertEquals("smoker: logPattern: %d with a time zone or locale is not supported", e.getMessage());
            }
        }
    }

    @Test
    public void ofJulMapsLevels() {
        assertEquals(LogLevel.ERROR, LogLevel.ofJul(java.util.logging.Level.SEVERE));
        assertEquals(LogLevel.WARN, LogLevel.ofJul(java.util.logging.Level.WARNING));
        assertEquals(LogLevel.INFO, LogLevel.ofJul(java.util.logging.Level.INFO));
        assertEquals(LogLevel.DEBUG, LogLevel.ofJul(java.util.logging.Level.FINE));
    }

    @Test
    public void ofEquinoxSeverityMapsValues() {
        assertEquals(LogLevel.INFO, LogLevel.ofEquinoxSeverity(1));
        assertEquals(LogLevel.WARN, LogLevel.ofEquinoxSeverity(2));
        assertEquals(LogLevel.ERROR, LogLevel.ofEquinoxSeverity(4));
        assertEquals(LogLevel.ERROR, LogLevel.ofEquinoxSeverity(8));
        assertNull(LogLevel.ofEquinoxSeverity(0));
    }
}
