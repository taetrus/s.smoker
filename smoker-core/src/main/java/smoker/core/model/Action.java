package smoker.core.model;

/** One user action, replayed semantically by default (R-31). */
public class Action {

    public enum Type {
        /** Click a button / menu item / generic component. */
        CLICK,
        /** Replace the text of a text component with {@link #value}. */
        SET_TEXT,
        /** Select an item in JComboBox/JList/JTabbedPane/JTable by {@link #value} (text or index). */
        SELECT,
        /** Press a key on the focus owner; {@link #value} = KeyEvent VK name, e.g. {@code ENTER}, {@code F5}. */
        KEY,
        /** Sleep {@link #value} ms (use sparingly; prefer quiescence). */
        WAIT,
        /** Close the window identified by locator.windowTitle. */
        CLOSE_WINDOW
    }

    public Type type;
    public Locator locator;
    public String value;
    /** KeyEvent modifier mask for KEY, if any. */
    public Integer modifiers;

    public static Action click(Locator l) {
        Action a = new Action();
        a.type = Type.CLICK;
        a.locator = l;
        return a;
    }

    public static Action setText(Locator l, String text) {
        Action a = new Action();
        a.type = Type.SET_TEXT;
        a.locator = l;
        a.value = text;
        return a;
    }

    public static Action key(String vkName, int modifiers) {
        Action a = new Action();
        a.type = Type.KEY;
        a.value = vkName;
        a.modifiers = modifiers == 0 ? null : modifiers;
        return a;
    }

    @Override
    public String toString() {
        return type + (locator != null ? " " + locator : "") + (value != null ? " = \"" + value + "\"" : "");
    }
}
