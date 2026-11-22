package smoker.agent;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Wraps {@code System.out} or {@code System.err} (WP-04, spec 6.4). The bytes go to the real stream unchanged.
 * The tee also decodes each line with the charset of the stream and gives it to a joiner. Each writing thread has
 * its own line and its own joiner (F-22): the order of bytes between threads is not an order of lines.
 * Locks: the joiner runs under the lock of this stream, and its sink takes the lock of {@link Observations}.
 */
final class ConsoleTee extends PrintStream {

    /** The line state of one writing thread. Guarded by the lock of the tee. */
    private static final class Line {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final EventJoiner joiner;
        /** The write call that gave the first byte of the current line, or -1 before its first byte. */
        long lineStart = -1;
        /** The write call that ended the line before. */
        long lastEnd = -2;

        Line(EventJoiner joiner) {
            this.joiner = joiner;
        }
    }

    private final Charset cs;
    private final Supplier<EventJoiner> joiners;
    /** Guarded by the lock of this stream, as all fields below. */
    private final Map<Thread, Line> lines = new LinkedHashMap<Thread, Line>(); // first-write order
    /** From this size on, a new thread first removes the state of the threads that ended. */
    private static final int SWEEP_AT = 16;
    /** Number of the current write call, over all threads. */
    private long write;

    /** The tee encodes text with {@code cs}: give the charset of the real stream. Each thread gets a new joiner. */
    ConsoleTee(PrintStream real, Charset cs, Supplier<EventJoiner> joiners) throws UnsupportedEncodingException {
        super(real, true, cs.name());
        this.cs = cs;
        this.joiners = joiners;
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
            see(current(), b);
        }
    }

    @Override
    public void write(byte[] buf, int off, int len) {
        synchronized (this) {
            super.write(buf, off, len);
            write++;
            Line line = current();
            for (int i = off; i < off + len; i++) see(line, buf[i]);
        }
    }

    /**
     * Closes the open event of each thread. A thread without a partial line loses its state. A partial line of a
     * live thread stays; the partial line of an ended thread goes to its joiner first.
     */
    void drain() {
        synchronized (this) {
            for (Iterator<Map.Entry<Thread, Line>> it = lines.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<Thread, Line> e = it.next();
                Line line = e.getValue();
                if (!e.getKey().isAlive()) {
                    release(line);
                    it.remove();
                    continue;
                }
                line.joiner.close();
                if (line.bytes.size() == 0) it.remove();
            }
        }
    }

    /** Number of threads with line state: for the tests. */
    int threads() {
        synchronized (this) {
            return lines.size();
        }
    }

    /** Caller holds the lock of this stream. */
    private Line current() {
        Thread t = Thread.currentThread();
        Line line = lines.get(t);
        if (line == null) {
            if (lines.size() >= SWEEP_AT) sweep(); // no drain in record mode: keep the map bounded
            line = new Line(joiners.get());
            lines.put(t, line);
        }
        return line;
    }

    /** Caller holds the lock of this stream. Removes the state of each thread that ended. */
    private void sweep() {
        for (Iterator<Map.Entry<Thread, Line>> it = lines.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Thread, Line> e = it.next();
            if (e.getKey().isAlive()) continue;
            release(e.getValue());
            it.remove();
        }
    }

    /** Caller holds the lock of this stream. The thread of {@code line} ended: its partial line and event close. */
    private void release(Line line) {
        if (line.bytes.size() > 0) emit(line);
        line.joiner.close();
    }

    /** Caller holds the lock of this stream. Never throws into the print call of the application. */
    private void see(Line line, int b) {
        if (line.lineStart < 0) line.lineStart = write;
        if (b != '\n') {
            line.bytes.write(b);
            if (line.bytes.size() >= EventJoiner.MAX_LINE_BYTES) emit(line); // a long line goes in parts
            return;
        }
        emit(line);
    }

    private void emit(Line line) {
        String text = new String(line.bytes.toByteArray(), cs);
        line.bytes.reset();
        if (text.endsWith("\r")) text = text.substring(0, text.length() - 1);
        boolean sameWrite = line.lineStart == line.lastEnd;
        line.lastEnd = write;
        line.lineStart = -1;
        line.joiner.lineSafely(text, System.currentTimeMillis(), sameWrite);
    }
}
