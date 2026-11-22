package smoker.agent;

import javax.swing.MenuSelectionManager;
import javax.swing.SwingUtilities;
import java.awt.Dialog;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Closes the dialogs that a scenario left open, before the reset commands run (WP-05, F-04). */
final class DialogCloser {

    /** The result when the EDT does not answer within the time limit. */
    static final String EDT_SILENT = "(the EDT does not answer)";

    private static final long DEFAULT_EDT_TIMEOUT_MS = 5000;

    private DialogCloser() {}

    static String closeAll(int maxRounds) {
        return closeAll(maxRounds, DEFAULT_EDT_TIMEOUT_MS);
    }

    /**
     * Clears the menu selection, then disposes each showing dialog that is not a window of the tool. Repeats
     * while a round closed a dialog, at most {@code maxRounds} times.
     *
     * @param edtTimeoutMs the time limit of each call to the EDT
     * @return null, or the title of a dialog that still shows after the last round, or {@link #EDT_SILENT}
     */
    static String closeAll(int maxRounds, long edtTimeoutMs) {
        String closed = null;
        for (int round = 0; round < maxRounds; round++) {
            FutureTask<List<String>> task = new FutureTask<List<String>>(new Callable<List<String>>() {
                @Override
                public List<String> call() {
                    MenuSelectionManager.defaultManager().clearSelectedPath();
                    List<String> titles = new ArrayList<String>();
                    for (Window w : Window.getWindows()) {
                        if (!(w instanceof Dialog) || !w.isShowing()) continue;
                        String name = w.getName();
                        if (name != null && name.startsWith(SnapshotTaker.SMOKER_WINDOW_PREFIX)) continue;
                        String title = ((Dialog) w).getTitle();
                        titles.add(title == null ? "" : title);
                        w.dispose();
                    }
                    return titles;
                }
            });
            SwingUtilities.invokeLater(task);
            List<String> titles;
            try {
                titles = task.get(edtTimeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                task.cancel(false);
                Thread.currentThread().interrupt();
                return EDT_SILENT;
            } catch (Exception e) { // timeout or failure
                task.cancel(false);
                return EDT_SILENT;
            }
            if (titles.isEmpty()) return null;
            closed = titles.get(0);
        }
        return closed; // a dialog that opens again in each round
    }
}
