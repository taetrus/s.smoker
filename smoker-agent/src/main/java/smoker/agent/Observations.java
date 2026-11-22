package smoker.agent;

import smoker.core.model.RunResult;

import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Collects "clues" from every source we can reach without touching bundle classloaders (R-10..R-12):
 * tailed log files, System.out, System.err, java.util.logging and uncaught exceptions (incl. EDT).
 * Logback inside OSGi bundles is observed through its file or console output; see architecture.md for the
 * phase-2 bridge. Each closed event goes through the {@link FailRule} once; a hit waits for its step window.
 * <p>
 * Locks: {@link #lock} guards the hit list only. Never print and never take another lock while holding it:
 * a {@link ConsoleTee} calls {@link #accept} inside the lock of {@code System.out}.
 */
public final class Observations {

    private static final int MAX_LINE = 2000;

    private final Object lock = new Object();
    /** Guarded by {@link #lock}. */
    private final List<Collected> hits = new ArrayList<Collected>();
    private final LogPattern pattern;
    private final FailRule rule;
    private final List<LogTailer> tailers = new ArrayList<LogTailer>();
    private final List<ConsoleTee> tees = new ArrayList<ConsoleTee>();
    private final Consumer<LogEvent> sink = new Consumer<LogEvent>() {
        @Override
        public void accept(LogEvent e) {
            Observations.this.accept(e);
        }
    };

    public Observations(AgentConfig cfg) {
        this.pattern = LogPattern.compile(cfg.logPattern);
        this.rule = new FailRule(cfg.failLevel, Pattern.compile(cfg.failRegex),
                cfg.allowRegex == null ? null : Pattern.compile(cfg.allowRegex));
    }

    /** Installs all hooks. Call once, early. */
    public void install(AgentConfig cfg) {
        // 1. Uncaught exceptions on any thread (the EDT included — it uses the default handler since Java 7).
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                uncaught("[" + t.getName() + "] ", e);
                if (prev != null) prev.uncaughtException(t, e);
                else e.printStackTrace();
            }
        });
        // Java 8 still honours this legacy property for the EDT.
        System.setProperty("sun.awt.exception.handler", EdtExceptionHandler.class.getName());

        // 2. System.out and System.err tees.
        System.setOut(tee(System.out, ConsoleTee.streamCharset("stdout"), "stdout"));
        System.setErr(tee(System.err, ConsoleTee.streamCharset("stderr"), "stderr"));

        // 3. java.util.logging root handler: one event for each record.
        Logger.getLogger("").addHandler(new Handler() {
            @Override
            public void publish(LogRecord r) {
                String first = r.getLevel() + " " + r.getLoggerName() + " - " + r.getMessage()
                        + (r.getThrown() != null ? " " + r.getThrown() : "");
                LogEvent e = new LogEvent("jul", LogEvent.Kind.JUL, r.getMillis(), 1, LogLevel.ofJul(r.getLevel()),
                        lines(first, r.getThrown()));
                e.hasStackTrace = r.getThrown() != null;
                accept(e);
            }
            @Override public void flush() {}
            @Override public void close() {}
        });

        // 4. Log files (Logback/Log4j output).
        for (Path p : cfg.logFiles) tail(p, cfg.logCharset);
    }

    /** Public static hook for the legacy sun.awt.exception.handler mechanism. */
    public static final class EdtExceptionHandler {
        public void handle(Throwable t) {
            Observations o = SmokerAgent.observations();
            if (o != null) o.uncaught("[AWT-EventQueue] ", t);
            t.printStackTrace();
        }
    }

    /** Wraps one console stream; its own joiner reads the lines (source {@code stdout} or {@code stderr}). */
    ConsoleTee tee(PrintStream real, Charset cs, String source) {
        try {
            ConsoleTee tee = new ConsoleTee(real, cs, new EventJoiner(source, pattern, false, sink));
            synchronized (tees) {
                tees.add(tee);
            }
            return tee;
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e); // a Charset always has a supported name
        }
    }

    /** Starts a tailer for one log file; its own joiner reads the lines with their log time. */
    LogTailer tail(Path file, Charset cs) {
        LogTailer t = new LogTailer(file, cs, new EventJoiner("file:" + file.getFileName(), pattern, true, sink));
        synchronized (tailers) {
            tailers.add(t);
        }
        t.start();
        return t;
    }

    private void uncaught(String prefix, Throwable e) {
        LogEvent event = new LogEvent("uncaught", LogEvent.Kind.UNCAUGHT, System.currentTimeMillis(), 1, null,
                lines(prefix + e, e));
        event.hasStackTrace = true;
        accept(event);
    }

    /** The first line, then the stack trace of {@code thrown} without its first line. */
    private static List<String> lines(String first, Throwable thrown) {
        List<String> out = new ArrayList<String>();
        out.add(first);
        if (thrown != null) {
            StringWriter w = new StringWriter();
            thrown.printStackTrace(new PrintWriter(w));
            String[] trace = w.toString().split("\r?\n");
            for (int i = 1; i < trace.length && out.size() < LogEvent.MAX_LINES; i++) out.add(trace[i]);
        }
        return out;
    }

    /** Sink of the joiners, the JUL handler and the uncaught hooks. Runs the fail rule once for each event. */
    void accept(LogEvent e) {
        if (!rule.isHit(e)) return;
        RunResult.LogHit h = new RunResult.LogHit();
        h.timestamp = e.time;
        h.source = e.source;
        String line = e.firstLine();
        h.line = line.length() > MAX_LINE ? line.substring(0, MAX_LINE) + "…" : line;
        h.text = e.text();
        synchronized (lock) {
            hits.add(new Collected(h, e.precisionMs));
        }
    }

    /** Reads each log file to its end and closes the open event of each source. Call before a step closes. */
    void drain() {
        List<LogTailer> ts;
        synchronized (tailers) {
            ts = new ArrayList<LogTailer>(tailers);
        }
        for (LogTailer t : ts) t.drain();
        List<ConsoleTee> cs;
        synchronized (tees) {
            cs = new ArrayList<ConsoleTee>(tees);
        }
        for (ConsoleTee t : cs) t.drain();
    }

    /**
     * Drains, then removes and gives every hit with a time at or before {@code to}, ordered by time (F-08).
     * A hit before the window (from, to] gets the source prefix {@code late:}. With second precision the window
     * starts at the start of the second of {@code from}. {@code Long.MIN_VALUE} means no lower limit.
     * A plain file line has the time of reading: call {@link #drain()} before you read the clock for {@code to}.
     */
    public List<RunResult.LogHit> takeHits(long from, long to) {
        drain();
        List<RunResult.LogHit> out = new ArrayList<RunResult.LogHit>();
        synchronized (lock) {
            for (Iterator<Collected> it = hits.iterator(); it.hasNext(); ) {
                Collected c = it.next();
                if (c.hit.timestamp > to) continue;
                it.remove();
                if (from != Long.MIN_VALUE && isLate(c, from)) c.hit.source = "late:" + c.hit.source;
                out.add(c.hit);
            }
        }
        Collections.sort(out, new Comparator<RunResult.LogHit>() {
            @Override
            public int compare(RunResult.LogHit a, RunResult.LogHit b) {
                return Long.compare(a.timestamp, b.timestamp);
            }
        });
        return out;
    }

    private static boolean isLate(Collected c, long from) {
        if (c.precisionMs <= 1) return c.hit.timestamp <= from;
        return c.hit.timestamp < from - from % c.precisionMs;
    }

    public void stop() {
        synchronized (tailers) {
            for (LogTailer t : tailers) t.stopTailing();
        }
    }

    /** One hit and the time precision of its event. */
    private static final class Collected {
        final RunResult.LogHit hit;
        final int precisionMs;

        Collected(RunResult.LogHit hit, int precisionMs) {
            this.hit = hit;
            this.precisionMs = precisionMs;
        }
    }
}
