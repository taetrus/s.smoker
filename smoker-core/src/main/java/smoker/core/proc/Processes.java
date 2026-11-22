package smoker.core.proc;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Commands and background processes of the tool (WP-05): the reset commands of the agent and the commands of the
 * CLI suite runner. Java 8 source level: the process tree is stopped through reflection on {@code ProcessHandle}.
 */
public final class Processes {

    /** The result of {@link #run} when the command did not end within its time limit. */
    public static final int TIMEOUT = -999;

    private Processes() {}

    /** Minimal shell-like splitting: whitespace separated, double quotes group. */
    public static List<String> split(String commandLine) {
        List<String> out = new ArrayList<String>();
        StringBuilder cur = new StringBuilder();
        boolean q = false;
        for (char c : commandLine.toCharArray()) {
            if (c == '"') q = !q;
            else if (Character.isWhitespace(c) && !q) {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
            } else cur.append(c);
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    /**
     * Starts the command. Output and errors go to the end of {@code log}. A relative first word that names a
     * file in {@code workDir} becomes its absolute path: the process builder would else look in the current
     * directory of this JVM.
     *
     * @param workDir the directory of the process; null keeps the current directory
     */
    public static Process start(List<String> cmd, Path workDir, Path log) throws IOException {
        List<String> full = new ArrayList<String>(cmd);
        if (workDir != null && !full.isEmpty()) {
            String first = full.get(0);
            if (!new File(first).isAbsolute()) {
                Path candidate = workDir.resolve(first);
                if (Files.isRegularFile(candidate)) full.set(0, candidate.toAbsolutePath().toString());
            }
        }
        ProcessBuilder pb = new ProcessBuilder(full);
        if (workDir != null) pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
        return pb.start();
    }

    /**
     * Starts a command that runs to its end: like {@link #start}, but stdin is closed. A command that reads
     * stdin (a {@code .bat} with {@code pause}) gets end of input and does not wait.
     */
    public static Process startForeground(List<String> cmd, Path workDir, Path log) throws IOException {
        Process p = start(cmd, workDir, log);
        try {
            p.getOutputStream().close();
        } catch (IOException e) {
            // the process ended already
        }
        return p;
    }

    /** @return the exit code, or {@link #TIMEOUT} after {@code stopTree} when the command ran longer than timeoutMs */
    public static int run(List<String> cmd, Path workDir, Path log, long timeoutMs)
            throws IOException, InterruptedException {
        Process p = startForeground(cmd, workDir, log);
        if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            stopTree(p);
            p.waitFor(10, TimeUnit.SECONDS);
            return TIMEOUT;
        }
        return p.exitValue();
    }

    /**
     * Stops the process and, on Java 9 or later, all its descendants.
     *
     * @return true when the descendants were stopped too; false on Java 8, where only the process stops
     */
    public static boolean stopTree(Process p) {
        boolean tree = false;
        try {
            Class<?> handleClass = Class.forName("java.lang.ProcessHandle");
            Object handle = Process.class.getMethod("toHandle").invoke(p);
            Object stream = handleClass.getMethod("descendants").invoke(handle);
            Object[] all = (Object[]) Class.forName("java.util.stream.Stream").getMethod("toArray").invoke(stream);
            Method destroy = handleClass.getMethod("destroyForcibly");
            for (Object d : all) destroy.invoke(d);
            tree = true;
        } catch (Exception e) {
            // Java 8, or no access: the process alone stops below.
        }
        p.destroyForcibly();
        return tree;
    }
}
