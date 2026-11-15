package smoker.core.io;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Pretty, stable JSON so scenario files diff cleanly in Git (R-25). */
public final class Json {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();
    /** Writes primitives (escaping as {@link #GSON}). */
    private static final Gson PLAIN = new GsonBuilder().disableHtmlEscaping().create();
    /** {@link #write}: an object or array whose one-line form is at most this long is written on one line. */
    static final int ONE_LINE_MAX = 100;

    private Json() {}

    public static <T> T read(Path file, Class<T> type) throws IOException {
        Reader r = new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8);
        try {
            return GSON.fromJson(r, type);
        } finally {
            r.close();
        }
    }

    /** Reads the file as a JSON tree, to check it before it is mapped to a class. */
    public static JsonElement readTree(Path file) throws IOException {
        Reader r = new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8);
        try {
            return JsonParser.parseReader(r);
        } finally {
            r.close();
        }
    }

    public static <T> T fromTree(JsonElement tree, Class<T> type) {
        return GSON.fromJson(tree, type);
    }

    /**
     * Compact layout for all files (scenario, golden, run result): an object or array is written on one line when
     * its one-line form has at most {@value #ONE_LINE_MAX} characters, else one member per line. One changed value
     * gives one changed line, and a one-line golden entry shows the path and the value together.
     */
    public static void write(Path file, Object value) throws IOException {
        StringBuilder out = new StringBuilder();
        compact(GSON.toJsonTree(value), "", out);
        writeText(file, out);
    }

    private static void writeText(Path file, StringBuilder text) throws IOException {
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        Writer w = new OutputStreamWriter(Files.newOutputStream(file), StandardCharsets.UTF_8);
        try {
            w.write(text.toString());
            w.write('\n');
        } finally {
            w.close();
        }
    }

    private static void compact(JsonElement e, String indent, StringBuilder out) {
        String line = oneLine(e);
        if (line.length() <= ONE_LINE_MAX || !(e.isJsonObject() || e.isJsonArray())) {
            out.append(line);
        } else {
            block(e, indent, out);
        }
    }

    /** One member per line, two spaces of indent per depth, as Gson pretty printing. Empty gives {} or []. */
    private static void block(JsonElement e, String indent, StringBuilder out) {
        String inner = indent + "  ";
        boolean first = true;
        if (e.isJsonObject()) {
            out.append('{');
            for (Map.Entry<String, JsonElement> m : e.getAsJsonObject().entrySet()) {
                if (m.getValue().isJsonNull()) continue; // as Gson without serializeNulls
                out.append(first ? "\n" : ",\n").append(inner).append(PLAIN.toJson(m.getKey())).append(": ");
                compact(m.getValue(), inner, out);
                first = false;
            }
            out.append(first ? "}" : "\n" + indent + "}");
        } else {
            out.append('[');
            for (JsonElement x : e.getAsJsonArray()) {
                out.append(first ? "\n" : ",\n").append(inner);
                compact(x, inner, out);
                first = false;
            }
            out.append(first ? "]" : "\n" + indent + "]");
        }
    }

    /** {@code {"a": 1, "b": [1, 2]}}: the separators of Gson pretty printing, without line breaks. */
    private static String oneLine(JsonElement e) {
        if (!e.isJsonObject() && !e.isJsonArray()) return PLAIN.toJson(e);
        StringBuilder out = new StringBuilder();
        boolean first = true;
        if (e.isJsonObject()) {
            out.append('{');
            for (Map.Entry<String, JsonElement> m : e.getAsJsonObject().entrySet()) {
                if (m.getValue().isJsonNull()) continue;
                if (!first) out.append(", ");
                out.append(PLAIN.toJson(m.getKey())).append(": ").append(oneLine(m.getValue()));
                first = false;
            }
            return out.append('}').toString();
        }
        out.append('[');
        for (JsonElement x : e.getAsJsonArray()) {
            if (!first) out.append(", ");
            out.append(oneLine(x));
            first = false;
        }
        return out.append(']').toString();
    }

    public static String toString(Object value) {
        return GSON.toJson(value);
    }
}
