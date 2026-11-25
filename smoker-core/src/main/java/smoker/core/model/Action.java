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
        WAIT_FOR,
        /** Set the cell ({@link #row}, {@link #column}) of a JTable to {@link #value}. */
        SET_CELL
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
    /** WAIT_FOR: timeout in ms; {@code null} means {@link #DEFAULT_WAIT_TIMEOUT_MS}. */
    public Long timeoutMs;
    /** CLICK: click count; {@code null} means 1. */
    public Integer count;
    /** CLICK: mouse button, 1 or 3; {@code null} means 1. */
    public Integer button;
    /** CLICK, SET_CELL: view row of a JTable or JList. */
    public Integer row;
    /** CLICK, SET_CELL: column of a JTable (header text, or the view index as a decimal string). */
    public String column;
    /** SELECT, CLICK: labels from the root to a node of a JTree. */
    public java.util.List<String> path;

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

    public static Action select(Locator l, java.util.List<String> path) {
        Action a = new Action();
        a.type = Type.SELECT;
        a.locator = l;
        a.path = path;
        return a;
    }

    public static Action setCell(Locator l, int row, String column, String value) {
        Action a = new Action();
        a.type = Type.SET_CELL;
        a.locator = l;
        a.row = row;
        a.column = column;
        a.value = value;
        return a;
    }

    public int clickCount() {
        return count != null ? count : 1;
    }

    public int mouseButton() {
        return button != null ? button : 1;
    }

    /** True when the action names a row or a tree node inside the component. */
    public boolean hasPlace() {
        return row != null || path != null;
    }

    public long waitTimeoutMs() {
        return timeoutMs != null ? timeoutMs : DEFAULT_WAIT_TIMEOUT_MS;
    }

    @Override
    public String toString() {
        if (type == Type.WAIT_FOR) {
            return "WAIT_FOR" + (locator != null ? " " + locator : "") + " " + attribute + " ~ \"" + matches + "\"";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(type);
        if (locator != null) sb.append(' ').append(locator);
        if (row != null) {
            sb.append(" [row ").append(row);
            if (column != null) sb.append(", ").append(column);
            sb.append(']');
        } else if (path != null) {
            sb.append(" [");
            for (int i = 0; i < path.size(); i++) sb.append(i > 0 ? "/" : "").append(path.get(i));
            sb.append(']');
        }
        if (value != null) sb.append(" = \"").append(value).append('"');
        if (clickCount() > 1) sb.append(" x").append(clickCount());
        if (mouseButton() == 3) sb.append(" right");
        return sb.toString();
    }
}
