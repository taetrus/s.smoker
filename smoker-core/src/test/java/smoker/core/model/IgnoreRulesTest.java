package smoker.core.model;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import smoker.core.io.Json;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class IgnoreRulesTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void emptyRulesIgnoreNothing() {
        IgnoreRules r = new IgnoreRules();
        assertFalse(r.ignoresPath("[Demo]/#demo.result"));
        assertFalse(r.ignoresAttribute("text"));
        assertFalse(r.valuesEquivalent("a", "a"));
    }

    @Test
    public void pathPatternMatchesAPartOfThePath() {
        IgnoreRules r = new IgnoreRules();
        r.paths.add("#demo\\.clock");
        assertTrue(r.ignoresPath("[Demo]/JPanel[0]/#demo.clock"));
        assertFalse(r.ignoresPath("[Demo]/JPanel[0]/#demo.result"));
    }

    @Test
    public void attributeNameMustAgreeExactly() {
        IgnoreRules r = new IgnoreRules();
        r.attributes.add("rowCount");
        assertTrue(r.ignoresAttribute("rowCount"));
        assertFalse(r.ignoresAttribute("rowcount"));
        assertFalse(r.ignoresAttribute("row"));
    }

    @Test
    public void valuesAreEquivalentOnlyWhenBothSidesMatch() {
        IgnoreRules r = new IgnoreRules();
        r.valuePatterns.add("Run #\\d+");
        assertTrue(r.valuesEquivalent("Run #1", "Run #42"));
        assertFalse(r.valuesEquivalent("Run #1", "Run x"));
        assertFalse(r.valuesEquivalent(null, "Run #1"));
        assertFalse(r.valuesEquivalent("Run #1", null));
    }

    @Test
    public void rulesReadFromJsonCompileTheirPatterns() throws Exception {
        Path f = tmp.getRoot().toPath().resolve("ignore.json");
        Files.write(f, "{\"paths\":[\"#x$\"],\"attributes\":[\"title\"],\"valuePatterns\":[\"\\\\d+\"]}"
                .getBytes(StandardCharsets.UTF_8));
        IgnoreRules r = Json.read(f, IgnoreRules.class);
        assertTrue(r.ignoresPath("[W]/#x"));
        assertTrue(r.ignoresAttribute("title"));
        assertTrue(r.valuesEquivalent("1", "2"));
    }
}
