package smoker.core.report;

import smoker.core.model.RunResult;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** JUnit XML for GitLab's test-report widget (R-40). One testsuite per scenario, one testcase per step. */
public final class JUnitXmlWriter {

    private JUnitXmlWriter() {}

    public static void write(Path file, List<RunResult> runs) throws IOException {
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        Writer w = new OutputStreamWriter(Files.newOutputStream(file), StandardCharsets.UTF_8);
        try {
            w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<testsuites>\n");
            for (RunResult r : runs) {
                int failures = 0, errors = 0;
                for (RunResult.StepResult s : r.steps) {
                    if (s.status == RunResult.Status.FAIL || s.status == RunResult.Status.HANG) failures++;
                    else if (s.status == RunResult.Status.ERROR) errors++;
                }
                if (r.status == RunResult.Status.ERROR && r.steps.isEmpty()) errors++;
                w.write(String.format(Locale.ROOT, "  <testsuite name=\"%s\" tests=\"%d\" failures=\"%d\" errors=\"%d\" time=\"%.3f\">\n",
                        esc(r.scenarioName), Math.max(r.steps.size(), 1), failures, errors,
                        (r.finishedAt - r.startedAt) / 1000.0));
                if (r.steps.isEmpty()) {
                    w.write("    <testcase name=\"startup\" classname=\"" + esc(r.scenarioName) + "\">\n");
                    w.write("      <error message=\"" + esc(r.error) + "\"/>\n    </testcase>\n");
                }
                for (RunResult.StepResult s : r.steps) {
                    w.write(String.format(Locale.ROOT, "    <testcase name=\"%s\" classname=\"%s\" time=\"%.3f\">\n",
                            esc(s.stepId + " " + s.stepName), esc(r.scenarioName), s.durationMs / 1000.0));
                    StringBuilder body = new StringBuilder();
                    for (String d : s.differences) body.append(d).append('\n');
                    for (RunResult.LogHit h : s.logHits) body.append("[log:").append(h.source).append("] ").append(h.text != null ? h.text : h.line).append('\n');
                    if (s.error != null) body.append(s.error).append('\n');
                    if (s.threadDump != null) body.append("\n--- thread dump ---\n").append(s.threadDump);
                    switch (s.status) {
                        case FAIL:
                        case HANG:
                            w.write("      <failure message=\"" + esc(s.status + ": " + firstLine(body)) + "\">" + esc(body.toString()) + "</failure>\n");
                            break;
                        case ERROR:
                            w.write("      <error message=\"" + esc(firstLine(body)) + "\">" + esc(body.toString()) + "</error>\n");
                            break;
                        case NO_GOLDEN:
                            w.write("      <skipped message=\"no golden snapshot yet — run approve\"/>\n");
                            break;
                        default:
                            break;
                    }
                    w.write("    </testcase>\n");
                }
                if (r.note != null) w.write("    <system-out>" + esc(r.note) + "</system-out>\n");
                w.write("  </testsuite>\n");
            }
            w.write("</testsuites>\n");
        } finally {
            w.close();
        }
    }

    private static String firstLine(StringBuilder sb) {
        String s = sb.toString().trim();
        int i = s.indexOf('\n');
        return i < 0 ? s : s.substring(0, i);
    }

    static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '&': sb.append("&amp;"); break;
                case '"': sb.append("&quot;"); break;
                default:
                    if (c < 0x20 && c != '\n' && c != '\t' && c != '\r') sb.append(' ');
                    else sb.append(c);
            }
        }
        return sb.toString();
    }
}
