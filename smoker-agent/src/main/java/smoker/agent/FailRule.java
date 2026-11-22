package smoker.agent;

import java.util.regex.Pattern;

/** Decides once for each closed event whether it is a hit (WP-04, spec 6.3). */
final class FailRule {

    private final LogLevel failLevel;
    private final Pattern failRegex;
    private final Pattern allowRegex;

    FailRule(LogLevel failLevel, Pattern failRegex, Pattern allowRegex) {
        this.failLevel = failLevel;
        this.failRegex = failRegex;
        this.allowRegex = allowRegex;
    }

    boolean isHit(LogEvent e) {
        if (allowRegex != null && allowRegex.matcher(e.text()).find()) {
            return false;
        }
        switch (e.kind) {
            case UNCAUGHT:
                return true;
            case JUL:
                return e.hasStackTrace || e.level != null && e.level.compareTo(failLevel) >= 0;
            case EQUINOX:
                // Severity 4 and 8 are ERROR, severity 2 is WARN. !SESSION and severity 1 are no hit.
                return e.level != null && e.level.compareTo(LogLevel.WARN) >= 0 && e.level.compareTo(failLevel) >= 0;
            case LOGBACK:
                if (e.level != null) {
                    return e.level.compareTo(failLevel) >= 0;
                }
                return plainHit(e); // the pattern has no level
            default:
                return plainHit(e);
        }
    }

    private boolean plainHit(LogEvent e) {
        return e.hasStackTrace || failRegex.matcher(e.firstLine()).find();
    }
}
