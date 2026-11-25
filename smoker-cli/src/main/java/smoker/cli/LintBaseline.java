package smoker.cli;

import com.google.gson.annotations.SerializedName;
import smoker.core.model.LintEntry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The lint baseline file (WP-07, R-41): the lint results that a team accepts. Only {@code key} is read back; the
 * other fields help a person who reads the file or a diff.
 */
final class LintBaseline {

    int version = 1;
    List<Entry> entries;

    static final class Entry {
        String key;
        String window;
        @SerializedName("class")
        String runtimeClass;
        String field;
        String type;
        String text;
        String proposedName;
    }

    /** One entry for each baseline key (the first entry wins), sorted by key. */
    static LintBaseline of(List<LintEntry> lint) {
        LintBaseline b = new LintBaseline();
        b.entries = new ArrayList<Entry>();
        Set<String> seen = new HashSet<String>();
        for (LintEntry e : lint) {
            String key = Lint.baselineKey(e);
            if (!seen.add(key)) continue;
            Entry n = new Entry();
            n.key = key;
            n.window = e.window;
            n.runtimeClass = e.runtimeClass;
            n.field = e.field;
            n.type = e.type;
            n.text = e.text;
            n.proposedName = e.proposedName;
            b.entries.add(n);
        }
        Collections.sort(b.entries, new Comparator<Entry>() {
            public int compare(Entry x, Entry y) {
                return x.key.compareTo(y.key);
            }
        });
        return b;
    }

    Set<String> keys() {
        Set<String> keys = new LinkedHashSet<String>();
        if (entries != null) for (Entry e : entries) if (e != null && e.key != null) keys.add(e.key);
        return keys;
    }
}
