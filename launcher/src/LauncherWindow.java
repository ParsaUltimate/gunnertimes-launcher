import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.text.NumberFormat;
import java.util.EnumMap;
import java.util.concurrent.ExecutionException;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;

final class LauncherWindow {
    private static final Color BG = new Color(0x11151B), RAIL = new Color(0x151A21),
        PANEL = new Color(0x1B222B), EDGE = new Color(0x303A46), TEXT = new Color(0xF5F1E8),
        MUTED = new Color(0xAAB7C5), GOLD = new Color(0xF9BA68), GREEN = new Color(0x90D5AF),
        RED = new Color(0xFFABA0);
    private enum Page { PLAY, SETTINGS, HELP }

    private final JFrame frame = new JFrame("Gunnertimes");
    private final BufferedImage art = loadArt();
    private LauncherText text;
    private GunnertimesLauncher.Preferences settings;
    private LauncherSession session = new LauncherSession();
    private Page page = Page.PLAY;
    private boolean busy, expanded, noticeError;
    private String notice = "", noticeDetails = "";
    private JTextField username, folder, memory;
    private JButton primary, openLog;
    private JLabel noticeLabel, status, release, counts, stats, file, active, estimate;
    private JProgressBar transfer;
    private JTextArea details;
    private JPanel detailPanel;
    private final EnumMap<Updater.Step,JLabel> stages = new EnumMap<>(Updater.Step.class);

    LauncherWindow(GunnertimesLauncher.Preferences settings) {
        this.settings = settings;
        text = new LauncherText(settings.language());
    }

    void show() {
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) { if (!busy) frame.dispose(); }
        });
        frame.setMinimumSize(new Dimension(870, 630));
        frame.setSize(1130, 800);
        frame.setLocationRelativeTo(null);
        render();
        frame.setVisible(true);
        var timer = new Timer(650, event -> {
            if (!frame.isDisplayable()) ((Timer) event.getSource()).stop();
            else if (page == Page.PLAY && busy) refresh();
        });
        timer.start();
    }

    private void render() {
        var shell = panel(new BorderLayout(), BG);
        shell.add(navigation(), BorderLayout.LINE_START);
        var main = panel(new BorderLayout(), BG);
        var head = panel(new BorderLayout(), BG);
        head.setBorder(new EmptyBorder(15, 26, 14, 26));
        head.add(label(t(pageKey(page)), 19, true, TEXT), BorderLayout.LINE_START);
        var language = button(t("switchLanguage"), false);
        language.addActionListener(event -> changeLanguage(text.rtl ? "en" : "fa"));
        head.add(language, BorderLayout.LINE_END);
        main.add(head, BorderLayout.PAGE_START);
        var content = new PageViewport();
        content.setBackground(BG);
        content.setBorder(new EmptyBorder(0, 26, 24, 26));
        content.add(switch (page) {
            case PLAY -> playPage();
            case SETTINGS -> settingsPage();
            case HELP -> helpPage();
        }, BorderLayout.PAGE_START);
        JScrollPane scroll = scroll(content);
        scroll.getVerticalScrollBar().setUnitIncrement(20);
        main.add(scroll, BorderLayout.CENTER);
        shell.add(main, BorderLayout.CENTER);
        text.orient(shell);
        frame.setContentPane(shell);
        frame.getRootPane().setDefaultButton(page == Page.PLAY ? primary : null);
        frame.revalidate();
        frame.repaint();
        if (page == Page.PLAY) refresh();
    }

    private static String pageKey(Page page) {
        return switch (page) {
            case PLAY -> "navPlay";
            case SETTINGS -> "navSettings";
            case HELP -> "navHelp";
        };
    }

    private JPanel navigation() {
        var rail = panel(new BorderLayout(), RAIL);
        rail.setBorder(new EmptyBorder(24, 16, 20, 16));
        rail.setPreferredSize(new Dimension(181, 0));
        var items = stack(RAIL, 7);
        var brand = panel(new FlowLayout(FlowLayout.LEADING, 7, 0), RAIL);
        brand.add(label("G", 22, true, GOLD));
        brand.add(label("GUNNERTIMES", 14, true, TEXT));
        items.add(brand);
        items.add(Box.createVerticalStrut(18));
        for (Page target : Page.values()) {
            var action = button(t(pageKey(target)), false);
            action.setIcon(navigationIcon(target));
            action.setIconTextGap(10);
            action.setHorizontalAlignment(SwingConstants.LEADING);
            action.setBackground(page == target ? new Color(0x32313A) : RAIL);
            action.setForeground(page == target ? GOLD : MUTED);
            action.addActionListener(event -> {
                if (page != target) {
                    page = target;
                    notice = "";
                    noticeDetails = "";
                    render();
                }
            });
            items.add(action);
        }
        rail.add(items, BorderLayout.PAGE_START);
        return rail;
    }

    private JPanel playPage() {
        var body = stack(BG, 16);
        var hero = new Hero(art, text.rtl);
        hero.setLayout(new BorderLayout());
        hero.setBorder(new EmptyBorder(24, 24, 20, 24));
        hero.setPreferredSize(new Dimension(680, 280));
        var headline = stack(null, 3);
        headline.add(label("Gunnertimes", 49, true, TEXT));
        headline.add(label(t("heroSubtitle"), 15, true, TEXT));
        hero.add(headline, BorderLayout.PAGE_START);
        var actions = panel(new BorderLayout(15, 0), null);
        primary = button(t("play"), true);
        primary.setIcon(navigationIcon(Page.PLAY));
        primary.setIconTextGap(8);
        primary.setPreferredSize(new Dimension(164, 48));
        primary.addActionListener(event -> start());
        actions.add(primary, BorderLayout.LINE_START);
        var identity = stack(null, 3);
        identity.add(label(settings.username().isEmpty() ? t("noName") : settings.username(), 15, true, TEXT));
        var path = label(t("profile") + "   " + ltr(settings.profile()), 12, false, TEXT);
        path.setPreferredSize(new Dimension(150, 18));
        path.setToolTipText(settings.profile());
        identity.add(path);
        actions.add(identity, BorderLayout.CENTER);
        hero.add(actions, BorderLayout.PAGE_END);
        body.add(hero);
        body.add(progressPanel());
        return body;
    }

    private JPanel progressPanel() {
        var card = card(10);
        var title = panel(new BorderLayout(10, 0), PANEL);
        title.add(label(t("progress"), 19, true, TEXT), BorderLayout.LINE_START);
        release = label("", 12, false, MUTED);
        title.add(release, BorderLayout.LINE_END);
        card.add(title);
        var timeline = panel(new GridLayout(1, 5, 6, 0), PANEL);
        stages.clear();
        for (var stage : Updater.Step.values()) {
            var tile = stack(BG, 3);
            tile.setBorder(new EmptyBorder(9, 8, 9, 8));
            tile.add(label(t("step." + stage.name()), 13, true, TEXT));
            var state = label("", 11, false, MUTED);
            stages.put(stage, state);
            tile.add(state);
            timeline.add(tile);
        }
        card.add(timeline);
        status = label("", 15, true, TEXT);
        file = technical(label("", 12, false, MUTED));
        file.setPreferredSize(new Dimension(140, 17));
        counts = label("", 12, false, MUTED);
        stats = label("", 12, false, MUTED);
        active = label("", 12, false, MUTED);
        estimate = label("", 12, false, MUTED);
        for (var row : new JLabel[]{status, file, counts, stats, active}) card.add(row);
        transfer = new JProgressBar(0, 1000);
        transfer.setPreferredSize(new Dimension(150, 8));
        transfer.setForeground(GOLD);
        transfer.setBackground(EDGE);
        transfer.setBorderPainted(false);
        transfer.getAccessibleContext().setAccessibleName(t("fileProgress"));
        card.add(transfer);
        card.add(estimate);
        var toolbar = panel(new FlowLayout(FlowLayout.LEADING, 7, 0), PANEL);
        var toggle = button(t("details"), false);
        toggle.addActionListener(event -> {
            expanded = !expanded;
            refresh();
            if (expanded) SwingUtilities.invokeLater(() ->
                detailPanel.scrollRectToVisible(new Rectangle(detailPanel.getSize())));
        });
        toolbar.add(toggle);
        openLog = button(t("openLog"), false);
        openLog.addActionListener(event -> {
            String location = session.snapshot().log();
            if (!location.isEmpty()) open(Path.of(location));
        });
        toolbar.add(openLog);
        card.add(toolbar);

        detailPanel = stack(PANEL, 7);
        detailPanel.add(label(t("technical"), 13, true, MUTED));
        details = technical(new JTextArea(7, 40));
        details.setBackground(BG);
        details.setForeground(TEXT);
        details.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        details.setLineWrap(true);
        details.setEditable(false);
        details.setBorder(new EmptyBorder(8, 8, 8, 8));
        detailPanel.add(scroll(details));
        var copy = button(t("copy"), false);
        copy.addActionListener(event -> {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(details.getText()), null);
            copy.setText(t("copied"));
        });
        var copyRow = panel(new FlowLayout(FlowLayout.LEADING, 0, 0), PANEL);
        copyRow.add(copy);
        detailPanel.add(copyRow);
        card.add(detailPanel);
        return card;
    }

    private JPanel settingsPage() {
        var body = stack(BG, 15);
        body.add(label(t("settingsSubtitle"), 15, false, MUTED));
        var form = card(13);
        username = field(settings.username(), 24);
        form.add(labeled(t("player"), username, username));
        folder = field(settings.profile(), 32);
        var picker = panel(new BorderLayout(8, 0), PANEL);
        picker.add(folder, BorderLayout.CENTER);
        var choose = button(t("browse"), false);
        choose.addActionListener(event -> browse());
        picker.add(choose, BorderLayout.LINE_END);
        form.add(labeled(t("profile"), picker, folder));
        form.add(wrapped(t("profileHint")));

        memory = field(Integer.toString(settings.ramGiB()), 5);
        form.add(labeled(t("ram"), memory, memory));
        form.add(wrapped(t("ramHint")));
        var languages = new JComboBox<>(new String[]{"English", "فارسی"});
        languages.setSelectedIndex(text.rtl ? 1 : 0);
        languages.setBackground(BG);
        languages.setForeground(TEXT);
        languages.setFont(font(14, false));
        languages.addActionListener(event -> changeLanguage(languages.getSelectedIndex() == 1 ? "fa" : "en"));
        form.add(labeled(t("language"), languages, languages));

        var buttons = panel(new FlowLayout(FlowLayout.LEADING, 8, 0), PANEL);
        var save = button(t("save"), true);
        save.setEnabled(!busy);
        save.addActionListener(event -> saveSettings());
        buttons.add(save);
        for (String key : new String[]{"openProfile", "openLogs"}) {
            var action = button(t(key), false);
            action.addActionListener(event -> {
                try {
                    Path path = GunnertimesLauncher.profilePath(folder.getText());
                    open(key.equals("openLogs") ? path.resolve("logs") : path);
                } catch (GunnertimesLauncher.InputError failure) {
                    notifyUser(failure.key, "", true);
                }
            });
            buttons.add(action);
        }
        form.add(buttons);
        noticeLabel = label("", 13, true, MUTED);
        form.add(noticeLabel);
        updateNotice();
        body.add(form);
        return body;
    }

    private JPanel helpPage() {
        var body = stack(BG, 12);
        body.add(label(t("helpSubtitle"), 15, false, MUTED));
        body.add(guide("guideStart", "guideStartBody"));
        body.add(guide("guideFolder", "guideFolderBody"));
        var examples = guide("guideExamples", null);
        examples.add(label(t("guideDefault"), 13, true, MUTED));
        examples.add(example("%LOCALAPPDATA%\\Gunnertimes\\profiles\\Gunnertimes"));
        examples.add(label(t("guideExisting"), 13, true, MUTED));
        examples.add(example("%APPDATA%\\.minecraft\\versions\\Goharian New Server"));
        body.add(examples);
        body.add(guide("guideFix", "guideFixBody"));
        body.add(guide("guideLogs", "guideLogsBody"));
        return body;
    }

    private JPanel guide(String title, String body) {
        var guide = card(7);
        guide.add(label(t(title), 16, true, GOLD));
        if (body != null) guide.add(wrapped(t(body)));
        return guide;
    }

    private JTextField example(String path) {
        var example = field(path, 40);
        example.setEditable(false);
        return example;
    }

    private void browse() {
        var picker = new JFileChooser();
        picker.setLocale(text.locale);
        picker.updateUI();
        picker.setDialogTitle(t("chooseFolder"));
        picker.setApproveButtonText(t("browse"));
        picker.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        try {
            Path location = GunnertimesLauncher.profilePath(folder.getText());
            while (location != null && !Files.isDirectory(location)) location = location.getParent();
            if (location != null) picker.setCurrentDirectory(location.toFile());
        } catch (GunnertimesLauncher.InputError ignored) {
            // The picker can replace a malformed path.
        }
        text.orient(picker);
        if (picker.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION)
            folder.setText(picker.getSelectedFile().getAbsolutePath());
    }

    private void changeLanguage(String language) {
        if (settings.language().equals(language)) return;
        String[] drafts = page == Page.SETTINGS
            ? new String[]{username.getText(), folder.getText(), memory.getText()} : null;
        var translated = new GunnertimesLauncher.Preferences(settings.username(), settings.profile(),
            language, settings.ramGiB());
        try {
            GunnertimesLauncher.save(translated);
            settings = translated;
            text = new LauncherText(language);
            render();
            if (drafts != null) {
                username.setText(drafts[0]);
                folder.setText(drafts[1]);
                memory.setText(drafts[2]);
            }
        } catch (IOException failure) {
            notifyUser("settingsFailure", failure.toString(), true);
        }
    }

    private void saveSettings() {
        try {
            var updated = GunnertimesLauncher.validated(username.getText(), folder.getText(),
                settings.language(), memory.getText(), false);
            GunnertimesLauncher.save(updated);
            settings = updated;
            folder.setText(updated.profile());
            notifyUser("saved", "", false);
        } catch (GunnertimesLauncher.InputError failure) {
            notifyUser(failure.key, "", true);
        } catch (IOException failure) {
            notifyUser("settingsFailure", failure.toString(), true);
        }
    }

    private void start() {
        if (busy) return;
        try {
            GunnertimesLauncher.validated(settings.username(), settings.profile(), settings.language(),
                Integer.toString(settings.ramGiB()), true);
        } catch (GunnertimesLauncher.InputError failure) {
            page = Page.SETTINGS;
            notice = failure.key;
            noticeError = true;
            render();
            username.requestFocusInWindow();
            return;
        }
        session = new LauncherSession();
        busy = true;
        expanded = false;
        var selected = settings;
        var runningSession = session;
        refresh();
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception {
                GunnertimesLauncher.play(selected, runningSession::accept);
                return null;
            }
            @Override protected void done() {
                busy = false;
                try {
                    get();
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException failure) {
                    expanded = true;
                }
                if (page == Page.PLAY) refresh();
                else if (page == Page.SETTINGS) render();
            }
        }.execute();
    }

    private void refresh() {
        var snapshot = session.snapshot();
        var event = snapshot.current();
        boolean failed = event != null && event.status() == Updater.Status.FAILED;
        primary.setText(t(failed ? "retry" : busy
            ? "running".equals(snapshot.steps().get(Updater.Step.RUN)) ? "inGame" : "preparing"
            : "play"));
        primary.setEnabled(!busy);
        for (var pair : stages.entrySet()) {
            String state = snapshot.steps().getOrDefault(pair.getKey(), "wait");
            pair.getValue().setText(t("state." + state));
            pair.getValue().setForeground(switch (state) {
                case "failed" -> RED;
                case "active" -> GOLD;
                case "done", "running", "closed" -> GREEN;
                default -> MUTED;
            });
        }
        release.setText(snapshot.release().isEmpty() ? "" : t("packStatus") + "  " + ltr(snapshot.release()));
        release.setToolTipText(snapshot.offline() ? t("status.offline") : null);
        status.setText(status(event));
        status.setForeground(failed ? RED : TEXT);
        status.setToolTipText(status.getText());

        var first = snapshot.active().stream().findFirst().orElse(null);
        String currentFile = failed && !event.file().isEmpty() ? event.file()
            : first != null ? first.file() : event == null ? "" : event.file();
        file.setVisible(!currentFile.isEmpty());
        file.setText(currentFile);
        file.setToolTipText(currentFile.isEmpty() ? null : currentFile);
        String counters = snapshot.counts().values().stream()
            .filter(counter -> counter.total() > 0)
            .map(counter -> t(counter.step() == Updater.Step.INSTALL ? "step.INSTALL" : "group." + counter.group())
                + " " + number(counter.count()) + "/" + number(counter.total()))
            .collect(java.util.stream.Collectors.joining("   ·   "));
        counts.setText(counters);
        counts.setToolTipText(counters);
        counts.setVisible(!counters.isEmpty());
        stats.setText(t("verifiedFiles") + " " + number(snapshot.verified()) + "   ·   "
            + t("reuseCount") + " " + number(snapshot.reused()) + "   ·   " + t("transferred") + " " + bytes(snapshot.received())
            + (first == null ? "" : "   ·   " + t("speed") + " " + bytes(snapshot.bytesPerSecond()) + "/s"));
        stats.setVisible(event != null);

        active.setVisible(first != null);
        active.setText(t("activeFiles") + "  " + number(snapshot.active().size()));
        active.setToolTipText(snapshot.active().stream().map(Updater.Progress::file)
            .collect(java.util.stream.Collectors.joining(" · ")));
        transfer.setVisible(first != null);
        boolean known = first != null && first.total() > 0;
        transfer.setIndeterminate(first != null && !known);
        if (known) transfer.setValue((int) Math.min(1000, 1000.0 * first.count() / first.total()));
        String amount = first == null ? "" : bytes(first.count()) + (known
            ? " / " + bytes(first.total()) + "   ·   " + number(Math.min(100, 100 * first.count() / first.total())) + "%" : "");
        if (known && snapshot.currentFileBytesPerSecond() > 0 && first.total() > first.count()) {
            long seconds = (long) Math.ceil((first.total() - first.count()) / (double) snapshot.currentFileBytesPerSecond());
            amount += "   ·   " + t("estimate") + " " + number(seconds) + " s";
        }
        estimate.setText(amount);
        estimate.setVisible(first != null);
        openLog.setVisible(!snapshot.log().isEmpty());
        detailPanel.setVisible(expanded);
        if (expanded) showDetails(snapshot);
    }

    private void showDetails(LauncherSession.Snapshot snapshot) {
        var lines = new StringBuilder();
        if (!snapshot.log().isEmpty()) lines.append(snapshot.log()).append("\n\n");
        for (var download : snapshot.active())
            lines.append(download.file()).append("   ").append(bytes(download.count())).append('\n');
        for (var event : snapshot.history()) {
            lines.append(t("step." + event.step().name())).append("  ·  ").append(status(event));
            if (!event.file().isEmpty()) lines.append("  ·  ").append(event.file());
            if (event.total() >= 0) lines.append("  ").append(event.count()).append('/').append(event.total());
            if (!event.detail().isEmpty()) lines.append('\n').append(event.detail());
            lines.append('\n');
        }
        String body = lines.toString();
        if (!body.equals(details.getText())) {
            boolean follow = details.getCaretPosition() == details.getDocument().getLength();
            int begin = details.getSelectionStart(), end = details.getSelectionEnd();
            details.setText(body);
            if (follow && begin == end) details.setCaretPosition(body.length());
            else details.select(Math.min(begin, body.length()), Math.min(end, body.length()));
        }
    }

    private String status(Updater.Progress event) {
        if (event == null) return t("idle");
        if (event.status() == Updater.Status.FAILED) {
            String error = event.detail();
            if (error.contains("mod conflict") || error.contains("file conflict") || error.contains("Cannot read mod"))
                return t("failure.conflict");
            return t("failure." + event.step().name());
        }
        return switch (event.status()) {
            case OFFLINE -> t("status.offline");
            case REUSED -> t("status.reused");
            case RETRY -> t("status.retry");
            case VERIFY -> t("status.verify");
            case VERIFIED -> t("status.verified");
            case SKIPPED -> t("status.noInstall");
            default -> t(switch (event.step()) {
                case CHECK -> event.group().equals("release") && event.status() == Updater.Status.BEGIN
                    ? "status.release" : "status.check";
                case DOWNLOAD -> "status.download";
                case INSTALL -> "status.install";
                case START -> "status.start";
                case RUN -> event.status() == Updater.Status.DONE ? "status.closed" : "status.run";
            });
        };
    }

    private void open(Path path) {
        if (!Files.exists(path)) {
            notifyUser("missingFolder", path.toString(), false);
            return;
        }
        try {
            Desktop.getDesktop().open(path.toFile());
        } catch (IOException | UnsupportedOperationException failure) {
            notifyUser("openFailure", failure.toString(), true);
        }
    }

    private void notifyUser(String key, String detail, boolean error) {
        notice = key;
        noticeDetails = detail;
        noticeError = error;
        if (page == Page.SETTINGS) updateNotice();
        else JOptionPane.showMessageDialog(frame, t(key) + (detail.isEmpty() ? "" : "\n" + detail),
            "Gunnertimes", error ? JOptionPane.ERROR_MESSAGE : JOptionPane.INFORMATION_MESSAGE);
    }

    private void updateNotice() {
        noticeLabel.setText(notice.isEmpty() ? "" : t(notice));
        noticeLabel.setToolTipText(noticeDetails.isEmpty() ? null : noticeDetails);
        noticeLabel.setForeground(noticeError ? RED : GREEN);
    }

    private String t(String key) { return text.get(key); }
    private String number(long value) { return NumberFormat.getIntegerInstance(text.locale).format(value); }

    private String bytes(long value) {
        if (value < 1024) return number(value) + " B";
        double amount = value / 1024.0;
        String unit = " KiB";
        if (amount >= 1024) { amount /= 1024; unit = " MiB"; }
        if (amount >= 1024) { amount /= 1024; unit = " GiB"; }
        var format = NumberFormat.getNumberInstance(text.locale);
        format.setMaximumFractionDigits(1);
        return format.format(amount) + unit;
    }

    private static String ltr(String value) { return "\u2066" + value + "\u2069"; }

    private Font font(int size, boolean bold) {
        return new Font(text.rtl ? "Tahoma" : "Segoe UI", bold ? Font.BOLD : Font.PLAIN, size);
    }

    private JLabel label(String title, int size, boolean bold, Color color) {
        var label = new JLabel(title, SwingConstants.LEADING);
        label.setFont(font(size, bold));
        label.setForeground(color);
        return label;
    }

    private Icon navigationIcon(Page page) {
        return new Icon() {
            public int getIconWidth() { return 16; }
            public int getIconHeight() { return 16; }
            public void paintIcon(Component component, Graphics graphics, int x, int y) {
                var g = (Graphics2D) graphics.create();
                g.translate(x, y);
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(component.getForeground());
                g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                switch (page) {
                    case PLAY -> g.drawPolygon(new int[]{4, 4, 12}, new int[]{3, 13, 8}, 3);
                    case SETTINGS -> {
                        g.drawLine(2, 4, 14, 4);
                        g.drawLine(2, 8, 14, 8);
                        g.drawLine(2, 12, 14, 12);
                        g.drawLine(5, 2, 5, 6);
                        g.drawLine(10, 6, 10, 10);
                        g.drawLine(6, 10, 6, 14);
                    }
                    case HELP -> {
                        g.drawOval(1, 1, 14, 14);
                        g.setFont(new Font("Dialog", Font.BOLD, 11));
                        g.drawString("?", 5, 12);
                    }
                }
                g.dispose();
            }
        };
    }

    private JButton button(String title, boolean accent) {
        var button = new JButton(title) {
            @Override protected void paintComponent(Graphics graphics) {
                var g = (Graphics2D) graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color fill = isEnabled() ? getBackground() : EDGE;
                if (getModel().isPressed()) fill = fill.darker();
                else if (getModel().isRollover()) fill = fill.brighter();
                g.setColor(fill);
                g.fillRoundRect(0, 0, getWidth(), getHeight(), 11, 11);
                if (isFocusOwner()) {
                    g.setColor(accent ? BG : GOLD);
                    g.drawRoundRect(3, 3, getWidth() - 7, getHeight() - 7, 8, 8);
                }
                g.dispose();
                super.paintComponent(graphics);
            }
        };
        button.setContentAreaFilled(false);
        button.setFocusPainted(false);
        button.setBorder(new EmptyBorder(9, 12, 9, 12));
        button.setBackground(accent ? GOLD : PANEL);
        button.setForeground(accent ? BG : TEXT);
        button.setFont(font(14, true));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return button;
    }

    private JTextField field(String value, int columns) {
        var input = technical(new JTextField(value, columns));
        input.setFont(font(14, false));
        input.setForeground(TEXT);
        input.setCaretColor(GOLD);
        input.setSelectionColor(new Color(0x65523D));
        input.setBackground(BG);
        input.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(EDGE),
            new EmptyBorder(8, 10, 8, 10)));
        return input;
    }

    private JPanel labeled(String title, JComponent input, JComponent focus) {
        var row = panel(new BorderLayout(0, 5), PANEL);
        var caption = label(title, 14, true, TEXT);
        caption.setLabelFor(focus);
        row.add(caption, BorderLayout.PAGE_START);
        row.add(input, BorderLayout.CENTER);
        return row;
    }

    private JTextArea wrapped(String value) {
        var area = new JTextArea(value, 0, 38);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFont(font(14, false));
        area.setForeground(MUTED);
        area.setBackground(PANEL);
        area.setBorder(new EmptyBorder(1, 0, 2, 0));
        area.putClientProperty("wrapGuide", true);
        return area;
    }

    private static <T extends JComponent> T technical(T component) {
        component.putClientProperty("technical", true);
        return component;
    }

    private static JPanel panel(LayoutManager layout, Color color) {
        var panel = new JPanel(layout);
        if (color == null) panel.setOpaque(false);
        else panel.setBackground(color);
        return panel;
    }

    private static JPanel stack(Color color, int gap) { return panel(new Vertical(gap), color); }

    private static JPanel card(int gap) {
        var panel = stack(PANEL, gap);
        panel.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(EDGE),
            new EmptyBorder(15, 17, 15, 17)));
        return panel;
    }

    private static JScrollPane scroll(JComponent value) {
        var pane = new JScrollPane(value, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
            JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        pane.setBorder(BorderFactory.createEmptyBorder());
        pane.getViewport().setBackground(BG);
        return pane;
    }

    private static BufferedImage loadArt() {
        try (var source = LauncherWindow.class.getResourceAsStream("/gunnertimes-hero.png")) {
            return source == null ? null : ImageIO.read(source);
        } catch (IOException ignored) {
            return null;
        }
    }

    private static final class Hero extends JPanel {
        final BufferedImage art;
        final boolean rtl;
        Hero(BufferedImage art, boolean rtl) { this.art = art; this.rtl = rtl; }

        @Override protected void paintComponent(Graphics graphics) {
            var g = (Graphics2D) graphics.create();
            int w = getWidth(), h = getHeight();
            g.setColor(new Color(0x202B37));
            g.fillRect(0, 0, w, h);
            if (art != null) {
                double scale = Math.max(w / (double) art.getWidth(), h / (double) art.getHeight());
                int width = (int) Math.ceil(art.getWidth() * scale);
                int height = (int) Math.ceil(art.getHeight() * scale);
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(art, rtl ? (w + width) / 2 : (w - width) / 2,
                    (h - height) / 2, rtl ? -width : width, height, null);
            }
            g.setPaint(new GradientPaint(rtl ? w : 0, 0, new Color(0xD0141B23, true),
                rtl ? w * .26f : w * .74f, 0, new Color(0x00141B23, true)));
            g.fillRect(0, 0, w, h);
            g.setPaint(new GradientPaint(0, h * .33f, new Color(0x00121921, true), 0, h,
                new Color(0xEE121921, true)));
            g.fillRect(0, 0, w, h);
            g.dispose();
        }
    }

    private static final class PageViewport extends JPanel implements Scrollable {
        PageViewport() { super(new BorderLayout()); }
        @Override public Dimension getPreferredSize() {
            Component body = getComponentCount() == 0 ? null : getComponent(0);
            if (body == null) return super.getPreferredSize();
            Insets padding = getInsets();
            int available = getParent() != null ? getParent().getWidth() : getWidth();
            int width = available > 0 ? available : 800;
            return new Dimension(width, Vertical.height(body, width - padding.left - padding.right)
                + padding.top + padding.bottom);
        }
        public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        public int getScrollableUnitIncrement(Rectangle view, int axis, int direction) { return 20; }
        public int getScrollableBlockIncrement(Rectangle view, int axis, int direction) {
            return Math.max(20, view.height - 30);
        }
        public boolean getScrollableTracksViewportWidth() { return true; }
        public boolean getScrollableTracksViewportHeight() { return false; }
    }

    private static final class Vertical implements LayoutManager {
        final int gap;
        Vertical(int gap) { this.gap = gap; }
        public void addLayoutComponent(String name, Component child) {}
        public void removeLayoutComponent(Component child) {}

        public Dimension preferredLayoutSize(Container parent) {
            Insets insets = parent.getInsets();
            int width = Math.max(0, parent.getWidth() - insets.left - insets.right);
            return new Dimension(width, requiredHeight(parent, width > 0 ? width : 600));
        }

        static int height(Component component, int width) {
            if (component instanceof Container container && container.getLayout() instanceof Vertical stack) {
                Insets insets = container.getInsets();
                return stack.requiredHeight(container, Math.max(1, width - insets.left - insets.right));
            }
            if (component instanceof JTextArea area && Boolean.TRUE.equals(area.getClientProperty("wrapGuide"))) {
                area.setSize(Math.max(1, width), Short.MAX_VALUE);
            }
            return component.getPreferredSize().height;
        }

        private int requiredHeight(Container parent, int width) {
            Insets insets = parent.getInsets();
            int height = insets.top + insets.bottom, count = 0;
            for (Component child : parent.getComponents()) {
                if (child.isVisible()) height += height(child, width) + (count++ == 0 ? 0 : gap);
            }
            return height;
        }

        public Dimension minimumLayoutSize(Container parent) { return preferredLayoutSize(parent); }

        public void layoutContainer(Container parent) {
            Insets insets = parent.getInsets();
            int width = Math.max(0, parent.getWidth() - insets.left - insets.right);
            int y = insets.top;
            for (Component child : parent.getComponents()) {
                if (!child.isVisible()) continue;
                int height = height(child, width);
                child.setBounds(insets.left, y, width, height);
                y += height + gap;
            }
        }
    }
}
