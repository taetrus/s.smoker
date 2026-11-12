package smoker.agent;

import smoker.core.model.RunResult;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Collects "clues" from every source we can reach without touching bundle classloaders (R-10..R-12):
 * tailed log files, System.err, java.util.logging and uncaught exceptions (incl. EDT).
 * Logback inside OSGi bundles is observed through its file output; see architecture.md for the phase-2 bridge.
 */
public final class Observations {

    private final List<RunResult.LogHit> hits = new ArrayList<RunResult.LogHit>();
    private final Pattern fail;
    private final Pattern allow;
    private final List<LogTailer> tailers = new ArrayList<LogTailer>();

    public Observations(AgentConfig cfg) {
        this.fail = Pattern.compile(cfg.failRegex);
        this.allow = cfg.allowRegex == null ? null : Pattern.compile(cfg.allowRegex);
    }

    /** Installs all hooks. Call once, early. */
    public void install(AgentConfig cfg) {
        // 1. Uncaught exceptions on any thread (the EDT included — it uses the default handler since Java 7).
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                record("uncaught", "[" + t.getName() + "] " + e, true);
                if (prev != null) prev.uncaughtException(t, e);
                else e.printStackTrace();
            }
        });
        // Java 8 still honours this legacy property for the EDT.
        System.setProperty("sun.awt.exception.handler", EdtExceptionHandler.class.getName());

        // 2. System.err tee.
        final PrintStream realErr = System.err;
        System.setErr(new PrintStream(realErr, true) {
            private final StringBuilder line = new StringBuilder();
            @Override
            public void write(byte[] buf, int off, int len) {
                super.write(buf, off, len);
                for (int i = off; i < off + len; i++) {
                    char c = (char) buf[i];
                    if (c == '\n') { flushLine(); } else if (c != '\r') line.append(c);
                }
            }
            private void flushLine() {
                if (line.length() > 0) record("stderr", line.toString(), false);
                line.setLength(0);
            }
        });

        // 3. java.util.logging root handler.
        Logger.getLogger("").addHandler(new Handler() {
            @Override
            public void publish(LogRecord r) {
                if (r.getLevel().intValue() >= Level.WARNING.intValue() || r.getThrown() != null) {
                    record("jul", r.getLevel() + " " + r.getLoggerName() + " - " + r.getMessage()
                            + (r.getThrown() != null ? " " + r.getThrown() : ""), r.getThrown() != null);
                }
            }
            @Override public void flush() {}
            @Override public void close() {}
        });

        // 4. Log files (Logback/Log4j output). Tail from the current end.
        for (java.nio.file.Path p : cfg.logFiles) {
            LogTailer t = new LogTailer(p, this);
            t.start();
            tailers.add(t);
        }
    }

    /** Public static hook for the legacy sun.awt.exception.handler mechanism. */
    public static final class EdtExceptionHandler {
        public void handle(Throwable t) {
            Observations o = SmokerAgent.observations();
            if (o != null) o.record("uncaught", "[AWT-EventQueue] " + t, true);
            t.printStackTrace();
        }
    }

    void record(String source, String line, boolean forceHit) {
        if (!forceHit) {
            if (!fail.matcher(line).find()) return;
            if (allow != null && allow.matcher(line).find()) return;
        }
        RunResult.LogHit h = new RunResult.LogHit();
        h.timestamp = System.currentTimeMillis();
        h.source = source;
        h.line = line.length() > 2000 ? line.substring(0, 2000) + "…" : line;
        synchronized (hits) {
            hits.add(h);
        }
    }

    /** Hits whose timestamp falls in [from, to]. Correlates log lines with the step that was executing (R-10). */
    public List<RunResult.LogHit> hitsBetween(long from, long to) {
        List<RunResult.LogHit> out = new ArrayList<RunResult.LogHit>();
        synchronized (hits) {
            for (RunResult.LogHit h : hits) if (h.timestamp >= from && h.timestamp <= to) out.add(h);
        }
        return out;
    }

    public void stop() {
        for (LogTailer t : tailers) t.stopTailing();
    }
}
