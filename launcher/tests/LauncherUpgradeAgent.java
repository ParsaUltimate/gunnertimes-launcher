import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.instrument.Instrumentation;
import java.nio.file.*;
import javax.imageio.ImageIO;
import javax.swing.*;

/** Drives the visible Swing actions in unmodified public launcher binaries. */
public final class LauncherUpgradeAgent {
    public static void premain(String path, Instrumentation ignored) {
        Path evidence = Path.of(path);
        SwingUtilities.invokeLater(() -> new Timer(250, event -> {
            try { tick(evidence); }
            catch (Exception failure) { failure.printStackTrace(); }
        }).start());
    }

    private static void tick(Path evidence) throws Exception {
        JFrame frame = null;
        for (Window window : Window.getWindows())
            if (window instanceof JFrame candidate && candidate.isShowing()
                    && candidate.getTitle().equals("Gunnertimes")) frame = candidate;
        if (frame == null) return;
        try (var input = LauncherUpgradeAgent.class.getResourceAsStream("/launcher-version.txt")) {
            if (input == null) return;
            String version = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).strip();
            String mode = Boolean.getBoolean("gunnertimes.installed") ? "installed" : "portable";
            Path running = evidence.resolve("running-" + version);
            String state = mode + " " + ProcessHandle.current().pid();
            if (!Files.exists(running) || !Files.readString(running).equals(state)) Files.writeString(running, state);
        }
        Path request = evidence.resolve("command.txt");
        if (!Files.exists(request)) return;
        String[] command = Files.readString(request).strip().split("\\|", 3);
        if (command.length < 2) return;
        if (command[0].equals("exit")) {
            Files.delete(request);
            System.exit(0);
        }
        Window root = frame;
        if (command[0].equals("dialog")) {
            root = null;
            for (Window window : frame.getOwnedWindows())
                if (window instanceof JDialog && window.isShowing()) root = window;
            if (root == null) return;
        }
        JButton button = find(root, command[1]);
        if (button == null || !button.isEnabled()) return;
        if (command.length == 3) capture(root, evidence.resolve(command[2] + ".png"));
        Files.delete(request);
        SwingUtilities.invokeLater(() -> button.doClick(0));
    }

    static JButton find(Container parent, String label) {
        for (Component child : parent.getComponents()) {
            if (!child.isShowing()) continue;
            if (child instanceof JButton button && button.getText().equals(label)) return button;
            if (child instanceof Container container) {
                JButton found = find(container, label);
                if (found != null) return found;
            }
        }
        return null;
    }

    static void capture(Window window, Path path) throws Exception {
        var panel = ((RootPaneContainer) window).getContentPane();
        var image = new BufferedImage(panel.getWidth(), panel.getHeight(), BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try { panel.printAll(graphics); }
        finally { graphics.dispose(); }
        ImageIO.write(image, "png", path.toFile());
    }
}
