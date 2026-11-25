package smoker.core.report;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import smoker.core.model.RunResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
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

    @Test
    public void reportShowsATextProposalWithoutFieldOrAutomaticName() throws Exception {
        RunResult r = new RunResult();
        r.scenarioName = "settings";
        smoker.core.model.LintEntry l = new smoker.core.model.LintEntry();
        l.window = "[Settings]";
        l.runtimeClass = "app.SettingsDialog";
        l.type = "JButton";
        l.text = "Cancel";
        l.proposedName = "settings.btn.cancel";
        l.source = "text";
        r.lint.add(l);
        smoker.core.model.LintEntry t = new smoker.core.model.LintEntry();
        t.window = "[Settings]";
        t.runtimeClass = "app.SettingsDialog";
        t.type = "JTextField";
        t.label = "Base URL:";
        t.proposedName = "settings.txt.baseUrl";
        t.source = "label";
        r.lint.add(t);
        Path f = tmp.getRoot().toPath().resolve("report.html");
        HtmlReportWriter.write(f, Collections.singletonList(r));
        String html = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
        assertTrue(html, html.contains("settings.btn.cancel"));
        assertTrue(html, html.contains("<th>Label</th>"));
        assertTrue(html, html.contains("<td>Base URL:</td>"));
        assertTrue(html, html.contains("<td>SettingsDialog</td>"));
        assertTrue(!html.contains("null"));
    }

    @Test
    public void hitShowsTheFullText() throws Exception {
        RunResult r = new RunResult();
        r.scenarioName = "orders";
        RunResult.StepResult s = new RunResult.StepResult();
        s.stepId = "s001";
        s.status = RunResult.Status.FAIL;
        RunResult.LogHit h = new RunResult.LogHit();
        h.source = "stdout";
        h.line = "ERROR first <b>";
        h.text = "ERROR first <b>\nCaused by: second\n\tat third";
        s.logHits.add(h);
        RunResult.LogHit old = new RunResult.LogHit();
        old.source = "stderr";
        old.line = "ERROR only line";
        s.logHits.add(old);
        r.steps.add(s);
        Path f = tmp.getRoot().toPath().resolve("report.html");
        HtmlReportWriter.write(f, Collections.singletonList(r));
        String html = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
        assertTrue(html, html.contains("[stdout] ERROR first &lt;b&gt;\nCaused by: second\n\tat third\n"));
        assertTrue(html, html.contains("[stderr] ERROR only line\n"));
    }

    @Test
    public void noteShowsUnderTheScenario() throws Exception {
        RunResult r = new RunResult();
        r.scenarioName = "orders";
        r.note = "failed <in> a shared JVM";
        Path f = tmp.getRoot().toPath().resolve("report.html");
        HtmlReportWriter.write(f, Collections.singletonList(r));
        String html = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
        assertTrue(html, html.contains("<p class='note'>failed &lt;in&gt; a shared JVM</p>"));
        assertTrue(html, html.indexOf("<p class='note'>") < html.indexOf("<table><tr><th style='width:160px'>"));
    }

    @Test
    public void groupGivesAHeaderRow() throws Exception {
        RunResult r = new RunResult();
        r.scenarioName = "orders";
        String[] groups = {null, "Create <order>", null};
        for (int i = 0; i < 3; i++) {
            RunResult.StepResult s = new RunResult.StepResult();
            s.stepId = "s00" + (i + 1);
            s.stepName = "step";
            s.group = groups[i];
            r.steps.add(s);
        }
        Path f = tmp.getRoot().toPath().resolve("report.html");
        HtmlReportWriter.write(f, Collections.singletonList(r));
        String html = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
        int header = html.indexOf("<tr class='group'>");
        assertTrue(html, header > 0);
        assertTrue(html, html.indexOf("<tr class='group'>", header + 1) < 0);
        assertTrue(html, html.indexOf("<td>s001<") < header);
        assertTrue(html, header < html.indexOf("<td>s002<"));
        assertTrue(html, html.contains("Create &lt;order&gt;"));
    }

    @Test
    public void stepRowsHaveAnId() throws Exception {
        RunResult r = new RunResult();
        r.scenarioName = "Orders x";
        RunResult.StepResult st = new RunResult.StepResult();
        st.stepId = "s001";
        st.stepName = "open";
        st.status = RunResult.Status.PASS;
        st.group = "Open";
        r.steps.add(st);
        Path f = tmp.getRoot().toPath().resolve("report.html");
        HtmlReportWriter.write(f, Collections.singletonList(r));
        String html = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
        assertTrue(html, html.contains("<tr id='Orders_x-s001' class='PASS'>"));
        assertTrue(html, HtmlReportWriter.stepAnchor("Orders x", "s001").equals("Orders_x-s001"));
        assertTrue(html, html.contains("<h2 id='Orders_x'>"));
        int groups = html.split("<tr class='group'>", -1).length - 1;
        assertTrue(html, groups == 1);
    }

    @Test
    public void warningsShowBelowTheHeading() throws Exception {
        RunResult r = new RunResult();
        r.scenarioName = "orders";
        r.note = "the note";
        r.warnings.add("locale is tr-TR, not the configured en-US");
        r.warnings.add("window <size>");
        String html = page(r);
        int h2 = html.indexOf("<h2 id=");
        int note = html.indexOf("<p class='note'>the note</p>", h2);
        int first = html.indexOf("<p class='note'>locale is tr-TR, not the configured en-US</p>", h2);
        int second = html.indexOf("<p class='note'>window &lt;size&gt;</p>");
        assertTrue(html, h2 >= 0 && h2 < note && note < first && first < second);
        assertTrue(html, second < html.indexOf("<table><tr><th style='width:160px'>"));
        assertEquals(html, 4, html.split("class='note'", -1).length - 1); // the summary table does not change
    }

    @Test
    public void noWarningsNoChange() throws Exception {
        RunResult r = new RunResult();
        r.scenarioName = "orders";
        String html = page(r);
        assertTrue(html, !html.contains("class='note'"));
        RunResult n = new RunResult();
        n.scenarioName = "orders";
        n.warnings = null; // an old result file
        assertEquals(html.replaceAll("[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9:]+", ""),
                page(n).replaceAll("[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9:]+", ""));
    }

    private String page(RunResult r) throws Exception {
        Path f = tmp.getRoot().toPath().resolve("report.html");
        HtmlReportWriter.write(f, Collections.singletonList(r));
        return new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
    }
}
