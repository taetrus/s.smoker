package smoker.cli;

import smoker.core.diff.SnapshotDiff;
import smoker.core.io.Json;
import smoker.core.io.ScenarioFiles;
import smoker.core.model.NodeSnapshot;
import smoker.core.model.RunResult;
import smoker.core.model.Scenario;
import smoker.core.model.Snapshot;
import smoker.core.model.Step;
import smoker.core.report.HtmlReportWriter;
import smoker.core.report.JUnitXmlWriter;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * <pre>
 * java -jar smoker-cli.jar run     --agent smoker-agent.jar --app "java -jar app.jar" --scenarios scenarios/ --out out/ [--log app.log] [--screenshots off] [--strict]
 * java -jar smoker-cli.jar record  --agent smoker-agent.jar --app "java -jar app.jar" --scenario scenarios/new.json [--log app.log]
 * java -jar smoker-cli.jar approve --run out/greet --scenario scenarios/greet.json [--steps s001,s002]
 * </pre>
 * {@code --app} is the full command line; the token {@code {agent}} marks where the -javaagent option goes.
 * Without the token it is inserted right after the first argument (the java executable).
 * For an Equinox product: {@code --app "myapp.exe -vmargs {agent}"} or add the option to eclipse.ini.
 */
public final class Main {

    public static void main(String[] argv) throws Exception {
        if (argv.length == 0) usage(1);
        Map<String, String> o = parse(Arrays.copyOfRange(argv, 1, argv.length));
        String cmd = argv[0];
        int code;
        if ("run".equals(cmd)) code = run(o);
        else if ("record".equals(cmd)) code = record(o);
        else if ("approve".equals(cmd)) code = approve(o);
        else { usage(1); return; }
        System.exit(code);
    }

    // ---- run -----------------------------------------------------------------------------------

    static int run(Map<String, String> o) throws Exception {
        Path agent = Paths.get(req(o, "agent")).toAbsolutePath();
        Path out = Paths.get(o.containsKey("out") ? o.get("out") : "smoker-out").toAbsolutePath();
        List<Path> scenarios = scenarios(req(o, "scenarios"));
        boolean strict = o.containsKey("strict");
        // Check every scenario and golden file before the first application starts.
        Map<Path, Scenario> parsed = new HashMap<Path, Scenario>();
        try {
            for (Path sc : scenarios) {
                parsed.put(sc, validated(sc));
            }
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            return 2;
        }
        Files.createDirectories(out);
        List<RunResult> results = new ArrayList<RunResult>();
        int failures = 0;
        for (Path sc : scenarios) {
            String name = stripExt(sc.getFileName().toString());
            Path runDir = out.resolve(name);
            Files.createDirectories(runDir);
            StringBuilder agentArgs = new StringBuilder("mode=play;scenario=").append(sc.toAbsolutePath())
                    .append(";out=").append(runDir);
            if (o.containsKey("log")) agentArgs.append(";log=").append(o.get("log"));
            if (o.containsKey("screenshots")) agentArgs.append(";screenshots=").append(o.get("screenshots"));
            if (o.containsKey("failRegex")) agentArgs.append(";failRegex=").append(o.get("failRegex"));
            if (o.containsKey("allowRegex")) agentArgs.append(";allowRegex=").append(o.get("allowRegex"));

            Scenario scenario = parsed.get(sc);
            long budgetMs = scenario.startupTimeoutMs + (scenario.steps.size() + 1) * (scenario.stepTimeoutMs + 2000) + 10_000;
            if (o.containsKey("timeout")) budgetMs = Long.parseLong(o.get("timeout")) * 1000;

            System.out.println("== " + name);
            int exit = launch(req(o, "app"), agent, agentArgs.toString(), runDir.resolve("app-output.log"), budgetMs);

            RunResult r;
            Path resultFile = runDir.resolve("run-result.json");
            if (Files.exists(resultFile)) {
                r = Json.read(resultFile, RunResult.class);
            } else {
                r = new RunResult();
                r.scenarioName = name;
                r.scenarioFile = sc.toString();
                r.status = exit == -999 ? RunResult.Status.HANG : RunResult.Status.ERROR;
                r.error = exit == -999 ? "application did not exit within " + budgetMs + " ms (killed)"
                        : "no run-result.json produced; app exit code " + exit + " — see app-output.log";
                r.startedAt = r.finishedAt = System.currentTimeMillis();
            }
            // make screenshot paths relative to the report
            for (RunResult.StepResult s : r.steps) if (s.screenshot != null) s.screenshot = name + "/" + s.screenshot;
            results.add(r);
            boolean ok = r.status == RunResult.Status.PASS || (!strict && r.status == RunResult.Status.NO_GOLDEN);
            if (!ok) failures++;
            System.out.println("   " + r.status + (r.error != null ? " — " + r.error : ""));
            for (RunResult.StepResult s : r.steps) {
                if (s.status != RunResult.Status.PASS) {
                    System.out.println("   " + s.stepId + " " + s.status + (s.error != null ? ": " + s.error : ""));
                    for (String d : s.differences) System.out.println("      " + d);
                    for (RunResult.LogHit h : s.logHits) System.out.println("      [" + h.source + "] " + h.line);
                }
            }
        }
        JUnitXmlWriter.write(out.resolve("junit.xml"), results);
        HtmlReportWriter.write(out.resolve("report.html"), results);
        System.out.println("\n" + results.size() + " scenario(s), " + failures + " failed. Report: " + out.resolve("report.html"));
        return failures == 0 ? 0 : 1;
    }

    // ---- record --------------------------------------------------------------------------------

    static int record(Map<String, String> o) throws Exception {
        Path agent = Paths.get(req(o, "agent")).toAbsolutePath();
        Path scenario = Paths.get(req(o, "scenario")).toAbsolutePath();
        // The agent continues an existing scenario. Check it here: the agent can only log a wrong file.
        if (Files.exists(scenario)) {
            try {
                validated(scenario);
            } catch (IllegalArgumentException e) {
                System.err.println(e.getMessage());
                return 2;
            }
        }
        String agentArgs = "mode=record;scenario=" + scenario + (o.containsKey("log") ? ";log=" + o.get("log") : "");
        Path log = scenario.getParent().resolve(stripExt(scenario.getFileName().toString()) + ".record.log");
        int exit = launch(req(o, "app"), agent, agentArgs, log, Long.MAX_VALUE);
        System.out.println("application exited with " + exit + "; scenario file: " + scenario);
        return 0;
    }

    // ---- approve -------------------------------------------------------------------------------

    static int approve(Map<String, String> o) throws Exception {
        Path runDir = Paths.get(req(o, "run"));
        Path scFile = Paths.get(req(o, "scenario"));
        RunResult r = Json.read(runDir.resolve("run-result.json"), RunResult.class);
        Scenario sc;
        LinkedHashMap<String, Snapshot> old;
        try {
            sc = ScenarioFiles.readScenario(scFile);
            old = ScenarioFiles.readGolden(scFile);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            return 2;
        }
        // A golden of the other compare mode has other nodes: every later run would fail.
        String runCompare = r.compare != null ? r.compare : "all";
        if (!runCompare.equals(sc.compare)) {
            System.err.println("approve: run used compare=" + runCompare + ", scenario has compare=" + sc.compare
                    + "; golden would not match");
            return 2;
        }
        List<String> only = o.containsKey("steps") ? Arrays.asList(o.get("steps").split(",")) : null;
        if (only != null) {
            Set<String> inRun = new HashSet<String>();
            for (RunResult.StepResult sr : r.steps) inRun.add(sr.stepId);
            for (String id : only) {
                if (!inRun.contains(id)) System.err.println("approve: step " + id + " is not in the run");
            }
        }
        Map<String, Snapshot> merged = new HashMap<String, Snapshot>(old);
        List<String> approved = new ArrayList<String>();
        for (RunResult.StepResult sr : r.steps) {
            if (sr.actual == null) continue;
            if (only != null && !only.contains(sr.stepId)) continue;
            if (sc.findStep(sr.stepId) == null) continue; // "startup" and steps removed from the scenario
            merged.put(sr.stepId, sr.actual);
            approved.add(sr.stepId);
        }
        // The golden file has the steps of the scenario, in scenario order. Removed steps drop out.
        LinkedHashMap<String, Snapshot> golden = new LinkedHashMap<String, Snapshot>();
        for (Step s : sc.steps) {
            if (merged.containsKey(s.id)) golden.put(s.id, merged.get(s.id));
        }
        String name = sc.name != null ? sc.name : stripExt(scFile.getFileName().toString());
        ScenarioFiles.writeGolden(scFile, name, golden);
        // Spec 4.5: how many steps changed and, for each step, the number of changed components.
        Map<String, Integer> changed = new LinkedHashMap<String, Integer>();
        for (String id : approved) {
            int k = changedComponents(old.get(id), golden.get(id));
            if (k > 0) changed.put(id, k);
        }
        System.out.println("approved " + approved.size() + " step(s) into " + ScenarioFiles.goldenPath(scFile));
        System.out.println("changed " + changed.size() + " step(s)");
        for (Map.Entry<String, Integer> e : changed.entrySet()) {
            System.out.println("  " + e.getKey() + ": " + e.getValue() + " component(s) changed");
        }
        return 0;
    }

    /** Number of paths that were added, removed or changed. Without an old snapshot, all nodes count. */
    static int changedComponents(Snapshot before, Snapshot after) {
        Map<String, NodeSnapshot> a = SnapshotDiff.byPath(before);
        Map<String, NodeSnapshot> b = SnapshotDiff.byPath(after);
        Set<String> paths = new HashSet<String>(a.keySet());
        paths.addAll(b.keySet());
        int n = 0;
        for (String p : paths) {
            NodeSnapshot x = a.get(p);
            NodeSnapshot y = b.get(p);
            if (x == null || y == null || !Json.toString(x).equals(Json.toString(y))) n++;
        }
        return n;
    }

    // ---- helpers -------------------------------------------------------------------------------

    /**
     * Reads the scenario file and its golden file, so that a wrong file stops the command before the application starts.
     *
     * @throws IllegalArgumentException for the old format, a newer schemaVersion or a wrong structure
     */
    static Scenario validated(Path scenario) throws IOException {
        Scenario sc = ScenarioFiles.readScenario(scenario);
        ScenarioFiles.readGolden(scenario);
        return sc;
    }

    /** @return exit code, or -999 if killed on timeout. */
    static int launch(String appCmd, Path agentJar, String agentArgs, Path outputLog, long budgetMs) throws IOException, InterruptedException {
        String javaagent = "-javaagent:" + agentJar + "=" + agentArgs;
        List<String> cmd = new ArrayList<String>(splitCommand(appCmd));
        int at = cmd.indexOf("{agent}");
        if (at >= 0) cmd.set(at, javaagent);
        else cmd.add(1, javaagent);
        System.out.println("   $ " + join(cmd));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        pb.redirectOutput(outputLog.toFile());
        Process p = pb.start();
        if (budgetMs == Long.MAX_VALUE) return p.waitFor();
        if (!p.waitFor(budgetMs, TimeUnit.MILLISECONDS)) {
            p.destroyForcibly();
            p.waitFor(10, TimeUnit.SECONDS);
            return -999;
        }
        return p.exitValue();
    }

    /** Minimal shell-like splitting: whitespace separated, double quotes group. */
    static List<String> splitCommand(String s) {
        List<String> out = new ArrayList<String>();
        StringBuilder cur = new StringBuilder();
        boolean q = false;
        for (char c : s.toCharArray()) {
            if (c == '"') q = !q;
            else if (Character.isWhitespace(c) && !q) { if (cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); } }
            else cur.append(c);
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    static List<Path> scenarios(String spec) throws IOException {
        List<Path> out = new ArrayList<Path>();
        for (String s : spec.split(File.pathSeparator)) {
            Path p = Paths.get(s);
            if (Files.isDirectory(p)) {
                DirectoryStream<Path> ds = Files.newDirectoryStream(p, "*.json");
                try {
                    for (Path f : ds) if (!ScenarioFiles.isGoldenFile(f)) out.add(f);
                } finally {
                    ds.close();
                }
            } else {
                out.add(p);
            }
        }
        java.util.Collections.sort(out);
        return out;
    }

    static Map<String, String> parse(String[] args) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--")) continue;
            String k = args[i].substring(2);
            if (i + 1 < args.length && !args[i + 1].startsWith("--")) m.put(k, args[++i]);
            else m.put(k, "true");
        }
        return m;
    }

    static String req(Map<String, String> o, String k) {
        if (!o.containsKey(k)) {
            System.err.println("missing --" + k);
            usage(2);
        }
        return o.get(k);
    }

    static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) sb.append(s.contains(" ") ? "\"" + s + "\"" : s).append(' ');
        return sb.toString().trim();
    }

    static String stripExt(String n) {
        int i = n.lastIndexOf('.');
        return i > 0 ? n.substring(0, i) : n;
    }

    static void usage(int code) {
        System.err.println("usage:\n"
                + "  run     --agent smoker-agent.jar --app \"<cmd>\" --scenarios <dir|file> --out <dir> [--log <file>] [--screenshots off] [--strict] [--timeout <s>]\n"
                + "  record  --agent smoker-agent.jar --app \"<cmd>\" --scenario <file> [--log <file>]\n"
                + "  approve --run <out/scenario-dir> --scenario <file> [--steps s001,s002]\n"
                + "  <cmd> may contain the token {agent} where -javaagent:... must be placed (default: after the first word).");
        System.exit(code);
    }
}
