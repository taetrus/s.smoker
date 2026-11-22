package smoker.core.report;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import smoker.core.model.RunResult;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;

public class JUnitXmlWriterTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void timeAttributesUseAPointInEachLocale() throws Exception {
        RunResult r = new RunResult();
        r.scenarioName = "greet <1>";
        r.startedAt = 1000;
        r.finishedAt = 2500;
        RunResult.StepResult s = new RunResult.StepResult();
        s.stepId = "s001";
        s.stepName = "enter \"name\"";
        s.status = RunResult.Status.FAIL;
        s.durationMs = 1234;
        s.differences.add("CHANGED [Demo]/#demo.result .text: expected=\"a\" actual=\"b\"");
        r.steps.add(s);

        Path f = tmp.getRoot().toPath().resolve("junit.xml");
        JUnitXmlWriter.write(f, Arrays.asList(r));

        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f.toFile());
        Element suite = (Element) doc.getElementsByTagName("testsuite").item(0);
        assertEquals("greet <1>", suite.getAttribute("name"));
        assertEquals("1.500", suite.getAttribute("time"));
        Element tc = (Element) doc.getElementsByTagName("testcase").item(0);
        assertEquals("1.234", tc.getAttribute("time"));
        NodeList failures = tc.getElementsByTagName("failure");
        assertEquals(1, failures.getLength());
    }

    @Test
    public void failureBodyHasTheFullTextOfAHit() throws Exception {
        RunResult r = new RunResult();
        r.scenarioName = "x";
        RunResult.StepResult s = new RunResult.StepResult();
        s.stepId = "s001";
        s.stepName = "n";
        s.status = RunResult.Status.FAIL;
        RunResult.LogHit h = new RunResult.LogHit();
        h.source = "stdout";
        h.line = "ERROR first";
        h.text = "ERROR first\nCaused by: second";
        s.logHits.add(h);
        RunResult.LogHit old = new RunResult.LogHit();
        old.source = "stderr";
        old.line = "ERROR only line";
        s.logHits.add(old);
        r.steps.add(s);
        Path f = tmp.getRoot().toPath().resolve("junit.xml");
        JUnitXmlWriter.write(f, Arrays.asList(r));
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f.toFile());
        String body = doc.getElementsByTagName("failure").item(0).getTextContent();
        org.junit.Assert.assertTrue(body, body.contains("[log:stdout] ERROR first\nCaused by: second\n"));
        org.junit.Assert.assertTrue(body, body.contains("[log:stderr] ERROR only line\n"));
    }

    @Test
    public void noteGoesToSystemOut() throws Exception {
        RunResult r = new RunResult();
        r.scenarioName = "greet";
        r.note = "failed in a shared JVM <&> x";
        RunResult.StepResult s = new RunResult.StepResult();
        s.stepId = "s001";
        s.stepName = "one";
        r.steps.add(s);
        Path f = tmp.getRoot().toPath().resolve("junit.xml");
        JUnitXmlWriter.write(f, Arrays.asList(r));
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f.toFile());
        NodeList out = doc.getElementsByTagName("system-out");
        assertEquals(1, out.getLength());
        assertEquals("failed in a shared JVM <&> x", out.item(0).getTextContent());
    }
}
