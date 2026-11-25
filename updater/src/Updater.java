import com.google.gson.Gson;
import com.google.gson.JsonParser;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.*;
import java.util.zip.ZipFile;
import javax.swing.*;

public final class Updater {
    static final Gson JSON = new Gson();
    static final String STATE = ".gunnertimes";
    static final HttpClient HTTP = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();
    record Entry(String path, String sha512, String url, String modId) {}
    record Pack(int schema, String version, List<Entry> files) {}
    record Config(String manifest, String publicKey) {}
    record Change(Path target, Path staged, Path backup, String originalHash) {}

    public static void main(String[] args) {
        try {
            if (args.length == 0) {
                SwingUtilities.invokeLater(Updater::showInstaller);
                return;
            }
            if (args.length == 2 && args[0].equals("sync")) {
                Path root = Path.of(args[1]);
                Config config = configuration(root);
                install(root, config, manifest(config));
            } else if (args.length == 3 && args[0].equals("sync")) {
                Path root = Path.of(args[1]);
                Config config = JSON.fromJson(Files.readString(Path.of(args[2])), Config.class);
                install(root, config, manifest(config));
            } else if (args.length == 3 && args[0].equals("after-exit")) {
                ProcessHandle.of(Long.parseLong(args[2])).ifPresent(process -> process.onExit().join());
                Path root = Path.of(args[1]);
                Config config = configuration(root);
                install(root, config, manifest(config));
                JOptionPane.showMessageDialog(null, "Gunnertimes updated. Reopen Minecraft.");
            } else {
                throw new IOException("Usage: java -jar Gunnertimes-Updater.jar sync PROFILE [CHANNEL_JSON]");
            }
        } catch (Exception failure) {
            System.err.println(failure.getMessage());
            if (args.length == 0 || args[0].equals("after-exit"))
                JOptionPane.showMessageDialog(null, failure.getMessage(), "Gunnertimes Updater", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }
    }

    static void showInstaller() {
        JFrame window = new JFrame("Gunnertimes Updater");
        window.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        JPanel screen = new JPanel();
        screen.setBorder(BorderFactory.createEmptyBorder(22, 24, 20, 24));
        screen.setLayout(new BoxLayout(screen, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("Install or update Gunnertimes");
        title.setFont(title.getFont().deriveFont(java.awt.Font.BOLD, 23f));
        title.setAlignmentX(0);
        screen.add(title);
        screen.add(Box.createVerticalStrut(8));
        JLabel instruction = new JLabel("Close Minecraft, then select the game folder used by your Fabric 26.3 profile.");
        instruction.setAlignmentX(0);
        screen.add(instruction);
        screen.add(Box.createVerticalStrut(16));
        JTextField profile = new JTextField(42);
        JTextField channel = new JTextField(42);
        try {
            Path jar = Path.of(Updater.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path adjacent = jar.resolveSibling("channel.json");
            if (Files.isRegularFile(adjacent)) channel.setText(adjacent.toString());
        } catch (Exception ignored) { }
        addPicker(screen, "Game folder", profile, JFileChooser.DIRECTORIES_ONLY);
        if (Updater.class.getResource("/gunnertimes-channel.json") == null) {
            addPicker(screen, "Release channel", channel, JFileChooser.FILES_ONLY);
        } else {
            channel.setText("");
        }
        JButton install = new JButton("Install / Update");
        install.setAlignmentX(0);
        JProgressBar progress = new JProgressBar();
        progress.setVisible(false);
        progress.setIndeterminate(true);
        progress.setAlignmentX(0);
        JTextArea status = new JTextArea("Choose the game folder containing the mods directory.", 3, 42);
        status.setEditable(false);
        status.setLineWrap(true);
        status.setWrapStyleWord(true);
        status.setOpaque(false);
        JScrollPane feedback = new JScrollPane(status);
        feedback.setAlignmentX(0);
        feedback.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        screen.add(install);
        screen.add(progress);
        screen.add(feedback);
        install.addActionListener(event -> {
            String folder = profile.getText().trim();
            String channelFile = channel.getText().trim();
            if (folder.isEmpty()) {
                status.setText("Choose a game folder first.");
                return;
            }
            install.setEnabled(false);
            progress.setVisible(true);
            status.setText("Checking the release signature and installing verified files...");
            new SwingWorker<String, Void>() {
                @Override protected String doInBackground() throws Exception {
                    Path root = Path.of(folder);
                    if (root.getFileName() != null && root.getFileName().toString().equalsIgnoreCase("mods"))
                        root = root.toAbsolutePath().getParent();
                    Config config = channelFile.isEmpty() ? configuration(root)
                        : JSON.fromJson(Files.readString(Path.of(channelFile)), Config.class);
                    Pack pack = manifest(config);
                    install(root, config, pack);
                    return "Installed Gunnertimes " + pack.version + ". Open this folder with your Fabric 26.3 launcher profile.";
                }
                @Override protected void done() {
                    try {
                        status.setText(get());
                    } catch (Exception failure) {
                        Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
                        status.setText("Installation stopped. " + cause.getMessage());
                    } finally {
                        progress.setVisible(false);
                        install.setEnabled(true);
                    }
                }
            }.execute();
        });
        window.setContentPane(screen);
        window.pack();
        window.setLocationRelativeTo(null);
        window.setVisible(true);
    }

    static void addPicker(JPanel screen, String name, JTextField field, int mode) {
        JLabel label = new JLabel(name);
        label.setAlignmentX(0);
        screen.add(label);
        JPanel row = new JPanel(new java.awt.BorderLayout(8, 0));
        row.setAlignmentX(0);
        row.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, field.getPreferredSize().height + 8));
        JButton browse = new JButton("Browse…");
        browse.addActionListener(event -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileSelectionMode(mode);
            chooser.setDialogTitle(name);
            if (chooser.showOpenDialog(screen) == JFileChooser.APPROVE_OPTION)
                field.setText(chooser.getSelectedFile().getAbsolutePath());
        });
        row.add(field, java.awt.BorderLayout.CENTER);
        row.add(browse, java.awt.BorderLayout.EAST);
        screen.add(row);
        screen.add(Box.createVerticalStrut(12));
    }

    static Config configuration(Path root) throws IOException {
        Path file = root.resolve(STATE).resolve("channel.json");
        if (Files.isRegularFile(file)) {
            Config saved = JSON.fromJson(Files.readString(file), Config.class);
            if (saved.manifest.startsWith("https:")) return saved;
            if (Updater.class.getResource("/gunnertimes-channel.json") == null) return saved;
        }
        try (InputStream resource = Updater.class.getResourceAsStream("/gunnertimes-channel.json")) {
            if (resource == null) throw new IOException("This build has no default release channel.");
            return JSON.fromJson(new InputStreamReader(resource, java.nio.charset.StandardCharsets.UTF_8), Config.class);
        }




    }

    static InputStream open(URI uri, boolean local) throws Exception {
        if (local && "file".equals(uri.getScheme())) return Files.newInputStream(Path.of(uri));
        if ("bundle".equals(uri.getScheme())) {
            String path = uri.getPath();
            if (path == null || !path.matches("/(manifest[.]json([.]sig)?|[a-f0-9]{128})"))
                throw new IOException("Invalid bundled resource.");
            InputStream data = Updater.class.getResourceAsStream("/gunnertimes-payload" + path);
            if (data == null) throw new IOException("Missing bundled resource.");
            return data;
        }
        if (!"https".equals(uri.getScheme())) throw new IOException("Downloads require HTTPS.");
        var response = HTTP.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(45)).build(),
            HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200 || !"https".equals(response.uri().getScheme())) {
            response.body().close();
            throw new IOException("Download failed: HTTP " + response.statusCode());
        }
        return response.body();
    }

    enum Step { CHECK, DOWNLOAD, INSTALL, START, RUN }
    enum Status { BEGIN, COUNT, REUSED, OFFLINE, TRANSFER, RETRY, VERIFY, VERIFIED, SKIPPED, DONE, FAILED }

    record Progress(Step step, Status status, String group, String file, long count, long total, String detail) {
        static Progress of(Step step, Status status, String group, String file) {
            return new Progress(step, status, group, file, 0, -1, "");
        }

        static Progress count(Step step, String group, String file, long count, long total) {
            return new Progress(step, Status.COUNT, group, file, count, total, "");
        }

        static Progress failed(Step step, String group, String file, Throwable failure) {
            return new Progress(step, Status.FAILED, group, file, 0, -1, failure.toString());
        }
    }

    @FunctionalInterface
    interface ProgressSink {
        void report(Progress progress);
    }

    private static final ProgressSink SILENT = ignored -> {};

    static void downloadFile(URI uri, boolean local, Path target) throws Exception {
        downloadFile(uri, local, target, "files", target.getFileName().toString(), SILENT);
    }

    static void downloadFile(URI uri, boolean local, Path target, String group, String name,
            ProgressSink progress) throws Exception {
        if ("bundle".equals(uri.getScheme()) || local && "file".equals(uri.getScheme())) {
            try (InputStream input = open(uri, local)) {
                Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return;
        }
        if (!"https".equals(uri.getScheme())) throw new IOException("Downloads require HTTPS.");
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(45)).build();
        for (int attempt = 1; ; attempt++) {
            progress.report(Progress.of(Step.DOWNLOAD, attempt == 1 ? Status.BEGIN : Status.RETRY, group, name));
            var transfer = HTTP.sendAsync(request, response -> {
                var subscriber = HttpResponse.BodySubscribers.ofFile(target,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
                if (response.statusCode() != 200) return subscriber;
                long total = response.headers().firstValueAsLong("Content-Length").orElse(-1);
                return new HttpResponse.BodySubscriber<Path>() {
                    long bytes;
                    long lastReport;

                    public java.util.concurrent.CompletionStage<Path> getBody() { return subscriber.getBody(); }
                    public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) {
                        subscriber.onSubscribe(subscription);
                    }
                    public void onNext(List<java.nio.ByteBuffer> buffers) {
                        for (var buffer : buffers) bytes += buffer.remaining();
                        subscriber.onNext(buffers);
                        long now = System.nanoTime();
                        if (now - lastReport >= 150_000_000L || bytes == total) {
                            reportBytes();
                            lastReport = now;
                        }
                    }
                    public void onError(Throwable error) { subscriber.onError(error); }
                    public void onComplete() {
                        reportBytes();
                        subscriber.onComplete();
                    }
                    private void reportBytes() {
                        progress.report(new Progress(Step.DOWNLOAD, Status.TRANSFER,
                            group, name, bytes, total, ""));
                    }
                };
            });
            try {
                var response = transfer.get(600, java.util.concurrent.TimeUnit.SECONDS);
                int status = response.statusCode();
                if (status == 200 && "https".equals(response.uri().getScheme())) return;
                if (attempt < 3 && (status == 429 || status >= 500)) continue;
                throw new IOException("Download failed: HTTP " + status + " · " + uri);
            } catch (java.util.concurrent.TimeoutException failure) {
                throw new IOException("Download timed out: " + uri, failure);
            } catch (java.util.concurrent.ExecutionException failure) {
                if (attempt == 3)
                    throw new IOException("Download failed: " + uri + " · " + failure.getCause().getMessage(), failure);
            } finally {
                transfer.cancel(true);
            }
        }
    }

    static byte[] fetch(URI uri, boolean local, int limit) throws Exception {
        try (InputStream input = open(uri, local)) {
            byte[] data = input.readNBytes(limit + 1);
            if (data.length > limit) throw new IOException("File exceeds expected limit.");
            return data;
        }
    }

    static Pack manifest(Config config) throws Exception {
        URI uri = URI.create(config.manifest);
        boolean local = "file".equals(uri.getScheme());
        byte[] bytes = fetch(uri, local, 4_000_000);
        byte[] signature = fetch(URI.create(config.manifest + ".sig"), local, 64);
        return verifyManifest(config, bytes, signature);
    }

    static Pack verifyManifest(Config config, byte[] bytes, byte[] signature) throws Exception {
        var key = KeyFactory.getInstance("Ed25519").generatePublic(
            new X509EncodedKeySpec(Base64.getDecoder().decode(config.publicKey)));
        var verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(key);
        verifier.update(bytes);
        if (!verifier.verify(signature)) throw new IOException("Manifest signature is invalid.");
        Pack pack = JSON.fromJson(new String(bytes, java.nio.charset.StandardCharsets.UTF_8), Pack.class);
        if (pack.schema != 1 || pack.version == null || pack.files == null)
            throw new IOException("Unsupported manifest.");
        return pack;
    }

    static Path destination(Path root, String name) throws IOException {
        if (!name.matches("(mods/[^/:\\\\]+[.]jar|tacz/[^:\\\\]+)") ||
            name.chars().anyMatch(c -> c < 32))
            throw new IOException("Invalid managed path: " + name);
        Path target = root.resolve(name).normalize();
        if (!target.startsWith(root.resolve(name.substring(0, name.indexOf('/')))) ||
            !root.relativize(target).toString().replace('\\', '/').equals(name))
            throw new IOException("Unsafe managed path: " + name);
        for (Path part = target; !part.equals(root); part = part.getParent())
            if (Files.isSymbolicLink(part) || Files.exists(part) && !part.toRealPath().equals(part))
                throw new IOException("Linked path: " + part);
        return target;
    }

    static String hash(Path path) throws Exception {
        var digest = MessageDigest.getInstance("SHA-512");
        try (InputStream raw = Files.newInputStream(path);
             var stream = new DigestInputStream(raw, digest)) {
            stream.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static String modId(Path file) throws IOException {
        try (var jar = new ZipFile(file.toFile())) {
            var metadata = jar.getEntry("fabric.mod.json");
            if (metadata == null) return null;
            try (var reader = new InputStreamReader(jar.getInputStream(metadata),
                    java.nio.charset.StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader).getAsJsonObject().get("id").getAsString();
            }
        }
    }

    static Path cache(Path root, Config config, Entry entry) throws Exception {
        return cache(root, config, entry, SILENT);
    }

    private static Path cache(Path root, Config config, Entry entry, ProgressSink progress) throws Exception {
        if (!entry.sha512.matches("[a-f0-9]{128}")) throw new IOException("Invalid SHA-512.");
        Path cache = root.resolve(STATE).resolve("cache");
        Files.createDirectories(cache);
        if (!cache.toRealPath().equals(cache)) throw new IOException("Linked cache directory.");
        Path file = cache.resolve(entry.sha512);
        if (Files.isRegularFile(file) && hash(file).equals(entry.sha512)) {
            progress.report(Progress.of(Step.CHECK, Status.REUSED, "pack", entry.path));
            return file;
        }
        Path pending = Files.createTempFile(cache, "download-", ".part");
        try {
            boolean local = "file".equals(URI.create(config.manifest).getScheme());
            downloadFile(URI.create(entry.url), local, pending, "pack", entry.path, progress);
            progress.report(Progress.of(Step.DOWNLOAD, Status.VERIFY, "pack", entry.path));
            if (!hash(pending).equals(entry.sha512)) throw new IOException("Verification failed: " + entry.path);
            Files.move(pending, file, StandardCopyOption.REPLACE_EXISTING);
            progress.report(Progress.of(Step.DOWNLOAD, Status.VERIFIED, "pack", entry.path));
            return file;
        } catch (Exception failure) {
            progress.report(Progress.failed(Step.DOWNLOAD, "pack", entry.path, failure));
            throw failure;
        } finally {
            Files.deleteIfExists(pending);
        }
    }

    static void install(Path profile, Config config, Pack pack) throws Exception {
        installObserved(profile, config, pack, true, SILENT);
    }

    static void install(Path profile, Config config, Pack pack, boolean installUpdater,
            java.util.function.Consumer<String> progress) throws Exception {
        installObserved(profile, config, pack, installUpdater, event -> {
            if (event.step == Step.CHECK && event.status == Status.COUNT)
                progress.accept("Mods " + event.count + "/" + event.total + " · " + event.file);
        });
    }

    static void installObserved(Path profile, Config config, Pack pack, boolean installUpdater,
            ProgressSink progress) throws Exception {
        installObserved(profile, config, pack, installUpdater, progress, null, Map.of());
    }

    static void installObserved(Path profile, Config config, Pack pack, boolean installUpdater,
            ProgressSink progress, String signedRelease, Map<String, String> repairHashes) throws Exception {
        Files.createDirectories(profile);
        Path root = profile.toRealPath();
        Path state = root.resolve(STATE);
        Files.createDirectories(state);
        if (!state.toRealPath().equals(state)) throw new IOException("Linked updater directory.");
        try (var channel = FileChannel.open(state.resolve("install.lock"), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE); var lock = channel.tryLock()) {
            if (lock == null) throw new IOException("Another installation is running.");
            apply(root, state, config, pack, installUpdater, progress, signedRelease, repairHashes);
        }
    }

    static void apply(Path root, Path state, Config config, Pack pack, boolean installUpdater,
            ProgressSink progress, String signedRelease, Map<String, String> repairHashes) throws Exception {
        Path previous = state.resolve("installed.json");
        Pack old = Files.exists(previous) ? JSON.fromJson(Files.readString(previous), Pack.class)
            : new Pack(1, "", List.of());
        Map<String, Entry> managed = new HashMap<>();
        for (Entry entry : old.files) managed.put(entry.path, entry);
        Set<String> seen = new HashSet<>();
        Set<String> ids = new HashSet<>();
        Path transaction = Files.createTempDirectory(state, "transaction-");
        List<Change> changes = new ArrayList<>();
        List<Entry> owned = new ArrayList<>();
        Map<String, List<Path>> manual = new HashMap<>();
        Path mods = root.resolve("mods");
        if (Files.isDirectory(mods)) {
            try (var files = Files.newDirectoryStream(mods, "*.jar")) {
                for (Path file : files) {
                    if (managed.containsKey("mods/" + file.getFileName())) continue;
                    String id;
                    try {
                        id = modId(file);
                    } catch (IOException | RuntimeException failure) {
                        throw new IOException("Cannot read mod: " + file.getFileName(), failure);
                    }
                    if (id != null) manual.computeIfAbsent(id, key -> new ArrayList<>()).add(file);
                }
            }
        }
        int checked = 0;
        progress.report(Progress.count(Step.CHECK, "pack", "", 0, pack.files.size()));
        for (Entry entry : pack.files) {
            progress.report(Progress.of(Step.CHECK, Status.BEGIN, "pack", entry.path));
            Path target = destination(root, entry.path);
            boolean jar = entry.path.startsWith("mods/");
            if (!seen.add(entry.path.toLowerCase(Locale.ROOT)) ||
                !entry.sha512.matches("[a-f0-9]{128}") ||
                jar && entry.modId != null && !ids.add(entry.modId))
                throw new IOException("Invalid or duplicate entry: " + entry.path);
            if (entry.path.equalsIgnoreCase("mods/Gunnertimes-Updater.jar") ||
                "gunnertimes_updater".equals(entry.modId))
                throw new IOException("The updater is installed separately from pack files.");
            boolean exists = Files.exists(target);
            boolean identicalManual = false;
            for (Path file : manual.getOrDefault(entry.modId, List.of())) {
                if (file.equals(target)) continue;
                if (!exists && !identicalManual && hash(file).equals(entry.sha512)) identicalManual = true;
                else throw new IOException("Manual mod conflict: " + file.getFileName() + " / " + entry.path);
            }
            if (identicalManual || exists && hash(target).equals(entry.sha512)) {
                if (!identicalManual && managed.containsKey(entry.path)) owned.add(entry);
                progress.report(Progress.of(Step.CHECK, Status.REUSED, "pack", entry.path));
                progress.report(Progress.count(Step.CHECK, "pack", entry.path, ++checked, pack.files.size()));
                continue;
            }
            Entry prior = managed.get(entry.path);
            String currentHash = exists ? hash(target) : null;
            if (exists) {
                boolean approvedRepair = prior != null && prior.sha512.equals(entry.sha512)
                    && currentHash.equals(repairHashes.get(entry.path));
                if (prior == null || !currentHash.equals(prior.sha512) && !approvedRepair)
                    throw new IOException("Local file conflict: " + entry.path);
            }
            Path staged = transaction.resolve("new-" + changes.size());
            progress.report(Progress.count(Step.CHECK, "pack", entry.path, ++checked, pack.files.size()));
            Files.copy(cache(root, config, entry, progress), staged);
            if (!hash(staged).equals(entry.sha512) || jar && entry.modId != null &&
                !entry.modId.equals(modId(staged))) throw new IOException("Verification failed: " + entry.path);
            changes.add(new Change(target, staged, exists ? transaction.resolve("old-" + changes.size()) : null, currentHash));
            owned.add(entry);
        }
        for (Entry entry : old.files) {
            if (seen.contains(entry.path.toLowerCase(Locale.ROOT))) continue;
            Path target = destination(root, entry.path);
            if (Files.exists(target) && hash(target).equals(entry.sha512))
                changes.add(new Change(target, null, transaction.resolve("old-" + changes.size()), entry.sha512));
        }
        Path updater = destination(root, "mods/Gunnertimes-Updater.jar");
        if (installUpdater && !Files.exists(updater) && !manual.containsKey("gunnertimes_updater")) {
            Path self = Path.of(Updater.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (Files.isRegularFile(self)) {
                Path staged = Files.copy(self, transaction.resolve("updater.jar"));
                changes.add(new Change(updater, staged, null, null));
            }
        }
        stageMetadata(changes, transaction, previous, JSON.toJson(new Pack(1, pack.version, owned)));
        stageMetadata(changes, transaction, state.resolve("channel.json"), JSON.toJson(config));
        if (signedRelease != null)
            stageMetadata(changes, transaction, state.resolve("signed-release.json"), signedRelease);
        for (Change change : changes) {
            boolean exists = Files.exists(change.target, LinkOption.NOFOLLOW_LINKS);
            if (exists != (change.backup != null) || exists && change.originalHash != null &&
                    (Files.isSymbolicLink(change.target) || !hash(change.target).equals(change.originalHash)))
                throw new IOException("Local file changed during preparation: " + change.target.getFileName());
        }
        List<Change> completed = new ArrayList<>();
        long toApply = changes.stream().filter(change -> !change.target.startsWith(state)).count();
        long applied = 0;
        progress.report(Progress.count(Step.INSTALL, "pack", "", 0, toApply));
        try {
            for (Change change : changes) {
                Files.createDirectories(change.target.getParent());
                if (change.backup != null) Files.move(change.target, change.backup);
                completed.add(change);
                if (change.staged != null) Files.move(change.staged, change.target);
                if (!change.target.startsWith(state))
                    progress.report(Progress.count(Step.INSTALL, "pack", root.relativize(change.target).toString(),
                        ++applied, toApply));
            }
        } catch (IOException failure) {
            progress.report(Progress.failed(Step.INSTALL, "pack", "", failure));
            Collections.reverse(completed);
            for (Change change : completed) {
                try {
                    if (change.staged != null && !Files.exists(change.staged)) Files.deleteIfExists(change.target);
                    if (change.backup != null) Files.move(change.backup, change.target);
                } catch (IOException recovery) { failure.addSuppressed(recovery); }
            }
            throw failure;
        }
        try (var members = Files.newDirectoryStream(transaction)) {
            if (!members.iterator().hasNext()) {
                members.close();
                Files.deleteIfExists(transaction);
            }
        } catch (IOException ignored) {
            // The installation already committed.
        }
        progress.report(Progress.of(Step.INSTALL, toApply == 0 ? Status.SKIPPED : Status.DONE, "pack", ""));
        System.out.println("Gunnertimes " + pack.version + " installed.");
    }

    static void stageMetadata(List<Change> changes, Path transaction, Path target, String content)
            throws Exception {
        if (Files.isSymbolicLink(target) || Files.exists(target) && !target.toRealPath().equals(target))
            throw new IOException("Linked metadata file: " + target);
        if (Files.isRegularFile(target) && Files.readString(target).equals(content)) return;
        Path staged = Files.writeString(transaction.resolve("new-" + changes.size()), content);
        boolean exists = Files.exists(target);
        changes.add(new Change(target, staged, exists
            ? transaction.resolve("old-" + changes.size()) : null, exists ? hash(target) : null));
    }
}




