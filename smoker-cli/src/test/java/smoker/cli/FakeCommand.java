package smoker.cli;

import smoker.core.io.ConfigFile;
import smoker.core.io.Json;
import smoker.core.model.RunResult;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;

/**
 * A command for the tests of the suite runner. Arguments: {@code exit <n>}, {@code sleep <ms>}, {@code touch <file>},
 * {@code sleeptouch <ms> <file>}, {@code results -javaagent:...=config=<file>}.
 */
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
        } else if ("results".equals(op)) {
            results(args[1].substring(args[1].indexOf("=config=") + "=config=".length()));
        }
    }

    /**
     * As an agent in a shared JVM: a result for each scenario of the configuration. The first scenario of the start
     * passes, a later one fails (the state of an earlier scenario).
     */
    private static void results(String cfgFile) throws Exception {
        Map<String, String> cfg = ConfigFile.read(new File(cfgFile).toPath());
        Path out = new File(cfg.get("out")).toPath();
        for (int i = 1; cfg.containsKey("scenario." + i); i++) {
            String file = new File(cfg.get("scenario." + i)).getName();
            String name = file.substring(0, file.lastIndexOf('.'));
            RunResult r = new RunResult();
            r.scenarioName = "Scenario " + name;
            r.status = i == 1 ? RunResult.Status.PASS : RunResult.Status.FAIL;
            RunResult.StepResult startup = new RunResult.StepResult();
            startup.stepId = "startup";
            r.steps.add(startup);
            RunResult.StepResult step = new RunResult.StepResult();
            step.stepId = "s001";
            step.status = r.status;
            r.steps.add(step);
            r.startedAt = System.currentTimeMillis();
            r.finishedAt = r.startedAt + 1;
            Files.createDirectories(out.resolve(name));
            Json.write(out.resolve(name).resolve("run-result.json"), r);
        }
    }
}
