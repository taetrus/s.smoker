package smoker.agent;

import org.junit.Test;
import smoker.core.model.Locator;

import static org.junit.Assert.assertEquals;

/** The picker text of the recorder toolbar. Needs no window. */
public class RecorderToolbarTest {

    @Test
    public void automaticNameNeedsARealName() {
        Locator l = new Locator();
        l.name = "@OrdersPanel.saveButton";
        assertEquals("#@OrdersPanel.saveButton (automatic name - needs a real name)   [JButton]",
                RecorderToolbar.pickerText(l, "JButton"));
    }

    @Test
    public void realNameHasNoNote() {
        Locator l = new Locator();
        l.name = "orders.save";
        assertEquals("#orders.save   [JButton]", RecorderToolbar.pickerText(l, "JButton"));
    }
}
