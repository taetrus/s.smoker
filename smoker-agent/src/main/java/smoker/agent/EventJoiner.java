package smoker.agent;

import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Joins the lines of one log source into events and gives each closed event to a sink (WP-04, F-09, F-22).
 * One joiner is for one source. Calls come from one thread at a time.
 */
final class EventJoiner {

    private static final String SMOKER_PREFIX = "[smoker] ";
    private static final Pattern ENTRY = Pattern.compile(
            "^!ENTRY\\s+\\S+\\s+(\\d+)\\s+\\d+\\s+(\\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d\\.\\d{3})");
    private static final Pattern SESSION = Pattern.compile(
            "^!SESSION\\s+(\\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d\\.\\d{3})");
    private static final Pattern EQUINOX_PART = Pattern.compile("^!(MESSAGE|SUBENTRY|STACK)\\b");
    private static final Pattern AT_LINE = Pattern.compile("^\\s+at ");
    private static final Pattern CONTINUATION = Pattern.compile(
            "^(\\s.*|Caused by:.*|Suppressed:.*|\\.\\.\\. \\d+ more.*)$");
    private static final Pattern EXCEPTION_NAME = Pattern.compile("^[\\w.$]+(Exception|Error|Throwable)\\b.*");

    private final String source;
    private final LogPattern pattern;
    private final boolean useLineTime;
    private final Consumer<LogEvent> sink;

    private LogEvent open;
    /** True while the open event is !SESSION and its header has no empty line yet: the header lines join it. */
    private boolean inSession;
    /** An empty line that waits for the next line. */
    private boolean heldEmpty;

    EventJoiner(String source, LogPattern pattern, boolean useLineTime, Consumer<LogEvent> sink) {
        this.source = source;
        this.pattern = pattern;
        this.useLineTime = useLineTime;
        this.sink = sink;
    }

    void line(String line, long arrivedAt) {
        if (line.startsWith(SMOKER_PREFIX)) {
            close();
            return;
        }
        Matcher entry = ENTRY.matcher(line);
        if (entry.find()) {
            LogLevel level = LogLevel.ofEquinoxSeverity(Integer.parseInt(entry.group(1)));
            start(new LogEvent(source, LogEvent.Kind.EQUINOX, equinoxTime(entry.group(2), arrivedAt), 1, level,
                    new ArrayList<String>()), line);
            return;
        }
        Matcher session = SESSION.matcher(line);
        if (session.find()) {
            start(new LogEvent(source, LogEvent.Kind.EQUINOX, equinoxTime(session.group(1), arrivedAt), 1, null,
                    new ArrayList<String>()), line);
            inSession = true;
            return;
        }
        if (line.isEmpty() && inSession) {
            close(); // the header of a session ends at its empty line, and so does its event
            return;
        }
        LogPattern.Match match = pattern.match(line, arrivedAt);
        if (match == null && open != null && open.kind == LogEvent.Kind.EQUINOX
                && (inSession || EQUINOX_PART.matcher(line).find())) {
            join(line);
            return;
        }
        if (match != null) {
            long time = useLineTime ? match.time : arrivedAt;
            start(new LogEvent(source, LogEvent.Kind.LOGBACK, time, pattern.precisionMs(), match.level,
                    new ArrayList<String>()), line);
            return;
        }
        if (line.isEmpty()) {
            heldEmpty = open != null;
            return;
        }
        if (CONTINUATION.matcher(line).matches()) {
            if (open == null) {
                startPlain(line, arrivedAt);
            } else {
                join(line);
            }
            return;
        }
        if (open != null && EXCEPTION_NAME.matcher(line).matches()) {
            heldEmpty = false; // an empty line joins only before a continuation line
            join(line);
            return;
        }
        startPlain(line, arrivedAt);
    }

    /** Closes the open event and drops a held empty line. */
    void close() {
        heldEmpty = false;
        inSession = false;
        if (open != null) {
            LogEvent done = open;
            open = null;
            sink.accept(done);
        }
    }

    private void startPlain(String line, long arrivedAt) {
        start(new LogEvent(source, LogEvent.Kind.PLAIN, arrivedAt, 1, null, new ArrayList<String>()), line);
    }

    private void start(LogEvent event, String firstLine) {
        close();
        open = event;
        add(firstLine);
    }

    private void join(String line) {
        if (heldEmpty) {
            add("");
            heldEmpty = false;
        }
        add(line);
    }

    private void add(String line) {
        if (open.lines.size() < LogEvent.MAX_LINES) {
            open.lines.add(line);
        }
        if (AT_LINE.matcher(line).find()) {
            open.hasStackTrace = true;
        }
    }

    private static long equinoxTime(String text, long arrivedAt) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT);
        format.setLenient(false);
        Date parsed = format.parse(text, new ParsePosition(0));
        return parsed == null ? arrivedAt : parsed.getTime();
    }
}
