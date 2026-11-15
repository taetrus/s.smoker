package smoker.agent;

import smoker.core.io.ScenarioFiles;
import smoker.core.model.Scenario;
import smoker.core.model.Snapshot;

import javax.swing.SwingUtilities;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.util.LinkedHashMap;

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
        Scenario sc = ScenarioFiles.readScenario(cfg.scenario);
        if (sc.name == null) sc.name = stripExt(cfg.scenario.getFileName().toString());
        LinkedHashMap<String, Snapshot> golden = ScenarioFiles.readGolden(cfg.scenario);
        Locators.useWindowKeys(new WindowKeys(sc.windowKeys));
        AutoNames.enable(sc.autoNames);
        Files.createDirectories(cfg.out);
        new Player(cfg, sc, golden, obs, watchdog).run();
    }

    private static void record(final AgentConfig cfg) throws Exception {
        final Scenario sc;
        LinkedHashMap<String, Snapshot> golden = new LinkedHashMap<String, Snapshot>();
        if (Files.exists(cfg.scenario)) {
            sc = ScenarioFiles.readScenario(cfg.scenario); // continue an existing scenario and keep its snapshots
            golden = ScenarioFiles.readGolden(cfg.scenario);
        } else {
            sc = new Scenario();
            sc.name = stripExt(cfg.scenario.getFileName().toString());
        }
        Locators.useWindowKeys(new WindowKeys(sc.windowKeys));
        AutoNames.enable(sc.autoNames);
        // show the toolbar once the app's first frame is up, so it floats above it
        long deadline = System.currentTimeMillis() + sc.startupTimeoutMs;
        while (System.currentTimeMillis() < deadline && !Player.mainWindowShown(1000)) Thread.sleep(200);
        final Recorder rec = new Recorder(cfg, sc, golden);
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                new RecorderToolbar(rec).setVisible(true);
            }
        });
    }

    private static String stripExt(String n) {
        int i = n.lastIndexOf('.');
        return i > 0 ? n.substring(0, i) : n;
    }
}
