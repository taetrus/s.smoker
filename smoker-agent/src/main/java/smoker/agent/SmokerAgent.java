package smoker.agent;

import smoker.core.io.Json;
import smoker.core.model.Scenario;

import javax.swing.SwingUtilities;
import java.awt.Frame;
import java.awt.Window;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;

/**
 * Entry point: {@code -javaagent:smoker-agent.jar=mode=play;scenario=x.json;out=run1;log=app.log} (R-04).
 * No bytecode instrumentation is performed; the agent only needs to be in the JVM early to install hooks.
 * Works unchanged inside an Equinox/Felix launch because Swing lives on the bootstrap classpath.
 */
public final class SmokerAgent {

    private static volatile Observations OBS;

    private SmokerAgent() {}

    public static void premain(String args, Instrumentation inst) {
        start(args);
    }

    /** Dynamic attach entry (Java 9+ needs {@code -Djdk.attach.allowAttachSelf} on the attaching side). */
    public static void agentmain(String args, Instrumentation inst) {
        start(args);
    }

    static Observations observations() {
        return OBS;
    }

    static void log(String s) {
        System.out.println("[smoker] " + s);
    }

    private static void start(String args) {
        final AgentConfig cfg = AgentConfig.parse(args);
        final Observations obs = new Observations(cfg);
        OBS = obs;
        obs.install(cfg);
        final EdtWatchdog watchdog = new EdtWatchdog(cfg.edtHangMs);

        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // The EDT may not exist yet when premain runs; touching Swing here starts it lazily.
                    watchdog.start();
                    if (cfg.mode == AgentConfig.Mode.RECORD) record(cfg);
                    else play(cfg, obs, watchdog);
                } catch (Throwable e) {
                    System.err.println("[smoker] fatal: " + e);
                    e.printStackTrace();
                    if (cfg.mode == AgentConfig.Mode.PLAY && cfg.exitWhenDone) System.exit(2);
                }
            }
        }, "smoker-main");
        t.setDaemon(true);
        t.start();
        log("agent started, mode=" + cfg.mode + ", scenario=" + cfg.scenario);
    }

    private static void play(AgentConfig cfg, Observations obs, EdtWatchdog watchdog) throws Exception {
        Scenario sc = Json.read(cfg.scenario, Scenario.class);
        if (sc.name == null) sc.name = stripExt(cfg.scenario.getFileName().toString());
        Files.createDirectories(cfg.out);
        new Player(cfg, sc, obs, watchdog).run();
    }

    private static void record(final AgentConfig cfg) throws Exception {
        final Scenario sc;
        if (Files.exists(cfg.scenario)) {
            sc = Json.read(cfg.scenario, Scenario.class); // continue an existing scenario
        } else {
            sc = new Scenario();
            sc.name = stripExt(cfg.scenario.getFileName().toString());
        }
        // show the toolbar once the app's first frame is up, so it floats above it
        long deadline = System.currentTimeMillis() + sc.startupTimeoutMs;
        while (System.currentTimeMillis() < deadline && !anyFrameShowing()) Thread.sleep(200);
        final Recorder rec = new Recorder(cfg, sc);
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                new RecorderToolbar(rec).setVisible(true);
            }
        });
    }

    private static boolean anyFrameShowing() {
        for (Window w : Window.getWindows()) if (w instanceof Frame && w.isShowing()) return true;
        return false;
    }

    private static String stripExt(String n) {
        int i = n.lastIndexOf('.');
        return i > 0 ? n.substring(0, i) : n;
    }
}
