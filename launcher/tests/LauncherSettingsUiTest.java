import java.awt.*;
import javax.swing.*;
class LauncherSettingsUiTest {
    static LauncherWindow app;
    static JButton find(Container root,String name) {
        for(var c:root.getComponents()) {
            if (!c.isShowing()) continue;
            if(c instanceof JButton b && b.getText().equals(name)) return b;
            if(c instanceof Container p){var b=find(p,name);if(b!=null)return b;}
        }
        return null;
    }
    static JButton button(String name) {
        var b=find(app.frame(),name);
        if(b==null||!b.isEnabled())throw new AssertionError(name);
        return b;
    }
    static void click(String name){button(name).doClick(0);}
    static void language(String locale) {
        String title = new LauncherText(app.isPersian() ? "fa" : "en").get("language");
        var matches = all(app.frame(), JButton.class).stream()
            .filter(b -> b.getText().isEmpty() && title.equals(b.getAccessibleContext().getAccessibleName())).toList();
        if (matches.size() != 1 || matches.getFirst().getIcon() == null) throw new AssertionError("Accessible globe");
        matches.getFirst().doClick(0);
        var path = MenuSelectionManager.defaultManager().getSelectedPath();
        if (path.length < 1 || !(path[0] instanceof JPopupMenu popup)) throw new AssertionError("Language menu");
        if (popup.getComponentCount() != 2) throw new AssertionError("Language menu count");
        var current = (JRadioButtonMenuItem) popup.getComponent(app.isPersian() ? 0 : 1);
        if (!current.isSelected()) throw new AssertionError("Current language is not marked");
        var next = (JRadioButtonMenuItem) popup.getComponent(locale.equals("fa") ? 0 : 1);
        MenuSelectionManager.defaultManager().clearSelectedPath();
        next.doClick(0);
    }
    static <T extends Component> java.util.List<T> all(Container root, Class<T> type) {
        var result = new java.util.ArrayList<T>();
        for (var c : root.getComponents()) {
            if (!c.isShowing()) continue;
            if (type.isInstance(c)) result.add(type.cast(c));
            if (c instanceof Container child) result.addAll(all(child, type));
        }
        return result;
    }
    static int helpRailHeight(String locale) {
        var text = new LauncherText(locale);
        return all(app.frame(), JScrollPane.class).stream()
            .filter(s -> text.get("helpTopics").equals(s.getAccessibleContext().getAccessibleName()))
            .findFirst().orElseThrow().getHeight();
    }
    static JTextField field(String name)throws Exception {
        var ref=LauncherWindow.class.getDeclaredField("settingsPanel");ref.setAccessible(true);
        var field=LauncherSettings.class.getDeclaredField(name);field.setAccessible(true);
        return (JTextField)field.get(ref.get(app));
    }
    static void navigation(String start,String expected) {
        JButton from=button(start);
        Component next=app.frame().getFocusTraversalPolicy().getComponentAfter(app.frame(), from);
        if(!(next instanceof JButton target)||!target.getText().equals(expected))
            throw new AssertionError("Native focus order after "+start+": "+next);
        if(!from.getAccessibleContext().getAccessibleName().equals(start))
            throw new AssertionError("Accessible navigation label");
    }
    public static void main(String[] args)throws Exception {
        SwingUtilities.invokeAndWait(()->{
            try {
                var copiedPath = new java.util.concurrent.atomic.AtomicReference<String>();
                app=new LauncherWindow(GunnertimesLauncher.defaults("en"), copiedPath::set);
                app.show();
                navigation("Home","Pack");
                click("Settings");
                click("Java and memory");
                field("ram").setText("999");
                click("General");
                click("Java and memory");
                if(!field("ram").getText().equals("999")) throw new AssertionError("lost draft");
                click("Save settings");
                if(GunnertimesLauncher.preferences().ramGiB()!=4)throw new AssertionError("invalid memory persisted");
                if (!new LauncherText("en").get("invalidRam").equals(
                        field("ram").getAccessibleContext().getAccessibleDescription()))
                    throw new AssertionError("RAM has no inline validation description");
                field("ram").setText("5");
                click("Save settings");
                if(GunnertimesLauncher.preferences().ramGiB()!=5)throw new AssertionError("save failed");
                click("Java and memory");
                field("ram").setText("8");
                click("Cancel");
                click("Java and memory");
                if(!field("ram").getText().equals("5"))throw new AssertionError("cancel failed");
                language("fa");
                navigation("خانه","پک");
                if(!GunnertimesLauncher.preferences().language().equals("fa") ||
                    app.frame().getContentPane().getComponentOrientation().isLeftToRight() ||
                    !field("ram").getComponentOrientation().isLeftToRight())
                    throw new AssertionError("Persian direction or preference");
                var font = app.font(16, false);
                String mixed = "به‌روزرسانی Gunnertimes 26.3";
                if (!font.getFamily().contains("Dastkhat") || font.canDisplayUpTo(mixed) != -1)
                    throw new AssertionError("Bundled Persian font or mixed-script coverage");
                if (font.getFontName().equals(app.controlFont(16).getFontName()) ||
                        app.controlFont(16).getFontName().equals(app.font(16, true).getFontName()))
                    throw new AssertionError("Static typography weights unavailable");
                var context = new java.awt.font.FontRenderContext(null, true, true);
                if (new java.awt.font.TextLayout("بب", font, context).getAdvance() ==
                        new java.awt.font.TextLayout("ب‌ب", font, context).getAdvance())
                    throw new AssertionError("Half-space did not affect Persian shaping");

                click("راهنما");
                app.frame().setSize(690, 550);
                app.frame().validate();
                int rail = helpRailHeight("fa");
                if (rail <= 0) throw new AssertionError("Missing Help sidebar");
                for (String key : new String[]{"guideStart", "guidePlayNav", "guideFix", "guideLogs", "guideShared", "guideFolder"}) {
                    click(new LauncherText("fa").get(key));
                    app.frame().validate();
                    if (rail != helpRailHeight("fa")) throw new AssertionError("Help sidebar changed height: " + key);
                }
                click(new LauncherText("fa").get("guideLogs"));
                language("en");
                app.frame().validate();
                if (!Boolean.TRUE.equals(button(new LauncherText("en").get("guideLogs")).getClientProperty("selected")))
                    throw new AssertionError("Language change lost the Help topic");
                for (String key : new String[]{"guideStart", "guidePlayNav", "guideFix", "guideLogs", "guideShared", "guideFolder"})
                    click(new LauncherText("en").get(key));
                language("fa");
                click("پوشهٔ پروفایل چیست؟");
                click("کپی مسیر");
                if (!"%LOCALAPPDATA%\\Gunnertimes\\profiles\\Gunnertimes".equals(copiedPath.get()))
                    throw new AssertionError("Copied profile path is incorrect");
                click(new LauncherText("fa").get("guideShared"));
                click("کپی مسیر");
                if (!"%APPDATA%\\.minecraft".equals(copiedPath.get()))
                    throw new AssertionError("Copied shared Minecraft path is incorrect");
                System.out.println("PASS: Dastkhat static weights, mixed text, ZWNJ shaping, Help navigation and path copy.");
                System.out.println("PASS: native focus traversal in both languages, accessible navigation, draft retention, invalid RAM, Save, Cancel, and RTL technical fields.");
            } catch (Exception error) {
                throw new RuntimeException(error);
            } finally {
                if (app != null) app.frame().dispose();
            }
        });
    }
}
