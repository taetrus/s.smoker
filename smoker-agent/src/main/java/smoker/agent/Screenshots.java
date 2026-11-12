package smoker.agent;

import javax.imageio.ImageIO;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

/** Evidence screenshots (R-24). Needs a real or virtual display; silently skipped when headless. */
final class Screenshots {

    private Robot robot;

    /** @return relative file name written, or null if unavailable. */
    String capture(Path dir, String baseName) {
        try {
            if (GraphicsEnvironment.isHeadless()) return null;
            if (robot == null) robot = new Robot();
            Rectangle r = null;
            for (Window w : Window.getWindows()) {
                if (!w.isShowing()) continue;
                if (w.getName() != null && w.getName().startsWith(SnapshotTaker.SMOKER_WINDOW_PREFIX)) continue;
                r = r == null ? w.getBounds() : r.union(w.getBounds());
            }
            if (r == null || r.isEmpty()) return null;
            Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
            r = r.intersection(screen);
            if (r.isEmpty()) return null;
            BufferedImage img = robot.createScreenCapture(r);
            Files.createDirectories(dir);
            String file = baseName + ".png";
            ImageIO.write(img, "png", dir.resolve(file).toFile());
            return file;
        } catch (Throwable t) {
            return null;
        }
    }
}
