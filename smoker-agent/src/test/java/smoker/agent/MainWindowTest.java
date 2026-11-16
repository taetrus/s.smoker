package smoker.agent;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The startup check accepts only a shown and displayable frame. Skipped without a display. */
public class MainWindowTest {

    private JFrame frame;

    /**
     * The fake frame reports "showing" only while this flag is set. Window.getWindows() keeps the fake until it is
     * collected, so a fake that stays "showing" goes into the snapshots of later test classes in the same JVM.
     */
    private final AtomicBoolean fakeShowing = new AtomicBoolean();

    @Before
    public void display() {
        Assume.assumeFalse(GraphicsEnvironment.isHeadless());
    }

    @After
    public void dispose() throws Exception {
        fakeShowing.set(false);
        if (frame == null) return;
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                frame.dispose();
            }
        });
    }

    private boolean onEdt(final Callable<Boolean> c) throws Exception {
        final boolean[] r = new boolean[1];
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                try {
                    r[0] = c.call();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });
        return r[0];
    }

    @Test
    public void undisplayableFrameThatReportsShowingIsNotAMainWindow() throws Exception {
        fakeShowing.set(true);
        final JFrame f = new JFrame() {
            @Override
            public boolean isShowing() {
                return fakeShowing.get();
            }
        };
        frame = f;
        assertFalse(onEdt(new Callable<Boolean>() {
            @Override
            public Boolean call() {
                assertFalse(f.isDisplayable());
                return Player.isMainWindow(f);
            }
        }));
    }

    @Test
    public void shownFrameIsAMainWindow() throws Exception {
        frame = onEdtFrame("demo.main");
        assertTrue(onEdt(new Callable<Boolean>() {
            @Override
            public Boolean call() {
                return Player.isMainWindow(frame);
            }
        }));
        assertTrue(Player.mainWindowShown(5000));
    }

    /** An interrupt gives "no main window" and stays set for the caller. */
    @Test
    public void interruptGivesFalseAndKeepsTheFlag() {
        Thread.currentThread().interrupt();
        boolean shown = Player.mainWindowShown(5000);
        boolean flag = Thread.interrupted();   // also clears the flag for the next test
        assertFalse(shown);
        assertTrue(flag);
    }

    @Test
    public void smokerWindowIsNotAMainWindow() throws Exception {
        frame = onEdtFrame("smoker.toolbar");
        assertFalse(onEdt(new Callable<Boolean>() {
            @Override
            public Boolean call() {
                return Player.isMainWindow(frame);
            }
        }));
    }

    private JFrame onEdtFrame(final String name) throws Exception {
        final JFrame[] f = new JFrame[1];
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                f[0] = new JFrame();
                f[0].setName(name);
                f[0].setSize(100, 100);
                f[0].setVisible(true);
            }
        });
        return f[0];
    }
}
