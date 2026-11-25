package smoker.core.report;

import smoker.core.model.JobResult;
import smoker.core.model.RunResult;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/** One overview page for the {@code job-result.json} files of several jobs (WP-07, F-18, R-41). */
public final class MatrixReportWriter {

    private MatrixReportWriter() {}

    /** One job: a column of the page. */
    public static final class Job {
        public final String column;
        /** Href prefix of the folder of the job; ends with {@code /}. */
        public final String link;
        public final JobResult result;

        public Job(String column, String link, JobResult result) {
            this.column = column;
            this.link = link;
            this.result = result;
        }
    }

    /** All fields are optional. */
    public static final class Header {
        public String runId;
        public String ref;
        public long time;
        public String historyMessage;
    }

    /** A row of one scenario or one step: the cell of each job, or null when the job did not run it. */
    private static final class Row {
        String name;
        String group;
        final JobResult.Scenario[] scenarioOf;
        final JobResult.Step[] stepOf;

        Row(int jobs) {
            scenarioOf = new JobResult.Scenario[jobs];
            stepOf = new JobResult.Step[jobs];
        }
    }

    public static void write(Path file, List<Job> jobs, Header header) throws IOException {
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        Writer w = new OutputStreamWriter(Files.newOutputStream(file), StandardCharsets.UTF_8);
        try {
            w.write("<!doctype html><html><head><meta charset='utf-8'><title>s.smoker matrix report</title><style>"
                    + "body{font:14px/1.4 system-ui,Segoe UI,sans-serif;margin:24px;color:#222}"
                    + "h1{font-size:20px}small{color:#555}"
                    + "table{border-collapse:collapse;margin-top:16px}td,th{border:1px solid #ddd;padding:6px 8px;text-align:left;vertical-align:top}"
                    + ".PASS{background:#e6f4ea}.FAIL,.HANG,.ERROR{background:#fce8e6}.NO_GOLDEN{background:#fff4e5}"
                    + "tr.scenario td{font-weight:bold}tr.group td{background:#eee;font-weight:bold}"
                    + "td.step{padding-left:24px}p.note{margin:0;font-size:12px;white-space:pre-wrap}"
                    + "</style></head><body>");
            w.write("<h1>s.smoker matrix report");
            String where = headerText(header);
            if (!where.isEmpty()) w.write(" <small>" + e(where) + "</small>");
            w.write("</h1>");
            if (header.historyMessage != null) w.write("<p>" + e(header.historyMessage) + "</p>");
            w.write("<table><tr><th></th>");
            for (Job j : jobs) w.write("<th>" + e(j.column) + "</th>");
            w.write("</tr>");
            w.write(environmentRow("Java", jobs, 0));
            w.write(environmentRow("OS", jobs, 1));
            w.write(environmentRow("Locale", jobs, 2));
            w.write(environmentRow("Scenarios", jobs, 3));
            w.write("</table>");
            w.write("<table><tr><th>Scenario / step</th>");
            for (Job j : jobs) w.write("<th>" + e(j.column) + "</th>");
            w.write("</tr>");
            for (Map.Entry<String, Row> sc : scenarioRows(jobs).entrySet()) {
                Row row = sc.getValue();
                w.write("<tr class='scenario'><td>" + e(row.name) + "</td>");
                for (int i = 0; i < jobs.size(); i++) {
                    JobResult.Scenario s = row.scenarioOf[i];
                    w.write(cell(s == null ? null : s.status, jobs.get(i).link + "report.html#"
                            + HtmlReportWriter.scenarioAnchor(s == null ? null : s.name)));
                }
                w.write("</tr>");
                for (int i = 0; i < jobs.size(); i++) {
                    JobResult.Scenario s = row.scenarioOf[i];
                    if (s == null) continue;
                    String col = jobs.get(i).column;
                    if (s.note != null) w.write(textRow(jobs.size(), col, s.note));
                    if (s.error != null) w.write(textRow(jobs.size(), col, s.error));
                    for (String warning : s.warnings) w.write(textRow(jobs.size(), col, warning));
                }
                String lastGroup = null;
                for (Row step : stepRows(jobs, sc.getKey())) {
                    if (step.group != null && !step.group.equals(lastGroup)) {
                        w.write("<tr class='group'><td colspan='" + (jobs.size() + 1) + "'>" + e(step.group) + "</td></tr>");
                    }
                    if (step.group != null) lastGroup = step.group;
                    w.write("<tr><td class='step'>" + e(step.name) + "</td>");
                    for (int i = 0; i < jobs.size(); i++) {
                        JobResult.Step s = step.stepOf[i];
                        JobResult.Scenario owner = row.scenarioOf[i];
                        w.write(cell(s == null ? null : s.status, s == null ? null : jobs.get(i).link + "report.html#"
                                + HtmlReportWriter.stepAnchor(owner.name, s.id)));
                    }
                    w.write("</tr>");
                }
            }
            w.write("</table></body></html>\n");
        } finally {
            w.close();
        }
    }

    private static String headerText(Header h) {
        List<String> parts = new ArrayList<String>();
        if (h.runId != null) parts.add("run " + h.runId);
        if (h.ref != null) parts.add(h.ref);
        if (h.time > 0) {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm 'UTC'", Locale.ROOT);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            parts.add(f.format(new Date(h.time)));
        }
        StringBuilder sb = new StringBuilder();
        for (String p : parts) sb.append(sb.length() == 0 ? "" : ", ").append(p);
        return sb.toString();
    }

    private static String environmentRow(String title, List<Job> jobs, int kind) {
        StringBuilder sb = new StringBuilder("<tr><th>" + title + "</th>");
        for (Job j : jobs) {
            JobResult r = j.result;
            String v;
            if (kind == 0) v = r.javaVersion;
            else if (kind == 1) v = r.os;
            else if (kind == 2) {
                v = r.locale != null && r.timeZone != null ? r.locale + ", " + r.timeZone
                        : r.locale != null ? r.locale : r.timeZone;
            } else {
                int pass = 0;
                for (JobResult.Scenario s : r.scenarios) if (s.status == RunResult.Status.PASS) pass++;
                v = pass + " of " + r.scenarios.size() + " PASS";
            }
            sb.append("<td>").append(e(v)).append("</td>");
        }
        return sb.append("</tr>").toString();
    }

    /** The scenarios by key: the order of the first job, then the scenarios that only later jobs have. */
    private static Map<String, Row> scenarioRows(List<Job> jobs) {
        Map<String, Row> rows = new LinkedHashMap<String, Row>();
        for (int i = 0; i < jobs.size(); i++) {
            for (JobResult.Scenario s : jobs.get(i).result.scenarios) {
                Row row = rows.get(s.key);
                if (row == null) {
                    row = new Row(jobs.size());
                    row.name = s.name != null ? s.name : s.key;
                    rows.put(s.key, row);
                }
                row.scenarioOf[i] = s;
            }
        }
        return rows;
    }

    /** The steps of one scenario by id: the order of the first job that has it, then the steps of later jobs. */
    private static List<Row> stepRows(List<Job> jobs, String key) {
        Map<String, Row> rows = new LinkedHashMap<String, Row>();
        for (int i = 0; i < jobs.size(); i++) {
            for (JobResult.Scenario s : jobs.get(i).result.scenarios) {
                if (!key.equals(s.key)) continue;
                for (JobResult.Step st : s.steps) {
                    Row row = rows.get(st.id);
                    if (row == null) {
                        row = new Row(jobs.size());
                        row.name = st.id + (st.name != null ? " " + st.name : "");
                        row.group = st.group;
                        rows.put(st.id, row);
                    }
                    row.stepOf[i] = st;
                }
            }
        }
        return new ArrayList<Row>(rows.values());
    }

    private static String cell(RunResult.Status status, String href) {
        if (status == null) return "<td>-</td>";
        return "<td class='" + status + "'><a href='" + e(href) + "'>" + status + "</a></td>";
    }

    private static String textRow(int jobs, String column, String text) {
        return "<tr><td colspan='" + (jobs + 1) + "'><p class='note'>" + e(column + ": " + text) + "</p></td></tr>";
    }

    private static String e(String s) {
        return JUnitXmlWriter.esc(s);
    }
}
