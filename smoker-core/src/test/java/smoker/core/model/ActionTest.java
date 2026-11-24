package smoker.core.model;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import smoker.core.io.Json;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

public class ActionTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static Locator named(String name) {
        Locator l = new Locator();
        l.name = name;
        return l;
    }

    private <T> T roundTrip(T value, Class<T> type) throws Exception {
        Path f = tmp.newFile().toPath();
        Json.write(f, value);
        return Json.read(f, type);
    }

    private String written(Object value) throws Exception {
        Path f = tmp.newFile().toPath();
        Json.write(f, value);
        return new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
    }

    @Test
    public void oldClickHasDefaults() throws Exception {
        Path f = tmp.newFile().toPath();
        Files.write(f, "{\"type\":\"CLICK\",\"locator\":{\"name\":\"x\"}}".getBytes(StandardCharsets.UTF_8));
        Action a = Json.read(f, Action.class);
        assertEquals(1, a.clickCount());
        assertEquals(1, a.mouseButton());
        assertFalse(a.hasPlace());
        assertEquals("CLICK #x", a.toString());
    }

    @Test
    public void newFieldsRoundTrip() throws Exception {
        Action a = Action.click(named("orders.table"));
        a.row = 2;
        a.column = "Status";
        a.count = 2;
        Action b = roundTrip(a, Action.class);
        assertEquals(Integer.valueOf(2), b.row);
        assertEquals("Status", b.column);
        assertEquals(2, b.clickCount());
        assertFalse("absent fields are not written", written(Action.click(named("x"))).contains("count"));
    }

    @Test
    public void toStringShowsThePlace() {
        Action a = Action.click(named("orders.table"));
        a.row = 2;
        a.column = "Status";
        a.count = 2;
        assertEquals("CLICK #orders.table [row 2, Status] x2", a.toString());
        Action r = Action.click(named("files.tree"));
        r.path = Arrays.asList("Root", "src");
        r.button = 3;
        assertEquals("CLICK #files.tree [Root/src] right", r.toString());
        assertEquals("SELECT #files.tree [Root/src/Main.java]",
                Action.select(named("files.tree"), Arrays.asList("Root", "src", "Main.java")).toString());
        assertEquals("SET_CELL #orders.table [row 2, Qty] = \"5\"",
                Action.setCell(named("orders.table"), 2, "Qty", "5").toString());
        Action l = Action.click(named("names"));
        l.row = 4;
        l.count = 2;
        assertEquals("CLICK #names [row 4] x2", l.toString());
    }

    @Test
    public void stepGroupRoundTrips() throws Exception {
        Step s = new Step("s001", "a", Action.click(named("x")));
        assertFalse(written(s).contains("group"));
        s.group = "Create order";
        assertEquals("Create order", roundTrip(s, Step.class).group);
        assertNull(roundTrip(new Step("s002", "b", Action.click(named("x"))), Step.class).group);
    }
}
