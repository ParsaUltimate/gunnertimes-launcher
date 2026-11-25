import java.awt.*;
import java.util.Locale;
import java.util.ResourceBundle;
import javax.swing.*;

final class LauncherText {
    final boolean rtl;
    final Locale locale;
    final ComponentOrientation direction;
    private final ResourceBundle messages;

    LauncherText(String language) {
        rtl = language.equals("fa");
        locale = rtl ? Locale.forLanguageTag("fa-IR") : Locale.ENGLISH;
        direction = rtl ? ComponentOrientation.RIGHT_TO_LEFT : ComponentOrientation.LEFT_TO_RIGHT;
        messages = ResourceBundle.getBundle("LauncherMessages", locale,
            ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));
    }

    String get(String key) { return messages.getString(key); }

    void orient(Component component) {
        component.applyComponentOrientation(direction);
        technicalFields(component);
    }

    private void technicalFields(Component component) {
        if (component instanceof JComponent swing && Boolean.TRUE.equals(swing.getClientProperty("technical"))) {
            component.applyComponentOrientation(ComponentOrientation.LEFT_TO_RIGHT);
            return;
        }
        if (component instanceof Container children)
            for (Component child : children.getComponents()) technicalFields(child);
    }
}
