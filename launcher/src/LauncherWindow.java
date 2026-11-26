import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.text.NumberFormat;
import java.util.EnumMap;
import java.util.Map;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.concurrent.ExecutionException;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;

final class LauncherWindow {
    static final Color BG = new Color(0x101114), RAIL = new Color(0x17181B),
        PANEL = new Color(0x1C1D21), EDGE = new Color(0x34363D), TEXT = new Color(0xF2EFEA),
        MUTED = new Color(0xAAB0BA), GOLD = new Color(0xFFB23E), GREEN = new Color(0x90D5AF),
        RED = new Color(0xFFABA0);
    private enum Page { PLAY, PACK, SETTINGS, HELP }

    private final java.util.function.Consumer<String> copyToClipboard;
    private final JFrame frame = new JFrame("Gunnertimes");
    private final BufferedImage art = loadArt();
    private LauncherText text;
    private GunnertimesLauncher.Preferences settings;
    private LauncherSession session = new LauncherSession();
    private Page page = Page.PLAY;
    private boolean busy, expanded, noticeError;
    private String notice = "", noticeDetails = "";
    private LauncherSettings settingsPanel;
    private LauncherSettings.Draft draft;
    private boolean compact;
    private int helpTopic;
    private JButton activeHelpTopic, languageButton;
    private boolean checkingLauncher, updatingLauncher;
    private LauncherUpdates.Release launcherRelease;
    private boolean checking, catalogReady, serverChecking = true;
    private boolean progressed, minimized;
    private GunnertimesLauncher.Action lastAction;
    private PackService.Release installed;
    private PackService.Check catalog = PackService.Check.empty();
    private PackService.Inspection inspection;
    private ServerStatus.Result server = ServerStatus.Result.unknown();
    private String catalogError = "", profileError = "", search = "";
    private JButton primary, openLog, retry;
    private JLabel status, release, counts, stats, file, active, estimate, joinStatus;
    private JScrollPane pageScroll;
    private JProgressBar transfer;
    private JTextArea details;
    private JPanel detailPanel;
    private final EnumMap<Updater.Step,JLabel> stages = new EnumMap<>(Updater.Step.class);

    LauncherWindow(GunnertimesLauncher.Preferences settings) {
        this(settings, value -> Toolkit.getDefaultToolkit().getSystemClipboard()
            .setContents(new StringSelection(value), null));
    }

    LauncherWindow(GunnertimesLauncher.Preferences settings, java.util.function.Consumer<String> copyToClipboard) {
        this.copyToClipboard = copyToClipboard;
        this.settings = settings;
        text = new LauncherText(settings.language());
    }

    void show() {
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.setFocusTraversalPolicy(new ContainerOrderFocusTraversalPolicy());
        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) { if (!busy && !updatingLauncher) frame.dispose(); }
        });
        frame.setMinimumSize(new Dimension(690, 550));
        frame.setSize(1270, 805);
        frame.addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent event) {
                boolean next = frame.getWidth() < 1020;
                if (next != compact) {
                    compact = next;
                    render();
                } else if (page == Page.PLAY && pageScroll != null) pageScroll.revalidate();
            }
        });
        frame.setLocationRelativeTo(null);
        render();
        frame.setVisible(true);
        lookup(true);
        probeServer();
        checkLauncher(false);
        var timer = new Timer(650, event -> {
            if (!frame.isDisplayable()) ((Timer) event.getSource()).stop();
            else if (busy) {
                if (!minimized && settings.options().launchWindow().equals("minimize") && session.isRunning()) {
                    frame.setState(Frame.ICONIFIED);
                    minimized = true;
                }
                if (page == Page.PLAY) refresh();
            }
        });
        timer.start();
    }

    private void render() {
        if (settingsPanel != null) draft = settingsPanel.draft();
        settingsPanel = null;
        var shell = panel(new BorderLayout(), BG);
        shell.add(navigation(), BorderLayout.PAGE_START);
        var content = new PageViewport();
        content.setBackground(BG);
        content.setBorder(page == Page.PLAY ? new EmptyBorder(0, 0, 0, 0) : new EmptyBorder(22, 28, 24, 28));
        content.add(switch (page) {
            case PLAY -> playPage();
            case PACK -> packPage();
            case SETTINGS -> settingsPage();
            case HELP -> helpPage();
        }, page == Page.HELP ? BorderLayout.CENTER : BorderLayout.PAGE_START);
        if (page == Page.HELP) {
            pageScroll = null;
            shell.add(content, BorderLayout.CENTER);
        } else {
            pageScroll = scroll(content);
            pageScroll.getVerticalScrollBar().setUnitIncrement(20);
            shell.add(pageScroll, BorderLayout.CENTER);
        }
        if (page == Page.PLAY) shell.add(packStrip(), BorderLayout.PAGE_END);
        text.orient(shell);
        frame.setContentPane(shell);
        frame.getRootPane().setDefaultButton(page == Page.PLAY ? primary : null);
        frame.revalidate();
        frame.repaint();
        if (page == Page.PLAY && progressed) refresh();
    }

    private static String pageKey(Page page) {
        return switch (page) {
            case PLAY -> "navHome";
            case PACK -> "navPack";
            case SETTINGS -> "navSettings";
            case HELP -> "navHelp";
        };
    }

    private JPanel navigation() {
        var bar = panel(new BorderLayout(14, 0), RAIL);
        bar.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, EDGE),
            new EmptyBorder(10, 18, 10, 18)));
        var brand = panel(new BorderLayout(9, 0), RAIL);
        var mark = label("", 20, true, GOLD);
        mark.setIcon(navigationIcon(Page.PACK));
        brand.add(mark, BorderLayout.LINE_START);
        var wordmark = panel(new GridLayout(compact ? 1 : 2, 1, 0, -3), null);
        wordmark.add(label("Gunnertimes", compact ? 19 : 27, true, TEXT));
        if (!compact) wordmark.add(label("MINECRAFT MODPACK LAUNCHER", 10, true, MUTED));
        brand.add(wordmark, BorderLayout.CENTER);
        bar.add(brand, BorderLayout.LINE_START);
        var tabs = panel(new FlowLayout(FlowLayout.CENTER, compact ? 2 : 8, 0), RAIL);
        for (Page target : Page.values()) {
            var action = button(t(pageKey(target)), false);
            if (!compact) action.setIcon(target == Page.PLAY ? new UtilityIcon("home") : navigationIcon(target));
            action.setIconTextGap(7);
            action.setBackground(page == target ? new Color(0x47311C) : RAIL);
            action.setForeground(page == target ? GOLD : TEXT);
            action.getAccessibleContext().setAccessibleDescription(page == target ? t("selectedPage") : "");
            action.addActionListener(event -> {
                if (page != target) {
                    page = target;
                    notice = "";
                    noticeDetails = "";
                    render();
                }
            });
            tabs.add(action);
        }
        bar.add(tabs, BorderLayout.CENTER);
        var language = button("", false);
        languageButton = language;
        language.setIcon(new UtilityIcon("globe"));
        language.setToolTipText(t("language"));
        language.getAccessibleContext().setAccessibleName(t("language"));
        language.putClientProperty("outlined", true);
        language.addActionListener(event -> {
            var menu = new JPopupMenu();
            var group = new ButtonGroup();
            String[] codes = {"fa", "en"};
            String[] names = {"فارسی", "English"};
            for (int i = 0; i < codes.length; i++) {
                String code = codes[i];
                var choice = new JRadioButtonMenuItem(names[i], settings.language().equals(code));
                choice.setBackground(PANEL);
                choice.setForeground(TEXT);
                choice.setFont(code.equals("fa") ? PersianTypography.control(15) : new Font("Segoe UI", Font.PLAIN, 15));
                choice.addActionListener(selected -> changeLanguage(code));
                group.add(choice);
                menu.add(choice);
            }
            text.orient(menu);
            menu.show(language, text.rtl ? 0 : language.getWidth() - menu.getPreferredSize().width, language.getHeight());
            MenuSelectionManager.defaultManager().setSelectedPath(new MenuElement[]{menu, (MenuElement) menu.getComponent(text.rtl ? 0 : 1)});
        });
        bar.add(language, BorderLayout.LINE_END);
        return bar;
    }

    private JPanel playPage() {
        var body = stack(BG, 0);
        var split = panel(new GridBagLayout(), BG);
        int height = compact ? 425 : Math.max(470, frame.getHeight() - 157);
        var hero = new Hero(art, text.rtl);
        hero.setLayout(new BorderLayout());
        hero.setBorder(new EmptyBorder(25, 22, 23, 22));
        hero.setPreferredSize(new Dimension(compact ? 540 : 618, height));
        var profile = stack(null, 4);
        profile.add(label(settings.username().isBlank() ? t("noName") : settings.username(), 16, true, TEXT));
        var path = label(ltr(settings.profile()), 12, false, TEXT);
        path.setPreferredSize(new Dimension(160, 18));
        path.setToolTipText(settings.profile());
        profile.add(path);
        hero.add(profile, BorderLayout.PAGE_START);

        var actions = panel(new GridBagLayout(), null);
        var action = mainAction();
        primary = button(t(actionKey(action)), true);
        primary.setIcon(navigationIcon(Page.PLAY));
        primary.setFont(font(compact ? 17 : 23, true));
        primary.setEnabled(catalogReady && !busy && profileError.isEmpty() &&
            (installed != null || catalog.available() != null));
        primary.addActionListener(event -> perform(action));
        var join = button(t("joinServer"), false);
        join.putClientProperty("outlined", true);
        join.setFont(font(compact ? 13 : 15, true));
        join.setEnabled(!busy && installed != null && (inspection == null || inspection.healthy())
            && compatibility() != ServerStatus.Compatibility.INCOMPATIBLE);
        join.setToolTipText(t("compat." + compatibility().name()));
        join.addActionListener(event -> perform(GunnertimesLauncher.Action.JOIN));
        var update = button(t("updatePack"), false);
        downloadStyle(update);
        update.putClientProperty("outlined", true);
        update.setFont(font(compact ? 13 : 15, true));
        update.setEnabled(!busy && canUpdate());
        update.setToolTipText(t(canUpdate() ? "updatePack" : "noUpdate"));
        update.addActionListener(event -> perform(GunnertimesLauncher.Action.UPDATE));
        addAction(actions, primary, 0, .38);
        addAction(actions, join, 1, .40);
        addAction(actions, update, 2, .22);
        hero.add(actions, BorderLayout.PAGE_END);

        var editorial = new Editorial(text.rtl);
        editorial.setLayout(new BorderLayout());
        editorial.setPreferredSize(new Dimension(compact ? 540 : 420, compact ? 305 : height));
        editorial.setBorder(new EmptyBorder(compact ? 23 : 48, 32, 27, 32));
        var copy = stack(null, 13);
        copy.add(label(t("releaseNews"), 13, true, GOLD));
        String headline = catalog.available() == null ? t(checking ? "checkingRelease" : "releaseUnavailable")
            : ltr(catalog.available().version());
        var releaseTitle = label(headline, compact ? 23 : 29, true, TEXT);
        releaseTitle.setToolTipText(headline);
        releaseTitle.setPreferredSize(new Dimension(200, compact ? 29 : 37));
        copy.add(releaseTitle);
        String news = catalog.notes().isBlank()
            ? t(catalog.notesStale() ? "notesUnavailable" : "noReleaseNotes") : catalog.notes();
        var newsText = wrapped(news);
        newsText.setOpaque(false);
        newsText.setRows(compact ? 2 : 4);
        var newsScroll = scroll(newsText);
        newsScroll.setOpaque(false);
        newsScroll.getViewport().setOpaque(false);
        newsScroll.setPreferredSize(new Dimension(220, compact ? 48 : 92));
        copy.add(newsScroll);
        if (catalog.notesStale() && !catalog.notes().isBlank()) copy.add(label(t("staleNews"), 12, false, GOLD));
        var line = new JSeparator();
        line.setForeground(EDGE);
        copy.add(line);
        copy.add(label(t("serverStatus"), 13, true, GOLD));
        boolean online = server.state() == ServerStatus.State.ONLINE;
        String state = t(serverChecking ? "serverChecking" : online ? "serverOnline" : "serverUnknown");
        if (online && server.players() != null) state += "   ·   " + number(server.players()) + " " + t("players");
        copy.add(label(state, 20, true, online ? GREEN : TEXT));
        copy.add(label(ltr(ServerStatus.ADDRESS), 12, false, MUTED));
        if (compatibility() == ServerStatus.Compatibility.INCOMPATIBLE)
            copy.add(label(t("compat.INCOMPATIBLE"), 13, true, RED));
        editorial.add(copy, BorderLayout.PAGE_START);

        addSplit(split, hero, 0, 0, compact ? 1 : .60, height);
        addSplit(split, editorial, compact ? 0 : 1, compact ? 1 : 0, compact ? 1 : .40,
            compact ? 305 : height);
        split.setPreferredSize(new Dimension(700, height + (compact ? 305 : 0)));
        body.add(split);
        if (progressed) {
            var progress = panel(new BorderLayout(), BG);
            progress.setBorder(new EmptyBorder(14, 22, 14, 22));
            progress.add(progressPanel(), BorderLayout.CENTER);
            body.add(progress);
        }
        return body;
    }

    private static void addSplit(JPanel target, Component child, int x, int y, double weight, int height) {
        var constraints = new GridBagConstraints();
        constraints.gridx = x;
        constraints.gridy = y;
        constraints.weightx = weight;
        constraints.weighty = height;
        constraints.fill = GridBagConstraints.BOTH;
        target.add(child, constraints);
    }

    private static void addAction(JPanel target, JButton button, int x, double weight) {
        var constraints = new GridBagConstraints();
        constraints.gridx = x;
        constraints.weightx = weight;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.ipadx = 0;
        constraints.ipady = 13;
        constraints.insets = new Insets(0, x == 0 ? 0 : 7, 0, 0);
        target.add(button, constraints);
    }

    private JPanel packStrip() {
        var strip = panel(new BorderLayout(14, 0), RAIL);
        strip.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, EDGE),
            new EmptyBorder(12, 22, 12, 22)));
        var summary = panel(new FlowLayout(FlowLayout.LEADING, 15, 2), RAIL);
        var caption = label(t("packStrip"), 14, true, TEXT);
        caption.setIcon(navigationIcon(Page.PACK));
        caption.setIconTextGap(8);
        summary.add(caption);
        summary.add(label(t("installedVersion") + "  " + ltr(installed == null ? t("none") : installed.version()),
            13, false, MUTED));
        summary.add(label(t("availableVersion") + "  " + ltr(catalog.available() == null
            ? t("unknown") : catalog.available().version()) + (catalog.available() != null && catalog.stale()
            ? " · " + t("cached") : ""), 13, false, GOLD));
        strip.add(summary, BorderLayout.CENTER);
        if (!profileError.isEmpty()) {
            var error = button(t("details"), false);
            error.addActionListener(event -> detailDialog(t("failure.CHECK"), profileError));
            strip.add(error, BorderLayout.LINE_END);
        }
        return strip;
    }

    private JPanel packPage() {
        var body = stack(BG, 15);
        body.add(utilityTitle("officialMods", compact ? 33 : 43));
        body.add(label(t("packIntro"), 15, false, MUTED));
        var toolbar = outline(panel(new BorderLayout(12, 0), PANEL), 13);
        var summary = panel(new FlowLayout(FlowLayout.LEADING, 14, 3), PANEL);
        summary.add(label(t("installedVersion") + "  " + (installed == null ? t("none") : ltr(installed.version())),
            14, true, TEXT));
        String health = !profileError.isEmpty() ? t("failure.CHECK") : checking ? t("healthChecking")
            : inspection == null ? t("unknown") : t(inspection.healthy() ? "healthyPack" : "issuesFound");
        summary.add(label(t("fileHealth") + "  " + health, 13, true,
            inspection != null && inspection.healthy() && !checking ? GREEN : GOLD));
        toolbar.add(summary, BorderLayout.CENTER);

        var actions = panel(new FlowLayout(FlowLayout.TRAILING, 7, 0), PANEL);
        var verify = button(t("verifyPack"), true);
        verify.setIcon(new UtilityIcon("check", BG));
        verify.setFont(controlFont(compact ? 14 : 18));
        verify.setEnabled(installed != null && !busy && !checking);
        verify.addActionListener(event -> verify());
        actions.add(verify);
        var repair = button(t("repairPack"), false);
        repair.putClientProperty("outlined", true);
        repair.setIcon(new UtilityIcon("repair"));
        repair.setFont(controlFont(compact ? 14 : 18));
        repair.setEnabled(installed != null && inspection != null && !inspection.healthy() && !busy && !checking);
        repair.addActionListener(event -> perform(GunnertimesLauncher.Action.REPAIR));
        actions.add(repair);
        if (!profileError.isEmpty()) {
            var error = button(t("details"), false);
            error.addActionListener(event -> detailDialog(t("failure.CHECK"), profileError));
            actions.add(error);
        }
        toolbar.add(actions, compact ? BorderLayout.PAGE_END : BorderLayout.LINE_END);
        body.add(toolbar);

        var official = installed != null ? installed : catalog.available();
        if (official == null) {
            body.add(wrapped(t(checking ? "checkingRelease" : "releaseUnavailable")));
            return body;
        }
        if (installed == null) body.add(label(t("availableVersion") + "  " + ltr(official.version()), 13, false, MUTED));
        var searchField = field(search, 25);
        var searchRow = panel(new FlowLayout(FlowLayout.LEADING, 0, 0), BG);
        searchRow.add(labeled(t("searchMods"), searchField, searchField));
        body.add(searchRow);
        var model = new javax.swing.table.DefaultTableModel(new Object[]{t("modName"), t("modVersion"), t("fileStatus")}, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
        record OfficialRow(String file, String id, PackService.Condition condition) {}
        var rows = new java.util.ArrayList<OfficialRow>();
        var inspected = new java.util.HashMap<String, PackService.Item>();
        if (inspection != null) for (var item : inspection.items()) inspected.put(item.entry().path(), item);
        for (var entry : official.pack().files()) {
            if (!entry.path().startsWith("mods/")) continue;
            var item = inspected.get(entry.path());
            String file = entry.path().substring(5);
            String id = entry.modId() == null ? "" : entry.modId();
            String name = item != null && !item.modName().isBlank() ? item.modName() : id.isBlank() ? file : id;
            String version = item != null && !item.modVersion().isBlank() ? item.modVersion() : "—";
            var condition = item == null ? null : item.condition();
            model.addRow(new Object[]{name, version, condition == null ? t("unknown") : t("condition." + condition.name())});
            rows.add(new OfficialRow(file, id, condition));
        }

        var table = new JTable(model) {
            @Override public String getToolTipText(java.awt.event.MouseEvent event) {
                int row = rowAtPoint(event.getPoint());
                if (row < 0) return null;
                var entry = rows.get(convertRowIndexToModel(row));
                return entry.id().isEmpty() ? entry.file() : entry.file() + "  ·  " + entry.id();
            }
        };
        javax.swing.ToolTipManager.sharedInstance().registerComponent(table);
        table.getAccessibleContext().setAccessibleName(t("officialMods"));
        table.setBackground(PANEL);
        table.setForeground(TEXT);
        table.setGridColor(EDGE);
        table.setShowVerticalLines(false);
        table.setSelectionBackground(new Color(0x51391D));
        table.setSelectionForeground(TEXT);
        table.setRowHeight(compact ? 43 : 54);
        table.setFont(font(15, false));
        var renderer = new javax.swing.table.DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable source, Object value,
                    boolean selected, boolean focus, int row, int column) {
                super.getTableCellRendererComponent(source, value, selected, focus, row, column);
                putClientProperty("html.disable", true);
                setBorder(new EmptyBorder(0, 13, 0, 13));
                setHorizontalAlignment(column == 0 ? (text.rtl ? RIGHT : LEFT) : CENTER);
                setText(column == 1 ? ltr(String.valueOf(value)) : String.valueOf(value));
                setFont(column == 0 ? controlFont(compact ? 15 : 19) : font(compact ? 14 : 17, false));
                setIcon(column == 0 ? navigationIcon(Page.PACK) : null);
                setIconTextGap(10);
                if (!selected) {
                    var item = rows.get(source.convertRowIndexToModel(row));
                    setForeground(column == 2 && item.condition() != null
                        ? item.condition() == PackService.Condition.VERIFIED ? GREEN : GOLD : TEXT);
                }
                if (focus) setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(GOLD), getBorder()));
                return this;
            }
        };
        table.setDefaultRenderer(Object.class, renderer);
        var header = table.getTableHeader();
        header.setBackground(RAIL);
        header.setForeground(TEXT);
        header.setFont(controlFont(15));
        header.setReorderingAllowed(false);
        header.setPreferredSize(new Dimension(0, compact ? 44 : 52));
        header.setDefaultRenderer(new javax.swing.table.DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable source, Object value,
                    boolean selected, boolean focus, int row, int column) {
                super.getTableCellRendererComponent(source, value, selected, focus, row, column);
                setBackground(RAIL);
                setForeground(TEXT);
                setFont(controlFont(15));
                setHorizontalAlignment(column == 0 ? (text.rtl ? RIGHT : LEFT) : CENTER);
                setBorder(new EmptyBorder(0, 13, 0, 13));
                return this;
            }
        });
        table.getColumnModel().getColumn(0).setPreferredWidth(490);
        table.getColumnModel().getColumn(1).setPreferredWidth(140);
        table.getColumnModel().getColumn(2).setPreferredWidth(180);
        var sorter = new javax.swing.table.TableRowSorter<>(model);
        table.setRowSorter(sorter);
        var empty = label(t("noMatchingMods"), 14, false, MUTED);
        Runnable filter = () -> {
            search = searchField.getText();
            String query = search.strip().toLowerCase(java.util.Locale.ROOT);
            sorter.setRowFilter(new javax.swing.RowFilter<javax.swing.table.DefaultTableModel, Integer>() {
                @Override public boolean include(Entry<? extends javax.swing.table.DefaultTableModel, ? extends Integer> row) {
                    var entry = rows.get(row.getIdentifier());
                    return (row.getStringValue(0) + " " + row.getStringValue(1) + " " + entry.id() + " " + entry.file())
                        .toLowerCase(java.util.Locale.ROOT).contains(query);
                }
            });
            empty.setVisible(table.getRowCount() == 0);
        };
        searchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent event) { filter.run(); }
            public void removeUpdate(javax.swing.event.DocumentEvent event) { filter.run(); }
            public void changedUpdate(javax.swing.event.DocumentEvent event) { filter.run(); }
        });
        var list = scroll(table);
        list.setBorder(new RoundedSurface(11, RAIL, EDGE, 4));
        list.setCorner(JScrollPane.UPPER_TRAILING_CORNER, panel(new BorderLayout(), RAIL));
        list.setBackground(RAIL);
        list.setPreferredSize(new Dimension(500, Math.clamp(frame.getHeight() - 430, 210, 505)));
        body.add(list);
        body.add(empty);
        filter.run();
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
        joinStatus = label("", 13, true, MUTED);
        active = label("", 12, false, MUTED);
        estimate = label("", 12, false, MUTED);
        for (var row : new JLabel[]{status, joinStatus, file, counts, stats, active}) card.add(row);
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
        retry = button(t("retry"), false);
        retry.addActionListener(event -> { if (lastAction != null) perform(lastAction); });
        toolbar.add(retry);
        card.add(toolbar);

        detailPanel = stack(PANEL, 7);
        detailPanel.add(label(t("technical"), 13, true, MUTED));
        details = technical(new JTextArea(7, 40));
        details.setBackground(BG);
        details.setForeground(TEXT);
        details.setFont(text.rtl ? font(13, false) : new Font(Font.MONOSPACED, Font.PLAIN, 12));
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
        settingsPanel = new LauncherSettings(this, draft == null ? LauncherSettings.Draft.from(settings) : draft);
        return settingsPanel.component();
    }

    private JPanel helpPage() {
        var body = panel(new BorderLayout(0, 15), BG);
        var header = stack(BG, 14);
        header.add(utilityTitle("helpTitle", compact ? 33 : 40));
        var rule = new JSeparator();
        rule.setForeground(EDGE);
        header.add(rule);
        body.add(header, BorderLayout.PAGE_START);
        var layout = panel(new BorderLayout(18, 0), BG);
        var menu = stack(BG, 4);
        String[] topics = {"guideFolder", "guideStart", "guideActions", "guideFix", "guideLogs"};
        var article = stack(BG, 15);
        article.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(
            0, text.rtl ? 0 : 1, 0, text.rtl ? 1 : 0, EDGE), new EmptyBorder(10, 20, 23, 20)));
        String[] icons = {"folder", "play", "server", "download", "document"};
        for (int i = 0; i < topics.length; i++) {
            int topic = i;
            String navLabel = t(i == 2 ? "guidePlayNav" : topics[i]);
            var button = button(navLabel, false);
            button.setHorizontalAlignment(SwingConstants.LEADING);
            button.setIcon(new UtilityIcon(icons[i]));
            button.setIconTextGap(10);
            button.setFont(controlFont(compact ? 13 : 17));
            button.setBackground(i == helpTopic ? new Color(0x47311C) : BG);
            if (i == helpTopic) {
                button.putClientProperty("outlined", true);
                button.putClientProperty("selected", true);
                activeHelpTopic = button;
            }
            button.setToolTipText(t(topics[i]));
            button.addActionListener(event -> {
                helpTopic = topic;
                render();
                focusHelpTopic();
            });
            menu.add(button);
            if (i < topics.length - 1) menu.add(new JSeparator());
        }
        String topic = topics[helpTopic];
        article.add(label(t(topic), compact ? 24 : 34, true, TEXT));
        var description = wrapped(t(topic + "Body"));
        description.setFont(font(compact ? 15 : 19, false));
        description.setBackground(BG);
        article.add(description);
        if (helpTopic == 0) {
            article.add(label(t("guideDefault"), 14, true, MUTED));
            article.add(copyPath("%LOCALAPPDATA%\\Gunnertimes\\profiles\\Gunnertimes"));
            article.add(label(t("guideExisting"), 14, true, MUTED));
            article.add(copyPath("%APPDATA%\\.minecraft\\versions\\Goharian New Server"));
            var divider = new JSeparator();
            divider.setForeground(EDGE);
            article.add(divider);
            article.add(label(t("guideLogs"), 20, true, TEXT));
            article.add(wrapped(t("logsHint")));
        }
        if (helpTopic == 0 || helpTopic == 4) {
            var row = panel(new FlowLayout(FlowLayout.LEADING, 0, 0), PANEL);
            var logs = button(t("openLogs"), false);
            logs.putClientProperty("outlined", true);
            logs.setIcon(new UtilityIcon("folder"));
            logs.addActionListener(event -> {
                try { open(GunnertimesLauncher.profilePath(settings.profile()).resolve("logs")); }
                catch (GunnertimesLauncher.InputError failure) { notifyUser(failure.key, "", true); }
            });
            row.add(logs);
            article.add(row);
        }
        var topicsViewport = new PageViewport();
        topicsViewport.add(menu, BorderLayout.PAGE_START);
        var articleViewport = new PageViewport();
        articleViewport.add(article, BorderLayout.PAGE_START);
        var topicScroll = scroll(topicsViewport);
        topicScroll.setPreferredSize(new Dimension(compact ? 192 : 318, 0));
        topicScroll.getAccessibleContext().setAccessibleName(t("helpTopics"));
        var articleScroll = scroll(articleViewport);
        articleScroll.getAccessibleContext().setAccessibleName(t(topic));
        layout.add(topicScroll, BorderLayout.LINE_START);
        layout.add(articleScroll, BorderLayout.CENTER);
        body.add(layout, BorderLayout.CENTER);
        return body;
    }

    private JPanel copyPath(String path) {
        var row = panel(new BorderLayout(8, 0), PANEL);
        var value = field(path, 24);
        value.setEditable(false);
        value.setText(path);
        value.setCaretPosition(0);
        value.setToolTipText(path);
        value.getAccessibleContext().setAccessibleName(path);
        var copy = button(t("copyPath"), false);
        copy.putClientProperty("outlined", true);
        copy.addActionListener(event -> {
            copyToClipboard.accept(path);
            copy.setText(t("copied"));
        });
        row.add(value, BorderLayout.CENTER);
        row.add(copy, BorderLayout.LINE_END);
        return row;
    }

    private void changeLanguage(String language) {
        if (settings.language().equals(language)) return;
        var translated = new GunnertimesLauncher.Preferences(settings.username(), settings.profile(),
            language, settings.ramGiB(), settings.options());
        try {
            GunnertimesLauncher.save(translated);
            settings = translated;
            text = new LauncherText(language);
            render();
            if (page == Page.HELP) focusHelpTopic();
            else languageButton.requestFocusInWindow();
        } catch (IOException failure) {
            notifyUser("settingsFailure", failure.toString(), true);
        }
    }

    void language(String value) { changeLanguage(value); }

    private void focusHelpTopic() {
        SwingUtilities.invokeLater(() -> {
            if (page == Page.HELP && activeHelpTopic != null) {
                activeHelpTopic.scrollRectToVisible(new Rectangle(activeHelpTopic.getSize()));
                activeHelpTopic.requestFocusInWindow();
            }
        });
    }
    boolean working() { return busy; }
    boolean isPersian() { return text.rtl; }
    JFrame frame() { return frame; }
    GunnertimesLauncher.Preferences settings() { return settings; }
    String installedVersion() { return installed == null ? t("none") : installed.version(); }
    String availableVersion() {
        return catalog.available() == null ? t("unknown") : catalog.available().version();
    }
    String lastCheck() {
        if (catalog.checked() == null) return t("unknown");
        return java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.MEDIUM)
            .withLocale(text.locale).withZone(java.time.ZoneId.systemDefault()).format(catalog.checked());
    }
    boolean checking() { return checking; }
    void checkNow() { lookup(true); probeServer(); }

    void checkLauncher(boolean manual) {
        if (checkingLauncher || updatingLauncher) return;
        if (manual && launcherRelease != null) {
            offerLauncherUpdate();
            return;
        }
        checkingLauncher = true;
        new SwingWorker<LauncherUpdates.Release, Void>() {
            protected LauncherUpdates.Release doInBackground() throws Exception { return LauncherUpdates.check(); }
            protected void done() {
                checkingLauncher = false;
                if (!frame.isDisplayable()) return;
                try {
                    launcherRelease = get();
                    if (launcherRelease != null && !busy) offerLauncherUpdate();
                    else if (manual && launcherRelease == null)
                        JOptionPane.showMessageDialog(frame, t("launcherCurrent"), t("launcherUpdate"), JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception failure) {
                    if (manual) detailDialog(t("launcherCheckFailed"), failure.toString());
                }
            }
        }.execute();
    }

    private void offerLauncherUpdate() {
        if (updatingLauncher || busy || launcherRelease == null) return;
        try {
            var asset = launcherRelease.artifact(LauncherUpdates.type());
            var info = stack(PANEL, 8);
            info.add(label(t("launcherVersionAvailable") + " " + launcherRelease.version() + "  ·  " + bytes(asset.size()),
                17, true, TEXT));
            info.add(dialogText(launcherRelease.notes()));
            text.orient(info);
            String[] actions = {t("updateRestart"), t("updateLater")};
            int choice = JOptionPane.showOptionDialog(frame, info, t("launcherUpdate"), JOptionPane.DEFAULT_OPTION,
                JOptionPane.INFORMATION_MESSAGE, null, actions, actions[1]);
            if (choice == 0) applyLauncherUpdate(asset);
        } catch (IOException failure) {
            detailDialog(t("launcherCheckFailed"), failure.toString());
        }
    }

    private void applyLauncherUpdate(LauncherUpdates.Artifact asset) {
        updatingLauncher = true;
        var progress = new JProgressBar(0, 1000);
        progress.setIndeterminate(true);
        progress.setStringPainted(true);
        progress.setString(t("launcherDownloading"));
        var dialog = new JDialog(frame, t("launcherUpdate"), Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        var cancel = button(t("cancel"), false);
        var body = stack(PANEL, 12);
        body.setBorder(new EmptyBorder(20, 20, 20, 20));
        body.add(progress);
        body.add(cancel);
        dialog.setContentPane(body);
        dialog.setSize(420, 144);
        dialog.setLocationRelativeTo(frame);
        var worker = new SwingWorker<Path, Updater.Progress>() {
            protected Path doInBackground() throws Exception { return LauncherUpdates.download(asset, this::publish); }
            protected void process(java.util.List<Updater.Progress> events) {
                var change = events.getLast();
                if (change.status() == Updater.Status.TRANSFER && change.count() >= 0) {
                    progress.setIndeterminate(false);
                    progress.setValue((int) Math.min(1000, 1000 * change.count() / asset.size()));
                    progress.setString(bytes(change.count()) + " / " + bytes(asset.size()));
                }
            }
            protected void done() {
                dialog.dispose();
                updatingLauncher = false;
                if (isCancelled()) return;
                Path payload = null;
                try {
                    payload = get();
                    LauncherUpdates.handoff(asset, payload, text.rtl);
                    frame.dispose();
                    System.exit(0);
                } catch (Exception failure) {
                    if (payload != null) try { Files.deleteIfExists(payload); } catch (IOException ignored) {}
                    detailDialog(t("launcherUpdateFailed"), failure.toString());
                }
            }
        };
        cancel.addActionListener(event -> worker.cancel(true));
        worker.execute();
        dialog.setVisible(true);
    }
    String checkError() { return catalogError; }

    void acceptSettings(GunnertimesLauncher.Preferences value) throws IOException {
        GunnertimesLauncher.save(value);
        boolean changedProfile = !settings.profile().equals(value.profile());
        settings = value;
        if (changedProfile) {
            installed = null;
            inspection = null;
            catalogReady = false;
            profileError = "";
            progressed = false;
        }
        draft = LauncherSettings.Draft.from(value);
        settingsPanel = null;
        notice = "saved";
        noticeDetails = "";
        noticeError = false;
        render();
        if (changedProfile) lookup(false);
    }

    void cancelSettings() {
        settingsPanel = null;
        draft = null;
        notice = "";
        noticeDetails = "";
        render();
    }

    String messageKey() { return notice; }
    String noticeDetails() { return noticeDetails; }
    boolean noticeError() { return noticeError; }

    private ServerStatus.Compatibility compatibility() {
        return ServerStatus.compatibility(catalog, installed == null ? null : installed.version());
    }

    private boolean canUpdate() {
        return installed != null && catalog.available() != null && !catalog.stale()
            && PackService.newer(installed.version(), catalog.available().version());
    }

    private GunnertimesLauncher.Action mainAction() {
        if (installed == null) return GunnertimesLauncher.Action.INSTALL;
        return inspection != null && !inspection.healthy()
            ? GunnertimesLauncher.Action.REPAIR : GunnertimesLauncher.Action.PLAY;
    }

    private static String actionKey(GunnertimesLauncher.Action action) {
        return switch (action) {
            case PLAY -> "play";
            case JOIN -> "joinServer";
            case INSTALL -> "installPack";
            case UPDATE -> "updatePack";
            case REPAIR -> "repairPack";
        };
    }

    private record Lookup(PackService.Release installed, PackService.Inspection inspection, PackService.Check check,
                          String requestError, String localError) {}

    private void acceptLookup(Lookup result) {
        catalogReady = true;
        installed = result.installed();
        inspection = result.inspection();
        catalog = result.check();
        profileError = result.localError() == null ? "" : result.localError();
    }

    private void lookup(boolean online) {
        if (checking) return;
        checking = true;
        catalogError = "";
        var previous = catalog;
        var target = settings.profile();
        if (frame.isShowing()) render();
        new SwingWorker<Lookup, Void>() {
            @Override protected Lookup doInBackground() throws Exception {
                var service = new PackService(GunnertimesLauncher.HOME);
                var check = previous.available() == null ? service.cached() : previous;
                String error = "", localError = "";
                PackService.Release pinned = null;
                PackService.Inspection result = null;
                try {
                    Path profile = GunnertimesLauncher.profilePath(target);
                    pinned = service.installed(profile);
                    if (pinned != null) result = service.inspect(profile, pinned, ignored -> {});
                } catch (Exception failure) {
                    localError = failure.getMessage();
                }
                if (online) {
                    var local = new Lookup(pinned, result, check, "", localError);
                    SwingUtilities.invokeLater(() -> {
                        if (!target.equals(settings.profile()) || busy || !frame.isDisplayable()) return;
                        acceptLookup(local);
                        render();
                    });
                    try { check = service.check(); }
                    catch (Exception failure) {
                        check = new PackService.Check(check.available(), check.checked(), true, true, check.notes());
                        error = failure.getMessage();
                    }
                }
                return new Lookup(pinned, result, check, error, localError);
            }
            @Override protected void done() {
                checking = false;
                try {
                    var result = get();
                    catalog = result.check();
                    catalogError = result.requestError() == null ? "" : result.requestError();
                    if (target.equals(settings.profile())) {
                        if (!busy) acceptLookup(result);
                    } else {
                        lookup(false);
                        return;
                    }
                } catch (Exception failure) {
                    catalogError = failure.toString();
                }
                if (frame.isDisplayable() && !busy) render();
            }
        }.execute();
    }

    private void probeServer() {
        serverChecking = true;
        new SwingWorker<ServerStatus.Result, Void>() {
            @Override protected ServerStatus.Result doInBackground() { return ServerStatus.probe(); }
            @Override protected void done() {
                try { server = get(); }
                catch (Exception failure) { server = ServerStatus.Result.unknown(); }
                serverChecking = false;
                if (frame.isDisplayable() && page == Page.PLAY && !busy) render();
            }
        }.execute();
    }

    private void verify() {
        if (installed == null || busy) return;
        lookup(false);
    }

    private void perform(GunnertimesLauncher.Action action) {
        if (busy) return;
        var selected = settings;
        try {
            boolean startsGame = action == GunnertimesLauncher.Action.PLAY || action == GunnertimesLauncher.Action.JOIN;
            GunnertimesLauncher.validated(selected.username(), selected.profile(), selected.language(),
                Integer.toString(selected.ramGiB()), selected.options(), startsGame);
        } catch (GunnertimesLauncher.InputError failure) {
            page = Page.SETTINGS;
            notice = failure.key;
            noticeError = true;
            render();
            if (settingsPanel != null) settingsPanel.focusInvalidInput(failure.key);
            return;
        }
        PackService.Release selection = null;
        Map<String, String> repairs = Map.of();
        if (action == GunnertimesLauncher.Action.INSTALL || action == GunnertimesLauncher.Action.UPDATE) {
            if (catalog.available() == null || action == GunnertimesLauncher.Action.UPDATE && !canUpdate()) return;
            selection = catalog.available();
            String before = installed == null ? t("none") : installed.version();
            if (!confirm(t(actionKey(action)), t("installedVersion") + "  " + before + "\n"
                    + t("targetVersion") + "  " + selection.version())) return;
        } else if (action == GunnertimesLauncher.Action.REPAIR) {
            if (installed == null || inspection == null) return;
            selection = installed;
            var problems = inspection.problems();
            if (problems.isEmpty()) return;
            var preview = problems.stream().map(item -> t("condition." + item.condition().name()) + "  " + item.location())
                .collect(java.util.stream.Collectors.joining("\n"));
            if (problems.stream().anyMatch(item -> item.condition() == PackService.Condition.CONFLICT)) {
                detailDialog(t("failure.conflict"), preview);
                return;
            }
            if (!confirm(t("repairPack"), t("repairPreview") + "\n\n" + preview)) return;
            repairs = inspection.approvedRepairs();
        }
        start(selected, new GunnertimesLauncher.Request(action, selection, catalog, repairs));
    }

    private void start(GunnertimesLauncher.Preferences selected, GunnertimesLauncher.Request request) {
        session = new LauncherSession();
        lastAction = request.action();
        progressed = true;
        busy = true;
        expanded = false;
        page = Page.PLAY;
        var runningSession = session;
        render();
        SwingUtilities.invokeLater(() -> pageScroll.getVerticalScrollBar().setValue(
            pageScroll.getVerticalScrollBar().getMaximum()));
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception {
                GunnertimesLauncher.run(selected, request, runningSession::accept);
                return null;
            }
            @Override protected void done() {
                busy = false;
                if (minimized) {
                    frame.setState(Frame.NORMAL);
                    minimized = false;
                }
                try { get(); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
                catch (ExecutionException failure) { expanded = true; }
                render();
                if (page == Page.PLAY) SwingUtilities.invokeLater(() -> pageScroll.getVerticalScrollBar()
                    .setValue(pageScroll.getVerticalScrollBar().getMaximum()));
                lookup(false);
            }
        }.execute();
    }

    boolean confirm(String title, String description) {
        var contents = dialogText(description);
        Object[] choices = {t("continue"), t("cancel")};
        var pane = new JOptionPane(contents, JOptionPane.QUESTION_MESSAGE, JOptionPane.OK_CANCEL_OPTION,
            null, choices, choices[1]);
        var dialog = pane.createDialog(frame, title);
        text.orient(dialog);
        dialog.pack();
        dialog.setLocationRelativeTo(frame);
        dialog.setVisible(true);
        dialog.dispose();
        return choices[0].equals(pane.getValue());
    }

    void detailDialog(String title, String description) {
        var pane = new JOptionPane(dialogText(description), JOptionPane.INFORMATION_MESSAGE);
        var dialog = pane.createDialog(frame, title);
        text.orient(dialog);
        dialog.pack();
        dialog.setLocationRelativeTo(frame);
        dialog.setVisible(true);
        dialog.dispose();
    }

    private JScrollPane dialogText(String message) {
        var area = new JTextArea(message, Math.min(10, Math.max(2, message.split("\\R").length + 1)), 46);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFont(font(14, false));
        area.setForeground(TEXT);
        area.setBackground(PANEL);
        area.setBorder(new EmptyBorder(10, 10, 10, 10));
        text.orient(area);
        return scroll(area);
    }

    private void refresh() {
        var snapshot = session.snapshot();
        var event = snapshot.current();
        boolean failed = event != null && event.status() == Updater.Status.FAILED;
        if (busy) {
            primary.setText(t("running".equals(snapshot.steps().get(Updater.Step.RUN)) ? "inGame" : "preparing"));
            primary.setEnabled(false);
        }
        String serverResult = snapshot.join();
        joinStatus.setText(serverResult.isEmpty() ? "" : t("join." + serverResult));
        joinStatus.setVisible(!serverResult.isEmpty());
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
        transfer.setIndeterminate(first != null && !known && !LaunchOptions.reduceMotion(settings.options()));
        if (!known) transfer.setValue(0);
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
        openLog.setText(t(snapshot.steps().containsKey(Updater.Step.RUN) ? "openLog" : "openLauncherLog"));
        retry.setVisible(!busy && failed && lastAction != null);
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
        if (event.group().equals("join")) return t("join." + LauncherSession.joinState(event.status()));
        if (event.status() == Updater.Status.DONE && event.step() == Updater.Step.INSTALL) return t("installDone");
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

    void open(Path path) {
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
        if (page == Page.SETTINGS) render();
        else JOptionPane.showMessageDialog(frame, t(key) + (detail.isEmpty() ? "" : "\n" + detail),
            "Gunnertimes", error ? JOptionPane.ERROR_MESSAGE : JOptionPane.INFORMATION_MESSAGE);
    }

    String t(String key) { return text.get(key); }
    private String number(long value) { return NumberFormat.getIntegerInstance(text.locale).format(value); }

    String bytes(long value) {
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

    Font font(int size, boolean bold) {
        return text.rtl ? (bold ? PersianTypography.heading(size) : PersianTypography.body(size))
            : new Font("Segoe UI", bold ? Font.BOLD : Font.PLAIN, size);
    }

    Font controlFont(int size) {
        return text.rtl ? PersianTypography.control(size) : font(size, true);
    }

    JPanel utilityTitle(String key, int size) {
        var row = panel(new BorderLayout(11, 0), null);
        var accent = panel(new BorderLayout(), GOLD);
        accent.setPreferredSize(new Dimension(4, 1));
        row.add(accent, BorderLayout.LINE_START);
        row.add(label(t(key), size, true, TEXT), BorderLayout.CENTER);
        return row;
    }

    static JPanel outline(JPanel surface, int padding) {
        surface.setOpaque(false);
        surface.setBorder(new RoundedSurface(12, surface.getBackground(), EDGE, padding));
        return surface;
    }

    JLabel label(String title, int size, boolean bold, Color color) {
        var label = new JLabel(title, SwingConstants.LEADING);
        label.putClientProperty("html.disable", true);
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
                    case PACK -> {
                        g.drawPolygon(new int[]{8, 14, 14, 8, 2, 2}, new int[]{1, 4, 11, 15, 11, 4}, 6);
                        g.drawLine(2, 4, 8, 8);
                        g.drawLine(14, 4, 8, 8);
                        g.drawLine(8, 8, 8, 15);
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

    JButton button(String title, boolean accent) {
        var button = new JButton(title) {
            @Override protected void paintComponent(Graphics graphics) {
                var g = (Graphics2D) graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color fill = isEnabled() ? getBackground() : EDGE;
                if (getModel().isPressed()) fill = fill.darker();
                else if (getModel().isRollover()) fill = fill.brighter();
                g.setColor(fill);
                g.fillRoundRect(0, 0, getWidth(), getHeight(), 11, 11);
                if (Boolean.TRUE.equals(getClientProperty("outlined"))) {
                    boolean selected = Boolean.TRUE.equals(getClientProperty("selected"));
                    g.setColor(!isEnabled() ? EDGE : selected ? GOLD : getClientProperty("actionColor") instanceof Color color
                        ? color : new Color(0x727078));
                    g.setStroke(new BasicStroke(1.7f));
                    g.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 11, 11);
                }
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
        button.setFont(controlFont(14));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return button;
    }

    void downloadStyle(JButton action) {
        action.putClientProperty("actionColor", new Color(0x82BAFF));
        action.putClientProperty("outlined", true);
        action.setBackground(new Color(0x1B3654));
    }

    void removalStyle(JButton action) {
        action.putClientProperty("actionColor", new Color(0xF29D9D));
        action.putClientProperty("outlined", true);
        action.setBackground(new Color(0x47272D));
    }

    JTextField field(String value, int columns) {
        var input = technical(new JTextField(value, columns));
        input.setFont(font(14, false));
        input.setForeground(TEXT);
        input.setCaretColor(GOLD);
        input.setSelectionColor(new Color(0x65523D));
        input.setBackground(BG);
        input.setOpaque(false);
        input.setBorder(new RoundedSurface(9, BG, EDGE, 8));
        return input;
    }

    JPanel labeled(String title, JComponent input, JComponent focus) {
        var row = panel(new BorderLayout(0, 5), PANEL);
        var caption = label(title, 14, true, TEXT);
        caption.setLabelFor(focus);
        row.add(caption, BorderLayout.PAGE_START);
        row.add(input, BorderLayout.CENTER);
        return row;
    }

    JTextArea wrapped(String value) {
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

    static <T extends JComponent> T technical(T component) {
        component.putClientProperty("technical", true);
        return component;
    }

    static JPanel panel(LayoutManager layout, Color color) {
        var panel = new JPanel(layout);
        if (color == null) panel.setOpaque(false);
        else panel.setBackground(color);
        return panel;
    }

    static JPanel stack(Color color, int gap) { return panel(new Vertical(gap), color); }

    static JPanel card(int gap) {
        return outline(stack(PANEL, gap), 15);
    }

    private static final class RoundedSurface extends javax.swing.border.AbstractBorder {
        private final int radius, padding;
        private final Color fill, stroke;

        RoundedSurface(int radius, Color fill, Color stroke, int padding) {
            this.radius = radius;
            this.fill = fill;
            this.stroke = stroke;
            this.padding = padding;
        }

        @Override public Insets getBorderInsets(Component component) {
            return new Insets(padding, padding, padding, padding);
        }

        @Override public void paintBorder(Component component, Graphics graphics, int x, int y, int width, int height) {
            var g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(fill);
            if (component instanceof JTextField) {
                var area = new java.awt.geom.Area(new java.awt.geom.RoundRectangle2D.Double(x, y, width, height, radius, radius));
                area.subtract(new java.awt.geom.Area(new Rectangle(x + padding, y + padding,
                    Math.max(0, width - 2 * padding), Math.max(0, height - 2 * padding))));
                g.fill(area);
            } else {
                g.fillRoundRect(x, y, width, height, radius, radius);
            }
            g.setColor(component.isFocusOwner() ? GOLD : stroke);
            g.drawRoundRect(x, y, width - 1, height - 1, radius, radius);
            g.dispose();
        }
    }

    static JScrollPane scroll(JComponent value) {
        var pane = new JScrollPane(value, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
            JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        pane.setBorder(BorderFactory.createEmptyBorder());
        pane.getViewport().setBackground(BG);
        var bar = pane.getVerticalScrollBar();
        bar.setPreferredSize(new Dimension(10, 0));
        bar.setUI(new javax.swing.plaf.basic.BasicScrollBarUI() {
            @Override protected JButton createDecreaseButton(int orientation) { return scrollEnd(); }
            @Override protected JButton createIncreaseButton(int orientation) { return scrollEnd(); }
            @Override protected void paintTrack(Graphics g, JComponent c, Rectangle bounds) {
                g.setColor(RAIL);
                g.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
            }
            @Override protected void paintThumb(Graphics g, JComponent c, Rectangle bounds) {
                if (bounds.isEmpty() || !c.isEnabled()) return;
                g.setColor(isThumbRollover() ? new Color(0x73757B) : new Color(0x51545D));
                g.fillRoundRect(bounds.x + 2, bounds.y + 2, bounds.width - 4, bounds.height - 4, 7, 7);
            }
            private JButton scrollEnd() {
                JButton end = new JButton();
                Dimension empty = new Dimension(0, 0);
                end.setPreferredSize(empty);
                end.setMinimumSize(empty);
                end.setMaximumSize(empty);
                end.setFocusable(false);
                return end;
            }
        });
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
                int offset = Math.min((width - w) / 2, w / 11);
                g.drawImage(art, !rtl ? (w + width) / 2 + offset : (w - width) / 2 - offset,
                    (h - height) / 2, !rtl ? -width : width, height, null);
            }
            g.setPaint(new GradientPaint(rtl ? w : 0, 0, new Color(0x99141B23, true),
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
