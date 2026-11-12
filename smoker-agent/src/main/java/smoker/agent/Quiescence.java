package smoker.agent;

import javax.swing.SwingUtilities;
import java.awt.EventQueue;
import java.awt.Toolkit;
import java.lang.reflect.InvocationTargetException;

/**
 * Waits until the UI is "settled" (R-23): the EDT answers promptly and the system event queue has been
 * empty for {@code quietMs} in a row, or {@code timeoutMs} elapsed. Background SwingWorkers that later
 * touch the UI are covered by the quiet window; apps with long async work should expose a hint (R-05).
 */
final class Quiescence {

    private Quiescence() {}

    /** @return true if quiescent, false if the timeout hit. */
    static boolean await(long quietMs, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        long quietSince = -1;
        EventQueue q = Toolkit.getDefaultToolkit().getSystemEventQueue();
        while (System.currentTimeMillis() < deadline) {
            boolean idle = q.peekEvent() == null && edtResponds(Math.min(1000, deadline - System.currentTimeMillis()));
            long now = System.currentTimeMillis();
            if (idle) {
                if (quietSince < 0) quietSince = now;
                if (now - quietSince >= quietMs) return true;
            } else {
                quietSince = -1;
            }
            try {
                Thread.sleep(40);
            } catch (InterruptedException e) {
                return false;
            }
        }
        return false;
    }

    /** Posts a ping and waits for it; true if the EDT (or a nested modal loop) serviced it in time. */
    static boolean edtResponds(long maxWaitMs) {
        final Object lock = new Object();
        final boolean[] done = {false};
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                synchronized (lock) {
                    done[0] = true;
                    lock.notifyAll();
                }
            }
        });
        synchronized (lock) {
            long end = System.currentTimeMillis() + Math.max(1, maxWaitMs);
            while (!done[0] && System.currentTimeMillis() < end) {
                try {
                    lock.wait(Math.max(1, end - System.currentTimeMillis()));
                } catch (InterruptedException e) {
                    return false;
                }
            }
            return done[0];
        }
    }

    /** Runs on the EDT and rethrows any exception. */
    static <T> T onEdt(final java.util.concurrent.Callable<T> c) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) return c.call();
        final Object[] result = new Object[1];
        final Exception[] error = new Exception[1];
        try {
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override
                public void run() {
                    try {
                        result[0] = c.call();
                    } catch (Exception e) {
                        error[0] = e;
                    }
                }
            });
        } catch (InvocationTargetException e) {
            throw new RuntimeException(e.getCause());
        }
        if (error[0] != null) throw error[0];
        @SuppressWarnings("unchecked")
        T t = (T) result[0];
        return t;
    }
}
