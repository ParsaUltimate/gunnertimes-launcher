import java.awt.*;
import java.awt.geom.Path2D;
import javax.swing.JPanel;

final class Editorial extends JPanel {
    private final boolean rtl;

    Editorial(boolean rtl) { this.rtl = rtl; }

    @Override protected void paintComponent(Graphics graphics) {
        var g = (Graphics2D) graphics.create();
        int width = getWidth(), height = getHeight();
        g.setColor(LauncherWindow.RAIL);
        g.fillRect(0, 0, width, height);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        if (rtl) {
            g.translate(width, 0);
            g.scale(-1, 1);
        }
        for (int line = 0; line < 13; line++) {
            var curve = new Path2D.Double();
            for (int x = 0; x <= width + 8; x += 8) {
                double fraction = x / (double) Math.max(1, width);
                double ridge = 54 * Math.sin(fraction * 6.9 + line * .19) + 15 * Math.sin(fraction * 15.6);
                double y = height - 75 + ridge + line * 10 - fraction * 57;
                if (x == 0) curve.moveTo(x, y); else curve.lineTo(x, y);
            }
            g.setColor(line == 4 ? new Color(0x42FFB23E, true) : new Color(0x20FFB23E, true));
            g.draw(curve);
        }
        g.dispose();
    }
}
