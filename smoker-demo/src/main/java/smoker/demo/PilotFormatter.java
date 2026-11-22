package smoker.demo;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;

/**
 * Writes the Logback default pattern of the pilot application:
 * {@code HH:mm:ss.SSS [thread] LEVEL logger - message}, then the stack trace of the throwable.
 * The thread is the current thread: a handler formats the record in the thread that logs it.
 */
public class PilotFormatter extends Formatter {

    @Override
    public String format(LogRecord r) {
        StringBuilder sb = new StringBuilder();
        sb.append(new SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT).format(new Date(r.getMillis())));
        sb.append(" [").append(Thread.currentThread().getName()).append("] ");
        String level = level(r.getLevel());
        sb.append(level);
        for (int i = level.length(); i < 5; i++) sb.append(' ');
        sb.append(' ').append(r.getLoggerName()).append(" - ").append(formatMessage(r)).append('\n');
        if (r.getThrown() != null) {
            StringWriter sw = new StringWriter();
            r.getThrown().printStackTrace(new PrintWriter(sw));
            sb.append(sw.toString().replace("\r\n", "\n"));
        }
        return sb.toString();
    }

    private static String level(Level l) {
        int v = l.intValue();
        if (v >= Level.SEVERE.intValue()) return "ERROR";
        if (v >= Level.WARNING.intValue()) return "WARN";
        if (v >= Level.INFO.intValue()) return "INFO";
        if (v >= Level.FINE.intValue()) return "DEBUG";
        return "TRACE";
    }
}
