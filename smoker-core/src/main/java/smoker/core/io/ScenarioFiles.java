package smoker.core.io;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import smoker.core.golden.GoldenCodec;
import smoker.core.model.Golden;
import smoker.core.model.Scenario;
import smoker.core.model.Snapshot;

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

    /** @throws IllegalArgumentException for the old format or a newer schemaVersion */
    public static Scenario readScenario(Path file) throws IOException {
        JsonObject root = checkedRoot(file);
        JsonElement steps = root.get("steps");
        if (steps != null && steps.isJsonArray()) {
            for (JsonElement step : steps.getAsJsonArray()) {
                if (step.isJsonObject() && step.getAsJsonObject().has("expected")) throw oldFormat(file);
            }
        }
        return Json.fromTree(root, Scenario.class);
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
        for (int i = 0; i < g.steps.size(); i++) {
            Golden.GoldenStep s = g.steps.get(i);
            boolean ok = i == 0 ? s.full != null && s.changes == null : s.changes != null && s.full == null;
            if (!ok) {
                throw new IllegalArgumentException(file + ": step " + s.id
                        + (i == 0 ? " must have \"full\" and no \"changes\"." : " must have \"changes\" and no \"full\"."));
            }
        }
        return GoldenCodec.decode(g);
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
