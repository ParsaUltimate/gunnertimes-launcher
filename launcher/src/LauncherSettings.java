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
        "downloadSection", "storageSection", "diagnosticSection"};
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
    private final JButton[] tabs = new JButton[SECTIONS.length];
    private final java.util.ArrayList<JTextField> javaReadouts = new java.util.ArrayList<>();
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
        customResolution.setFont(ui.font(14, false));

        body = LauncherWindow.stack(LauncherWindow.BG, 14);
        body.add(ui.label(ui.t("navSettings"), 30, true, LauncherWindow.TEXT));
        var layout = LauncherWindow.panel(new BorderLayout(21, 0), LauncherWindow.BG);
        var menu = LauncherWindow.stack(LauncherWindow.BG, 4);
        menu.setPreferredSize(new Dimension(180, 315));
        for (int index = 0; index < SECTIONS.length; index++) {
            int target = index;
            var action = ui.button(ui.t(SECTIONS[index]), false);
            action.setHorizontalAlignment(SwingConstants.LEADING);
            action.addActionListener(event -> switchSection(target));
            tabs[index] = action;
            menu.add(action);
        }
        layout.add(menu, BorderLayout.LINE_START);
        pages = LauncherWindow.panel(new CardLayout(), LauncherWindow.BG);
        var renderers = List.of(general(), profile(), display(), java(), downloads(), storage(), diagnostics());
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
        var footer = LauncherWindow.panel(new FlowLayout(FlowLayout.LEADING, 8, 0), LauncherWindow.BG);
        save = ui.button(ui.t("save"), true);
        save.setEnabled(!ui.working());
        save.addActionListener(event -> save());
        footer.add(save);
        var cancel = ui.button(ui.t("cancel"), false);
        cancel.addActionListener(event -> ui.cancelSettings());
        footer.add(cancel);
        body.add(footer);
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
            tabs[index].setBackground(index == chosen ? new Color(0x47311C) : LauncherWindow.BG);
            tabs[index].setForeground(index == chosen ? LauncherWindow.GOLD : LauncherWindow.TEXT);
        }
    }

    private JPanel section(String key) {
        var area = LauncherWindow.stack(LauncherWindow.PANEL, 12);
        area.setBorder(new EmptyBorder(19, 21, 21, 21));
        area.add(ui.label(ui.t(key), 19, true, LauncherWindow.TEXT));
        var line = new JSeparator();
        line.setForeground(LauncherWindow.EDGE);
        area.add(line);
        return area;
    }

    private void add(JPanel area, String key, JComponent input) {
        area.add(ui.labeled(ui.t(key), input, input));
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
        area.add(ui.labeled(ui.t("profile"), pathPicker(profile, false), profile));
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
        var dimensions = LauncherWindow.panel(new GridLayout(1, 2, 9, 0), LauncherWindow.PANEL);
        dimensions.add(ui.labeled(ui.t("width"), width, width));
        dimensions.add(ui.labeled(ui.t("height"), height, height));
        area.add(dimensions);
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
        var area = section("javaSection");
        add(area, "javaChoice", javaChoice);
        var picker = pathPicker(javaPath, true);
        area.add(ui.labeled(ui.t("javaExecutable"), picker, javaPath));
        Runnable enable = () -> {
            boolean custom = javaChoice.getSelectedIndex() == 2;
            javaPath.setEnabled(custom);
            for (Component child : picker.getComponents()) child.setEnabled(custom);
        };
        javaChoice.addActionListener(event -> enable.run());
        enable.run();
        var resolved = readOnly(ui.t("unknown"));
        add(area, "resolvedJava", resolved);
        javaReadouts.add(resolved);
        add(area, "ram", ram);
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
        add(area, "cachePath", readOnly(GunnertimesLauncher.CACHE.toString()));
        var check = action(ui.checking() ? "checkingRelease" : "checkNow", ui::checkNow);
        check.setEnabled(!ui.checking() && !ui.working());
        area.add(actions(check));
        if (!ui.checkError().isEmpty())
            area.add(actions(action("details", () -> ui.detailDialog(ui.t("releaseUnavailable"), ui.checkError()))));
        return area;
    }

    private JPanel storage() {
        var area = section("storageSection");
        area.add(actions(action("openProfile", () -> withProfile(ui::open)),
            action("openCache", () -> ui.open(GunnertimesLauncher.CACHE)),
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
        area.add(actions(action("copyDiagnostics", () -> copyDiagnostics(resolved.getText())),
            action("resetPreferences", () -> {
                if (!ui.working() && ui.confirm(ui.t("resetPreferences"), ui.t("resetConfirm"))) {
                    try { ui.acceptSettings(GunnertimesLauncher.defaults(ui.settings().language())); }
                    catch (IOException failure) { message("settingsFailure", failure.toString(), true); }
                }
            })));
        return area;
    }

    private JComboBox<String> options(String[] choices, String selected) {
        String[] labels = java.util.Arrays.stream(choices).map(choice -> ui.t("option." + choice)).toArray(String[]::new);
        var box = new JComboBox<>(labels);
        box.setSelectedIndex(Math.max(0, List.of(choices).indexOf(selected)));
        box.setBackground(LauncherWindow.BG);
        box.setForeground(LauncherWindow.TEXT);
        box.setFont(ui.font(14, false));
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
            picker.setComponentOrientation(ui.isPersian() ? ComponentOrientation.RIGHT_TO_LEFT
                : ComponentOrientation.LEFT_TO_RIGHT);
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
                message("invalidResolution", "", true);
                width.requestFocusInWindow();
                return;
            }
        }
        if (draft.java().equals("custom") && draft.javaPath().isBlank()) {
            switchSection(3);
            message("invalidJava", "", true);
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
            switchSection(failure.key.equals("invalidRam") ? 3 : 1);
            message(failure.key, "", true);
            switch (failure.key) {
                case "invalidRam" -> ram.requestFocusInWindow();
                case "invalidName" -> name.requestFocusInWindow();
                default -> profile.requestFocusInWindow();
            }
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
                    message("invalidJava", failure.getCause().toString(), true);
                    javaPath.requestFocusInWindow();
                }
            }
        }.execute();
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
                    String resolved = info.executable() + "  ·  " + info.version() + "  ·  " + info.architecture();
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
                    .append('\n').append(ui.t("gameCache")).append("  ").append(measureText(GunnertimesLauncher.CACHE));
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

    private void copyDiagnostics(String resolvedJava) {
        // This summary has no username, profile path, raw log, environment variables or machine name.
        String sanitized = "Gunnertimes " + MinecraftFiles.LAUNCHER_VERSION + "\nMinecraft " + MinecraftFiles.VERSION
            + " / Fabric " + MinecraftFiles.LOADER + "\nInstalled: " + ui.installedVersion()
            + "\nAvailable: " + ui.availableVersion() + "\nJava mode: " + ui.settings().options().javaMode()
            + "\nJava: " + (resolvedJava.contains("  ·  ")
                ? resolvedJava.substring(resolvedJava.indexOf("  ·  ") + 5) : "unknown")
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
