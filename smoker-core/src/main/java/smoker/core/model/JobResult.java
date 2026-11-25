package smoker.core.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Summary of one job (one {@code run}), written to {@code <out>/job-result.json} beside {@code report.html}.
 * The matrix report reads this file (WP-07, F-18, R-41).
 */
public class JobResult {

    public static final String FILE = "job-result.json";

    public static class Step {
        public String id;
        public String name;
        public String group;
        public RunResult.Status status;
    }

    public static class Scenario {
        /** File name of the scenario without the extension: the name of the result folder. Unique in a job. */
        public String key;
        /** {@code scenarioName} of the result, as {@code report.html} shows it. */
        public String name;
        public RunResult.Status status;
        public String note;
        public String error;
        public List<String> warnings = new ArrayList<String>();
        public List<Step> steps = new ArrayList<Step>();
    }

    public int version = 1;
    /** Null without {@code run --label}. */
    public String label;
    public long startedAt;
    public long finishedAt;
    public String javaVersion;
    public String os;
    public String locale;
    public String timeZone;
    public String lookAndFeel;
    public List<Scenario> scenarios = new ArrayList<Scenario>();

    /**
     * @param keys {@code keys.get(i)} is the key of {@code verdicts.get(i)}
     */
    public static JobResult of(String label, List<String> keys, List<RunResult> verdicts) {
        JobResult j = new JobResult();
        j.label = label;
        for (int i = 0; i < verdicts.size(); i++) {
            RunResult r = verdicts.get(i);
            if (r.startedAt > 0 && (j.startedAt == 0 || r.startedAt < j.startedAt)) j.startedAt = r.startedAt;
            if (r.finishedAt > j.finishedAt) j.finishedAt = r.finishedAt;
            if (j.javaVersion == null) j.javaVersion = r.javaVersion;
            if (j.os == null) j.os = r.os;
            if (j.locale == null) j.locale = r.locale;
            if (j.timeZone == null) j.timeZone = r.timeZone;
            if (j.lookAndFeel == null) j.lookAndFeel = r.lookAndFeel;
            Scenario s = new Scenario();
            s.key = keys.get(i);
            s.name = r.scenarioName;
            s.status = r.status;
            s.note = r.note;
            s.error = r.error;
            if (r.warnings != null) s.warnings.addAll(r.warnings);
            for (RunResult.StepResult sr : r.steps) {
                Step st = new Step();
                st.id = sr.stepId;
                st.name = sr.stepName;
                st.group = sr.group;
                st.status = sr.status;
                s.steps.add(st);
            }
            j.scenarios.add(s);
        }
        return j;
    }
}
