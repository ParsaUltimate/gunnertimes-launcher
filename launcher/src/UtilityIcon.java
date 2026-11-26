import java.awt.*;
import java.util.Map;
import javax.swing.Icon;

final class UtilityIcon implements Icon {
    private static final Map<String, int[][]> LINES = Map.ofEntries(
        Map.entry("home", new int[][]{{2,9,10,2,18,9},{4,8,4,18,8,18,8,12,12,12,12,18,16,18,16,8}}),
        Map.entry("folder", new int[][]{{2,5,8,5,10,8,18,8,18,17,2,17,2,5}}),
        Map.entry("play", new int[][]{{5,3,17,10,5,17,5,3}}),
        Map.entry("server", new int[][]{{2,3,18,3,18,8,2,8,2,3},{2,12,18,12,18,17,2,17,2,12},{5,5,5,6},{5,14,5,15}}),
        Map.entry("download", new int[][]{{10,2,10,13},{6,9,10,13,14,9},{2,13,2,18,18,18,18,13}}),
        Map.entry("document", new int[][]{{4,2,13,2,16,5,16,18,4,18,4,2},{7,8,13,8},{7,12,13,12}}),
        Map.entry("check", new int[][]{{10,2,17,5,16,13,10,18,4,13,3,5,10,2},{6,9,9,12,14,7}}),
        Map.entry("repair", new int[][]{{3,15,11,7,10,4,13,1,13,5,16,6,19,3,19,7,16,10,13,10,5,18,3,18,2,17,3,15}}),
        Map.entry("game", new int[][]{{2,3,18,3,18,14,2,14,2,3},{10,14,10,18},{6,18,14,18}}),
        Map.entry("person", new int[][]{{3,18,3,15,6,12,14,12,17,15,17,18},{7,7,7,4,10,2,13,4,13,7,10,9,7,7}}));
    private final String kind;
    private final Color color;
    UtilityIcon(String kind) { this(kind, LauncherWindow.GOLD); }
    UtilityIcon(String kind, Color color) {
        this.kind = kind;
        this.color = color;
    }
    public int getIconWidth() { return 20; }
    public int getIconHeight() { return 20; }
    public void paintIcon(Component owner, Graphics graphics, int x, int y) {
        var g = (Graphics2D) graphics.create();
        g.translate(x, y);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(owner.isEnabled() ? color : LauncherWindow.MUTED);
        g.setStroke(new BasicStroke(1.55f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        if (kind.equals("globe")) {
            g.drawOval(2, 2, 16, 16);
            g.drawOval(6, 2, 8, 16);
            g.drawLine(2, 10, 18, 10);
        }
        for (int[] line : LINES.getOrDefault(kind, new int[0][])) {
            var path = new java.awt.geom.Path2D.Double();
            path.moveTo(line[0], line[1]);
            for (int i = 2; i < line.length; i += 2) path.lineTo(line[i], line[i + 1]);
            g.draw(path);
        }
        g.dispose();
    }
}
