package smoker.agent;

import javax.swing.SwingUtilities;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Detects a blocked Event Dispatch Thread (R-13): a ping is posted every 250 ms; if the last ping
 * has not been serviced within {@code hangMs}, a thread dump is captured and {@link #hangDetected()} turns true.
 */
final class EdtWatchdog extends Thread {

    private final long hangMs;
    private final AtomicLong lastPong = new AtomicLong(System.currentTimeMillis());
    private volatile String threadDump;
    private volatile boolean running = true;

    EdtWatchdog(long hangMs) {
        super("smoker-edt-watchdog");
        this.hangMs = hangMs;
        setDaemon(true);
    }

    boolean hangDetected() {
        return threadDump != null;
    }

    String threadDump() {
        return threadDump;
    }

    void reset() {
        threadDump = null;
        lastPong.set(System.currentTimeMillis());
    }

    void shutdown() {
        running = false;
    }

    @Override
    public void run() {
        while (running) {
            final long sent = System.currentTimeMillis();
            SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    lastPong.set(System.currentTimeMillis());
                }
            });
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                return;
            }
            if (threadDump == null && sent - lastPong.get() > hangMs) {
                threadDump = dumpThreads();
            }
        }
    }

    static String dumpThreads() {
        StringBuilder sb = new StringBuilder();
        for (ThreadInfo ti : ManagementFactory.getThreadMXBean().dumpAllThreads(true, true)) {
            sb.append('"').append(ti.getThreadName()).append("\" ").append(ti.getThreadState());
            if (ti.getLockName() != null) sb.append(" on ").append(ti.getLockName());
            sb.append('\n');
            for (StackTraceElement el : ti.getStackTrace()) sb.append("\tat ").append(el).append('\n');
            sb.append('\n');
        }
        return sb.toString();
    }
}
