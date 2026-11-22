package smoker.agent;

import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts a Logback pattern into one regex and reads log lines with it (WP-04, F-09).
 * The regex has the groups time, level, logger, thread and msg, as far as the pattern has the conversions.
 */
final class LogPattern {

    static final String DEFAULT = "%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n";

    private static final String ISO8601 = "yyyy-MM-dd HH:mm:ss,SSS";
    private static final String LEVELS = "TRACE|DEBUG|INFO|WARN|ERROR";
    /** A line with a time without a date: more than this after "now" means the day before. */
    private static final long MIDNIGHT_SLACK_MS = 60000;
    private static final Pattern MODIFIER = Pattern.compile("-?\\d*(\\.-?\\d+)?");

    private static final Set<String> EMPTY_WORDS = new HashSet<String>(Arrays.asList(
            "ex", "exception", "throwable", "xEx", "xException", "xThrowable",
            "rEx", "rootException", "nopex", "nopexception", "wEx"));
    private static final Set<String> TEXT_WORDS = new HashSet<String>(Arrays.asList(
            "X", "mdc", "class", "C", "method", "M", "line", "L", "file", "F", "relative", "r", "kvp"));
    private static final Set<String> COLOR_WORDS = new HashSet<String>(Arrays.asList(
            "highlight", "black", "red", "green", "yellow", "blue", "magenta", "cyan", "white", "gray",
            "boldRed", "boldGreen", "boldYellow", "boldBlue", "boldMagenta", "boldCyan", "boldWhite"));

    /** One line that agrees with the pattern. A field is null when the pattern has no such conversion. */
    static final class Match {
        final long time;
        final LogLevel level;
        final String thread;
        final String logger;
        final String msg;

        Match(long time, LogLevel level, String thread, String logger, String msg) {
            this.time = time;
            this.level = level;
            this.thread = thread;
            this.logger = logger;
            this.msg = msg;
        }
    }

    private final Pattern regex;
    private final Set<String> groups;
    /** The format for {@code SimpleDateFormat}, or null when the pattern has no date conversion. */
    private final String timeFormat;
    private final boolean hasDate;
    private final boolean hasMillis;

    private LogPattern(Pattern regex, Set<String> groups, String timeFormat, boolean hasDate, boolean hasMillis) {
        this.regex = regex;
        this.groups = groups;
        this.timeFormat = timeFormat;
        this.hasDate = hasDate;
        this.hasMillis = hasMillis;
    }

    /** Throws IllegalArgumentException with the text of spec 6.2 for a part that is not supported. */
    static LogPattern compile(String pattern) {
        Compiler c = new Compiler(pattern);
        c.parse(false);
        return new LogPattern(Pattern.compile("^" + c.regex), c.groups, c.timeFormat, c.hasDate, c.hasMillis);
    }

    /** 1 when the date format has milliseconds, else 1000. */
    int precisionMs() {
        return hasMillis ? 1 : 1000;
    }

    /** Reads one line. Gives null when the line does not agree with the pattern or its time does not parse. */
    Match match(String line, long now) {
        Matcher m = regex.matcher(line);
        if (!m.lookingAt()) {
            return null;
        }
        long time = now;
        if (timeFormat != null) {
            Long t = parseTime(m.group("time"), now);
            if (t == null) {
                return null;
            }
            time = t;
        }
        LogLevel level = groups.contains("level") ? LogLevel.parse(m.group("level")) : null;
        return new Match(time, level,
                groups.contains("thread") ? m.group("thread") : null,
                groups.contains("logger") ? m.group("logger") : null,
                groups.contains("msg") ? m.group("msg") : null);
    }

    private Long parseTime(String text, long now) {
        SimpleDateFormat format = new SimpleDateFormat(timeFormat, Locale.ROOT);
        format.setLenient(false);
        ParsePosition position = new ParsePosition(0);
        Date parsed = format.parse(text, position);
        if (parsed == null || position.getIndex() != text.length()) {
            return null;
        }
        if (hasDate) {
            return parsed.getTime();
        }
        // The format has a time only: take the date of "now".
        Calendar time = new GregorianCalendar();
        time.setTime(parsed);
        Calendar result = new GregorianCalendar();
        result.setTimeInMillis(now);
        result.set(Calendar.HOUR_OF_DAY, time.get(Calendar.HOUR_OF_DAY));
        result.set(Calendar.MINUTE, time.get(Calendar.MINUTE));
        result.set(Calendar.SECOND, time.get(Calendar.SECOND));
        result.set(Calendar.MILLISECOND, time.get(Calendar.MILLISECOND));
        if (result.getTimeInMillis() > now + MIDNIGHT_SLACK_MS) {
            result.add(Calendar.DAY_OF_MONTH, -1);
        }
        return result.getTimeInMillis();
    }

    /** Scanner over the pattern text. */
    private static final class Compiler {
        final String pattern;
        int pos;
        final StringBuilder regex = new StringBuilder();
        final Set<String> groups = new HashSet<String>();
        String timeFormat;
        boolean hasDate;
        boolean hasMillis;

        Compiler(String pattern) {
            this.pattern = pattern;
        }

        /** Parses to the end of the pattern, or to the closing bracket when {@code inner}. */
        void parse(boolean inner) {
            StringBuilder literal = new StringBuilder();
            while (pos < pattern.length()) {
                char c = pattern.charAt(pos);
                if (inner && c == ')') {
                    flush(literal);
                    return;
                }
                if (c != '%') {
                    literal.append(c);
                    pos++;
                } else if (pos + 1 < pattern.length() && pattern.charAt(pos + 1) == '%') {
                    literal.append('%');
                    pos += 2;
                } else {
                    flush(literal);
                    pos++;
                    conversion();
                }
            }
            flush(literal);
            if (inner) {
                throw new IllegalArgumentException("smoker: logPattern: closing bracket ) is missing");
            }
        }

        private void flush(StringBuilder literal) {
            if (literal.length() > 0) {
                regex.append(Pattern.quote(literal.toString()));
                literal.setLength(0);
            }
        }

        private void conversion() {
            Matcher modifier = MODIFIER.matcher(pattern).region(pos, pattern.length());
            if (modifier.lookingAt()) {
                pos = modifier.end();
            }
            int start = pos;
            while (pos < pattern.length() && Character.isLetter(pattern.charAt(pos))) {
                pos++;
            }
            String word = pattern.substring(start, pos);
            String option = null;
            int options = 0;
            while (pos < pattern.length() && pattern.charAt(pos) == '{') {
                String text = readOption();
                if (options++ == 0) {
                    option = text;
                }
            }
            if ((word.equals("d") || word.equals("date"))
                    && (options > 1 || option != null && option.matches("(?s).*,\\s.*"))) {
                throw new IllegalArgumentException("smoker: logPattern: %d with a time zone or locale is not supported");
            }
            if (word.equals("n")) {
                regex.append("$");
            } else if (EMPTY_WORDS.contains(word)) {
                return;
            } else if (COLOR_WORDS.contains(word)) {
                if (pos < pattern.length() && pattern.charAt(pos) == '(') {
                    pos++;
                    parse(true);
                    pos++;
                }
            } else {
                padded(regexOf(word, option));
            }
        }

        private String regexOf(String word, String option) {
            switch (word) {
                case "d":
                case "date":
                    return group("time", dateRegex(option));
                case "level":
                case "le":
                case "p":
                    return group("level", LEVELS);
                case "logger":
                case "lo":
                case "c":
                    return group("logger", "\\S+?");
                case "thread":
                case "t":
                    return group("thread", ".*?");
                case "msg":
                case "m":
                case "message":
                    return group("msg", ".*");
                default:
                    if (TEXT_WORDS.contains(word)) {
                        return ".*?";
                    }
                    throw new IllegalArgumentException("smoker: logPattern: conversion word %" + word + " is not supported");
            }
        }

        /** Gives a capturing group for the first use of a name and a plain group after that. */
        private String group(String name, String body) {
            return groups.add(name) ? "(?<" + name + ">" + body + ")" : "(?:" + body + ")";
        }

        private void padded(String body) {
            regex.append(" *").append(body).append(" *");
        }

        /** Reads {...} at pos, with quotes allowed. Gives the text between the brackets, without outer quotes. */
        private String readOption() {
            int start = ++pos;
            char quote = 0;
            while (pos < pattern.length()) {
                char c = pattern.charAt(pos);
                if (quote != 0) {
                    if (c == quote) {
                        quote = 0;
                    }
                } else if (c == '\'' || c == '"') {
                    quote = c;
                } else if (c == '}') {
                    String text = pattern.substring(start, pos++);
                    // Strip the quotes only when the whole option is one quoted string.
                    if (text.length() >= 2 && (text.charAt(0) == '"' || text.charAt(0) == '\'')
                            && text.charAt(text.length() - 1) == text.charAt(0)
                            && text.indexOf(text.charAt(0), 1) == text.length() - 1) {
                        text = text.substring(1, text.length() - 1);
                    }
                    return text;
                }
                pos++;
            }
            throw new IllegalArgumentException("smoker: logPattern: closing bracket } is missing");
        }

        /** Gives the regex of a date format. The first date conversion also sets the format for parsing. */
        private String dateRegex(String option) {
            String format = option == null || option.equals("ISO8601") ? ISO8601 : option;
            StringBuilder re = new StringBuilder();
            StringBuilder sdf = new StringBuilder();
            boolean date = false;
            boolean millis = false;
            int i = 0;
            while (i < format.length()) {
                char c = format.charAt(i);
                if (c == '\'') {
                    int end = format.indexOf('\'', i + 1);
                    if (end < 0) {
                        throw new IllegalArgumentException("smoker: logPattern: date format has an open quote: " + format);
                    }
                    String text = end == i + 1 ? "'" : format.substring(i + 1, end);
                    re.append(Pattern.quote(text));
                    sdf.append(quoted(text));
                    i = end + 1;
                } else if (Character.isLetter(c)) {
                    int end = i;
                    while (end < format.length() && format.charAt(end) == c) {
                        end++;
                    }
                    String run = format.substring(i, end);
                    int digits = digits(c, run.length());
                    if (digits == 0) {
                        throw new IllegalArgumentException("smoker: logPattern: date format letters " + run + " are not supported");
                    }
                    re.append("\\d{").append(digits).append('}');
                    sdf.append(run);
                    date |= c == 'y' || c == 'M' || c == 'd';
                    millis |= c == 'S';
                    i = end;
                } else {
                    re.append(Pattern.quote(String.valueOf(c)));
                    sdf.append(quoted(String.valueOf(c)));
                    i++;
                }
            }
            if (timeFormat == null) {
                timeFormat = sdf.toString();
                hasDate = date;
                hasMillis = millis;
            }
            return re.toString();
        }

        /** Number of digits for a run of a date letter, or 0 when the letter or its length is not supported. */
        private static int digits(char letter, int length) {
            switch (letter) {
                case 'y':
                    return length == 4 || length == 2 ? length : 0;
                case 'M':
                case 'd':
                case 'H':
                case 'm':
                case 's':
                    return length == 2 ? 2 : 0;
                case 'S':
                    return length == 3 ? 3 : 0;
                default:
                    return 0;
            }
        }

        private static String quoted(String text) {
            return "'" + text.replace("'", "''") + "'";
        }
    }
}
