package smoker.agent;

import java.util.List;

/** One log event: the lines of one entry of a log source, joined (WP-04, F-09, F-22). */
final class LogEvent {

    enum Kind { LOGBACK, EQUINOX, PLAIN, JUL, UNCAUGHT }

    static final int MAX_LINES = 50;
    static final int MAX_CHARS = 8000;

    final String source;
    final Kind kind;
    final long time;
    final int precisionMs;
    /** Null for a plain event and for !SESSION. */
    final LogLevel level;
    boolean hasStackTrace;
    final List<String> lines;

    LogEvent(String source, Kind kind, long time, int precisionMs, LogLevel level, List<String> lines) {
        this.source = source;
        this.kind = kind;
        this.time = time;
        this.precisionMs = precisionMs;
        this.level = level;
        this.lines = lines;
    }

    String firstLine() {
        return lines.isEmpty() ? "" : lines.get(0);
    }

    /** The lines joined with a line feed: at most {@link #MAX_LINES} lines and {@link #MAX_CHARS} characters. */
    String text() {
        StringBuilder text = new StringBuilder();
        int count = Math.min(lines.size(), MAX_LINES);
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                text.append('\n');
            }
            text.append(lines.get(i));
            if (text.length() >= MAX_CHARS) {
                break;
            }
        }
        text.setLength(Math.min(text.length(), MAX_CHARS));
        return text.toString();
    }
}
