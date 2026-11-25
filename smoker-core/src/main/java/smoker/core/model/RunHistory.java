package smoker.core.model;

import smoker.core.io.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The statuses of the last runs of the matrix report, kept in the {@code --history} file (WP-07, F-18, R-42). */
public class RunHistory {

    /** The number of runs that the file keeps. */
    public static final int KEEP = 20;

    public static class ScenarioRun {
        public String status;
        /** Step id to status name. */
        public Map<String, String> steps = new LinkedHashMap<String, String>();
    }

    public static class Run {
        public long time;
        public String id;
        public String ref;
        /** Column to scenario key to the run of the scenario. */
        public Map<String, Map<String, ScenarioRun>> jobs = new LinkedHashMap<String, Map<String, ScenarioRun>>();
    }

    /** The result of {@link #load}: a message for the page and for {@code System.err}, or null. */
    public static class Loaded {
        public RunHistory history;
        public String message;
    }

    public int version = 1;
    public List<Run> runs = new ArrayList<Run>();

    public static Run runOf(long time, String id, String ref, Map<String, JobResult> byColumn) {
        Run run = new Run();
        run.time = time;
        run.id = id;
        run.ref = ref;
        for (Map.Entry<String, JobResult> e : byColumn.entrySet()) {
            Map<String, ScenarioRun> scenarios = new LinkedHashMap<String, ScenarioRun>();
            for (JobResult.Scenario s : e.getValue().scenarios) {
                ScenarioRun sr = new ScenarioRun();
                sr.status = s.status == null ? null : s.status.name();
                for (JobResult.Step st : s.steps) sr.steps.put(st.id, st.status == null ? null : st.status.name());
                scenarios.put(s.key, sr);
            }
            run.jobs.put(e.getKey(), scenarios);
        }
        return run;
    }

    /** Replaces the run with the same non-null id, else appends. Then keeps the last {@link #KEEP} runs. */
    public void add(Run run) {
        int at = -1;
        if (run.id != null) {
            for (int i = 0; i < runs.size(); i++) {
                if (run.id.equals(runs.get(i).id)) at = i;
            }
        }
        if (at >= 0) runs.set(at, run);
        else runs.add(run);
        while (runs.size() > KEEP) runs.remove(0);
    }

    /** One letter for each stored run, the oldest first. A null {@code stepId} gives the status of the scenario. */
    public String letters(String column, String scenarioKey, String stepId) {
        StringBuilder sb = new StringBuilder();
        for (Run run : runs) sb.append(letter(statusIn(run, column, scenarioKey, stepId)));
        return sb.toString();
    }

    /** The stored runs have a PASS and also a FAIL, HANG or ERROR. */
    public boolean unstable(String column, String scenarioKey, String stepId) {
        String l = letters(column, scenarioKey, stepId);
        return l.indexOf('P') >= 0 && (l.indexOf('F') >= 0 || l.indexOf('H') >= 0 || l.indexOf('E') >= 0);
    }

    private static String statusIn(Run run, String column, String key, String stepId) {
        Map<String, ScenarioRun> job = run.jobs == null ? null : run.jobs.get(column);
        ScenarioRun s = job == null ? null : job.get(key);
        if (s == null) return null;
        return stepId == null ? s.status : s.steps == null ? null : s.steps.get(stepId);
    }

    private static char letter(String status) {
        if (status == null) return '-';
        switch (status) {
            case "PASS": return 'P';
            case "FAIL": return 'F';
            case "HANG": return 'H';
            case "ERROR": return 'E';
            case "NO_GOLDEN": return 'N';
            default: return '-';
        }
    }

    /** A missing file gives an empty history. A file that is not version 1 is moved to {@code <file>.damaged}. */
    public static Loaded load(Path file) throws IOException {
        Loaded l = new Loaded();
        if (!Files.exists(file)) {
            l.history = new RunHistory();
            l.message = "history: new file";
            return l;
        }
        RunHistory h = null;
        try {
            h = Json.read(file, RunHistory.class);
        } catch (RuntimeException e) {
            // a parse error: the file is damaged
        }
        if (h != null && h.version == 1 && h.runs != null && !h.runs.contains(null)) {
            l.history = h;
            return l;
        }
        Files.move(file, file.resolveSibling(file.getFileName() + ".damaged"), StandardCopyOption.REPLACE_EXISTING);
        l.history = new RunHistory();
        l.message = "history: the file was damaged, a new history starts";
        return l;
    }

    public void save(Path file) throws IOException {
        Json.writeAtomic(file, this);
    }
}
