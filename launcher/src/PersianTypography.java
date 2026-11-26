import java.awt.Font;
import java.awt.FontFormatException;
import java.io.IOException;

final class PersianTypography {
    private static final Font REGULAR = load("Regular");
    private static final Font MEDIUM = load("Medium");
    private static final Font SEMIBOLD = load("SemiBold");

    private PersianTypography() {}

    static Font body(float size) { return REGULAR.deriveFont(size); }
    static Font control(float size) { return MEDIUM.deriveFont(size); }
    static Font heading(float size) { return SEMIBOLD.deriveFont(size); }

    private static Font load(String weight) {
        String resource = "/dastkhat/Dastkhat-" + weight + ".ttf";
        try (var stream = PersianTypography.class.getResourceAsStream(resource)) {
            if (stream == null) throw new IOException("Missing " + resource);
            return Font.createFont(Font.TRUETYPE_FONT, stream);
        } catch (IOException | FontFormatException failure) {
            throw new IllegalStateException("Could not load bundled Dastkhat " + weight, failure);
        }
    }
}
