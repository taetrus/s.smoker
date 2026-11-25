package smoker.cli;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ReleaseCheckTest {

    private static final String AGENT = "-javaagent:C:\\tools\\smoker-agent.jar=config=x.cfg";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();

    private int check(String... paths) throws Exception {
        PrintStream out = new PrintStream(outBytes, true, "UTF-8");
        PrintStream err = new PrintStream(errBytes, true, "UTF-8");
        return ReleaseCheck.run(paths, out, err);
    }

    private String out() throws Exception {
        return outBytes.toString("UTF-8");
    }

    private String err() throws Exception {
        return errBytes.toString("UTF-8");
    }

    private Path ini(Path file, String... lines) throws Exception {
        Files.createDirectories(file.getParent());
        Files.write(file, String.join("\n", lines).getBytes(StandardCharsets.ISO_8859_1));
        return file;
    }

    private Path zip(Path file, String entry, String content) throws Exception {
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(file.toFile()))) {
            z.putNextEntry(new ZipEntry(entry));
            z.write(content.getBytes(StandardCharsets.ISO_8859_1));
            z.closeEntry();
        }
        return file;
    }

    @Test
    public void cleanFolderGives0() throws Exception {
        Path dir = tmp.newFolder().toPath();
        ini(dir.resolve("eclipse.ini"), "-vmargs", "-Xmx1g");
        assertEquals(0, check(dir.toString()));
        assertTrue(out(), out().contains("check-release: 1 file(s) checked, no agent option"));
    }

    @Test
    public void agentOptionGives1() throws Exception {
        Path dir = tmp.newFolder().toPath();
        Path f = ini(dir.resolve("eclipse.ini"), "-vmargs", "-Xmx1g", AGENT);
        assertEquals(1, check(dir.toString()));
        assertTrue(out(), out().contains(f + ":3: " + AGENT));
    }

    @Test
    public void leadingBlanksAndUpperCase() throws Exception {
        Path dir = tmp.newFolder().toPath();
        ini(dir.resolve("eclipse.ini"), "   -javaagent:D:\\SMOKER\\Agent.jar");
        assertEquals(1, check(dir.toString()));
    }

    @Test
    public void otherAgentIsNoHit() throws Exception {
        Path dir = tmp.newFolder().toPath();
        ini(dir.resolve("eclipse.ini"), "-javaagent:jacoco.jar");
        assertEquals(0, check(dir.toString()));
    }

    @Test
    public void textInTheMiddleIsNoHit() throws Exception {
        Path dir = tmp.newFolder().toPath();
        ini(dir.resolve("eclipse.ini"), "# -javaagent:smoker", "-Dx=-javaagent:smoker");
        assertEquals(0, check(dir.toString()));
    }

    @Test
    public void iniInASubfolder() throws Exception {
        Path dir = tmp.newFolder().toPath();
        ini(dir.resolve("a/b/config.ini"), AGENT);
        assertEquals(1, check(dir.toString()));
    }

    @Test
    public void iniInAZip() throws Exception {
        Path dir = tmp.newFolder().toPath();
        Path z = zip(dir.resolve("product.zip"), "app/eclipse.ini", "-vmargs\n" + AGENT + "\n");
        assertEquals(1, check(dir.toString()));
        assertTrue(out(), out().startsWith(z + "!app/eclipse.ini:2: "));
    }

    @Test
    public void zipInAZipIsNotRead() throws Exception {
        Path dir = tmp.newFolder().toPath();
        Path inner = zip(dir.resolve("inner.zip"), "eclipse.ini", AGENT);
        Path outer = dir.resolve("outer/outer.zip");
        Files.createDirectories(outer.getParent());
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(outer.toFile()))) {
            z.putNextEntry(new ZipEntry("inner.zip"));
            z.write(Files.readAllBytes(inner));
            z.closeEntry();
            z.putNextEntry(new ZipEntry("eclipse.ini"));
            z.write("-Xmx1g".getBytes(StandardCharsets.ISO_8859_1));
            z.closeEntry();
        }
        Files.delete(inner);
        assertEquals(0, check(dir.toString()));
    }

    @Test
    public void singleIniFileAsPath() throws Exception {
        Path f = ini(tmp.newFolder().toPath().resolve("eclipse.ini"), AGENT);
        assertEquals(1, check(f.toString()));
    }

    @Test
    public void singleZipAsPath() throws Exception {
        Path z = zip(tmp.newFolder().toPath().resolve("product.zip"), "eclipse.ini", AGENT);
        assertEquals(1, check(z.toString()));
        assertTrue(out(), out().startsWith(z + "!eclipse.ini:1: "));
    }

    @Test
    public void noIniFileGives2() throws Exception {
        Path dir = tmp.newFolder().toPath();
        assertEquals(2, check(dir.toString()));
        assertTrue(err(), err().contains("check-release: no .ini file in " + dir));
    }

    @Test
    public void onePathWithoutIniGives2() throws Exception {
        Path a = tmp.newFolder().toPath();
        Path b = tmp.newFolder().toPath();
        ini(a.resolve("eclipse.ini"), "-Xmx1g");
        assertEquals(2, check(a.toString(), b.toString()));
    }

    @Test
    public void missingPathGives2() throws Exception {
        assertEquals(2, check(new File(tmp.getRoot(), "nope").toString()));
        assertTrue(err(), err().contains("does not exist"));
    }

    @Test
    public void noPathGives2() throws Exception {
        assertEquals(2, check());
        assertTrue(err(), err().contains("usage"));
    }

    @Test
    public void hitWinsOverCount() throws Exception {
        Path a = tmp.newFolder().toPath();
        Path b = tmp.newFolder().toPath();
        ini(a.resolve("eclipse.ini"), "-Xmx1g");
        ini(b.resolve("eclipse.ini"), AGENT);
        assertEquals(1, check(a.toString(), b.toString()));
    }

    @Test
    public void errorWinsOverHit() throws Exception {
        Path a = tmp.newFolder().toPath();
        Path b = tmp.newFolder().toPath();
        ini(a.resolve("eclipse.ini"), AGENT);
        assertEquals(2, check(a.toString(), b.toString()));
        assertTrue(out(), out().contains(":1: " + AGENT));
    }
}
