package smoker.agent;

import smoker.core.io.ScenarioFiles;
import smoker.core.model.Scenario;
import smoker.core.model.Snapshot;

import javax.swing.SwingUtilities;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;

/**
 * Entry point: {@code -javaagent:smoker-agent.jar=config=<file>} (R-04).
 * No bytecode instrumentation is performed; the agent only needs to be in the JVM early to install hooks.
 * Works unchanged inside an Equinox/Felix launch because Swing lives on the bootstrap classpath.
 */
public final class SmokerAgent {

    private static volatile Observations OBS;

    private SmokerAgent() {}

    public static void premain(String args, Instrumentation inst) {
        AgentConfig cfg;
        try {
            cfg = parseConfig(args);
        } catch (IllegalArgumentException e) {
            System.exit(2); // as the fatal path of start: the run cannot start
            return;
        }
        start(cfg);
    }

    /** Dynamic attach entry (Java 9+ needs {@code -Djdk.attach.allowAttachSelf} on the attaching side). */
    public static void agentmain(String args, Instrumentation inst) {
        start(parseConfig(args)); // the attach call fails, the application continues
    }

    /** Parses the agent arguments. A bad argument gives one line {@code [smoker] fatal: <message>} on System.err. */
    static AgentConfig parseConfig(String args) {
        try {
            return AgentConfig.fromArgs(args);
        } catch (IllegalArgumentException e) {
            System.err.println("[smoker] fatal: " + e.getMessage());
            throw e;
        }
    }

    static Observations observations() {
        return OBS;
    }

    static void log(String s) {
        System.out.println("[smoker] " + s);
    }

    private static void start(final AgentConfig cfg) {
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
        log("agent started, mode=" + cfg.mode + ", scenario=" + cfg.scenarios.get(0));
    }

    private static void play(AgentConfig cfg, Observations obs, EdtWatchdog watchdog) throws Exception {
        Path file = cfg.scenarios.get(0);
        Scenario sc = ScenarioFiles.readScenario(file);
        String name = stripExt(file.getFileName().toString());
        if (sc.name == null) sc.name = name;
        LinkedHashMap<String, Snapshot> golden = ScenarioFiles.readGolden(file);
        Locators.useWindowKeys(new WindowKeys(sc.windowKeys));
        AutoNames.enable(sc.autoNames);
        Path outDir = cfg.out.resolve(name);
        Files.createDirectories(outDir);
        new Player(cfg, file, sc, golden, obs, watchdog, outDir).run();
    }

    private static void record(final AgentConfig cfg) throws Exception {
        final Scenario sc;
        Path file = cfg.scenarios.get(0);
        LinkedHashMap<String, Snapshot> golden = new LinkedHashMap<String, Snapshot>();
        if (Files.exists(file)) {
            sc = ScenarioFiles.readScenario(file); // continue an existing scenario and keep its snapshots
            golden = ScenarioFiles.readGolden(file);
        } else {
            sc = new Scenario();
            sc.name = stripExt(file.getFileName().toString());
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
