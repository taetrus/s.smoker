package smoker.core.proc;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * A command for the tests of {@link Processes}. Arguments: {@code exit <n>}, {@code sleep <ms>},
 * {@code print <text>}, {@code child <pidFile>} (starts a copy of itself that writes its PID to the file and
 * sleeps).
 */
public final class FakeCommand {

    private FakeCommand() {}

    /** The command line that starts this class in a new JVM. */
    public static List<String> command(String... args) {
        List<String> cmd = new ArrayList<String>();
        cmd.add(System.getProperty("java.home") + File.separator + "bin" + File.separator + "java");
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add(FakeCommand.class.getName());
        for (String a : args) cmd.add(a);
        return cmd;
    }

    public static void main(String[] args) throws Exception {
        String op = args[0];
        if ("exit".equals(op)) {
            System.exit(Integer.parseInt(args[1]));
        } else if ("sleep".equals(op)) {
            Thread.sleep(Long.parseLong(args[1]));
        } else if ("print".equals(op)) {
            System.out.println(args[1]);
        } else if ("child".equals(op)) {
            List<String> cmd = command("pid", args[1]);
            Process child = new ProcessBuilder(cmd).inheritIO().start();
            child.waitFor();
        } else if ("pid".equals(op)) {
            Files.write(new File(args[1]).toPath(), String.valueOf(pid()).getBytes("UTF-8"));
            Thread.sleep(60000);
        }
    }

    /** The PID of this JVM, through reflection (Java 8 source level). */
    private static long pid() throws Exception {
        Class<?> ph = Class.forName("java.lang.ProcessHandle");
        Object current = ph.getMethod("current").invoke(null);
        return (Long) ph.getMethod("pid").invoke(current);
    }
}
