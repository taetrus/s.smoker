package smoker.core.model;

/**
 * Stable identification of a Swing component (R-21).
 * Priority when resolving: {@link #name} &gt; windowTitle + className + text + index.
 * {@link #x}/{@link #y} are a last-resort fallback (window-relative).
 */
public class Locator {
    /** {@code Component.getName()} — the preferred, stable key. */
    public String name;
    /** Title of the owning window (frame/dialog). */
    public String windowTitle;
    /** Simple class name, e.g. {@code JButton}. */
    public String className;
    /** Visible text/label of the component, if any. */
    public String text;
    /** Index among siblings of the same class with the same text. */
    public Integer index;
    /** Window-relative fallback coordinates. */
    public Integer x, y;

    public boolean isNamed() {
        return name != null && !name.isEmpty();
    }

    @Override
    public String toString() {
        if (isNamed()) return "#" + name;
        StringBuilder sb = new StringBuilder();
        if (windowTitle != null) sb.append('[').append(windowTitle).append("] ");
        sb.append(className == null ? "?" : className);
        if (text != null && !text.isEmpty()) sb.append("('").append(text).append("')");
        if (index != null && index > 0) sb.append('[').append(index).append(']');
        return sb.toString();
    }
}
