package smoker.cli;

import smoker.core.model.RunResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Verdicts and the retry rule of a suite (WP-05, F-03, design section 6.3). Pure logic: no file or process access.
 * The CLI makes the launch lists with {@link #launchList()} and gives the results back with {@link #record}.
 */
final class SuitePlan {

    static final String NOTE_PASSED_IN_NEW_JVM = "failed in a shared JVM, passed in a new JVM";
    static final String NOTE_FAILED_IN_NEW_JVM = "failed in a shared JVM and in a new JVM";
    static final String NOTE_NO_START_FOR_RETRY = "failed in a shared JVM; the application did not start for the retry";
    static final String ERROR_NO_START = "not run: the application did not start 3 times";
    static final String START_ENTRY_NAME = "(application start)";
    private static final int MAX_STARTUP_FAILURES = 3;

    private final List<String> names;
    private final Map<String, RunResult> verdicts = new LinkedHashMap<String, RunResult>();
    private final Map<String, Integer> attempts = new HashMap<String, Integer>();
    private String retryName;
    private RunResult retryShared;
    private int starts;
    private int startupFailures;
    private RunResult startEntry;
    private boolean gaveUp;

    SuitePlan(List<String> names) {
        this.names = new ArrayList<String>(names);
    }

    /** The scenario that waits for its retry first, then the names without a verdict. Counts one application start. */
    List<String> launchList() {
        List<String> list = new ArrayList<String>();
        if (retryName != null) list.add(retryName);
        for (String n : names) {
            if (!verdicts.containsKey(n) && !n.equals(retryName)) list.add(n);
        }
        starts++;
        return list;
    }

    /** Takes the results of a launch. Returns the names whose result folder the CLI moves to {@code <name>.shared}. */
    List<String> record(List<String> launched, Map<String, RunResult> results) {
        List<String> toMove = new ArrayList<String>();
        if (launched.isEmpty()) return toMove;
        for (String n : launched) {
            if (results.get(n) != null) attempts.put(n, attempts(n) + 1);
        }
        String first = launched.get(0);
        RunResult r = results.get(first);
        if (startupFailed(r)) {
            startupFailures++;
            startEntry = startEntryOf(r);
            if (startupFailures >= MAX_STARTUP_FAILURES) giveUp(ERROR_NO_START, NOTE_NO_START_FOR_RETRY);
            return toMove;
        }
        startupFailures = 0;
        if (first.equals(retryName)) {
            boolean ok = r.status == RunResult.Status.PASS || r.status == RunResult.Status.NO_GOLDEN;
            r.note = ok ? NOTE_PASSED_IN_NEW_JVM : NOTE_FAILED_IN_NEW_JVM;
            retryName = null;
            retryShared = null;
        }
        verdicts.put(first, r);
        for (int i = 1; i < launched.size(); i++) {
            String n = launched.get(i);
            RunResult s = results.get(n);
            if (s == null) continue; // did not run
            if (s.status == RunResult.Status.PASS || s.status == RunResult.Status.NO_GOLDEN) {
                verdicts.put(n, s);
            } else {
                retryName = n;
                retryShared = s;
                toMove.add(n);
                break;
            }
        }
        return toMove;
    }

    /** Stops the suite: names without a verdict get ERROR; the name that waits for its retry keeps its shared result. */
    void giveUp(String error, String retryNote) {
        for (String n : names) {
            if (verdicts.containsKey(n)) continue;
            if (n.equals(retryName)) {
                retryShared.note = retryNote;
                verdicts.put(n, retryShared);
            } else {
                RunResult e = new RunResult();
                e.scenarioName = n;
                e.status = RunResult.Status.ERROR;
                e.error = error;
                verdicts.put(n, e);
            }
        }
        retryName = null;
        retryShared = null;
        gaveUp = true;
    }

    boolean done() {
        return gaveUp || verdicts.size() == names.size();
    }

    int applicationStarts() {
        return starts;
    }

    int startupFailuresInSequence() {
        return startupFailures;
    }

    RunResult verdict(String name) {
        return verdicts.get(name);
    }

    int attempts(String name) {
        Integer a = attempts.get(name);
        return a == null ? 0 : a;
    }

    /** The {@code (application start)} entry of the last failed startup, or null. */
    RunResult applicationStartEntry() {
        return startEntry;
    }

    private static RunResult.StepResult startupStep(RunResult r) {
        for (RunResult.StepResult s : r.steps) {
            if ("startup".equals(s.stepId)) return s;
        }
        return null;
    }

    private static boolean startupFailed(RunResult r) {
        if (r == null) return true;
        RunResult.StepResult s = startupStep(r);
        return s == null || s.status != RunResult.Status.PASS || "startup".equals(r.currentStep);
    }

    private static RunResult startEntryOf(RunResult r) {
        RunResult e = new RunResult();
        e.scenarioName = START_ENTRY_NAME;
        if (r == null) {
            e.status = RunResult.Status.ERROR;
            e.error = "the application did not start: no result";
            return e;
        }
        RunResult.StepResult s = startupStep(r);
        e.scenarioFile = r.scenarioFile;
        e.startedAt = r.startedAt;
        e.finishedAt = r.finishedAt;
        e.javaVersion = r.javaVersion;
        e.os = r.os;
        e.error = r.error;
        e.status = r.status == RunResult.Status.PASS ? RunResult.Status.ERROR : r.status;
        if (s != null) e.steps.add(s);
        return e;
    }
}
