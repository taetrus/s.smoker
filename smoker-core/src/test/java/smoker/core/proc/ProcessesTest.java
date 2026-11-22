package smoker.core.proc;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ProcessesTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static boolean windows() {
        return File.separatorChar == '\\';
    }

    private static String read(Path p) throws Exception {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    @Test
    public void runGivesTheExitCode() throws Exception {
        Path log = tmp.getRoot().toPath().resolve("c.log");
        assertEquals(3, Processes.run(FakeCommand.command("exit", "3"), null, log, 30000));
        assertEquals(0, Processes.run(FakeCommand.command("exit", "0"), null, log, 30000));
    }

    @Test
    public void runClosesStdinOfAForegroundCommand() throws Exception {
        Path log = tmp.getRoot().toPath().resolve("c.log");
        long t0 = System.currentTimeMillis();
        assertEquals(0, Processes.run(FakeCommand.command("readstdin"), null, log, 30000));
        assertTrue(System.currentTimeMillis() - t0 < 25000);
    }

    @Test
    public void startKeepsStdinOpenForABackgroundProcess() throws Exception {
        Path log = tmp.getRoot().toPath().resolve("c.log");
        Process p = Processes.start(FakeCommand.command("readstdin"), null, log);
        try {
            assertFalse(p.waitFor(1500, java.util.concurrent.TimeUnit.MILLISECONDS));
        } finally {
            Processes.stopTree(p);
        }
    }

    @Test
    public void runAppendsTheOutputToTheLog() throws Exception {
        Path log = tmp.getRoot().toPath().resolve("c.log");
        Processes.run(FakeCommand.command("print", "first"), null, log, 30000);
        Processes.run(FakeCommand.command("print", "second"), null, log, 30000);
        String text = read(log);
        assertTrue(text, text.contains("first"));
        assertTrue(text, text.contains("second"));
        assertTrue(text, text.indexOf("first") < text.indexOf("second"));
    }

    @Test
    public void runStopsATimedOutCommand() throws Exception {
        Path log = tmp.getRoot().toPath().resolve("c.log");
        long t0 = System.currentTimeMillis();
        assertEquals(Processes.TIMEOUT, Processes.run(FakeCommand.command("sleep", "60000"), null, log, 1500));
        assertTrue(System.currentTimeMillis() - t0 < 1500 + 2000 + 5000);
    }

    @Test
    public void stopTreeStopsTheChildToo() throws Exception {
        Class<?> ph;
        try {
            ph = Class.forName("java.lang.ProcessHandle");
        } catch (ClassNotFoundException e) {
            Assume.assumeNoException(e);
            return;
        }
        Path pidFile = tmp.getRoot().toPath().resolve("child.pid");
        Path log = tmp.getRoot().toPath().resolve("c.log");
        Process p = Processes.start(FakeCommand.command("child", pidFile.toString()), null, log);
        long deadline = System.currentTimeMillis() + 20000;
        while (System.currentTimeMillis() < deadline && !(Files.exists(pidFile) && Files.size(pidFile) > 0)) {
            Thread.sleep(50);
        }
        assertTrue("the child wrote no PID: " + read(log), Files.exists(pidFile));
        long pid = Long.parseLong(read(pidFile).trim());
        Object opt = ph.getMethod("of", long.class).invoke(null, pid);
        java.lang.reflect.Method isPresent = Class.forName("java.util.Optional").getMethod("isPresent");
        assertTrue((Boolean) isPresent.invoke(opt));
        Object child = Class.forName("java.util.Optional").getMethod("get").invoke(opt);
        assertTrue((Boolean) ph.getMethod("isAlive").invoke(child));
        assertTrue(Processes.stopTree(p));
        p.waitFor();
        long end = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < end && (Boolean) ph.getMethod("isAlive").invoke(child)) Thread.sleep(50);
        assertFalse("the child still runs", (Boolean) ph.getMethod("isAlive").invoke(child));
    }

    @Test
    public void relativeProgramIsResolvedAgainstWorkDir() throws Exception {
        Path work = tmp.newFolder("work").toPath();
        Path bin = Files.createDirectories(work.resolve("bin"));
        Path log = tmp.getRoot().toPath().resolve("c.log");
        if (windows()) {
            Files.write(bin.resolve("hello.bat"), "@echo hello from bat\r\n".getBytes(StandardCharsets.US_ASCII));
            // cmd.exe finds "bin\hello.bat" itself; the resolved absolute path must work too
            assertEquals(0, Processes.run(Arrays.asList("bin/hello.bat"), work, log, 30000));
        } else {
            Path script = bin.resolve("hello");
            Files.write(script, "#!/bin/sh\necho hello from script\n".getBytes(StandardCharsets.US_ASCII));
            assertTrue(script.toFile().setExecutable(true));
            assertEquals(0, Processes.run(Arrays.asList("bin/hello"), work, log, 30000));
        }
        assertTrue(read(log), read(log).contains("hello from"));
    }

    @Test
    public void splitKeepsQuotedSpaces() {
        List<String> parts = Processes.split("  java -jar \"C:\\Program Files\\x.jar\"  --a   b ");
        assertEquals(Arrays.asList("java", "-jar", "C:\\Program Files\\x.jar", "--a", "b"), parts);
        assertTrue(Processes.split("   ").isEmpty());
    }
}
