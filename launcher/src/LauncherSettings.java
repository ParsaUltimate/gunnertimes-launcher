import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.ExecutionException;
import javax.swing.*;
import javax.swing.border.EmptyBorder;

final class LauncherSettings {
    private static final String[] SECTIONS = {"general", "profileSection", "displaySection", "javaSection",
        "downloadSection", "storageSection", "diagnosticSection", "aboutSection"};
    private static final String[] MOTION = {"system", "reduce", "full"};
    private static final String[] BEHAVIOR = {"visible", "minimize"};
    private static final String[] DISPLAY = {"saved", "fullscreen", "windowed"};
    private static final String[] JAVA = {"auto", "bundled", "custom"};

    record Draft(String name, String profile, String ram, String motion, String behavior, String display,
                 boolean resolution, String width, String height, String java, String javaPath, int section) {
        static Draft from(GunnertimesLauncher.Preferences settings) {
            var opts = settings.options();
            return new Draft(settings.username(), settings.profile(), Integer.toString(settings.ramGiB()),
                opts.motion(), opts.launchWindow(), opts.display(), opts.width() > 0,
                Integer.toString(opts.width() == 0 ? 1280 : opts.width()),
                Integer.toString(opts.height() == 0 ? 720 : opts.height()), opts.javaMode(), opts.javaPath(), 0);
        }
    }

    private final LauncherWindow ui;
    private final JPanel body;
    private final JPanel pages;
    private final JTextField name, profile, ram, width, height, javaPath;
    private final JComboBox<String> motion, behavior, display, javaChoice;
    private final JCheckBox customResolution;
    private final JLabel feedback;
    private final JButton save;
    private JButton aboutCheck, downloadsLauncherCheck;
    private JLabel launcherStatus;
    private final java.util.Map<JComponent, JLabel> fieldErrors = new java.util.IdentityHashMap<>();
    private final JButton[] tabs = new JButton[SECTIONS.length];
    private final java.util.ArrayList<JTextField> javaReadouts = new java.util.ArrayList<>();
    private LaunchOptions.JavaInfo resolvedJava;
    private int section;

    LauncherSettings(LauncherWindow ui, Draft initial) {
        this.ui = ui;
        section = initial.section();
        name = ui.field(initial.name(), 22);
        profile = ui.field(initial.profile(), 38);
        ram = ui.field(initial.ram(), 6);
        width = ui.field(initial.width(), 7);
        height = ui.field(initial.height(), 7);
        javaPath = ui.field(initial.javaPath(), 32);
        motion = options(MOTION, initial.motion());
        behavior = options(BEHAVIOR, initial.behavior());
        display = options(DISPLAY, initial.display());
        javaChoice = options(JAVA, initial.java());
        customResolution = new JCheckBox(ui.t("customResolution"), initial.resolution());
        customResolution.setOpaque(false);
        customResolution.setForeground(LauncherWindow.TEXT);
        customResolution.setFont(ui.controlFont(14));

        body = LauncherWindow.stack(LauncherWindow.BG, 14);
        body.add(ui.utilityTitle("navSettings", ui.frame().getWidth() < 1020 ? 33 : 40));
        var layout = LauncherWindow.panel(new BorderLayout(21, 0), LauncherWindow.BG);
        var menu = LauncherWindow.outline(LauncherWindow.stack(LauncherWindow.RAIL, 5), 10);
        menu.setPreferredSize(new Dimension(ui.frame().getWidth() < 1020 ? 196 : 287, 460));
        for (int index = 0; index < SECTIONS.length; index++) {
            int target = index;
            var action = ui.button(ui.t(SECTIONS[index]), false);
            action.setHorizontalAlignment(SwingConstants.LEADING);
            action.setToolTipText(ui.t(SECTIONS[index]));
            action.addActionListener(event -> switchSection(target));
            tabs[index] = action;
            String[] icons = {"check", "person", "game", "document", "download", "server", "repair", "document"};
            action.setIcon(new UtilityIcon(icons[index]));
            action.setIconTextGap(9);
            action.setFont(ui.controlFont(ui.frame().getWidth() < 1020 ? 13 : 16));
            menu.add(action);
            if (index < SECTIONS.length - 1) menu.add(new JSeparator());
        }
        layout.add(menu, BorderLayout.LINE_START);
        pages = LauncherWindow.panel(new CardLayout(), LauncherWindow.BG);
        var renderers = List.of(general(), profile(), display(), java(), downloads(), storage(), diagnostics(), about());
        launcherCheckChanged();
        for (int index = 0; index < SECTIONS.length; index++) {
            var wrap = LauncherWindow.panel(new BorderLayout(), LauncherWindow.BG);
            wrap.add(renderers.get(index), BorderLayout.PAGE_START);
            pages.add(wrap, SECTIONS[index]);
        }
        layout.add(pages, BorderLayout.CENTER);
        body.add(layout);

        feedback = ui.label("", 14, true, LauncherWindow.MUTED);
        body.add(feedback);
        if (!ui.messageKey().isEmpty()) message(ui.messageKey(), ui.noticeDetails(), ui.noticeError());
        var footer = LauncherWindow.panel(new FlowLayout(FlowLayout.TRAILING, 8, 0), LauncherWindow.BG);
        footer.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, LauncherWindow.EDGE));
        save = ui.button(ui.t("save"), true);
        save.setEnabled(!ui.working());
        save.addActionListener(event -> save());
        footer.add(save);
        var cancel = ui.button(ui.t("cancel"), false);
        cancel.addActionListener(event -> ui.cancelSettings());
        footer.add(cancel);
        pages.setBorder(new EmptyBorder(0, 0, 0, 0));
        layout.add(footer, BorderLayout.PAGE_END);
        switchSection(section);
        resolveJava();
    }

    JPanel component() { return body; }

    Draft draft() {
        return new Draft(name.getText(), profile.getText(), ram.getText(), value(motion, MOTION),
            value(behavior, BEHAVIOR), value(display, DISPLAY), customResolution.isSelected(),
            width.getText(), height.getText(), value(javaChoice, JAVA), javaPath.getText(), section);
    }

    private void switchSection(int chosen) {
        section = chosen;
        ((CardLayout) pages.getLayout()).show(pages, SECTIONS[chosen]);
        for (int index = 0; index < tabs.length; index++) {
            tabs[index].setBackground(index == chosen ? new Color(0x47311C) : LauncherWindow.RAIL);
            tabs[index].putClientProperty("outlined", index == chosen);
            tabs[index].putClientProperty("selected", index == chosen);
            tabs[index].getAccessibleContext().setAccessibleDescription(index == chosen ? ui.t("selectedPage") : "");
            tabs[index].setForeground(index == chosen ? LauncherWindow.GOLD : LauncherWindow.TEXT);
        }
    }

    private JPanel section(String key) {
        var area = LauncherWindow.outline(LauncherWindow.stack(LauncherWindow.PANEL, 10), 18);
        area.add(ui.utilityTitle(key, 22));
        var line = new JSeparator();
        line.setForeground(LauncherWindow.EDGE);
        area.add(line);
        return area;
    }

    private void add(JPanel area, String key, JComponent input) {
        area.add(formRow(key, input, input));
    }

    private JPanel formRow(String key, JComponent input, JComponent focus) {
        var row = LauncherWindow.panel(new BorderLayout(12, 4), LauncherWindow.PANEL);
        var label = ui.label(ui.t(key), 14, true, LauncherWindow.TEXT);
        label.setLabelFor(focus);
        label.setPreferredSize(new Dimension(ui.frame().getWidth() < 1020 ? 180 : 206, 28));
        label.setToolTipText(ui.t(key));
        row.add(label, ui.frame().getWidth() < 780 ? BorderLayout.PAGE_START : BorderLayout.LINE_START);
        row.add(input, BorderLayout.CENTER);
        if (focus instanceof JTextField field && field.isEditable()) {
            var error = ui.label("", 13, false, LauncherWindow.RED);
            error.setVisible(false);
            row.add(error, BorderLayout.PAGE_END);
            fieldErrors.put(focus, error);
            watch(field, () -> fieldError(focus, null, ""));
        }
        return row;
    }

    private void fieldError(JComponent field, String key, String detail) {
        var error = fieldErrors.get(field);
        if (error == null) return;
        error.setText(key == null ? "" : ui.t(key));
        error.setToolTipText(detail.isEmpty() ? null : detail);
        error.setVisible(key != null);
        field.getAccessibleContext().setAccessibleDescription(key == null ? null : ui.t(key));
        body.revalidate();
        body.repaint();
    }

    private JPanel general() {
        var area = section("general");
        var language = options(new String[]{"english", "persian"}, ui.isPersian() ? "persian" : "english");
        language.addActionListener(event -> ui.language(language.getSelectedIndex() == 0 ? "en" : "fa"));
        add(area, "language", language);
        add(area, "motion", motion);
        add(area, "gameStartBehavior", behavior);
        return area;
    }

    private JPanel profile() {
        var area = section("profileSection");
        add(area, "player", name);
        area.add(formRow("profile", pathPicker(profile, false), profile));
        var resolved = readOnly("");
        Runnable resolve = () -> {
            try { resolved.setText(GunnertimesLauncher.profilePath(profile.getText()).toString()); }
            catch (GunnertimesLauncher.InputError ignored) { resolved.setText(""); }
        };
        watch(profile, resolve);
        resolve.run();
        add(area, "resolvedFolder", resolved);
        area.add(ui.wrapped(ui.t("profileHint")));
        area.add(actions(action("openProfile", () -> withProfile(ui::open)),
            action("defaultFolder", () -> profile.setText(GunnertimesLauncher.DEFAULT_PROFILE.toString()))));
        return area;
    }

    private JPanel display() {
        var area = section("displaySection");
        add(area, "startupDisplay", display);
        area.add(customResolution);
        area.add(formRow("width", width, width));
        area.add(formRow("height", height, height));
        Runnable enable = () -> {
            boolean windowed = display.getSelectedIndex() == 2;
            customResolution.setEnabled(windowed);
            width.setEnabled(windowed && customResolution.isSelected());
            height.setEnabled(windowed && customResolution.isSelected());
        };
        display.addActionListener(event -> enable.run());
        customResolution.addActionListener(event -> enable.run());
        enable.run();
        add(area, "gameVersion", readOnly("Minecraft " + MinecraftFiles.VERSION + "   /   Fabric " + MinecraftFiles.LOADER));
        area.add(ui.wrapped(ui.t("nextLaunch")));
        return area;
    }

    private JPanel java() {
        var area = section("javaRuntimeHeading");
        var group = new ButtonGroup();
        for (int i = 0; i < JAVA.length; i++) {
            int mode = i;
            var option = new JRadioButton(ui.t("option." + JAVA[i]), javaChoice.getSelectedIndex() == i);
            option.setOpaque(false);
            option.setForeground(LauncherWindow.TEXT);
            option.setFont(ui.controlFont(15));
            option.setIcon(new Icon() {
                public int getIconWidth() { return 20; }
                public int getIconHeight() { return 20; }
                public void paintIcon(Component component, Graphics graphics, int x, int y) {
                    var g = (Graphics2D) graphics.create();
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setStroke(new BasicStroke(1.7f));
                    g.setColor(option.isSelected() || option.hasFocus() ? LauncherWindow.GOLD : LauncherWindow.MUTED);
                    g.drawOval(x + 2, y + 2, 15, 15);
                    if (option.isSelected()) g.fillOval(x + 6, y + 6, 8, 8);
                    g.dispose();
                }
            });
            option.setIconTextGap(8);
            option.addActionListener(event -> javaChoice.setSelectedIndex(mode));
            group.add(option);
            area.add(option);
        }
        var picker = pathPicker(javaPath, true);
        area.add(formRow("javaExecutable", picker, javaPath));
        Runnable enable = () -> {
            boolean custom = javaChoice.getSelectedIndex() == 2;
            for (Component child : picker.getComponents()) child.setEnabled(custom);
            if (!custom) fieldError(javaPath, null, "");
        };
        javaChoice.addActionListener(event -> enable.run());
        enable.run();
        var resolved = readOnly(ui.t("unknown"));
        add(area, "resolvedJava", resolved);
        javaReadouts.add(resolved);

        var divider = new JSeparator();
        divider.setForeground(LauncherWindow.EDGE);
        area.add(divider);
        area.add(ui.utilityTitle("memoryHeading", 20));
        add(area, "ram", ram);
        var slider = new JSlider(2, 32, Math.clamp(ui.settings().ramGiB(), 2, 32));
        slider.setOpaque(false);
        slider.setForeground(LauncherWindow.GOLD);
        slider.setUI(new javax.swing.plaf.basic.BasicSliderUI(slider) {
            @Override public void paintTrack(Graphics graphics) {
                int center = trackRect.y + trackRect.height / 2 - 3;
                int thumb = thumbRect.x + thumbRect.width / 2;
                int origin = drawInverted() ? trackRect.x + trackRect.width : trackRect.x;
                var g = (Graphics2D) graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(LauncherWindow.EDGE);
                g.fillRoundRect(trackRect.x, center, trackRect.width, 6, 6, 6);
                g.setColor(LauncherWindow.GOLD);
                g.fillRoundRect(Math.min(origin, thumb), center, Math.abs(origin - thumb), 6, 6, 6);
                g.dispose();
            }
            @Override public void paintThumb(Graphics graphics) {
                var g = (Graphics2D) graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int x = thumbRect.x + (thumbRect.width - 14) / 2;
                int y = thumbRect.y + (thumbRect.height - 14) / 2;
                g.setColor(LauncherWindow.GOLD);
                g.fillOval(x, y, 14, 14);
                g.setColor(LauncherWindow.TEXT);
                g.drawOval(x, y, 14, 14);
                g.dispose();
            }
        });
        slider.getAccessibleContext().setAccessibleName(ui.t("ram"));
        var adjusting = new boolean[1];
        Runnable reflectRam = () -> {
            try {
                int value = Integer.parseInt(ram.getText().strip());
                if (value >= 2 && value <= 32) {
                    adjusting[0] = true;
                    slider.setValue(value);
                }
            } catch (NumberFormatException ignored) {
                // Leave invalid input visible so the user can correct it.
            } finally { adjusting[0] = false; }
        };
        slider.addChangeListener(event -> {
            if (!adjusting[0] && !Integer.toString(slider.getValue()).equals(ram.getText()))
                ram.setText(Integer.toString(slider.getValue()));
        });
        watch(ram, reflectRam);
        reflectRam.run();
        var scale = LauncherWindow.panel(new BorderLayout(8, 0), LauncherWindow.PANEL);
        scale.add(ui.label("2 GiB", 12, false, LauncherWindow.MUTED), BorderLayout.LINE_START);
        scale.add(slider, BorderLayout.CENTER);
        scale.add(ui.label("32 GiB", 12, false, LauncherWindow.MUTED), BorderLayout.LINE_END);
        area.add(scale);
        long physical = LaunchOptions.physicalMemory();
        if (physical > 0) {
            long gib = physical / (1024L * 1024 * 1024);
            area.add(ui.wrapped(ui.t("systemMemory") + " " + ui.bytes(physical) + "   ·   " +
                ui.t("suggestedMaximum") + " " + Math.max(0, Math.min(32, gib - 3)) + " GiB"));
        }
        area.add(ui.wrapped(ui.t("ramHint") + " " + ui.t("nextLaunch")));
        return area;
    }

    private JPanel downloads() {
        var area = section("downloadSection");
        add(area, "installedVersion", readOnly(ui.installedVersion()));
        add(area, "availableVersion", readOnly(ui.availableVersion()));
        add(area, "lastCheck", readOnly(ui.lastCheck()));
        add(area, "cachePath", readOnly(MinecraftFiles.sharedDirectory().toString()));
        var check = action(ui.checking() ? "checkingRelease" : "checkNow", ui::checkNow);
        ui.downloadStyle(check);
        check.setEnabled(!ui.checking() && !ui.working());
        area.add(actions(check));
        if (!ui.checkError().isEmpty())
            area.add(actions(action("details", () -> ui.detailDialog(ui.t("releaseUnavailable"), ui.checkError()))));
        area.add(new JSeparator());
        add(area, "launcherBuild", readOnly(LauncherUpdates.VERSION));
        downloadsLauncherCheck = action("checkLauncherUpdate", () -> ui.checkLauncher(true));
        ui.downloadStyle(downloadsLauncherCheck);
        area.add(actions(downloadsLauncherCheck));
        return area;
    }

    private JPanel about() {
        var area = section("aboutSection");
        add(area, "launcherBuild", readOnly(LauncherUpdates.VERSION));
        aboutCheck = action("checkLauncherUpdate", () -> ui.checkLauncher(true));
        ui.downloadStyle(aboutCheck);
        area.add(actions(aboutCheck, action("officialLauncherReleases", ui::openLauncherReleases)));
        launcherStatus = ui.label("", 14, false, LauncherWindow.TEXT);
        area.add(launcherStatus);
        return area;
    }

    void launcherCheckChanged() {
        boolean available = !ui.launcherCheckBusy();
        aboutCheck.setEnabled(available);
        downloadsLauncherCheck.setEnabled(available);
        launcherStatus.setText(ui.launcherCheckMessage());
    }

    private JPanel storage() {
        var area = section("storageSection");
        area.add(actions(action("openProfile", () -> withProfile(ui::open)),
            action("openCache", () -> ui.open(MinecraftFiles.sharedDirectory())),
            action("openLogs", () -> withProfile(path -> ui.open(path.resolve("logs"))))));
        var summary = ui.wrapped(ui.t("storageEmpty"));
        area.add(summary);
        var measure = action("measureStorage", () -> withProfile(path -> measure(summary, path)));
        area.add(actions(measure));
        return area;
    }

    private JPanel diagnostics() {
        var area = section("diagnosticSection");
        add(area, "launcherBuild", readOnly(MinecraftFiles.LAUNCHER_VERSION));
        var resolved = readOnly(ui.t("unknown"));
        add(area, "resolvedJava", resolved);
        javaReadouts.add(resolved);
        var folders = actions(action("openLauncherLog", () -> withProfile(path -> lastLog(path, true))),
            action("openLog", () -> withProfile(path -> lastLog(path, false))),
            action("openCrashes", () -> withProfile(path -> ui.open(path.resolve("crash-reports")))));
        area.add(folders);
        var reset = action("resetPreferences", () -> {
                if (!ui.working() && ui.confirm(ui.t("resetPreferences"), ui.t("resetConfirm"))) {
                    try { ui.acceptSettings(GunnertimesLauncher.defaults(ui.settings().language())); }
                    catch (IOException failure) { message("settingsFailure", failure.toString(), true); }
                }
            });
        ui.removalStyle(reset);
        area.add(actions(action("copyDiagnostics", this::copyDiagnostics), reset));
        return area;
    }

    private JComboBox<String> options(String[] choices, String selected) {
        String[] labels = java.util.Arrays.stream(choices).map(choice -> ui.t("option." + choice)).toArray(String[]::new);
        var box = new JComboBox<>(labels);
        box.setSelectedIndex(Math.max(0, List.of(choices).indexOf(selected)));
        box.setBackground(LauncherWindow.BG);
        box.setForeground(LauncherWindow.TEXT);
        box.setFont(ui.controlFont(14));
        return box;
    }

    private static String value(JComboBox<String> box, String[] values) {
        return values[box.getSelectedIndex()];
    }

    private JTextField readOnly(String value) {
        var field = ui.field(value, 35);
        field.setEditable(false);
        field.setToolTipText(value);
        return field;
    }

    private JPanel pathPicker(JTextField input, boolean executable) {
        var box = LauncherWindow.panel(new BorderLayout(8, 0), LauncherWindow.PANEL);
        box.add(input, BorderLayout.CENTER);
        box.add(action(executable ? "chooseExecutable" : "browse", () -> {
            var picker = new JFileChooser();
            picker.setLocale(ui.isPersian() ? java.util.Locale.forLanguageTag("fa-IR") : java.util.Locale.ENGLISH);
            picker.updateUI();
            new LauncherText(ui.isPersian() ? "fa" : "en").orient(picker);
            picker.setApproveButtonText(ui.t(executable ? "chooseExecutable" : "chooseFolder"));
            picker.setDialogTitle(ui.t(executable ? "javaExecutable" : "chooseFolder"));
            picker.setFileSelectionMode(executable ? JFileChooser.FILES_ONLY : JFileChooser.DIRECTORIES_ONLY);
            try {
                Path current = Path.of(input.getText());
                if (Files.isDirectory(current)) picker.setCurrentDirectory(current.toFile());
                else if (current.getParent() != null && Files.isDirectory(current.getParent()))
                    picker.setCurrentDirectory(current.getParent().toFile());
            } catch (InvalidPathException ignored) {}
            if (picker.showOpenDialog(ui.frame()) == JFileChooser.APPROVE_OPTION)
                input.setText(picker.getSelectedFile().getAbsolutePath());
        }), BorderLayout.LINE_END);
        return box;
    }

    private JButton action(String key, Runnable callback) {
        var action = ui.button(ui.t(key), false);
        action.addActionListener(event -> callback.run());
        return action;
    }

    private JPanel actions(JButton... buttons) {
        var row = LauncherWindow.panel(new GridLayout(1, buttons.length, 7, 0), LauncherWindow.PANEL);
        for (var button : buttons) row.add(button);
        return row;
    }

    private void save() {
        Draft draft = draft();
        int desiredWidth = 0, desiredHeight = 0;
        if (draft.display().equals("windowed") && draft.resolution()) {
            try {
                desiredWidth = Integer.parseInt(draft.width().strip());
                desiredHeight = Integer.parseInt(draft.height().strip());
                if (desiredWidth < 640 || desiredWidth > 7680 || desiredHeight < 360 || desiredHeight > 4320)
                    throw new NumberFormatException();
            } catch (NumberFormatException error) {
                switchSection(2);
                JTextField invalid = isDimension(draft.width(), 640, 7680) ? height : width;
                fieldError(invalid, "invalidResolution", "");
                invalid.requestFocusInWindow();
                return;
            }
        }
        if (draft.java().equals("custom") && draft.javaPath().isBlank()) {
            switchSection(3);
            fieldError(javaPath, "invalidJava", "");
            javaPath.requestFocusInWindow();
            return;
        }
        var opts = new LaunchOptions.Settings(draft.motion(), draft.behavior(), draft.display(), desiredWidth,
            desiredHeight, draft.java(), draft.javaPath().strip());
        GunnertimesLauncher.Preferences candidate;
        try {
            candidate = GunnertimesLauncher.validated(draft.name(), draft.profile(), ui.settings().language(),
                draft.ram(), opts, false);
        } catch (GunnertimesLauncher.InputError failure) {
            focusInvalidInput(failure.key);
            return;
        }
        if (!opts.javaMode().equals("custom")) {
            persist(candidate);
            return;
        }
        save.setEnabled(false);
        message("validatingJava", "", false);
        new SwingWorker<LaunchOptions.JavaInfo, Void>() {
            @Override protected LaunchOptions.JavaInfo doInBackground() throws Exception {
                return LaunchOptions.validateJava(opts.javaPath());
            }
            @Override protected void done() {
                if (!body.isShowing()) return;
                save.setEnabled(!ui.working());
                try {
                    get();
                    var localized = new GunnertimesLauncher.Preferences(candidate.username(), candidate.profile(),
                        ui.settings().language(), candidate.ramGiB(), candidate.options());
                    persist(localized);
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException failure) {
                    switchSection(3);
                    feedback.setText("");
                    fieldError(javaPath, "invalidJava", failure.getCause().toString());
                    javaPath.requestFocusInWindow();
                }
            }
        }.execute();
    }

    void focusInvalidInput(String key) {
        switchSection(key.equals("invalidRam") ? 3 : 1);
        JTextField target = switch (key) {
            case "invalidRam" -> ram;
            case "invalidName" -> name;
            default -> profile;
        };
        fieldError(target, key, "");
        target.requestFocusInWindow();
    }

    private static boolean isDimension(String text, int min, int max) {
        try {
            int value = Integer.parseInt(text.strip());
            return value >= min && value <= max;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private void persist(GunnertimesLauncher.Preferences value) {
        try { ui.acceptSettings(value); }
        catch (IOException failure) { message("settingsFailure", failure.toString(), true); }
    }

    private void message(String key, String detail, boolean error) {
        feedback.setText(ui.t(key));
        feedback.setForeground(error ? LauncherWindow.RED : LauncherWindow.GREEN);
        feedback.setToolTipText(detail.isEmpty() ? null : detail);
    }

    private void withProfile(java.util.function.Consumer<Path> use) {
        try { use.accept(GunnertimesLauncher.profilePath(profile.getText())); }
        catch (GunnertimesLauncher.InputError failure) { message(failure.key, "", true); }
    }

    private void resolveJava() {
        var opts = ui.settings().options();
        new SwingWorker<LaunchOptions.JavaInfo, Void>() {
            @Override protected LaunchOptions.JavaInfo doInBackground() throws Exception { return LaunchOptions.resolve(opts); }
            @Override protected void done() {
                try {
                    var info = get();
                    resolvedJava = info;
                    String resolved = info.version() + "  ·  " + info.architecture() + "  ·  " + info.executable();
                    for (JTextField field : javaReadouts) {
                        field.setText(resolved);
                        field.setToolTipText(resolved);
                        field.setCaretPosition(0);
                    }
                } catch (Exception failure) {
                    for (JTextField field : javaReadouts) field.setToolTipText(failure.toString());
                }
            }
        }.execute();
    }

    private void measure(JTextArea summary, Path profileDirectory) {
        summary.setText(ui.t("storageMeasuring"));
        new SwingWorker<String, Void>() {
            @Override protected String doInBackground() {
                StringBuilder result = new StringBuilder();
                for (String category : List.of("mods", "saves", "config", "logs")) {
                    result.append(ui.t("storage." + category)).append("  ")
                        .append(measureText(profileDirectory.resolve(category))).append('\n');
                }
                result.append(ui.t("modCache")).append("  ").append(measureText(profileDirectory.resolve(".gunnertimes/cache")))
                    .append('\n').append(ui.t("gameCache")).append("  ").append(measureText(MinecraftFiles.sharedDirectory()));
                return result.toString();
            }
            @Override protected void done() {
                try {
                    summary.setText(get());
                    pages.revalidate();
                    body.revalidate();
                } catch (Exception failure) {
                    summary.setText(ui.t("storageUnknown"));
                }
            }
        }.execute();
    }

    private String measureText(Path path) {
        if (!Files.exists(path)) return ui.bytes(0);
        if (Files.isSymbolicLink(path)) return ui.t("unknown");
        try (var tree = Files.find(path, Integer.MAX_VALUE, (file, attrs) -> attrs.isRegularFile())) {
            long bytes = 0;
            var files = tree.iterator();
            while (files.hasNext()) bytes += Files.size(files.next());
            return ui.bytes(bytes);
        } catch (IOException | java.io.UncheckedIOException failure) {
            return ui.t("unknown");
        }
    }

    private void lastLog(Path profileDirectory, boolean launcher) {
        Path logs = profileDirectory.resolve("logs");
        if (!Files.isDirectory(logs)) {
            message("noLog", "", false);
            return;
        }
        try (var files = Files.list(logs)) {
            var latest = files.filter(Files::isRegularFile).filter(path -> {
                String name = path.getFileName().toString();
                return name.endsWith(".log") && (launcher ? name.startsWith("gunnertimes-launcher-")
                    : name.startsWith("gunnertimes-") && !name.startsWith("gunnertimes-launcher-"));
            }).max(java.util.Comparator.comparingLong(path -> path.toFile().lastModified()));
            if (latest.isPresent()) ui.open(latest.get());
            else message("noLog", "", false);
        } catch (IOException failure) {
            message("openFailure", failure.toString(), true);
        }
    }

    private void copyDiagnostics() {
        // This summary has no username, profile path, raw log, environment variables or machine name.
        String sanitized = "Gunnertimes " + MinecraftFiles.LAUNCHER_VERSION + "\nMinecraft " + MinecraftFiles.VERSION
            + " / Fabric " + MinecraftFiles.LOADER + "\nInstalled: " + ui.installedVersion()
            + "\nAvailable: " + ui.availableVersion() + "\nJava mode: " + ui.settings().options().javaMode()
            + "\nJava: " + (resolvedJava == null ? "unknown" : resolvedJava.version() + " / " + resolvedJava.architecture())
            + "\nMemory: " + ui.settings().ramGiB() + " GiB\nOS: " + System.getProperty("os.name")
            + " / " + System.getProperty("os.arch");
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(sanitized), null);
        message("copied", "", false);
    }

    private static void watch(JTextField input, Runnable changed) {
        input.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent event) { changed.run(); }
            public void removeUpdate(javax.swing.event.DocumentEvent event) { changed.run(); }
            public void changedUpdate(javax.swing.event.DocumentEvent event) { changed.run(); }
        });
    }
}
