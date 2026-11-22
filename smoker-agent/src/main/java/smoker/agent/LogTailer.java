package smoker.agent;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Framework-agnostic log observation (R-10): tails a file and gives its lines to an {@link EventJoiner}.
 * A file that exists at construction is read from its end; a file that appears later is read from 0 (WP-04).
 * Rotation (the size shrinks) restarts at 0. Works for Logback/Log4j output produced inside OSGi bundles
 * that the agent cannot reach via classloaders.
 * The poll thread and {@link #drain()} read under one lock, so the joiner sees each line once and in order.
 */
final class LogTailer extends Thread {

    private final Path file;
    private final Charset cs;
    private final EventJoiner joiner;
    private final Object lock = new Object();
    private final ByteArrayOutputStream partial = new ByteArrayOutputStream();
    private long pos;
    private volatile boolean running = true;

    LogTailer(Path file, Charset cs, EventJoiner joiner) {
        super("smoker-tail-" + file.getFileName());
        this.file = file;
        this.cs = cs;
        this.joiner = joiner;
        long start = 0;
        try {
            if (Files.exists(file)) start = Files.size(file);
        } catch (IOException e) {
            // read from 0 when it can be read
        }
        this.pos = start;
        setDaemon(true);
    }

    void stopTailing() {
        running = false;
    }

    /** Reads the file to its end on the calling thread, then closes the open event (WP-04, F-08). */
    void drain() {
        synchronized (lock) {
            try {
                readToEnd();
            } catch (IOException e) {
                // the file is gone or locked: the next poll tries again
            }
            joiner.close();
        }
    }

    @Override
    public void run() {
        while (running) {
            try {
                synchronized (lock) {
                    readToEnd();
                }
                Thread.sleep(150);
            } catch (IOException | RuntimeException e) {
                // the poll thread keeps reading: a later poll tries again
                try { Thread.sleep(500); } catch (InterruptedException ie) { return; }
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** Caller holds {@link #lock}. */
    private void readToEnd() throws IOException {
        if (!Files.exists(file)) return;
        RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r");
        try {
            long len = raf.length();
            if (len < pos) {                      // rotated/truncated
                pos = 0;
                partial.reset();
            }
            byte[] buf = new byte[1 << 16];
            raf.seek(pos);
            while (pos < len) {
                int n = raf.read(buf, 0, (int) Math.min(len - pos, buf.length));
                if (n <= 0) break;
                pos += n;
                long now = System.currentTimeMillis();
                for (int i = 0; i < n; i++) {
                    if (buf[i] != '\n') {
                        partial.write(buf[i]);
                        if (partial.size() >= EventJoiner.MAX_LINE_BYTES) emit(now); // a long line goes in parts
                        continue;
                    }
                    emit(now);
                }
            }
        } finally {
            raf.close();
        }
    }

    private void emit(long now) {
        String line = new String(partial.toByteArray(), cs);
        partial.reset();
        if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
        joiner.lineSafely(line, now);
    }
}
