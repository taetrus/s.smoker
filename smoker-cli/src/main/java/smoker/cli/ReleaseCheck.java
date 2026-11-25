package smoker.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * {@code check-release <path>...}: finds the {@code -javaagent} option of the smoke tester in the {@code .ini}
 * files of a product (WP-07, F-18, R-40a). A path is a folder, an {@code .ini} file or a {@code .zip} file.
 */
final class ReleaseCheck {

    private static final String OPTION = "-javaagent:";

    private ReleaseCheck() {}

    /** The state of one call: the hits and the count of the files that were read. */
    private static final class Scan {
        int files;
        int hits;
    }

    /**
     * @param paths the arguments after {@code check-release}
     * @return the exit code: 0 no agent option, 1 an agent option found, 2 a path that is wrong or has no .ini file
     */
    static int run(String[] paths, PrintStream out, PrintStream err) {
        if (paths.length == 0) {
            err.println("check-release: usage: check-release <path> [<path> ...]  (folder, .ini file or .zip file)");
            return 2;
        }
        Scan scan = new Scan();
        boolean failed = false;
        for (String p : paths) {
            int before = scan.files;
            try {
                Path path = Paths.get(p);
                if (!Files.exists(path)) {
                    err.println("check-release: " + p + " does not exist");
                    failed = true;
                    continue;
                }
                scanPath(path, scan, out);
            } catch (IOException | RuntimeException e) {
                // RuntimeException: a path character that the platform refuses (Windows: * ? "), a zip entry
                // name that is not UTF-8 on JRE 8. Exit code 1 is for an agent option only.
                err.println("check-release: cannot read " + p + ": " + e.getMessage());
                failed = true;
                continue;
            }
            if (scan.files == before) {
                err.println("check-release: no .ini file in " + p);
                failed = true;
            }
        }
        if (failed) return 2;
        if (scan.hits > 0) {
            out.println("check-release: " + scan.hits + " agent option(s) in " + scan.files + " file(s) checked");
            return 1;
        }
        out.println("check-release: " + scan.files + " file(s) checked, no agent option");
        return 0;
    }

    private static void scanPath(Path path, Scan scan, PrintStream out) throws IOException {
        if (Files.isDirectory(path)) {
            List<Path> files;
            try (Stream<Path> walk = Files.walk(path)) {
                files = walk.filter(Files::isRegularFile).collect(Collectors.toList());
            } catch (UncheckedIOException e) {
                throw e.getCause();
            }
            Collections.sort(files);
            for (Path f : files) scanFile(f, scan, out);
        } else {
            scanFile(path, scan, out);
        }
    }

    private static void scanFile(Path file, Scan scan, PrintStream out) throws IOException {
        String name = lower(file.getFileName().toString());
        if (name.endsWith(".ini")) {
            try (InputStream in = Files.newInputStream(file)) {
                scanLines(file.toString(), in, scan, out);
            }
            scan.files++;
        } else if (name.endsWith(".zip")) {
            try (ZipFile zip = new ZipFile(file.toFile())) {
                List<ZipEntry> entries = new ArrayList<ZipEntry>();
                for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements();) {
                    ZipEntry entry = e.nextElement();
                    if (!entry.isDirectory() && lower(entry.getName()).endsWith(".ini")) entries.add(entry);
                }
                for (ZipEntry entry : entries) {
                    try (InputStream in = zip.getInputStream(entry)) {
                        scanLines(file + "!" + entry.getName(), in, scan, out);
                    }
                    scan.files++;
                }
            }
        }
    }

    private static void scanLines(String label, InputStream in, Scan scan, PrintStream out) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.ISO_8859_1));
        String line;
        int n = 0;
        while ((line = r.readLine()) != null) {
            n++;
            if (isAgentOption(line)) {
                scan.hits++;
                out.println(label + ":" + n + ": " + line);
            }
        }
    }

    /** A line that starts (after blanks) with {@code -javaagent:} and has {@code smoker} after it. */
    static boolean isAgentOption(String line) {
        String t = lower(line).trim();
        return t.startsWith(OPTION) && t.indexOf("smoker", OPTION.length()) >= 0;
    }

    private static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
