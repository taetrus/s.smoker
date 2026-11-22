package smoker.cli;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;

/** A command for the tests of the suite runner. Arguments: {@code exit <n>}, {@code sleep <ms>}, {@code touch <file>}, {@code sleeptouch <ms> <file>}. */
public final class FakeCommand {

    private FakeCommand() {}

    /** The command line, as text for a suite file, that starts this class in a new JVM. */
    static String line(String... args) {
        StringBuilder sb = new StringBuilder();
        sb.append('"').append(System.getProperty("java.home")).append(File.separator).append("bin")
                .append(File.separator).append("java\" -cp \"").append(System.getProperty("java.class.path"))
                .append("\" ").append(FakeCommand.class.getName());
        for (String a : args) sb.append(" \"").append(a).append('"');
        return sb.toString();
    }

    public static void main(String[] args) throws Exception {
        String op = args[0];
        if ("exit".equals(op)) {
            System.exit(Integer.parseInt(args[1]));
        } else if ("sleep".equals(op)) {
            Thread.sleep(Long.parseLong(args[1]));
        } else if ("sleeptouch".equals(op)) {
            Thread.sleep(Long.parseLong(args[1]));
            Files.write(new File(args[2]).toPath(), new ArrayList<String>());
        } else if ("touch".equals(op)) {
            Files.write(new File(args[1]).toPath(), new ArrayList<String>());
        }
    }
}
