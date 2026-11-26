import java.awt.*;
import java.nio.file.*;
import javax.imageio.ImageIO;
import javax.swing.*;

/** Captures the real Swing window with the selected, read-only QA profile. */
final class LauncherScreenshots {
    public static void main(String[] args) throws Exception {
        Path output = Files.createDirectories(Path.of(args[0]));
        var defaults = GunnertimesLauncher.defaults("en");
        var settings = new GunnertimesLauncher.Preferences("PlayerQA", args[1], "en", 4, defaults.options());
        var app = new LauncherWindow(settings);
        LauncherSettingsUiTest.app = app;
        try {
            SwingUtilities.invokeAndWait(() -> {
                app.show();
                Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
                app.frame().setSize(Math.min(1270, screen.width - 30), Math.min(805, screen.height - 30));
                app.frame().setLocation(screen.x + 12, screen.y + 12);
            });
            Thread.sleep(4000);
            for (String locale : new String[]{"en", "fa"}) {
                var text = new LauncherText(locale);
                SwingUtilities.invokeAndWait(() -> app.language(locale));
                for (String page : new String[]{"Home", "Pack", "Settings", "Help"}) {
                    SwingUtilities.invokeAndWait(() -> {
                        LauncherSettingsUiTest.click(text.get("nav" + page));
                        if (page.equals("Settings")) LauncherSettingsUiTest.click(text.get("javaSection"));
                    });
                    Thread.sleep(450);
                    var capture = new java.util.concurrent.atomic.AtomicReference<java.awt.image.BufferedImage>();
                    SwingUtilities.invokeAndWait(() -> {
                        var panel = app.frame().getContentPane();
                        var transform = app.frame().getGraphicsConfiguration().getDefaultTransform();
                        double scale = transform.getScaleX();
                        var image = new java.awt.image.BufferedImage((int) Math.ceil(panel.getWidth() * scale),
                            (int) Math.ceil(panel.getHeight() * scale), java.awt.image.BufferedImage.TYPE_INT_RGB);
                        var graphics = image.createGraphics();
                        graphics.scale(scale, scale);
                        panel.printAll(graphics);
                        graphics.dispose();
                        capture.set(image);
                    });
                    ImageIO.write(capture.get(), "png", output.resolve(page.toLowerCase(java.util.Locale.ROOT) + "-" + locale + ".png").toFile());
                }
            }
        } finally {
            SwingUtilities.invokeAndWait(() -> app.frame().dispose());
        }
    }
}
