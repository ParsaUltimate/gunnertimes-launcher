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
                app=new LauncherWindow(GunnertimesLauncher.defaults("en"));
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
                field("ram").setText("5");
                click("Save settings");
                if(GunnertimesLauncher.preferences().ramGiB()!=5)throw new AssertionError("save failed");
                click("Java and memory");
                field("ram").setText("8");
                click("Cancel");
                click("Java and memory");
                if(!field("ram").getText().equals("5"))throw new AssertionError("cancel failed");
                click("فارسی");
                navigation("خانه","پک");
                if(!GunnertimesLauncher.preferences().language().equals("fa") ||
                    app.frame().getContentPane().getComponentOrientation().isLeftToRight() ||
                    !field("ram").getComponentOrientation().isLeftToRight())
                    throw new AssertionError("Persian direction or preference");
                System.out.println("PASS: native focus traversal in both languages, accessible navigation, draft retention, invalid RAM, Save, Cancel, and RTL technical fields.");
            } catch (Exception error) {
                throw new RuntimeException(error);
            } finally {
                if (app != null) app.frame().dispose();
            }
        });
    }
}
