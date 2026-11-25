package smoker.core.model;

import java.util.ArrayList;
import java.util.List;

/** Output of one scenario run, written by the agent and consumed by the CLI (R-35, R-40). */
public class RunResult {

    public enum Status { PASS, FAIL, HANG, ERROR, NO_GOLDEN }

    public static class LogHit {
        public long timestamp;
        public String source;   // file:<name> | stdout | stderr | jul | uncaught, with the prefix late: (WP-04)
        /** First line of the event, at most 2000 characters. */
        public String line;
        /** Full text of the event: at most 50 lines and 8000 characters (WP-04). Null in old result files. */
        public String text;
    }

    public static class StepResult {
        public String stepId;
        public String stepName;
        public String group;
        public Status status = Status.PASS;
        public long durationMs;
        public List<String> differences = new ArrayList<String>();
        public List<LogHit> logHits = new ArrayList<LogHit>();
        public Snapshot actual;
        public String screenshot;
        public String threadDump;
        public String error;
    }

    public String scenarioName;
    public String scenarioFile;
    public long startedAt;
    /** 0 until the run ends: a file with 0 is from before an action or from an exit (WP-04, F-22). */
    public long finishedAt;
    /** Step that runs when the file is written, {@code startup} included. Null when no step runs (WP-04, F-22). */
    public String currentStep;
    public Status status = Status.PASS;
    public String error;
    public String javaVersion;
    public String os;
    public List<StepResult> steps = new ArrayList<StepResult>();
    /** Components without setName() seen during the run (R-22). */
    public List<String> unnamedComponents = new ArrayList<String>();

    /** Names that more than one component has in one window, e.g. {@code [Orders]: #x (2x)} (F-17). */
    public List<String> duplicateNames = new ArrayList<String>();
    /** Components that need a real name, with proposals (WP-09). */
    public List<LintEntry> lint = new ArrayList<LintEntry>();
    /** Sorted automatic names used in the run (WP-09). */
    public List<String> autoNames = new ArrayList<String>();
    /** Compare mode of the scenario: {@code all} or {@code named}. */
    public String compare;

    /** True when this scenario ran in a JVM that ran an earlier scenario (WP-05, F-03). False in old files. */
    public boolean sharedJvm;
    /** A remark of the tool about the run, for example that the reset gave a problem (WP-05). Null when none. */
    public String note;

    /** Label of the job, from {@code run --label} (WP-07). Null when none. */
    public String label;
    /** Default locale, time zone, look and feel and size of the main window of the run (WP-07). Null in old files. */
    public String locale, timeZone, lookAndFeel, windowSize;
    /** Warnings of the tool about the run (WP-07). Empty in old files. */
    public List<String> warnings = new ArrayList<String>();

    public boolean passed() {
        return status == Status.PASS;
    }
}
