package smoker.agent;

import smoker.core.model.RunResult;

import javax.swing.LookAndFeel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Frame;
import java.awt.Window;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * The stable conditions of a run (WP-07, R-34): locale, time zone, look and feel and size of the main window from
 * the agent configuration. {@link #applyEarly} runs in {@code premain} and loads no Swing or AWT class; the code
 * that touches the UI is in the nested class {@link Ui}, which loads only when it is used.
 */
final class Conditions {

    /** Time limit of one EDT task of this class. */
    private static final long EDT_WAIT_MS = 5000;

    /** The window size was applied in this JVM. */
    private static volatile boolean sizeApplied;

    private Conditions() {}

    /** On the premain thread, before the application starts: sets the defaults and the system properties. */
    static void applyEarly(AgentConfig cfg) {
        if (cfg.locale != null) {
            Locale l = cfg.locale;
            Locale.setDefault(l);
            System.setProperty("user.language", l.getLanguage());
            if (!l.getCountry().isEmpty()) System.setProperty("user.country", l.getCountry());
            if (!l.getVariant().isEmpty()) System.setProperty("user.variant", l.getVariant());
            SmokerAgent.log("locale=" + l.toLanguageTag());
        }
        if (cfg.timeZone != null) {
            TimeZone.setDefault(cfg.timeZone);
            System.setProperty("user.timezone", cfg.timeZone.getID());
            SmokerAgent.log("timeZone=" + cfg.timeZone.getID());
        }
        if (cfg.lookAndFeel != null) {
            System.setProperty("swing.defaultlaf", cfg.lookAndFeel);
            SmokerAgent.log("lookAndFeel=" + cfg.lookAndFeel);
        }
    }

    /**
     * Sets the size of the first main window, one time for each JVM. Call off the EDT, after the main window shows.
     * Does nothing when the size is not set or no main window shows.
     */
    static void applyWindowSize(AgentConfig cfg) {
        if (cfg.windowWidth <= 0 || cfg.windowHeight <= 0 || sizeApplied) return;
        if (Ui.applySize(cfg.windowWidth, cfg.windowHeight)) {
            sizeApplied = true;
            SmokerAgent.log("windowSize=" + cfg.windowWidth + "x" + cfg.windowHeight);
        }
    }

    /** Writes the actual conditions to the result and a warning for each configured value that differs. Call off the EDT. */
    static void describe(AgentConfig cfg, RunResult r) {
        r.locale = Locale.getDefault().toLanguageTag();
        r.timeZone = TimeZone.getDefault().getID();
        String[] ui = Ui.read();
        if (ui != null) {
            r.lookAndFeel = ui[0];
            r.windowSize = ui[1];
        }
        if (cfg.locale != null) warn(r, "locale", r.locale, cfg.locale.toLanguageTag());
        if (cfg.timeZone != null) warn(r, "time zone", r.timeZone, cfg.timeZone.getID());
        if (cfg.lookAndFeel != null) warn(r, "look and feel", r.lookAndFeel, cfg.lookAndFeel);
        if (cfg.windowWidth > 0) warn(r, "window size", r.windowSize, cfg.windowWidth + "x" + cfg.windowHeight);
    }

    private static void warn(RunResult r, String what, String actual, String configured) {
        if (configured.equals(actual)) return;
        r.warnings.add(what + " is " + (actual == null ? "none" : actual) + ", not the configured " + configured);
    }

    /** For tests: the next {@link #applyWindowSize} sets the size again. */
    static void resetForTest() {
        sizeApplied = false;
    }

    /** The EDT part. A separate class: {@code premain} must not load AWT or Swing classes. */
    private static final class Ui {

        /** @return true when a main window got the size */
        static boolean applySize(final int w, final int h) {
            Boolean maximized = onEdt(new Callable<Boolean>() {
                @Override
                public Boolean call() {
                    Window win = mainWindow();
                    if (win == null) return null;
                    Frame f = (Frame) win; // Player.isMainWindow accepts only a Frame
                    if ((f.getExtendedState() & Frame.MAXIMIZED_BOTH) == 0) return Boolean.FALSE;
                    f.setExtendedState(Frame.NORMAL);
                    return Boolean.TRUE;
                }
            });
            if (maximized == null) return false;
            // The window manager restores the old bounds after the state change: set the size after that.
            if (maximized.booleanValue()) Quiescence.await(400, EDT_WAIT_MS);
            Boolean done = onEdt(new Callable<Boolean>() {
                @Override
                public Boolean call() {
                    Window win = mainWindow();
                    if (win == null) return Boolean.FALSE;
                    win.setSize(w, h);
                    return Boolean.TRUE;
                }
            });
            Quiescence.await(400, EDT_WAIT_MS);
            return done != null && done.booleanValue();
        }

        /** @return {class name of the look and feel or null, size of the first main window or null}; null when the EDT does not answer */
        static String[] read() {
            return onEdt(new Callable<String[]>() {
                @Override
                public String[] call() {
                    LookAndFeel laf = UIManager.getLookAndFeel();
                    Window win = mainWindow();
                    return new String[] {laf == null ? null : laf.getClass().getName(),
                            win == null ? null : win.getWidth() + "x" + win.getHeight()};
                }
            });
        }

        /** On the EDT: the first main window, or null. */
        private static Window mainWindow() {
            for (Window w : Window.getWindows()) if (Player.isMainWindow(w)) return w;
            return null;
        }

        /**
         * Runs the task on the EDT and waits at most {@link #EDT_WAIT_MS}, as {@link Player#mainWindowShown}: a
         * modal dialog of the application or a blocked EDT does not block the caller. Null on a timeout or a failure.
         */
        private static <T> T onEdt(Callable<T> c) {
            FutureTask<T> task = new FutureTask<T>(c);
            SwingUtilities.invokeLater(task);
            try {
                return task.get(EDT_WAIT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                task.cancel(false);
                Thread.currentThread().interrupt();
                return null;
            } catch (Exception e) { // timeout or failure
                task.cancel(false);
                SmokerAgent.log("conditions: no answer of the EDT: " + e);
                return null;
            }
        }
    }
}
