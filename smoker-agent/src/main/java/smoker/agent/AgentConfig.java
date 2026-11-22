package smoker.agent;

import smoker.core.io.ConfigFile;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The configuration of the agent. {@code -javaagent:smoker-agent.jar=config=<file>} names a file of
 * {@code key=value} lines (see {@link ConfigFile}). Keys:
 * <pre>
 *   mode=play|record           (default play)
 *   scenario.1=path/to/x.json  (required; scenario.2, scenario.3 ... without a gap)
 *   out=path/to/out            (root folder: play writes to out/&lt;scenario name&gt;/; record: not used)
 *   log.1=path/to/app.log      (optional, numbered like scenario; one file per key)
 *   logPattern=%d %msg%n       (optional: Logback pattern of the log lines, default LogPattern.DEFAULT)
 *   failLevel=ERROR            (optional: TRACE|DEBUG|INFO|WARN|ERROR, a Logback event at this level is a hit)
 *   failRegex=ERROR|FATAL      (optional: regex for plain events, default below)
 *   allowRegex=KnownNoise      (optional: regex on the full text of an event, a match is never a hit)
 *   logCharset=windows-1252    (optional: charset of the log files, default the platform charset)
 *   screenshots=evidence|off   (default evidence)
 *   edtHangMs=5000             (watchdog threshold)
 *   exitWhenDone=true|false    (play: System.exit after writing results)
 *   resetCommand.1=cmd         (optional, numbered; resetTimeoutS.N=60 is the time limit of command N)
 *   resetScenario=path.json    (optional)
 *   stopTimeoutMs=10000        (optional)
 *   aliveFile=path             (optional)
 * </pre>
 */
public final class AgentConfig {

    public enum Mode { PLAY, RECORD }

    private static final Set<String> KEYS = new HashSet<String>(Arrays.asList("mode", "out", "logPattern", "failLevel",
            "failRegex", "allowRegex", "logCharset", "screenshots", "edtHangMs", "exitWhenDone", "resetScenario",
            "stopTimeoutMs", "aliveFile"));
    private static final Pattern NUMBERED = Pattern.compile("(scenario|log|resetCommand|resetTimeoutS)\\.([0-9]{1,6})");
    private static final long DEFAULT_RESET_TIMEOUT_S = 60;

    public Mode mode = Mode.PLAY;
    public List<Path> scenarios = new ArrayList<Path>();
    /** Root folder of the results. */
    public Path out = Paths.get("smoker-out");
    public List<Path> logFiles = new ArrayList<Path>();
    public String logPattern = LogPattern.DEFAULT;
    public LogLevel failLevel = LogLevel.ERROR;
    public String failRegex = "\\b(ERROR|SEVERE|FATAL)\\b";
    public Charset logCharset = Charset.defaultCharset();
    public String allowRegex = null;
    public boolean screenshots = true;
    public long edtHangMs = 5000;
    public boolean exitWhenDone = true;
    public List<String> resetCommands = new ArrayList<String>();
    public List<Long> resetTimeoutS = new ArrayList<Long>();
    public Path resetScenario;
    public long stopTimeoutMs = 10_000;
    public Path aliveFile;

    /** @param args the agent argument: {@code config=<path>}; everything after {@code config=} is the path */
    public static AgentConfig fromArgs(String args) {
        if (args == null || !args.startsWith("config=") || args.length() == "config=".length()) {
            throw new IllegalArgumentException("use config=<file> (see README)");
        }
        return fromFile(Paths.get(args.substring("config=".length())));
    }

    public static AgentConfig fromFile(Path file) {
        try {
            return fromMap(ConfigFile.read(file), file.toString());
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot read " + file + ": " + e.getMessage());
        }
    }

    /** @param source names the origin of the values in error messages */
    public static AgentConfig fromMap(Map<String, String> kv, String source) {
        AgentConfig c = new AgentConfig();
        Map<String, Map<Integer, String>> numbered = new HashMap<String, Map<Integer, String>>();
        for (Map.Entry<String, String> e : kv.entrySet()) {
            Matcher m = NUMBERED.matcher(e.getKey());
            if (m.matches()) {
                if (Integer.parseInt(m.group(2)) < 1) throw unknown(e.getKey(), source);
                Map<Integer, String> group = numbered.get(m.group(1));
                if (group == null) {
                    group = new HashMap<Integer, String>();
                    numbered.put(m.group(1), group);
                }
                group.put(Integer.valueOf(m.group(2)), e.getValue());
            } else if (!KEYS.contains(e.getKey())) {
                throw unknown(e.getKey(), source);
            }
        }
        for (String p : fill(numbered, "scenario", source, true)) c.scenarios.add(Paths.get(p));
        for (String p : fill(numbered, "log", source, false)) c.logFiles.add(Paths.get(p));
        c.resetCommands.addAll(fill(numbered, "resetCommand", source, false));
        Map<Integer, String> timeouts = numbered.containsKey("resetTimeoutS")
                ? numbered.get("resetTimeoutS") : new HashMap<Integer, String>();
        for (Integer n : timeouts.keySet()) {
            if (n.intValue() > c.resetCommands.size()) throw unknown("resetTimeoutS." + n, source);
        }
        for (int n = 1; n <= c.resetCommands.size(); n++) {
            String t = timeouts.get(Integer.valueOf(n));
            c.resetTimeoutS.add(t == null ? DEFAULT_RESET_TIMEOUT_S : Long.valueOf(Long.parseLong(t)));
        }

        String mode = kv.get("mode");
        if (mode != null) c.mode = Mode.valueOf(mode.toUpperCase(Locale.ROOT));
        if (kv.get("out") != null) c.out = Paths.get(kv.get("out"));
        if (kv.get("logPattern") != null) c.logPattern = kv.get("logPattern");
        LogPattern.compile(c.logPattern); // an unsupported pattern stops the agent at start
        if (kv.get("failLevel") != null) c.failLevel = LogLevel.parse(kv.get("failLevel"));
        if (kv.get("failRegex") != null) c.failRegex = kv.get("failRegex");
        if (kv.get("logCharset") != null) c.logCharset = charset(kv.get("logCharset"));
        if (kv.get("allowRegex") != null) c.allowRegex = kv.get("allowRegex");
        if (kv.get("screenshots") != null) c.screenshots = !"off".equalsIgnoreCase(kv.get("screenshots"));
        if (kv.get("edtHangMs") != null) c.edtHangMs = Long.parseLong(kv.get("edtHangMs"));
        if (kv.get("exitWhenDone") != null) c.exitWhenDone = Boolean.parseBoolean(kv.get("exitWhenDone"));
        if (kv.get("resetScenario") != null) c.resetScenario = Paths.get(kv.get("resetScenario"));
        if (kv.get("stopTimeoutMs") != null) c.stopTimeoutMs = Long.parseLong(kv.get("stopTimeoutMs"));
        if (kv.get("aliveFile") != null) c.aliveFile = Paths.get(kv.get("aliveFile"));
        return c;
    }

    /** The values of {@code name.1 .. name.n}; a gap is an error. */
    private static List<String> fill(Map<String, Map<Integer, String>> numbered, String name, String source, boolean required) {
        Map<Integer, String> group = numbered.get(name);
        List<String> values = new ArrayList<String>();
        if (group == null) {
            if (required) throw new IllegalArgumentException("missing key '" + name + ".1' in " + source);
            return values;
        }
        int max = Collections.max(group.keySet()).intValue();
        for (int n = 1; n <= max; n++) {
            String v = group.get(Integer.valueOf(n));
            if (v == null) throw new IllegalArgumentException("missing key '" + name + "." + n + "' in " + source);
            values.add(v);
        }
        return values;
    }

    private static IllegalArgumentException unknown(String key, String source) {
        return new IllegalArgumentException("unknown key '" + key + "' in " + source);
    }

    private static Charset charset(String name) {
        try {
            return Charset.forName(name);
        } catch (IllegalArgumentException e) { // the name is illegal or the charset is not supported
            throw new IllegalArgumentException("smoker: logCharset: '" + name + "' is not supported");
        }
    }
}
