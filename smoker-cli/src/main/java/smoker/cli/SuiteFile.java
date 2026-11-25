package smoker.cli;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import smoker.core.io.Json;
import smoker.core.io.ScenarioFiles;
import smoker.core.model.ConditionValues;
import smoker.core.model.Scenario;
import smoker.core.proc.Processes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** The suite file of {@code run --suite}: the application, the commands and the scenarios (WP-05, F-03, F-04). */
final class SuiteFile {

    private static final Set<String> FIELDS = new HashSet<String>(Arrays.asList(
            "app", "workDir", "agentConfig", "before", "reset", "resetScenario", "after", "scenarios"));
    private static final Set<String> AGENT_KEYS = new HashSet<String>(Arrays.asList(
            "log", "logPattern", "failLevel", "failRegex", "allowRegex", "logCharset", "screenshots", "edtHangMs",
            "stopTimeoutMs", "locale", "timeZone", "lookAndFeel", "windowSize"));
    private static final Set<String> COMMAND_FIELDS = new HashSet<String>(Arrays.asList(
            "command", "timeoutS", "background", "readyPort", "readyTimeoutS"));

    /** A command line that the CLI runs around the scenarios. */
    static final class Command {
        String command;
        long timeoutS = 60;
        boolean background;
        Integer readyPort;
        long readyTimeoutS = 60;
    }

    String app;
    Path workDir;
    LinkedHashMap<String, String> agentConfig = new LinkedHashMap<String, String>();
    List<String> logFiles = new ArrayList<String>();
    List<Command> before = new ArrayList<Command>();
    List<Command> reset = new ArrayList<Command>();
    List<Command> after = new ArrayList<Command>();
    Path resetScenario;
    List<Path> scenarios = new ArrayList<Path>();
    Map<Path, Scenario> parsed = new LinkedHashMap<Path, Scenario>();
    Scenario parsedReset;

    private SuiteFile() {}

    /** @throws IllegalArgumentException with a text that starts with {@code suite: } for each wrong file */
    static SuiteFile read(Path file) throws IOException {
        JsonElement tree;
        try {
            tree = Json.readTree(file);
        } catch (IOException e) {
            throw new IllegalArgumentException("suite: cannot read " + file + ": " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("suite: " + file + " is not valid JSON: " + e.getMessage(), e);
        }
        if (tree == null || !tree.isJsonObject()) throw new IllegalArgumentException("suite: " + file + " must hold a JSON object");
        JsonObject root = tree.getAsJsonObject();
        for (Map.Entry<String, JsonElement> e : root.entrySet()) {
            if (!FIELDS.contains(e.getKey())) throw new IllegalArgumentException("suite: unknown field '" + e.getKey() + "'");
        }
        Path base = file.toAbsolutePath().normalize().getParent();
        SuiteFile s = new SuiteFile();
        s.app = commandLine(string(root, "app", true), "app");
        String wd = string(root, "workDir", false);
        s.workDir = wd == null ? Paths.get("").toAbsolutePath().normalize() : resolve(base, wd);
        if (root.has("agentConfig")) s.readAgentConfig(object(root.get("agentConfig"), "agentConfig"));
        s.before = commands(root, "before");
        s.reset = commands(root, "reset");
        s.after = commands(root, "after");
        for (Command c : s.before) {
            if (c.readyPort != null && !c.background) throw new IllegalArgumentException("suite: readyPort needs background in before");
        }
        String rs = string(root, "resetScenario", false);
        if (rs != null) {
            s.resetScenario = resolve(base, rs);
            s.parsedReset = readOne(s.resetScenario, false);
        }
        if (!root.has("scenarios")) throw new IllegalArgumentException("suite: missing field 'scenarios'");
        s.readScenarios(array(root.get("scenarios"), "scenarios"), base);
        return s;
    }

    private void readAgentConfig(JsonObject o) {
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            String k = e.getKey();
            if (!AGENT_KEYS.contains(k)) throw new IllegalArgumentException("suite: unknown agentConfig key '" + k + "'");
            if ("log".equals(k)) {
                if (e.getValue().isJsonArray()) {
                    for (JsonElement l : e.getValue().getAsJsonArray()) logFiles.add(noBreak(text(l, "agentConfig.log"), "agentConfig.log"));
                } else {
                    logFiles.add(noBreak(text(e.getValue(), "agentConfig.log"), "agentConfig.log"));
                }
            } else {
                agentConfig.put(k, agentValue(k, e.getValue()));
            }
        }
    }

    /** The text of one agentConfig value, checked for the keys that the CLI or the agent reads as a number or a choice. */
    private static String agentValue(String key, JsonElement value) {
        String name = "agentConfig." + key;
        if ("screenshots".equals(key)) {
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                String v = value.getAsString().trim().toLowerCase(Locale.ROOT);
                if ("evidence".equals(v) || "off".equals(v)) return v;
            }
            throw new IllegalArgumentException("suite: '" + name + "' must be \"evidence\" or \"off\"");
        }
        String v = noBreak(text(value, name), name);
        if ("stopTimeoutMs".equals(key) || "edtHangMs".equals(key)) {
            if (!v.matches("[0-9]{1,15}")) {
                throw new IllegalArgumentException("suite: '" + name + "' must be a whole number, 0 or more");
            }
        }
        try {
            if ("locale".equals(key)) ConditionValues.locale(v);
            if ("windowSize".equals(key)) ConditionValues.windowSize(v);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("suite: " + e.getMessage(), e);
        }
        return v;
    }

    /** A command line: not empty, no line break. */
    private static String commandLine(String v, String name) {
        if (v.trim().isEmpty() || Processes.split(v).isEmpty()) throw new IllegalArgumentException("suite: '" + name + "' must not be empty");
        return noBreak(v, name);
    }

    private static String noBreak(String v, String name) {
        if (v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("suite: '" + name + "' must not contain a line break");
        }
        return v;
    }

    private void readScenarios(JsonArray list, Path base) throws IOException {
        List<Path> files = new ArrayList<Path>();
        for (JsonElement e : list) {
            Path p = resolve(base, text(e, "scenarios"));
            if (Files.isDirectory(p)) files.addAll(Main.scenariosIn(p));
            else files.add(p);
        }
        if (files.isEmpty()) throw new IllegalArgumentException("suite: 'scenarios' has no scenario");
        Map<String, Path> names = new HashMap<String, Path>();
        for (Path p : files) {
            String name = Main.stripExt(p.getFileName().toString());
            if (names.put(name.toLowerCase(Locale.ROOT), p) != null) throw new IllegalArgumentException("suite: two scenarios have the name '" + name + "'");
            scenarios.add(p);
            parsed.put(p, readOne(p, true));
        }
    }

    private static Scenario readOne(Path p, boolean withGolden) throws IOException {
        if (!Files.isRegularFile(p)) throw new IllegalArgumentException("suite: scenario file not found: " + p);
        try {
            return withGolden ? Main.validated(p) : ScenarioFiles.readScenario(p);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("suite: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new IllegalArgumentException("suite: " + p + ": cannot read: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("suite: " + p + ": " + e.getMessage(), e);
        }
    }

    private static List<Command> commands(JsonObject root, String field) {
        List<Command> out = new ArrayList<Command>();
        if (!root.has(field)) return out;
        JsonArray a = array(root.get(field), field);
        for (int i = 0; i < a.size(); i++) {
            String where = field + "[" + i + "]";
            JsonObject o = object(a.get(i), where);
            for (String k : o.keySet()) {
                if (!COMMAND_FIELDS.contains(k)) throw new IllegalArgumentException("suite: unknown field '" + k + "' in " + where);
            }
            Command c = new Command();
            c.command = commandLine(string(o, "command", true, where), where + ".command");
            if (o.has("timeoutS")) c.timeoutS = number(o, "timeoutS", where);
            if (o.has("readyTimeoutS")) c.readyTimeoutS = number(o, "readyTimeoutS", where);
            if (o.has("readyPort")) {
                long port = number(o, "readyPort", where);
                if (port < 1 || port > 65535) throw new IllegalArgumentException("suite: " + where + ": readyPort must be 1..65535");
                c.readyPort = (int) port;
            }
            if (o.has("background")) {
                JsonElement b = o.get("background");
                if (!b.isJsonPrimitive() || !b.getAsJsonPrimitive().isBoolean()) {
                    throw new IllegalArgumentException("suite: background in " + where + " must be true or false");
                }
                c.background = b.getAsBoolean();
            }
            if (c.background && !"before".equals(field)) {
                throw new IllegalArgumentException("suite: background is permitted only in before, not in " + where);
            }
            out.add(c);
        }
        return out;
    }

    private static Path resolve(Path base, String p) {
        return base.resolve(p).toAbsolutePath().normalize();
    }

    private static String string(JsonObject o, String key, boolean required) {
        return string(o, key, required, null);
    }

    private static String string(JsonObject o, String key, boolean required, String where) {
        if (!o.has(key)) {
            if (required) throw new IllegalArgumentException("suite: missing field '" + key + "'" + (where == null ? "" : " in " + where));
            return null;
        }
        return text(o.get(key), where == null ? key : where + "." + key);
    }

    private static long number(JsonObject o, String key, String where) {
        JsonElement e = o.get(key);
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) {
            double d = e.getAsDouble();
            if (d == Math.rint(d) && d >= 0) return (long) d;
        }
        throw new IllegalArgumentException("suite: " + key + " in " + where + " must be a whole number, 0 or more");
    }

    /** A string, or a number as its decimal text. */
    private static String text(JsonElement e, String name) {
        if (e != null && e.isJsonPrimitive()) {
            JsonPrimitive p = e.getAsJsonPrimitive();
            if (p.isString()) return p.getAsString();
            if (p.isNumber()) {
                double d = p.getAsDouble();
                return d == Math.rint(d) && Math.abs(d) < 1e15 ? Long.toString((long) d) : p.getAsString();
            }
            if (p.isBoolean() && name.startsWith("agentConfig.")) return Boolean.toString(p.getAsBoolean());
        }
        throw new IllegalArgumentException("suite: '" + name + "' must be a string");
    }

    private static JsonObject object(JsonElement e, String name) {
        if (e == null || !e.isJsonObject()) throw new IllegalArgumentException("suite: '" + name + "' must be an object");
        return e.getAsJsonObject();
    }

    private static JsonArray array(JsonElement e, String name) {
        if (e == null || !e.isJsonArray()) throw new IllegalArgumentException("suite: '" + name + "' must be an array");
        return e.getAsJsonArray();
    }
}
