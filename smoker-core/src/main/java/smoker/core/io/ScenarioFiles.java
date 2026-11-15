package smoker.core.io;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import smoker.core.golden.GoldenCodec;
import smoker.core.model.Golden;
import smoker.core.model.Scenario;
import smoker.core.model.Snapshot;
import smoker.core.model.WindowKey;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;

/**
 * Reads and writes the scenario file and the golden file {@code <scenario>.golden.json} beside it (F-06).
 * Both files have {@code schemaVersion} 2. The old format (goldens in the steps) is version 1 and is refused (F-17).
 */
public final class ScenarioFiles {

    public static final int SCHEMA_VERSION = 2;

    private static final String SCENARIO_EXT = ".json";
    private static final String GOLDEN_EXT = ".golden.json";

    private ScenarioFiles() {}

    /** @throws IllegalArgumentException for the old format, a newer schemaVersion, a wrong compare or window key */
    public static Scenario readScenario(Path file) throws IOException {
        JsonObject root = checkedRoot(file);
        JsonElement steps = root.get("steps");
        if (steps != null && steps.isJsonArray()) {
            for (JsonElement step : steps.getAsJsonArray()) {
                if (step.isJsonObject() && step.getAsJsonObject().has("expected")) throw oldFormat(file);
            }
        }
        Scenario sc = Json.fromTree(root, Scenario.class);
        if (!"all".equals(sc.compare) && !"named".equals(sc.compare)) {
            throw new IllegalArgumentException(file + ": compare must be \"all\" or \"named\", not \"" + sc.compare + "\".");
        }
        try {
            WindowKey.compile(sc.windowKeys);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(file + ": " + e.getMessage(), e);
        }
        return sc;
    }

    /** {@code dir/greet.json} gives {@code dir/greet.golden.json}. */
    public static Path goldenPath(Path scenario) {
        String n = scenario.getFileName().toString();
        if (n.endsWith(SCENARIO_EXT)) n = n.substring(0, n.length() - SCENARIO_EXT.length());
        return scenario.resolveSibling(n + GOLDEN_EXT);
    }

    /** True for {@code *.golden.json}. A folder of scenarios also holds their golden files. */
    public static boolean isGoldenFile(Path file) {
        return file.getFileName().toString().endsWith(GOLDEN_EXT);
    }

    /**
     * @return the golden snapshot per step id, in file order; an empty map when the golden file does not exist
     * @throws IllegalArgumentException for the old format, a newer schemaVersion or a wrong structure
     */
    public static LinkedHashMap<String, Snapshot> readGolden(Path scenario) throws IOException {
        Path file = goldenPath(scenario);
        if (!Files.exists(file)) return new LinkedHashMap<String, Snapshot>();
        Golden g = Json.fromTree(checkedRoot(file), Golden.class);
        if (g.steps == null) throw new IllegalArgumentException(file + ": \"steps\" is missing.");
        for (int i = 0; i < g.steps.size(); i++) {
            Golden.GoldenStep s = g.steps.get(i);
            if (s == null) throw new IllegalArgumentException(file + ": step " + (i + 1) + " of \"steps\" is null.");
            boolean ok = i == 0 ? s.full != null && s.changes == null : s.changes != null && s.full == null;
            if (!ok) {
                throw new IllegalArgumentException(file + ": step " + s.id
                        + (i == 0 ? " must have \"full\" and no \"changes\"." : " must have \"changes\" and no \"full\"."));
            }
            if (s.changes != null) {
                for (Golden.Change c : s.changes) checkChange(file, s.id, c);
            }
        }
        return GoldenCodec.decode(g);
    }

    /** {@code add} needs {@code node}; {@code set} needs {@code node} or {@code unset}; {@code remove} has neither. */
    private static void checkChange(Path file, String stepId, Golden.Change c) {
        String where = file + ": step " + stepId + ", change " + c.path;
        if (c.path == null) throw new IllegalArgumentException(file + ": step " + stepId + ": a change has no \"path\".");
        boolean hasUnset = c.unset != null && !c.unset.isEmpty();
        if (GoldenCodec.ADD.equals(c.op)) {
            if (c.node == null) throw new IllegalArgumentException(where + ": \"add\" must have \"node\".");
        } else if (GoldenCodec.SET.equals(c.op)) {
            if (c.node == null && !hasUnset) {
                throw new IllegalArgumentException(where + ": \"set\" must have \"node\" or \"unset\".");
            }
        } else if (GoldenCodec.REMOVE.equals(c.op)) {
            if (c.node != null || c.unset != null) {
                throw new IllegalArgumentException(where + ": \"remove\" must have no \"node\" and no \"unset\".");
            }
        } else {
            throw new IllegalArgumentException(where + ": unknown op \"" + c.op
                    + "\" (use \"add\", \"set\" or \"remove\").");
        }
    }

    /** Writes the golden file beside the scenario. The order of {@code byStep} is the order in the file. */
    public static void writeGolden(Path scenario, String name, LinkedHashMap<String, Snapshot> byStep) throws IOException {
        Json.write(goldenPath(scenario), GoldenCodec.encode(name, byStep));
    }

    private static JsonObject checkedRoot(Path file) throws IOException {
        JsonElement tree = Json.readTree(file);
        if (!tree.isJsonObject()) throw new IllegalArgumentException(file + ": not a JSON object.");
        JsonObject root = tree.getAsJsonObject();
        JsonElement v = root.get("schemaVersion");
        if (v == null || v.isJsonNull()) throw oldFormat(file);
        int version;
        try {
            version = v.getAsInt();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(file + ": schemaVersion " + v + " is not a number.");
        }
        if (version < SCHEMA_VERSION) throw oldFormat(file);
        if (version > SCHEMA_VERSION) {
            throw new IllegalArgumentException(file + ": schemaVersion " + version
                    + " is newer than this s.smoker (" + SCHEMA_VERSION + ").");
        }
        return root;
    }

    private static IllegalArgumentException oldFormat(Path file) {
        return new IllegalArgumentException(file
                + ": old scenario format (schemaVersion 1). Convert it: see README, section Scenario file.");
    }
}
