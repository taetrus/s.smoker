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
        CLOSE_WINDOW,
        /**
         * Poll each 100 ms until {@link #attribute} of the component matches {@link #matches} (the whole value).
         * FAIL after {@link #timeoutMs}.
         */
        WAIT_FOR
    }

    public static final long DEFAULT_WAIT_TIMEOUT_MS = 10_000;

    public Type type;
    public Locator locator;
    public String value;
    /** KeyEvent modifier mask for KEY, if any. */
    public Integer modifiers;
    /** WAIT_FOR: the snapshot attribute to read, e.g. {@code text}. */
    public String attribute;
    /** WAIT_FOR: Java regex; the whole value must match. */
    public String matches;
    /** WAIT_FOR: time limit in ms; {@code null} means {@link #DEFAULT_WAIT_TIMEOUT_MS}. */
    public Long timeoutMs;

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

    public static Action waitFor(Locator l, String attribute, String matches, Long timeoutMs) {
        Action a = new Action();
        a.type = Type.WAIT_FOR;
        a.locator = l;
        a.attribute = attribute;
        a.matches = matches;
        a.timeoutMs = timeoutMs;
        return a;
    }

    public long waitTimeoutMs() {
        return timeoutMs != null ? timeoutMs : DEFAULT_WAIT_TIMEOUT_MS;
    }

    @Override
    public String toString() {
        if (type == Type.WAIT_FOR) {
            return "WAIT_FOR" + (locator != null ? " " + locator : "") + " " + attribute + " ~ \"" + matches + "\"";
        }
        return type + (locator != null ? " " + locator : "") + (value != null ? " = \"" + value + "\"" : "");
    }
}
