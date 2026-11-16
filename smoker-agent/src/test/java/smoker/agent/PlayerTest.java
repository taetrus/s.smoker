package smoker.agent;

import org.junit.Test;
import smoker.core.model.Action;
import smoker.core.model.Locator;
import smoker.core.model.RunResult.Status;
import smoker.core.model.Scenario;
import smoker.core.model.Step;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Scenario status: the worst step status, order ERROR > HANG > FAIL > NO_GOLDEN > PASS (F-21). */
public class PlayerTest {

    private static final Status[] ORDER = {Status.PASS, Status.NO_GOLDEN, Status.FAIL, Status.HANG, Status.ERROR};

    @Test
    public void worseGivesTheHigherRankInBothArgumentOrders() {
        for (int i = 0; i < ORDER.length; i++) {
            for (int j = 0; j < ORDER.length; j++) {
                Status expected = ORDER[Math.max(i, j)];
                assertEquals(ORDER[i] + " vs " + ORDER[j], expected, Player.worse(ORDER[i], ORDER[j]));
            }
        }
    }

    @Test
    public void skipsSnapshotOnlyBeforeWaitFor() {
        Locator l = new Locator();
        l.name = "x";
        Scenario sc = new Scenario();
        Action[] actions = {Action.click(l), Action.waitFor(l, "text", "a", null), Action.click(l),
                Action.waitFor(l, "text", "a", null), Action.waitFor(l, "text", "a", null), Action.click(l)};
        for (int i = 0; i < actions.length; i++) sc.steps.add(new Step("s00" + i, "n", actions[i]));
        assertTrue(Player.skipsSnapshot(sc, 0));
        assertFalse(Player.skipsSnapshot(sc, 1));
        assertTrue(Player.skipsSnapshot(sc, 2));
        assertTrue(Player.skipsSnapshot(sc, 3));
        assertFalse(Player.skipsSnapshot(sc, 4));
        assertFalse(Player.skipsSnapshot(sc, 5));
    }
}
