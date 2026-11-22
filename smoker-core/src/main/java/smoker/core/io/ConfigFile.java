package smoker.core.io;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The agent configuration file: one {@code key=value} per line, UTF-8. The first {@code =} ends the key.
 * Key and value are trimmed. Empty lines and lines that start with {@code #} are skipped.
 * A value has no escape rules: a backslash, {@code ;} and {@code =} stay as they are (R-39).
 */
public final class ConfigFile {

    private ConfigFile() {}

    /**
     * @throws IllegalArgumentException for a line without {@code =} and for a key that occurs two times
     */
    public static LinkedHashMap<String, String> read(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        LinkedHashMap<String, String> m = new LinkedHashMap<String, String>();
        for (int n = 0; n < lines.size(); n++) {
            String line = lines.get(n);
            if (n == 0 && line.startsWith("﻿")) line = line.substring(1);
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            int i = t.indexOf('=');
            if (i < 0) throw new IllegalArgumentException(file + ":" + (n + 1) + ": no '=' in the line");
            String key = t.substring(0, i).trim();
            if (m.containsKey(key)) {
                throw new IllegalArgumentException(file + ":" + (n + 1) + ": key '" + key + "' occurs two times");
            }
            m.put(key, t.substring(i + 1).trim());
        }
        return m;
    }

    /** @throws IllegalArgumentException for a key or value with a line break */
    public static void write(Path file, Map<String, String> values) throws IOException {
        List<String> lines = new ArrayList<String>();
        for (Map.Entry<String, String> e : values.entrySet()) {
            String k = e.getKey();
            String v = e.getValue();
            if (hasBreak(k) || hasBreak(v)) {
                throw new IllegalArgumentException(k + ": a value cannot contain a line break");
            }
            lines.add(k + "=" + v);
        }
        Files.write(file, lines, StandardCharsets.UTF_8);
    }

    private static boolean hasBreak(String s) {
        return s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0;
    }
}
