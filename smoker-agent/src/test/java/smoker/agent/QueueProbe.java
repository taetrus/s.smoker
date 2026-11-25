package smoker.agent;

import java.awt.AWTEvent;
import java.awt.EventQueue;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.InvocationEvent;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.TreeMap;

/** Test aid: describes what keeps the event queue busy when a quiet wait times out. */
final class QueueProbe {

    private QueueProbe() {}

    /** Samples the head of the system event queue for {@code ms} and lists the showing windows. */
    static String describe(long ms) {
        EventQueue q = Toolkit.getDefaultToolkit().getSystemEventQueue();
        Map<String, Integer> seen = new TreeMap<String, Integer>();
        int samples = 0;
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            AWTEvent e = q.peekEvent();
            samples++;
            if (e != null) {
                String k = key(e);
                Integer n = seen.get(k);
                seen.put(k, n == null ? 1 : n + 1);
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException ex) {
                break;
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append(" [queue probe: ").append(samples).append(" samples, busy ").append(seen).append("; windows:");
        for (Window w : Window.getWindows()) {
            if (w.isShowing()) sb.append(' ').append(w.getClass().getSimpleName()).append('(').append(w.getName()).append(')');
        }
        sb.append(']');
        return sb.toString();
    }

    private static String key(AWTEvent e) {
        String k = e.getClass().getSimpleName() + "#" + e.getID() + "@" + e.getSource().getClass().getSimpleName();
        if (e instanceof InvocationEvent) k += "/" + runnableOf((InvocationEvent) e);
        return k;
    }

    private static String runnableOf(InvocationEvent e) {
        try {
            Field f = InvocationEvent.class.getDeclaredField("runnable");
            f.setAccessible(true);
            Object r = f.get(e);
            return r == null ? "null" : r.getClass().getName();
        } catch (Exception ex) {
            return "?";
        }
    }
}
