package smoker.cli;

import smoker.core.io.Json;
import smoker.core.model.JobResult;
import smoker.core.report.MatrixReportWriter;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * {@code report --merge <dir>... --out <dir>}: joins the {@code job-result.json} files of several jobs into one
 * overview page (WP-07, F-18, R-41).
 */
final class Report {

    private Report() {}

    /** @param args the arguments after {@code report} @return the exit code: 0 page written, 2 wrong argument or file */
    static int run(String[] args) {
        List<String> merge = new ArrayList<String>();
        String out = null;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if ("--merge".equals(a)) {
                while (i + 1 < args.length && !args[i + 1].startsWith("--")) merge.add(args[++i]);
            } else if ("--out".equals(a)) {
                if (i + 1 >= args.length || args[i + 1].startsWith("--")) return fail("report: --out needs a folder");
                out = args[++i];
            } else {
                return fail("report: unknown argument " + a);
            }
        }
        if (merge.isEmpty()) return fail("report: --merge needs at least one folder");
        if (out == null) return fail("report: missing --out");
        Path outDir = Paths.get(out).toAbsolutePath().normalize();
        List<MatrixReportWriter.Job> jobs = new ArrayList<MatrixReportWriter.Job>();
        Set<String> columns = new HashSet<String>();
        for (String m : merge) {
            Path dir = Paths.get(m).toAbsolutePath().normalize();
            if (dir.equals(outDir)) return fail("report: --out must not be a job folder: " + m);
            JobResult r;
            try {
                r = Json.read(dir.resolve(JobResult.FILE), JobResult.class);
            } catch (IOException | RuntimeException e) {
                return fail("report: cannot read " + JobResult.FILE + " in " + m + ": " + e.getMessage());
            }
            if (r == null) return fail("report: cannot read " + JobResult.FILE + " in " + m + ": the file is empty");
            String column = r.label != null ? r.label : dir.getFileName() != null ? dir.getFileName().toString() : dir.toString();
            if (!columns.add(column)) return fail("report: two jobs have the column name " + column + "; use run --label");
            jobs.add(new MatrixReportWriter.Job(column, link(outDir, dir), r));
        }
        try {
            MatrixReportWriter.write(outDir.resolve("report.html"), jobs, new MatrixReportWriter.Header());
        } catch (IOException e) {
            return fail("report: cannot write " + outDir.resolve("report.html") + ": " + e.getMessage());
        }
        System.out.println("matrix report: " + outDir.resolve("report.html"));
        return 0;
    }

    private static int fail(String text) {
        System.err.println(text);
        return 2;
    }

    /** The href prefix of a job folder, relative to the folder of the page, or a {@code file:} URI. Ends with {@code /}. */
    static String link(Path out, Path job) {
        return link(out, job, true);
    }

    /** @param relativePossible false forces the {@code file:} URI, as for a job folder on another drive */
    static String link(Path out, Path job, boolean relativePossible) {
        if (relativePossible) {
            try {
                Path rel = out.relativize(job);
                StringBuilder sb = new StringBuilder();
                for (Path part : rel) {
                    if (part.toString().isEmpty()) continue;
                    sb.append(encode(part.toString())).append('/');
                }
                return sb.toString();
            } catch (IllegalArgumentException e) {
                // no relative path: use the file URI
            }
        }
        String uri = job.toUri().toString();
        return uri.endsWith("/") ? uri : uri + "/";
    }

    private static String encode(String segment) {
        try {
            return URLEncoder.encode(segment, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
