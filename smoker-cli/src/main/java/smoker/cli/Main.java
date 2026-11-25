package smoker.cli;

import smoker.core.diff.SnapshotDiff;
import smoker.core.io.ConfigFile;
import smoker.core.io.Json;
import smoker.core.io.ScenarioFiles;
import smoker.core.model.Action;
import smoker.core.model.JobResult;
import smoker.core.model.LintFile;
import smoker.core.model.NodeSnapshot;
import smoker.core.model.RunResult;
import smoker.core.model.Scenario;
import smoker.core.model.Snapshot;
import smoker.core.model.Step;
import smoker.core.proc.Processes;
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
 * java -jar smoker-cli.jar run     --agent smoker-agent.jar --app "java -jar app.jar" --scenarios scenarios/ --out out/ [--log app.log] [--screenshots off] [--strict] [--label l]
 *     [--failRegex r] [--allowRegex r] [--logPattern p] [--failLevel L] [--logCharset c]
 * java -jar smoker-cli.jar run     --agent smoker-agent.jar --suite suite.json [--out out/] [--strict] [--timeout s] [--label l]
 * java -jar smoker-cli.jar record  --agent smoker-agent.jar --app "java -jar app.jar" --scenario scenarios/new.json [--log app.log]
 *     [--logPattern p] [--failLevel L] [--logCharset c]
 * java -jar smoker-cli.jar approve --run out/greet --scenario scenarios/greet.json [--steps s001,s002]
 * java -jar smoker-cli.jar lint    --run out/ [--emit-patch names.patch]   (--run scenarios/ reads the *.lint.json files of record)
 * java -jar smoker-cli.jar report  --merge out-jre8/ out-jre21/ --out merged/   (one page for the job-result.json files)
 * </pre>
 * {@code --app} is the full command line; the token {@code {agent}} marks where the -javaagent option goes.
 * Without the token it is inserted right after the first argument (the java executable).
 * For an Equinox product: {@code --app "myapp.exe -vmargs {agent}"} or add the option to eclipse.ini.
 */
public final class Main {

    public static void main(String[] argv) throws Exception {
        if (argv.length == 0) usage(1);
        if ("report".equals(argv[0])) System.exit(Report.run(Arrays.copyOfRange(argv, 1, argv.length)));
        Map<String, String> o = parse(Arrays.copyOfRange(argv, 1, argv.length));
        String cmd = argv[0];
        int code;
        if ("run".equals(cmd)) code = run(o);
        else if ("record".equals(cmd)) code = record(o);
        else if ("approve".equals(cmd)) code = approve(o);
        else if ("lint".equals(cmd)) code = lint(o);
        else { usage(1); return; }
        System.exit(code);
    }

    // ---- run -----------------------------------------------------------------------------------

    /** Time limit of one application run: startup, one limit per step, plus the timeout of each WAIT_FOR. */
    static long budgetMs(Scenario s) {
        long ms = s.startupTimeoutMs + (s.steps.size() + 1) * (s.stepTimeoutMs + 2000) + 10_000;
        for (Step st : s.steps) {
            if (st.action != null && st.action.type == Action.Type.WAIT_FOR) ms += st.action.waitTimeoutMs();
        }
        return ms;
    }

    private static final java.util.Collection<String> SUITE_OPTIONS = Arrays.asList("suite", "agent", "out", "strict", "timeout", "label");

    /** {@code run --suite}: the suite file holds everything else (WP-05). */
    static int runSuite(Map<String, String> o) throws Exception {
        for (String k : o.keySet()) {
            if (!SUITE_OPTIONS.contains(k)) {
                System.err.println("run: --" + k + " is not permitted with --suite; put it in the suite file");
                return 2;
            }
        }
        if (o.containsKey("label") && checkLabel(o.get("label")) != null) {
            System.err.println(checkLabel(o.get("label")));
            return 2;
        }
        Path agent = Paths.get(req(o, "agent")).toAbsolutePath();
        Path out = Paths.get(o.containsKey("out") ? o.get("out") : "smoker-out").toAbsolutePath();
        Long timeoutS = null;
        try {
            if (o.containsKey("timeout")) timeoutS = Long.valueOf(o.get("timeout"));
        } catch (NumberFormatException e) {
            System.err.println("run: --timeout must be a whole number of seconds");
            return 2;
        }
        SuiteFile suite;
        try {
            suite = SuiteFile.read(Paths.get(o.get("suite")));
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            return 2;
        }
        return new SuiteRunner(suite, agent, out, o.containsKey("strict"), timeoutS, o.get("label")).run();
    }

    static int run(Map<String, String> o) throws Exception {
        if (o.containsKey("suite")) return runSuite(o);
        String label = o.get("label");
        if (o.containsKey("label") && checkLabel(label) != null) {
            System.err.println(checkLabel(label));
            return 2;
        }
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
        List<String> keys = new ArrayList<String>();
        int failures = 0;
        for (int index = 0; index < scenarios.size(); index++) {
            Path sc = scenarios.get(index);
            String name = stripExt(sc.getFileName().toString());
            Path runDir = out.resolve(name);
            Files.createDirectories(runDir);
            LinkedHashMap<String, String> cfg = agentConfig(o, Arrays.asList(sc.toAbsolutePath()), out);
            cfg.put("mode", "play");
            Path cfgFile = out.resolve("agent-" + (index + 1) + ".cfg");
            ConfigFile.write(cfgFile, cfg);
            Scenario scenario = parsed.get(sc);
            long budgetMs = budgetMs(scenario);
            if (o.containsKey("timeout")) budgetMs = Long.parseLong(o.get("timeout")) * 1000;

            System.out.println("== " + name);
            // An old result must not look like the result of this run.
            Files.deleteIfExists(runDir.resolve("run-result.json"));
            int exit = launch(req(o, "app"), agent, cfgFile, null, runDir.resolve("app-output.log"), budgetMs);

            RunResult r = resultOf(runDir, name, sc, exit, budgetMs, "app-output.log");
            r.label = label;
            keys.add(name);
            // make screenshot paths relative to the report
            for (RunResult.StepResult s : r.steps) if (s.screenshot != null) s.screenshot = name + "/" + s.screenshot;
            results.add(r);
            boolean ok = r.status == RunResult.Status.PASS || (!strict && r.status == RunResult.Status.NO_GOLDEN);
            if (!ok) failures++;
            printResult(r);
        }
        JUnitXmlWriter.write(out.resolve("junit.xml"), results);
        HtmlReportWriter.write(out.resolve("report.html"), results);
        Json.write(out.resolve(JobResult.FILE), JobResult.of(label, keys, results));
        System.out.println("\n" + results.size() + " scenario(s), " + failures + " failed. Report: " + out.resolve("report.html"));
        return failures == 0 ? 0 : 1;
    }

    /** The error text for a wrong {@code --label}, else null. */
    static String checkLabel(String label) {
        if (label != null && label.matches("[A-Za-z0-9._-]{1,40}")) return null;
        return "run: --label must have 1 to 40 characters of letters, digits, '.', '_' and '-'";
    }

    /** Prints the verdict of one scenario: status, error and each step that did not pass. */
    static void printResult(RunResult r) {
        System.out.println("   " + r.status + (r.error != null ? " \u2014 " + r.error : ""));
        for (RunResult.StepResult s : r.steps) {
            if (s.status != RunResult.Status.PASS) {
                System.out.println("   " + s.stepId + " " + s.status + (s.error != null ? ": " + s.error : ""));
                for (String d : s.differences) System.out.println("      " + d);
                for (RunResult.LogHit h : s.logHits) System.out.println("      [" + h.source + "] " + h.line);
            }
        }
    }

    /**
     * The agent configuration of one application run: the scenarios, the root folder of the results and the options
     * that pass through. {@code out} null leaves the key {@code out} out. The caller adds {@code mode}.
     */
    static LinkedHashMap<String, String> agentConfig(Map<String, String> o, List<Path> scenarios, Path out) {
        LinkedHashMap<String, String> c = new LinkedHashMap<String, String>();
        if (out != null) c.put("out", out.toString());
        for (int i = 0; i < scenarios.size(); i++) c.put("scenario." + (i + 1), scenarios.get(i).toAbsolutePath().toString());
        if (o.containsKey("log")) {
            String[] files = o.get("log").split(",");
            for (int i = 0; i < files.length; i++) c.put("log." + (i + 1), files[i].trim());
        }
        for (String k : new String[] {"screenshots", "failRegex", "allowRegex", "logPattern", "failLevel", "logCharset",
                "stopTimeoutMs", "label"}) {
            if (o.containsKey(k)) c.put(k, o.get(k));
        }
        return c;
    }

    /**
     * The result of one run. {@code outputLog} is the name of the output file of the application. The file of the agent has a step in {@code currentStep} and {@code finishedAt} 0 when the
     * application stopped before the end of the run (F-22). The CLI adds the exit code to the error of the exit hook.
     */
    static RunResult resultOf(Path runDir, String name, Path scenario, int exit, long budgetMs, String outputLog)
            throws IOException {
        Path resultFile = runDir.resolve("run-result.json");
        if (!Files.exists(resultFile)) {
            RunResult r = new RunResult();
            r.scenarioName = name;
            r.scenarioFile = scenario.toString();
            r.status = exit == -999 ? RunResult.Status.HANG : RunResult.Status.ERROR;
            r.error = exit == -999 ? "application did not exit within " + budgetMs + " ms (killed)"
                    : "no run-result.json produced; app exit code " + exit + " \u2014 see " + outputLog;
            r.startedAt = r.finishedAt = System.currentTimeMillis();
            return r;
        }
        RunResult r = Json.read(resultFile, RunResult.class);
        if (r.finishedAt == 0) {
            String step = r.currentStep != null ? r.currentStep : "unknown";
            if (exit == -999) {
                r.status = RunResult.Status.HANG;
                r.error = "the application stopped during step " + step + "; killed after " + budgetMs + " ms";
            } else {
                r.status = RunResult.Status.ERROR;
                r.error = "the application stopped during step " + step + "; exit code " + exit;
            }
            r.finishedAt = System.currentTimeMillis();
        } else if (r.error != null && r.error.startsWith("the application exited during step ")) {
            r.error = r.error + "; exit code " + exit;
        }
        return r;
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
        // The record log goes beside the scenario: the folder must exist before the application starts.
        try {
            Files.createDirectories(scenario.getParent());
        } catch (IOException e) {
            System.err.println("record: cannot create " + scenario.getParent() + ": " + e);
            return 2;
        }
        LinkedHashMap<String, String> cfg = agentConfig(o, Arrays.asList(scenario), null);
        cfg.put("mode", "record");
        Path log = scenario.getParent().resolve(stripExt(scenario.getFileName().toString()) + ".record.log");
        Path cfgFile = Files.createTempFile("smoker-agent-", ".cfg");
        long started = System.currentTimeMillis();
        int exit;
        try {
            ConfigFile.write(cfgFile, cfg);
            exit = launch(req(o, "app"), agent, cfgFile, null, log, Long.MAX_VALUE);
        } finally {
            Files.deleteIfExists(cfgFile);
        }
        System.out.println("application exited with " + exit + "; scenario file: " + scenario);
        printRecordLint(scenario, started);
        return 0;
    }

    /**
     * Prints the lint summary of the record session (WP-09). Prints nothing when the session did not save: the lint
     * file is then missing or from an earlier session.
     *
     * @param since the start of the session; 2 s less is accepted for a file system with a coarse file time
     */
    static void printRecordLint(Path scenario, long since) {
        Path f = ScenarioFiles.lintPath(scenario);
        try {
            if (!Files.isRegularFile(f) || Files.getLastModifiedTime(f).toMillis() < since - 2000) return;
            LintFile l = Json.read(f, LintFile.class);
            List<smoker.core.model.LintEntry> entries = l == null || l.lint == null
                    ? new ArrayList<smoker.core.model.LintEntry>() : l.lint;
            System.out.println("lint file: " + f);
            System.out.println(Lint.summary(entries));
            System.out.println("table and patch: lint --run " + f.getParent() + " --emit-patch <file>");
        } catch (Exception e) {
            System.err.println("lint: cannot read " + f + ": " + e.getMessage());
        }
    }

    // ---- approve -------------------------------------------------------------------------------

    static int approve(Map<String, String> o) throws Exception {
        Path runDir = Paths.get(req(o, "run"));
        Path scFile = Paths.get(req(o, "scenario"));
        if (!Files.exists(runDir.resolve("run-result.json"))) {
            System.err.println("approve: no run-result.json in " + runDir);
            return 2;
        }
        RunResult r = Json.read(runDir.resolve("run-result.json"), RunResult.class);
        if (r.sharedJvm) {
            System.err.println("approve: warning: the run used a shared JVM; the golden holds the state after other scenarios");
        }
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

    // ---- lint ----------------------------------------------------------------------------------

    static int lint(Map<String, String> o) throws Exception {
        Path dir = Paths.get(req(o, "run"));
        Lint.Loaded loaded;
        try {
            loaded = Files.isDirectory(dir) ? Lint.load(dir) : new Lint.Loaded();
        } catch (IOException | java.io.UncheckedIOException e) {
            System.err.println("lint: cannot read " + dir + ": " + e.getMessage());
            return 2;
        }
        if (loaded.files == 0) {
            System.err.println("lint: no run-result.json and no *.lint.json below " + dir);
            return 2;
        }
        List<smoker.core.model.LintEntry> entries = loaded.entries;
        System.out.print(Lint.table(entries));
        System.out.println();
        System.out.println(Lint.summary(entries));
        if (o.containsKey("emit-patch")) {
            Path f = Paths.get(o.get("emit-patch"));
            try {
                if (f.toAbsolutePath().getParent() != null) Files.createDirectories(f.toAbsolutePath().getParent());
                Files.write(f, Lint.patch(entries).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (IOException e) {
                System.err.println("lint: cannot write " + f + ": " + e.getMessage());
                return 2;
            }
            System.out.println("patch written to " + f);
        }
        return 0;
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

    /** Starts the application with the agent option. Its output goes to {@code outputLog}. */
    static Process start(String appCmd, Path agentJar, Path configFile, Path workDir, Path outputLog)
            throws IOException {
        String javaagent = "-javaagent:" + agentJar + "=config=" + configFile.toAbsolutePath();
        List<String> cmd = new ArrayList<String>(Processes.split(appCmd));
        int at = cmd.indexOf("{agent}");
        if (at >= 0) cmd.set(at, javaagent);
        else cmd.add(1, javaagent);
        System.out.println("   $ " + join(cmd));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (workDir != null) pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);
        pb.redirectOutput(outputLog.toFile());
        return pb.start();
    }

    /** @return exit code, or -999 if killed on timeout. */
    static int launch(String appCmd, Path agentJar, Path configFile, Path workDir, Path outputLog, long budgetMs)
            throws IOException, InterruptedException {
        Process p = start(appCmd, agentJar, configFile, workDir, outputLog);
        if (budgetMs == Long.MAX_VALUE) return p.waitFor();
        if (!p.waitFor(budgetMs, TimeUnit.MILLISECONDS)) {
            p.destroyForcibly();
            p.waitFor(10, TimeUnit.SECONDS);
            return -999;
        }
        return p.exitValue();
    }

    static List<Path> scenarios(String spec) throws IOException {
        List<Path> out = new ArrayList<Path>();
        for (String s : spec.split(File.pathSeparator)) {
            Path p = Paths.get(s);
            if (Files.isDirectory(p)) out.addAll(scenariosIn(p));
            else out.add(p);
        }
        java.util.Collections.sort(out);
        return out;
    }

    /** The scenario files of one folder: {@code *.json} without golden files and lint files, sorted. */
    static List<Path> scenariosIn(Path dir) throws IOException {
        List<Path> out = new ArrayList<Path>();
        DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.json");
        try {
            for (Path f : ds) if (ScenarioFiles.isScenarioFile(f)) out.add(f);
        } finally {
            ds.close();
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
                + "  run     --agent smoker-agent.jar --app \"<cmd>\" --scenarios <dir|file> --out <dir> [--log <file>] [--screenshots off] [--strict] [--timeout <s>] [--label <text>]\n"
                + "          [--failRegex <r>] [--allowRegex <r>] [--logPattern <p>] [--failLevel <L>] [--logCharset <c>] [--stopTimeoutMs <ms>]\n"
                + "  run     --agent smoker-agent.jar --suite <suite.json> [--out <dir>] [--strict] [--timeout <s>] [--label <text>]\n"
                + "          (the suite file holds the application, the commands, the log files and the scenarios)\n"
                + "  record  --agent smoker-agent.jar --app \"<cmd>\" --scenario <file> [--log <file>]\n"
                + "          [--logPattern <p>] [--failLevel <L>] [--logCharset <c>]\n"
                + "  approve --run <out/scenario-dir> --scenario <file> [--steps s001,s002]\n"
                + "  lint    --run <out dir or scenario folder> [--emit-patch <file>]\n"
                + "  report  --merge <dir> [<dir> ...] --out <dir> [--history <file>] [--run-id <text>] [--ref <text>]\n"
                + "  <cmd> may contain the token {agent} where -javaagent:... must be placed (default: after the first word).");
        System.exit(code);
    }
}
