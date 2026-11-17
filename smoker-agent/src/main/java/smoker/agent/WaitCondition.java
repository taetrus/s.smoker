package smoker.agent;

import smoker.core.model.Action;
import smoker.core.model.Locator;
import smoker.core.model.NodeSnapshot;

import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Window;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Reads one attribute of one component and polls it until it matches a regular expression (WP-03, F-02).
 * The player thread polls. Each read runs on the EDT; the player thread never blocks the EDT.
 */
final class WaitCondition {

    /** The attributes that WAIT_FOR can read. */
    static final List<String> ATTRIBUTES = Collections.unmodifiableList(Arrays.asList(
            "text", "title", "visible", "enabled", "selected", "rowCount", "itemCount", "selectedIndex"));

    /** The value of an attribute when no component has the locator. It never matches. */
    static final String NOT_FOUND = "<component not found>";

    static final long POLL_MS = 100;

    /** Longest wait for one read on the EDT. */
    private static final long READ_LIMIT_MS = 1000;

    enum Kind { MATCHED, TIMEOUT, HANG }

    static final class Outcome {
        final Kind kind;
        /** The last value that was read; null when nothing was read or the value is null. */
        final String lastValue;

        Outcome(Kind kind, String lastValue) {
            this.kind = kind;
            this.lastValue = lastValue;
        }
    }

    private WaitCondition() {
    }

    /** @return the compiled {@code matches} pattern @throws IllegalArgumentException when the action is not valid */
    static Pattern validate(Action a) {
        if (a.locator == null) throw new IllegalArgumentException("WAIT_FOR needs a locator");
        if (a.attribute == null || !ATTRIBUTES.contains(a.attribute)) {
            throw new IllegalArgumentException("WAIT_FOR attribute \"" + a.attribute + "\" is not known; allowed: "
                    + join(ATTRIBUTES));
        }
        if (a.matches == null) throw new IllegalArgumentException("WAIT_FOR needs \"matches\"");
        try {
            return Pattern.compile(a.matches);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("WAIT_FOR \"matches\" is not a valid regular expression: " + e.getMessage(), e);
        }
    }

    private static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) sb.append(sb.length() == 0 ? "" : ", ").append(s);
        return sb.toString();
    }

    /** The attribute as text, or null when the component has none. Must run on the EDT. A password field gives its mask (F-19). */
    static String valueOf(Component c, String attribute) {
        if ("title".equals(attribute)) return c instanceof Window ? Locators.titleOf((Window) c) : null;
        NodeSnapshot n = SnapshotTaker.attributes(c, 0);
        Object v;
        if ("text".equals(attribute)) v = n.text;
        else if ("visible".equals(attribute)) v = n.visible == null ? Boolean.TRUE : n.visible;
        else if ("enabled".equals(attribute)) v = n.enabled == null ? Boolean.TRUE : n.enabled;
        else if ("selected".equals(attribute)) v = n.selected;
        else if ("rowCount".equals(attribute)) v = n.rowCount;
        else if ("itemCount".equals(attribute)) v = n.itemCount;
        else if ("selectedIndex".equals(attribute)) v = n.selectedIndex;
        else throw new IllegalArgumentException("unknown attribute " + attribute);
        return v == null ? null : String.valueOf(v);
    }

    /** The attribute of the component of the locator, or {@link #NOT_FOUND}. Must run on the EDT. */
    static String read(Locator l, String attribute) {
        Component c = Locators.find(l);
        return c == null ? NOT_FOUND : valueOf(c, attribute);
    }

    /** True when the whole value matches. A missing value and {@link #NOT_FOUND} never match. */
    static boolean matches(Pattern p, String value) {
        return value != null && !NOT_FOUND.equals(value) && p.matcher(value).matches();
    }

    /**
     * Polls until the value matches or {@code a.waitTimeoutMs()} passes. Runs on the player thread.
     *
     * @param hangDetected tells whether the EDT is blocked; asked when a read did not return in time
     */
    static Outcome await(final Action a, Pattern p, BooleanSupplier hangDetected) {
        long deadline = System.currentTimeMillis() + a.waitTimeoutMs();
        String last = null;
        while (true) {
            FutureTask<String> task = new FutureTask<String>(new Callable<String>() {
                @Override
                public String call() {
                    return read(a.locator, a.attribute);
                }
            });
            SwingUtilities.invokeLater(task);
            try {
                last = task.get(READ_LIMIT_MS, TimeUnit.MILLISECONDS);
                if (matches(p, last)) return new Outcome(Kind.MATCHED, last);
            } catch (TimeoutException e) {
                task.cancel(false);
                if (hangDetected.getAsBoolean()) return new Outcome(Kind.HANG, last);
            } catch (InterruptedException e) {
                task.cancel(false);
                Thread.currentThread().interrupt();
                return new Outcome(Kind.TIMEOUT, last);
            } catch (Exception e) { // the read threw
                task.cancel(false);
            }
            if (System.currentTimeMillis() >= deadline) return new Outcome(Kind.TIMEOUT, last);
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Outcome(Kind.TIMEOUT, last);
            }
        }
    }

    static String timeoutMessage(Action a, String lastValue) {
        return "WAIT_FOR " + a.locator + " " + a.attribute + " did not match \"" + a.matches + "\" within "
                + a.waitTimeoutMs() + " ms; last value: " + (lastValue == null ? "null" : "\"" + lastValue + "\"");
    }
}
