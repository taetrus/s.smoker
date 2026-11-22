package smoker.agent;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;

/**
 * Wraps {@code System.out} or {@code System.err} (WP-04, spec 6.4). The bytes go to the real stream unchanged.
 * The tee also decodes each line with the charset of the stream and gives it to its joiner.
 * Locks: the joiner runs under the lock of this stream, and its sink takes the lock of {@link Observations}.
 */
final class ConsoleTee extends PrintStream {

    private final Charset cs;
    private final EventJoiner joiner;
    private final ByteArrayOutputStream line = new ByteArrayOutputStream();
    /** Number of the current write call. Guarded by the lock of this stream, as all fields below. */
    private long write;
    /** The write call that gave the first byte of the current line, or -1 before its first byte. */
    private long lineStart = -1;
    /** The write call that ended the line before. */
    private long lastEnd = -2;

    /** The tee encodes text with {@code cs}: give the charset of the real stream. */
    ConsoleTee(PrintStream real, Charset cs, EventJoiner joiner) throws UnsupportedEncodingException {
        super(real, true, cs.name());
        this.cs = cs;
        this.joiner = joiner;
    }

    /** Property {@code stdout.encoding}, then {@code sun.stdout.encoding}, then the platform charset. */
    static Charset streamCharset(String which) {
        for (String key : new String[] {which + ".encoding", "sun." + which + ".encoding"}) {
            String value = System.getProperty(key);
            if (value == null) continue;
            try {
                return Charset.forName(value);
            } catch (IllegalArgumentException e) {
                // try the next property
            }
        }
        return Charset.defaultCharset();
    }

    @Override
    public void write(int b) {
        synchronized (this) {
            super.write(b);
            write++;
            see(b);
        }
    }

    @Override
    public void write(byte[] buf, int off, int len) {
        synchronized (this) {
            super.write(buf, off, len);
            write++;
            for (int i = off; i < off + len; i++) see(buf[i]);
        }
    }

    /** Closes the open event of the joiner. A line without its line feed stays. */
    void drain() {
        synchronized (this) {
            joiner.close();
        }
    }

    /** Caller holds the lock of this stream. Never throws into the print call of the application. */
    private void see(int b) {
        if (lineStart < 0) lineStart = write;
        if (b != '\n') {
            line.write(b);
            if (line.size() >= EventJoiner.MAX_LINE_BYTES) emit(); // a long line goes in parts
            return;
        }
        emit();
    }

    private void emit() {
        String text = new String(line.toByteArray(), cs);
        line.reset();
        if (text.endsWith("\r")) text = text.substring(0, text.length() - 1);
        boolean sameWrite = lineStart == lastEnd;
        lastEnd = write;
        lineStart = -1;
        joiner.lineSafely(text, System.currentTimeMillis(), sameWrite);
    }
}
