package smoker.core.model;

import java.util.ArrayList;
import java.util.List;

/** Output of one scenario run, written by the agent and consumed by the CLI (R-35, R-40). */
public class RunResult {

    public enum Status { PASS, FAIL, HANG, ERROR, NO_GOLDEN }

    public static class LogHit {
        public long timestamp;
        public String source;   // file | stderr | jul | uncaught
        public String line;
    }

    public static class StepResult {
        public String stepId;
        public String stepName;
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
    public long finishedAt;
    public Status status = Status.PASS;
    public String error;
    public String javaVersion;
    public String os;
    public List<StepResult> steps = new ArrayList<StepResult>();
    /** Components without setName() seen during the run (R-22). */
    public List<String> unnamedComponents = new ArrayList<String>();

    public boolean passed() {
        return status == Status.PASS;
    }
}
