package smoker.agent;

import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Frame;
import java.awt.Window;
import java.awt.event.WindowEvent;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * Stops the application in three stages (WP-05, F-10, R-38): WINDOW_CLOSING to the frames, stop of the OSGi system
 * bundle, {@code System.exit}. A daemon thread calls {@code halt} when {@code exit} does not return.
 * The sleeps run on the calling thread, not on the EDT. The class does not depend on the player.
 */
final class AppStopper {

    /** The end of the JVM. A test replaces it. */
    interface Exit {
        void exit(int code);

        void halt(int code);
    }

    static final Exit SYSTEM = new Exit() {
        @Override
        public void exit(int code) {
            System.exit(code);
        }

        @Override
        public void halt(int code) {
            Runtime.getRuntime().halt(code);
        }
    };

    private final long stopTimeoutMs;
    private final Exit exit;

    AppStopper(long stopTimeoutMs, Exit exit) {
        this.stopTimeoutMs = stopTimeoutMs;
        this.exit = exit;
    }

    /** @param edtBlocked true when the EDT does not answer (status HANG): stage 1 is skipped */
    void stop(final int code, boolean edtBlocked) {
        if (!edtBlocked) {
            SmokerAgent.log("stop: closing the windows");
            closeFrames();
            sleep(stopTimeoutMs);
        }
        Object bundle = findSystemBundle();
        if (bundle != null) {
            SmokerAgent.log("stop: stopping the OSGi framework");
            try {
                invoke(bundle, "stop");
            } catch (Throwable t) {
                SmokerAgent.log("stop: the OSGi stop failed: " + t);
            }
            sleep(stopTimeoutMs);
        } else {
            SmokerAgent.log("stop: no OSGi framework");
        }
        Thread halt = new Thread(new Runnable() {
            @Override
            public void run() {
                sleep(stopTimeoutMs);
                SmokerAgent.log("stop: halt(" + code + ")");
                exit.halt(code);
            }
        }, "smoker-halt");
        halt.setDaemon(true);
        halt.start();
        SmokerAgent.log("stop: System.exit(" + code + ")");
        exit.exit(code);
    }

    /** Posts WINDOW_CLOSING to each main frame on the EDT. Does not wait for the EDT. */
    private static void closeFrames() {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                for (Window w : Window.getWindows()) {
                    if (w instanceof Frame && Player.isMainWindow(w)) w.dispatchEvent(new WindowEvent(w, WindowEvent.WINDOW_CLOSING));
                }
            }
        });
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** The OSGi system bundle (id 0) of the class loader, through {@code getBundle()}. Null on any failure. */
    static Object systemBundleOf(ClassLoader loader) {
        if (loader == null) return null;
        try {
            Object bundle = invoke(loader, "getBundle");
            if (bundle == null) return null;
            Object context = invoke(bundle, "getBundleContext");
            if (context == null) return null;
            Method m = find(context.getClass(), "getBundle", long.class);
            return m.invoke(context, 0L);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Finds the system bundle through the windows, then through EclipseStarter. Null when there is none. */
    static Object findSystemBundle() {
        final Set<ClassLoader> loaders = Collections.synchronizedSet(new LinkedHashSet<ClassLoader>());
        FutureTask<Void> task = new FutureTask<Void>(new Callable<Void>() {
            @Override
            public Void call() {
                for (Window w : Window.getWindows()) {
                    if (!w.isShowing()) continue;
                    loaders.add(w.getClass().getClassLoader());
                    collect(w, loaders);
                }
                return null;
            }
        });
        SwingUtilities.invokeLater(task);
        try {
            task.get(1, TimeUnit.SECONDS);
        } catch (Exception e) {
            task.cancel(false);
        }
        Set<ClassLoader> copy;
        synchronized (loaders) {
            copy = new LinkedHashSet<ClassLoader>(loaders);
        }
        for (ClassLoader l : copy) {
            Object b = systemBundleOf(l);
            if (b != null) return b;
        }
        Set<ClassLoader> starters = new LinkedHashSet<ClassLoader>();
        starters.add(ClassLoader.getSystemClassLoader());
        try {
            for (Thread t : Thread.getAllStackTraces().keySet()) starters.add(t.getContextClassLoader());
        } catch (Throwable ignored) {
            // the system class loader stays
        }
        for (ClassLoader l : starters) {
            if (l == null) continue;
            try {
                Class<?> starter = Class.forName("org.eclipse.core.runtime.adaptor.EclipseStarter", false, l);
                Object context = starter.getMethod("getSystemBundleContext").invoke(null);
                if (context == null) continue;
                Object b = find(context.getClass(), "getBundle", long.class).invoke(context, 0L);
                if (b != null) return b;
            } catch (Throwable ignored) {
                // not this loader
            }
        }
        return null;
    }

    private static void collect(Component c, Set<ClassLoader> loaders) {
        loaders.add(c.getClass().getClassLoader());
        if (c instanceof Container) for (Component k : ((Container) c).getComponents()) collect(k, loaders);
    }

    private static Object invoke(Object target, String name) throws Exception {
        return find(target.getClass(), name).invoke(target);
    }

    /** A public method by name. Opens it, because the declaring class of a bundle object can be non-public. */
    private static Method find(Class<?> c, String name, Class<?>... types) throws NoSuchMethodException {
        Method m = c.getMethod(name, types);
        m.setAccessible(true);
        return m;
    }
}
