package smoker.agent;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parsed from the {@code -javaagent:smoker-agent.jar=key=value;key=value} string.
 * <pre>
 *   mode=play|record           (required)
 *   scenario=path/to/x.json    (required)
 *   out=path/to/run-dir        (play: results + screenshots; record: where the scenario is saved)
 *   log=path/to/app.log        (optional, repeatable with ';' separated list via log=a.log,b.log)
 *   failRegex=ERROR|Exception  (optional, default below)
 *   allowRegex=KnownNoise      (optional)
 *   screenshots=evidence|off   (default evidence)
 *   edtHangMs=5000             (watchdog threshold)
 *   exitWhenDone=true|false    (play: System.exit after writing results)
 * </pre>
 * Separator is ';' because ',' is also used in log file lists; '=' separates key and value.
 */
public final class AgentConfig {

    public enum Mode { PLAY, RECORD }

    public Mode mode = Mode.PLAY;
    public Path scenario;
    public Path out = Paths.get("smoker-out");
    public List<Path> logFiles = new ArrayList<Path>();
    public String failRegex = "\\bERROR\\b|\\bSEVERE\\b|Exception|\\bError\\b";
    public String allowRegex = null;
    public boolean screenshots = true;
    public long edtHangMs = 5000;
    public boolean exitWhenDone = true;

    public static AgentConfig parse(String args) {
        AgentConfig c = new AgentConfig();
        if (args == null || args.trim().isEmpty()) throw new IllegalArgumentException("smoker: agent arguments missing");
        Map<String, String> kv = new LinkedHashMap<String, String>();
        for (String part : args.split(";")) {
            int i = part.indexOf('=');
            if (i < 0) continue;
            kv.put(part.substring(0, i).trim(), part.substring(i + 1).trim());
        }
        String mode = kv.get("mode");
        if (mode != null) c.mode = Mode.valueOf(mode.toUpperCase(Locale.ROOT));
        if (kv.get("scenario") == null) throw new IllegalArgumentException("smoker: scenario=<file> is required");
        c.scenario = Paths.get(kv.get("scenario"));
        if (kv.get("out") != null) c.out = Paths.get(kv.get("out"));
        if (kv.get("log") != null) for (String f : kv.get("log").split(",")) if (!f.trim().isEmpty()) c.logFiles.add(Paths.get(f.trim()));
        if (kv.get("failRegex") != null) c.failRegex = kv.get("failRegex");
        if (kv.get("allowRegex") != null) c.allowRegex = kv.get("allowRegex");
        if (kv.get("screenshots") != null) c.screenshots = !"off".equalsIgnoreCase(kv.get("screenshots"));
        if (kv.get("edtHangMs") != null) c.edtHangMs = Long.parseLong(kv.get("edtHangMs"));
        if (kv.get("exitWhenDone") != null) c.exitWhenDone = Boolean.parseBoolean(kv.get("exitWhenDone"));
        return c;
    }
}
