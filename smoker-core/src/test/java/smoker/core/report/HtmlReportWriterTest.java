package smoker.core.report;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import smoker.core.model.RunResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.Assert.assertTrue;

public class HtmlReportWriterTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void reportShowsModeAndDuplicateNames() throws Exception {
        RunResult r = new RunResult();
        r.scenarioName = "orders";
        r.compare = "all";
        r.duplicateNames.add("[Orders]: #x (2x)");
        Path f = tmp.getRoot().toPath().resolve("report.html");
        HtmlReportWriter.write(f, Collections.singletonList(r));
        String html = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
        assertTrue(html, html.contains("compare: all"));
        assertTrue(html, html.contains("#x (2x)"));
        assertTrue(html, html.contains("Names that are not unique"));
    }

    @Test
    public void reportShowsTheLintTableAndAutomaticNames() throws Exception {
        RunResult r = new RunResult();
        r.scenarioName = "orders";
        smoker.core.model.LintEntry l = new smoker.core.model.LintEntry();
        l.window = "[Orders]";
        l.runtimeClass = "app.OrdersPanel";
        l.field = "saveButton";
        l.type = "JButton";
        l.text = "<b>Save</b>";
        l.autoName = "@OrdersPanel.saveButton";
        l.proposedName = "orders.btn.save";
        l.source = "field";
        r.lint.add(l);
        r.autoNames.add("@OrdersPanel.saveButton");
        Path f = tmp.getRoot().toPath().resolve("report.html");
        HtmlReportWriter.write(f, Collections.singletonList(r));
        String html = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
        assertTrue(html, html.contains("Components without a real name"));
        assertTrue(html, html.contains("orders.btn.save"));
        assertTrue(html, html.contains("<td>OrdersPanel</td>"));
        assertTrue(html, html.contains("&lt;b&gt;Save&lt;/b&gt;"));
        assertTrue(html, html.contains("Automatic names used in this run"));
        assertTrue(html, html.contains("@OrdersPanel.saveButton"));
        assertTrue(html, html.contains("An automatic name changes when a developer renames the field."));
    }
}
