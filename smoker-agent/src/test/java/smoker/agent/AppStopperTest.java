package smoker.agent;

import org.junit.After;
import org.junit.Assume;
import org.junit.Test;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** The three stages of the stop (WP-05, F-10, R-38). The frame tests are skipped without a display. */
public class AppStopperTest {

    private JFrame frame;

    @After
    public void dispose() throws Exception {
        if (frame == null) return;
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                frame.dispose();
            }
        });
    }

    /** Records the calls in order. {@code exit} can block on a latch. */
    private static final class Recorder implements AppStopper.Exit {
        final List<String> calls = Collections.synchronizedList(new ArrayList<String>());
        final CountDownLatch halted = new CountDownLatch(1);
        final CountDownLatch blockExit;

        Recorder(CountDownLatch blockExit) {
            this.blockExit = blockExit;
        }

        @Override
        public void exit(int code) {
            calls.add("exit(" + code + ")");
            if (blockExit != null) {
                try {
                    blockExit.await();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        @Override
        public void halt(int code) {
            calls.add("halt(" + code + ")");
            halted.countDown();
        }
    }

    private void showFrame(final Recorder rec) throws Exception {
        showFrame(rec, JFrame.DO_NOTHING_ON_CLOSE);
    }

    private void showFrame(final Recorder rec, final int closeOperation) throws Exception {
        Assume.assumeFalse(GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                frame = new JFrame("stopper test");
                frame.setName("stopper.frame");
                frame.setDefaultCloseOperation(closeOperation);
                frame.addWindowListener(new WindowAdapter() {
                    @Override
                    public void windowClosing(WindowEvent e) {
                        rec.calls.add("closing");
                    }
                });
                frame.setSize(100, 100);
                frame.setVisible(true);
            }
        });
    }

    @Test
    public void stopClosesTheFramesFirst() throws Exception {
        Recorder rec = new Recorder(null);
        showFrame(rec);
        new AppStopper(200, rec).stop(0, false);
        assertEquals("closing", rec.calls.get(0));
        assertEquals("exit(0)", rec.calls.get(1));
    }

    @Test
    public void stopGoesOnWhenTheFramesAreGone() throws Exception {
        Recorder rec = new Recorder(null);
        showFrame(rec, JFrame.DISPOSE_ON_CLOSE);
        long t0 = System.currentTimeMillis();
        new AppStopper(5000, rec).stop(0, false);
        long ms = System.currentTimeMillis() - t0;
        assertEquals("closing", rec.calls.get(0));
        assertTrue(String.valueOf(rec.calls), rec.calls.contains("exit(0)"));
        assertTrue("the stop took " + ms + " ms", ms < 2500);
    }

    @Test
    public void stopWaitsWhileAFrameStillShows() throws Exception {
        Recorder rec = new Recorder(null);
        showFrame(rec);
        long t0 = System.currentTimeMillis();
        new AppStopper(600, rec).stop(0, false);
        long ms = System.currentTimeMillis() - t0;
        assertTrue("the stop took " + ms + " ms", ms >= 600);
    }

    @Test
    public void stopSkipsTheCloseWhenTheEdtIsBlocked() throws Exception {
        Recorder rec = new Recorder(null);
        showFrame(rec);
        new AppStopper(200, rec).stop(1, true);
        assertFalse(String.valueOf(rec.calls), rec.calls.contains("closing"));
        assertTrue(rec.calls.contains("exit(1)"));
    }

    @Test
    public void stopCallsExitWithTheCode() {
        Recorder rec = new Recorder(null);
        new AppStopper(50, rec).stop(1, false);
        assertTrue(String.valueOf(rec.calls), rec.calls.contains("exit(1)"));
    }

    @Test
    public void exitIsCalledAlsoWhenAnEarlierStageThrows() {
        java.io.PrintStream saved = System.out;
        System.setOut(new java.io.PrintStream(new java.io.OutputStream() {
            @Override
            public void write(int b) {
                throw new IllegalStateException("console broken");
            }
        }));
        Recorder rec = new Recorder(null);
        try {
            new AppStopper(50, rec).stop(1, false);
            fail("the exception of stage 1 must reach the caller");
        } catch (IllegalStateException expected) {
            assertEquals("console broken", expected.getMessage());
        } finally {
            System.setOut(saved);
        }
        assertTrue(String.valueOf(rec.calls), rec.calls.contains("exit(1)"));
    }

    @Test
    public void haltFollowsWhenExitDoesNotReturn() throws Exception {
        final CountDownLatch release = new CountDownLatch(1);
        final Recorder rec = new Recorder(release);
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                new AppStopper(200, rec).stop(1, false);
            }
        });
        t.setDaemon(true);
        t.start();
        try {
            assertTrue(String.valueOf(rec.calls), rec.halted.await(1, TimeUnit.SECONDS));
            assertTrue(rec.calls.contains("halt(1)"));
        } finally {
            release.countDown();
            t.join(2000);
        }
    }

    /** A loader of a bundle: the stage 2 finds the system bundle through it. */
    public static final class FakeBundleLoader extends ClassLoader {
        static final boolean[] STOPPED = new boolean[1];

        public Object getBundle() {
            return new Object() {
                public Object getBundleContext() {
                    return new Object() {
                        public Object getBundle(long id) {
                            return id == 0L ? new Object() {
                                public void stop() {
                                    STOPPED[0] = true;
                                }
                            } : null;
                        }
                    };
                }
            };
        }
    }

    @Test
    public void systemBundleOfUsesGetBundle() throws Exception {
        Object bundle = AppStopper.systemBundleOf(new FakeBundleLoader());
        assertNotNull(bundle);
        java.lang.reflect.Method stop = bundle.getClass().getMethod("stop");
        stop.setAccessible(true);
        stop.invoke(bundle);
        assertTrue(FakeBundleLoader.STOPPED[0]);
    }

    @Test
    public void systemBundleOfGivesNullForAPlainLoader() {
        assertNull(AppStopper.systemBundleOf(new ClassLoader() { }));
    }
}
