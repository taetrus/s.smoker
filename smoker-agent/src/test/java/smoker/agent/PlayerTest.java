package smoker.agent;

import org.junit.Test;
import smoker.core.model.RunResult.Status;

import static org.junit.Assert.assertEquals;

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
}
