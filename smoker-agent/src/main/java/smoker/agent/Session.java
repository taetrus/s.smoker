package smoker.agent;

import smoker.core.io.Json;
import smoker.core.io.ScenarioFiles;
import smoker.core.model.RunResult;
import smoker.core.model.Scenario;
import smoker.core.model.Snapshot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Runs the launch list of the agent in one JVM (WP-05, F-03, R-36): one player for each scenario, one shutdown
 * hook, one stop at the end. The list ends at the first scenario that is not PASS or NO_GOLDEN, or when the CLI
 * stops. Runs on the thread of the caller.
 */
final class Session implements Runnable {

    /** The CLI touches the alive file at least each 2 s (Task 7); a file older than this means the CLI stopped. */
    static final long ALIVE_MAX_AGE_MS = 10_000;

    private final AgentConfig cfg;
    private final Observations obs;
    private final EdtWatchdog watchdog;
    private final AppStopper.Exit exit;
    /** The player that runs now. The hook reads it. */
    private volatile Player current;

    Session(AgentConfig cfg, Observations obs, EdtWatchdog watchdog, AppStopper.Exit exit) {
        this.cfg = cfg;
        this.obs = obs;
        this.watchdog = watchdog;
        this.exit = exit;
    }

    /** @return false when the alive file is set and missing or older than 10 s */
    static boolean cliAlive(Path aliveFile, long nowMs) {
        if (aliveFile == null) return true;
        try {
            return nowMs - Files.getLastModifiedTime(aliveFile).toMillis() <= ALIVE_MAX_AGE_MS;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void run() {
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                Player p = current;
                if (p != null) p.onExit();
            }
        }, "smoker-exit"));
        boolean bad = false;
        RunResult.Status last = null;
        long lastEnd = Long.MIN_VALUE;
        try {
            for (int i = 0; i < cfg.scenarios.size(); i++) {
                if (i > 0 && !cliAlive(cfg.aliveFile, System.currentTimeMillis())) {
                    SmokerAgent.log("the CLI stopped; stopping the application");
                    break;
                }
                Path file = cfg.scenarios.get(i);
                String name = SmokerAgent.stripExt(file.getFileName().toString());
                Path outDir = cfg.out.resolve(name);
                try {
                    Scenario sc;
                    Map<String, Snapshot> golden;
                    try {
                        sc = ScenarioFiles.readScenario(file);
                        golden = ScenarioFiles.readGolden(file);
                    } catch (Exception e) {
                        writeReadError(file, name, outDir, i > 0, e);
                        bad = true;
                        last = RunResult.Status.ERROR;
                        break;
                    }
                    if (sc.name == null) sc.name = name;
                    Locators.useWindowKeys(new WindowKeys(sc.windowKeys));
                    AutoNames.enable(sc.autoNames);
                    Files.createDirectories(outDir);
                    Player player = new Player(cfg, file, sc, golden, obs, watchdog, outDir, i > 0);
                    Player.First first = i == 0 ? Player.First.STARTUP : Player.First.RESET;
                    player.begin(first);
                    current = player;
                    last = player.play(first, lastEnd);
                    lastEnd = player.lastEnd();
                } catch (Throwable t) {
                    System.err.println("[smoker] fatal: " + t);
                    bad = true;
                    last = RunResult.Status.ERROR;
                    break;
                }
                if (last != RunResult.Status.PASS && last != RunResult.Status.NO_GOLDEN) bad = true;
                if (bad) break;
            }
        } finally {
            obs.stop();
            watchdog.shutdown();
            if (cfg.exitWhenDone) {
                new AppStopper(cfg.stopTimeoutMs, exit).stop(bad ? 1 : 0, last == RunResult.Status.HANG);
            }
        }
    }

    /** A scenario file that cannot be read gives a result with status ERROR in its folder. */
    private static void writeReadError(Path file, String name, Path outDir, boolean shared, Exception e) {
        RunResult r = new RunResult();
        r.scenarioName = name;
        r.scenarioFile = file.toString();
        r.startedAt = System.currentTimeMillis();
        r.finishedAt = r.startedAt;
        r.status = RunResult.Status.ERROR;
        r.error = String.valueOf(e);
        r.sharedJvm = shared;
        SmokerAgent.log("scenario '" + name + "' ERROR: " + e);
        try {
            Files.createDirectories(outDir);
            Json.writeAtomic(outDir.resolve("run-result.json"), r);
        } catch (Exception io) {
            System.err.println("smoker: cannot write result: " + io);
        }
    }
}
