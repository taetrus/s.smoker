package smoker.cli;

import smoker.core.io.Json;
import smoker.core.model.LintEntry;
import smoker.core.model.RunResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** The lint command (WP-09): reads the lint entries of all runs and writes table, summary and patch. */
final class Lint {

    private Lint() {
    }

    /** All entries of all run-result.json files below the directory. One entry per key; the first one wins. */
    static List<LintEntry> read(Path outDir) throws IOException {
        List<Path> files;
        Stream<Path> walk = Files.walk(outDir);
        try {
            files = walk.filter(new java.util.function.Predicate<Path>() {
                public boolean test(Path p) {
                    return Files.isRegularFile(p) && "run-result.json".equals(p.getFileName().toString());
                }
            }).sorted().collect(Collectors.<Path>toList());
        } finally {
            walk.close();
        }
        Set<String> seen = new HashSet<String>();
        List<LintEntry> out = new ArrayList<LintEntry>();
        for (Path f : files) {
            RunResult r;
            try {
                r = Json.read(f, RunResult.class);
            } catch (Exception e) {
                System.err.println("lint: cannot read " + f + ": " + e.getMessage());
                continue;
            }
            if (r == null || r.lint == null) continue;
            for (LintEntry e : r.lint) {
                String key = e.window + "|" + e.runtimeClass + "|"
                        + (e.field != null ? "field:" + e.field : "text:" + e.text) + "|" + e.type;
                if (seen.add(key)) out.add(e);
            }
        }
        return out;
    }

    static String table(List<LintEntry> entries) {
        List<LintEntry> sorted = new ArrayList<LintEntry>(entries);
        Collections.sort(sorted, new Comparator<LintEntry>() {
            public int compare(LintEntry a, LintEntry b) {
                int c = nullsLast(a.window, b.window);
                if (c == 0) c = nullsLast(simple(a.runtimeClass), simple(b.runtimeClass));
                if (c == 0) c = nullsLast(a.proposedName, b.proposedName);
                return c;
            }
        });
        List<String[]> rows = new ArrayList<String[]>();
        rows.add(new String[] {"window", "class", "field", "type", "text", "automatic name", "proposed name", "source"});
        for (LintEntry e : sorted) {
            rows.add(new String[] {cell(e.window), cell(simple(e.runtimeClass)), cell(e.field), cell(e.type),
                    cell(e.text), cell(e.autoName), cell(e.proposedName), cell(e.source)});
        }
        int cols = rows.get(0).length;
        int[] width = new int[cols];
        for (String[] r : rows) for (int i = 0; i < cols; i++) width[i] = Math.max(width[i], r[i].length());
        StringBuilder sb = new StringBuilder();
        for (String[] r : rows) {
            for (int i = 0; i < cols; i++) {
                if (i > 0) sb.append(" | ");
                sb.append(r[i]);
                if (i < cols - 1) for (int k = r[i].length(); k < width[i]; k++) sb.append(' ');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    static String summary(List<LintEntry> entries) {
        int n = entries.size();
        int fields = 0;
        int texts = 0;
        int auto = 0;
        for (LintEntry e : entries) {
            if (e.proposedName != null) {
                if ("field".equals(e.source)) fields++;
                else if ("text".equals(e.source)) texts++;
            }
            if (e.autoName != null) auto++;
        }
        int p = fields + texts;
        int percent = n == 0 ? 0 : 100 * p / n;
        return n + " unnamed interactive component(s), " + p + " with a proposed name (" + percent + " %: "
                + fields + " from fields, " + texts + " from text), " + auto + " with an automatic name";
    }

    static String patch(List<LintEntry> entries) {
        Map<String, List<LintEntry>> groups = new java.util.TreeMap<String, List<LintEntry>>();
        for (LintEntry e : entries) {
            if (e.proposedName == null || e.runtimeClass == null) continue;
            List<LintEntry> g = groups.get(e.runtimeClass);
            if (g == null) groups.put(e.runtimeClass, g = new ArrayList<LintEntry>());
            g.add(e);
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<LintEntry>> g : groups.entrySet()) {
            List<LintEntry> list = g.getValue();
            Collections.sort(list, new Comparator<LintEntry>() {
                public int compare(LintEntry a, LintEntry b) {
                    int c = nullsLast(a.field, b.field);
                    return c != 0 ? c : nullsLast(a.text, b.text);
                }
            });
            if (sb.length() > 0) sb.append('\n');
            sb.append("// ").append(g.getKey()).append('\n');
            for (LintEntry e : list) {
                if (e.field != null) {
                    if (e.declaringClass == null || e.declaringClass.equals(e.runtimeClass)) {
                        sb.append(e.field).append(".setName(\"").append(e.proposedName).append("\");\n");
                    } else {
                        sb.append("// ").append(e.field).append(" is declared in ").append(simple(e.declaringClass))
                                .append(": set \"").append(e.proposedName).append("\" in ")
                                .append(simple(e.runtimeClass)).append('\n');
                    }
                } else if ("text".equals(e.source)) {
                    sb.append("// ").append(e.type).append(" \"").append(e.text)
                            .append("\" has no field: setName(\"").append(e.proposedName)
                            .append("\") where it is created\n");
                }
            }
        }
        return sb.toString();
    }

    private static String simple(String cls) {
        return cls == null ? null : cls.substring(cls.lastIndexOf('.') + 1);
    }

    private static String cell(String s) {
        return s == null ? "-" : s;
    }

    private static int nullsLast(String a, String b) {
        if (a == null) return b == null ? 0 : 1;
        if (b == null) return -1;
        return a.compareTo(b);
    }
}
