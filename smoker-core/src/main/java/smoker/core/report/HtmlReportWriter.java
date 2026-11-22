package smoker.core.report;

import smoker.core.model.LintEntry;
import smoker.core.model.RunResult;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Self-contained HTML report with per-step diffs, log hits and screenshots (R-40). */
public final class HtmlReportWriter {

    private HtmlReportWriter() {}

    public static void write(Path file, List<RunResult> runs) throws IOException {
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        Writer w = new OutputStreamWriter(Files.newOutputStream(file), StandardCharsets.UTF_8);
        try {
            w.write("<!doctype html><html><head><meta charset='utf-8'><title>s.smoker report</title><style>"
                    + "body{font:14px/1.4 system-ui,Segoe UI,sans-serif;margin:24px;color:#222}"
                    + "h1{font-size:20px}h2{font-size:16px;margin-top:28px}"
                    + "table{border-collapse:collapse;width:100%}td,th{border:1px solid #ddd;padding:6px 8px;text-align:left;vertical-align:top}"
                    + ".PASS{background:#e6f4ea}.FAIL,.HANG,.ERROR{background:#fce8e6}.NO_GOLDEN{background:#fff4e5}"
                    + "pre{white-space:pre-wrap;margin:0;font-size:12px}img{max-width:480px;border:1px solid #ccc}"
                    + "details summary{cursor:pointer}</style></head><body>");
            w.write("<h1>s.smoker report</h1>");
            w.write("<table><tr><th>Scenario</th><th>Status</th><th>Steps</th><th>Java</th><th>OS</th><th>Duration</th></tr>");
            for (RunResult r : runs) {
                w.write("<tr class='" + r.status + "'><td><a href='#" + id(r.scenarioName) + "'>" + e(r.scenarioName) + "</a></td><td>" + r.status
                        + "</td><td>" + r.steps.size() + "</td><td>" + e(r.javaVersion) + "</td><td>" + e(r.os) + "</td><td>"
                        + (r.finishedAt - r.startedAt) + " ms</td></tr>");
                if (r.note != null) w.write("<tr class='" + r.status + "'><td colspan='6'><p class='note'>" + e(r.note) + "</p></td></tr>");
            }
            w.write("</table>");
            for (RunResult r : runs) {
                w.write("<h2 id='" + id(r.scenarioName) + "'>" + e(r.scenarioName) + " — " + r.status
                        + " <small>(compare: " + e(r.compare == null ? "all" : r.compare) + ")</small></h2>");
                if (r.note != null) w.write("<p class='note'>" + e(r.note) + "</p>");
                if (r.error != null) w.write("<pre class='ERROR'>" + e(r.error) + "</pre>");
                if (!r.unnamedComponents.isEmpty()) {
                    w.write("<details><summary>Locator lint: " + r.unnamedComponents.size()
                            + " interactive components without setName()</summary><pre>");
                    for (String u : r.unnamedComponents) w.write(e(u) + "\n");
                    w.write("</pre></details>");
                }
                if (!r.duplicateNames.isEmpty()) {
                    w.write("<details><summary>Names that are not unique: " + r.duplicateNames.size() + "</summary><pre>");
                    for (String d : r.duplicateNames) w.write(e(d) + "\n");
                    w.write("</pre></details>");
                }
                if (!r.lint.isEmpty()) {
                    w.write("<details><summary>Components without a real name: " + r.lint.size() + "</summary>"
                            + "<table><tr><th>Window</th><th>Application class</th><th>Field</th><th>Type</th><th>Text</th>"
                            + "<th>Automatic name</th><th>Proposed name</th><th>Source</th></tr>");
                    for (LintEntry l : r.lint) {
                        w.write("<tr><td>" + e(l.window) + "</td><td>" + e(simple(l.runtimeClass)) + "</td><td>" + e(l.field)
                                + "</td><td>" + e(l.type) + "</td><td>" + e(l.text) + "</td><td>" + e(l.autoName)
                                + "</td><td>" + e(l.proposedName) + "</td><td>" + e(l.source) + "</td></tr>");
                    }
                    w.write("</table></details>");
                }
                if (!r.autoNames.isEmpty()) {
                    w.write("<details><summary>Automatic names used in this run: " + r.autoNames.size() + "</summary>"
                            + "<p>An automatic name changes when a developer renames the field.</p><pre>");
                    for (String a : r.autoNames) w.write(e(a) + "\n");
                    w.write("</pre></details>");
                }
                w.write("<table><tr><th style='width:160px'>Step</th><th style='width:90px'>Status</th><th>Details</th><th>Screenshot</th></tr>");
                for (RunResult.StepResult s : r.steps) {
                    w.write("<tr class='" + s.status + "'><td>" + e(s.stepId) + "<br><small>" + e(s.stepName) + "</small></td><td>" + s.status
                            + "<br><small>" + s.durationMs + " ms</small></td><td>");
                    if (!s.differences.isEmpty()) {
                        w.write("<pre>");
                        for (String d : s.differences) w.write(e(d) + "\n");
                        w.write("</pre>");
                    }
                    if (!s.logHits.isEmpty()) {
                        w.write("<details open><summary>" + s.logHits.size() + " log hit(s)</summary><pre>");
                        for (RunResult.LogHit h : s.logHits) w.write("[" + e(h.source) + "] " + e(h.text != null ? h.text : h.line) + "\n");
                        w.write("</pre></details>");
                    }
                    if (s.error != null) w.write("<pre>" + e(s.error) + "</pre>");
                    if (s.threadDump != null) w.write("<details><summary>thread dump</summary><pre>" + e(s.threadDump) + "</pre></details>");
                    w.write("</td><td>");
                    if (s.screenshot != null) w.write("<a href='" + e(s.screenshot) + "'><img src='" + e(s.screenshot) + "'></a>");
                    w.write("</td></tr>");
                }
                w.write("</table>");
            }
            w.write("</body></html>\n");
        } finally {
            w.close();
        }
    }

    private static String id(String s) {
        return s == null ? "x" : s.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private static String simple(String className) {
        return className == null ? null : className.substring(className.lastIndexOf('.') + 1);
    }

    private static String e(String s) {
        return JUnitXmlWriter.esc(s);
    }
}
