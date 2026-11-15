package smoker.agent;

import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Window;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Automatic names {@code @<Class>.<field>} for unnamed components (WP-09, spec 5.1).
 * The name comes from the instance field of an application-class ancestor that refers to the component.
 * Call {@link #of} on the EDT only. The class compares field values with {@code ==} and keeps no value.
 */
final class AutoNames {

    /** The automatic name of a component and where it comes from. */
    static final class Hit {
        /** {@code @<SimpleName of the runtime class>.<field>}. */
        final String name;
        /** Fully qualified name of the runtime class of the ancestor. */
        final String runtimeClass;
        /** Fully qualified name of the class that declares the field. */
        final String declaringClass;
        final String field;

        Hit(String name, String runtimeClass, String declaringClass, String field) {
            this.name = name;
            this.runtimeClass = runtimeClass;
            this.declaringClass = declaringClass;
            this.field = field;
        }
    }

    private static final String[] JDK_PREFIXES = {
            "java.", "javax.", "sun.", "com.sun.", "jdk.", "com.apple.", "apple.", "smoker."};

    private static final ConcurrentHashMap<Class<?>, Field[]> FIELDS = new ConcurrentHashMap<Class<?>, Field[]>();

    private static volatile boolean enabled = true;

    private AutoNames() {
    }

    static void enable(boolean on) {
        enabled = on;
    }

    static boolean enabled() {
        return enabled;
    }

    static boolean isApplicationClass(Class<?> c) {
        String n = c.getName();
        for (String p : JDK_PREFIXES) {
            if (n.startsWith(p)) return false;
        }
        return true;
    }

    /**
     * The automatic name of {@code c}, or null: when disabled, when {@code c} has a real name, when it is a
     * layout container, or when no application-class ancestor up to the window has a field that refers to it.
     */
    static Hit of(Component c) {
        assert SwingUtilities.isEventDispatchThread() : "AutoNames must run on the EDT";
        if (!enabled || c == null) return null;
        if (Locators.realName(c) != null) return null;
        return fieldWalk(c);
    }

    /**
     * The automatic name of {@code c} as if it had no real name, or null. Null also when disabled.
     * {@link Locators#find} uses it, so that a locator recorded before the real name still finds the component.
     */
    static String nameIgnoringRealName(Component c) {
        assert SwingUtilities.isEventDispatchThread() : "AutoNames must run on the EDT";
        if (!enabled || c == null) return null;
        Hit h = fieldWalk(c);
        return h == null ? null : h.name;
    }

    /** The field walk of {@link #of}, without the real-name check. */
    private static Hit fieldWalk(Component c) {
        if (SnapshotTaker.isLayoutContainer(c)) return null;
        if (c instanceof Window) return null;
        for (Component a = c.getParent(); a != null; a = logicalParent(a)) {
            Hit h = fieldOf(a, c);
            if (h != null) return h;
            // The owner of a window is not an ancestor in the component tree.
            if (a instanceof Window) break;
        }
        return null;
    }

    /**
     * The parent of {@code a} for the ancestor walks: for a popup menu with an invoker, the invoker
     * (as {@link Locators#logicalWindowOf}). So a menu item of a closed menu or of a heavyweight popup
     * reaches the application class of its menu bar or invoker. Must run on the EDT.
     */
    static Component logicalParent(Component a) {
        if (a instanceof JPopupMenu) {
            Component invoker = ((JPopupMenu) a).getInvoker();
            // An invoker inside its own popup would give an endless walk.
            if (invoker != null && !SwingUtilities.isDescendingFrom(invoker, a)) return invoker;
        }
        return a.getParent();
    }

    /** The first instance field of {@code holder} that refers to {@code c}: runtime class first, declaration order. */
    private static Hit fieldOf(Object holder, Component c) {
        Class<?> runtime = holder.getClass();
        for (Class<?> k = runtime; k != null && isApplicationClass(k); k = k.getSuperclass()) {
            for (Field f : fieldsOf(k)) {
                if (!f.getType().isInstance(c)) continue;
                try {
                    if (f.get(holder) == c) {
                        return new Hit("@" + simpleName(runtime) + "." + f.getName(),
                                runtime.getName(), k.getName(), f.getName());
                    }
                } catch (IllegalAccessException e) {
                    // skip the field
                } catch (RuntimeException e) {
                    // skip the field
                }
            }
        }
        return null;
    }

    /** The accessible, non-static, non-synthetic fields that {@code k} declares, in declaration order. Cached. */
    private static Field[] fieldsOf(Class<?> k) {
        Field[] cached = FIELDS.get(k);
        if (cached != null) return cached;
        List<Field> list = new ArrayList<Field>();
        Field[] declared;
        try {
            declared = k.getDeclaredFields();
        } catch (RuntimeException e) {
            declared = new Field[0];
        } catch (LinkageError e) {
            // OSGi: a field type that the bundle cannot see.
            declared = new Field[0];
        }
        for (Field f : declared) {
            if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic() || f.getType().isPrimitive()) continue;
            try {
                f.setAccessible(true);
                list.add(f);
            } catch (RuntimeException e) {
                // security manager or module rules: skip the field
            }
        }
        Field[] result = list.toArray(new Field[0]);
        Field[] prev = FIELDS.putIfAbsent(k, result);
        return prev != null ? prev : result;
    }

    /** The simple name of {@code k}; for an anonymous or local class, that of the nearest enclosing named class. */
    static String simpleName(Class<?> k) {
        Class<?> c = k;
        try {
            while ((c.isAnonymousClass() || c.isLocalClass()) && c.getEnclosingClass() != null) {
                c = c.getEnclosingClass();
            }
            String s = c.getSimpleName();
            if (!s.isEmpty()) return s;
        } catch (InternalError e) {
            // Java 8: malformed class name of some non-Java compilers.
        } catch (LinkageError e) {
            // OSGi: the enclosing class is not visible to the bundle.
        }
        // Fallback: the part of the binary name after the last '.' or '$'.
        String n = c.getName();
        return n.substring(Math.max(n.lastIndexOf('.'), n.lastIndexOf('$')) + 1);
    }
}
