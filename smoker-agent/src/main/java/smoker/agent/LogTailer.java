package smoker.agent;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Framework-agnostic log observation (R-10): tails a file from its current end, tolerating
 * late creation and rotation (size shrink → restart from 0). Works for Logback/Log4j output
 * produced inside OSGi bundles that the agent cannot reach via classloaders.
 */
final class LogTailer extends Thread {

    private final Path file;
    private final Observations sink;
    private volatile boolean running = true;

    LogTailer(Path file, Observations sink) {
        super("smoker-tail-" + file.getFileName());
        this.file = file;
        this.sink = sink;
        setDaemon(true);
    }

    void stopTailing() {
        running = false;
    }

    @Override
    public void run() {
        long pos = -1;
        StringBuilder partial = new StringBuilder();
        while (running) {
            try {
                if (!Files.exists(file)) {
                    Thread.sleep(250);
                    continue;
                }
                RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r");
                try {
                    long len = raf.length();
                    if (pos < 0) pos = len;              // first sight: start at end, ignore history
                    if (len < pos) pos = 0;               // rotated/truncated
                    if (len > pos) {
                        raf.seek(pos);
                        byte[] buf = new byte[(int) Math.min(len - pos, 1 << 16)];
                        int n = raf.read(buf);
                        pos += n;
                        String chunk = new String(buf, 0, n, StandardCharsets.UTF_8);
                        partial.append(chunk);
                        int nl;
                        while ((nl = partial.indexOf("\n")) >= 0) {
                            String line = partial.substring(0, nl).trim();
                            partial.delete(0, nl + 1);
                            if (!line.isEmpty()) sink.record("file:" + file.getFileName(), line, false);
                        }
                        continue; // more may be waiting
                    }
                } finally {
                    raf.close();
                }
                Thread.sleep(150);
            } catch (IOException e) {
                try { Thread.sleep(500); } catch (InterruptedException ie) { return; }
            } catch (InterruptedException e) {
                return;
            }
        }
    }
}
