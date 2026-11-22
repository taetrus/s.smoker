package smoker.agent;

import java.util.Locale;

/** Log level of one event, in the order of Logback (WP-04). */
enum LogLevel {
    TRACE, DEBUG, INFO, WARN, ERROR;

    /** Reads a level name with {@code Locale.ROOT}. Throws IllegalArgumentException for an unknown name. */
    static LogLevel parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("smoker: log level is missing");
        }
        try {
            return valueOf(text.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("smoker: unknown log level '" + text + "'");
        }
    }

    /** SEVERE is ERROR, WARNING is WARN, INFO is INFO. Every other level is DEBUG. */
    static LogLevel ofJul(java.util.logging.Level level) {
        int v = level.intValue();
        if (v == java.util.logging.Level.SEVERE.intValue()) {
            return ERROR;
        }
        if (v == java.util.logging.Level.WARNING.intValue()) {
            return WARN;
        }
        if (v == java.util.logging.Level.INFO.intValue()) {
            return INFO;
        }
        return DEBUG;
    }

    /** Severity of an Equinox log entry: 1 INFO, 2 WARN, 4 ERROR, 8 (cancel) ERROR. Else null. */
    static LogLevel ofEquinoxSeverity(int severity) {
        switch (severity) {
            case 1:
                return INFO;
            case 2:
                return WARN;
            case 4:
            case 8:
                return ERROR;
            default:
                return null;
        }
    }
}
