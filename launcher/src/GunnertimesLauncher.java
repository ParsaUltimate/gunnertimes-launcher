import com.google.gson.*;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.List;
import javax.swing.*;

public final class GunnertimesLauncher {
    static final Path HOME = Path.of(System.getenv("LOCALAPPDATA"), "Gunnertimes");
    static final Path DEFAULT_PROFILE = HOME.resolve("profiles/Gunnertimes");
    private static final Path PREFERENCES = HOME.resolve("launcher.json");
    static final Path CACHE = HOME.resolve("game");

    record Preferences(String username, String profile, String language, int ramGiB, LaunchOptions.Settings options) {
        Preferences {
            if (username == null) username = "";
            if (profile == null || profile.isBlank()) profile = DEFAULT_PROFILE.toString();
            if (!"fa".equals(language)) language = "en";
            if (ramGiB < 2 || ramGiB > 32) ramGiB = 4;
            if (options == null) options = LaunchOptions.Settings.defaults();
        }
    }

    static final class InputError extends IOException {
        final String key;
        InputError(String key) {
            super(key);
            this.key = key;
        }
    }
    enum Action { PLAY, JOIN, INSTALL, UPDATE, REPAIR }

    record Request(Action action, PackService.Release selection, PackService.Check check, Map<String, String> repairs) {
        Request(Action action, PackService.Check check) { this(action, null, check, Map.of()); }
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 3 && args[0].equals("--play")) {
            Preferences current = preferences();
            Preferences selected = validated(args[1], args[2], current.language(), Integer.toString(current.ramGiB()), current.options(), true);
            save(selected);
            run(selected, new Request(Action.PLAY, PackService.Check.empty()), event -> {
                if (event.status() != Updater.Status.REUSED && event.status() != Updater.Status.TRANSFER)
                    System.out.println(event);
            });
            return;
        }
        SwingUtilities.invokeLater(() -> new LauncherWindow(preferences()).show());
    }

    static Preferences preferences() {
        try {
            Preferences saved = Updater.JSON.fromJson(Files.readString(PREFERENCES), Preferences.class);
            if (saved != null) return saved;
        } catch (IOException | JsonParseException ignored) {
            // Start with defaults when there is no readable settings file.
        }
        return defaults("en");
    }

    static Preferences defaults(String language) {
        return new Preferences("", DEFAULT_PROFILE.toString(), language, 4, LaunchOptions.Settings.defaults());
    }

    static void save(Preferences preferences) throws IOException {
        MinecraftFiles.write(PREFERENCES, Updater.JSON.toJson(preferences)
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    static Preferences validated(String name, String folder, String language, String ram, LaunchOptions.Settings options, boolean requireName)
            throws InputError {
        name = name.strip();
        if ((requireName || !name.isEmpty()) && !name.matches("[A-Za-z0-9_]{3,16}"))
            throw new InputError("invalidName");
        int memory;
        try {
            memory = Integer.parseInt(ram.strip());
        } catch (NumberFormatException failure) {
            throw new InputError("invalidRam");
        }
        if (memory < 2 || memory > 32) throw new InputError("invalidRam");
        return new Preferences(name, profilePath(folder).toString(), language, memory, options);
    }

    static Path profilePath(String value) throws InputError {
        if (value.isBlank()) throw new InputError("invalidFolder");
        try {
            String expanded = value.strip();
            for (String variable : List.of("LOCALAPPDATA", "APPDATA")) {
                String token = "%" + variable + "%";
                if (expanded.regionMatches(true, 0, token, 0, token.length()) && System.getenv(variable) != null)
                    expanded = System.getenv(variable) + expanded.substring(token.length());
            }
            Path raw = Path.of(expanded);
            if (!raw.isAbsolute()) throw new InputError("invalidFolder");
            Path path = raw.normalize();
            if (path.getFileName() != null && path.getFileName().toString().equalsIgnoreCase("mods"))
                path = path.getParent();
            if (path.getFileName() != null) return path;
        } catch (InvalidPathException ignored) {
            // The UI reports the same validation message for invalid path syntax.
        }
        throw new InputError("invalidFolder");
    }

    static void run(Preferences settings, Request request, Updater.ProgressSink observer) throws Exception {
        Path profile;
        Path logs;
        Path journal;
        try {
            profile = Files.createDirectories(profilePath(settings.profile())).toRealPath();
            logs = Files.createDirectories(profile.resolve("logs"));
            if (!logs.toRealPath().equals(logs)) throw new IOException("Linked log directory.");
            journal = Files.createTempFile(logs, "gunnertimes-launcher-", ".log");
        } catch (Exception failure) {
            observer.report(Updater.Progress.failed(Updater.Step.CHECK, "session", "", failure));
            throw failure;
        }
        observer.report(Updater.Progress.of(Updater.Step.CHECK, Updater.Status.BEGIN, "session", journal.toString()));
        try (var writer = new PrintWriter(Files.newBufferedWriter(journal), true)) {
            operate(settings, request, profile, logs, event -> {
                observer.report(event);
                if (event.status() == Updater.Status.TRANSFER ||
                        event.status() == Updater.Status.REUSED && event.group().equals("assets")) return;
                writer.println(event);
            });
        }
    }

    private static void operate(Preferences settings, Request request, Path profile, Path logs,
            Updater.ProgressSink progress) throws Exception {
        Updater.Step stage = Updater.Step.CHECK;
        Path log = null;
        try {
            Files.createDirectories(HOME);
            try (FileChannel channel = FileChannel.open(HOME.resolve("launcher.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE); var lock = channel.tryLock()) {
                if (lock == null) throw new IOException("Gunnertimes is already preparing or running a game.");
                var packs = new PackService(HOME);
                var installed = packs.installed(profile);
                Action action = request.action;
                if (action == Action.INSTALL || action == Action.UPDATE || action == Action.REPAIR) {
                    var selected = request.selection;
                    if (selected == null) throw new IOException("No verified release selected.");
                    if (action == Action.INSTALL && installed != null)
                        throw new IOException("A pack is already installed. Use Update or Repair.");
                    if (action == Action.UPDATE && (installed == null ||
                            !PackService.newer(installed.version(), selected.version())))
                        throw new IOException("The selected release is no longer newer than the install.");
                    if (action == Action.REPAIR && (installed == null ||
                            !installed.envelope().equals(selected.envelope())))
                        throw new IOException("The installed release changed. Verify it again.");
                    progress.report(Updater.Progress.of(stage, Updater.Status.DONE, "release", selected.version()));
                    packs.apply(profile, selected, request.repairs, progress);
                    return;
                }

                if (installed == null) throw new IOException("Install the signed pack before playing.");
                progress.report(Updater.Progress.of(stage, Updater.Status.DONE, "release", installed.version()));
                if (action == Action.JOIN && ServerStatus.compatibility(request.check, installed.version())
                        == ServerStatus.Compatibility.INCOMPATIBLE)
                    throw new IOException("The official server lists this installed pack as incompatible.");
                var inspection = packs.inspect(profile, installed, progress);
                if (!inspection.healthy()) {
                    var first = inspection.problems().getFirst();
                    throw new IOException((first.condition() == PackService.Condition.CONFLICT
                        ? "Local file conflict: " : "Installed file needs repair: ") + first.location());
                }
                List<String> command = new MinecraftFiles(CACHE, progress)
                    .prepare(profile, settings.username(), settings.ramGiB(), settings.options());
                progress.report(Updater.Progress.of(Updater.Step.INSTALL, Updater.Status.SKIPPED, "pack", ""));
                stage = Updater.Step.START;
                progress.report(Updater.Progress.of(stage, Updater.Status.BEGIN, "game", ""));
                log = Files.createTempFile(logs, "gunnertimes-"
                    + LocalDateTime.now().format(DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss")) + "-", ".log");
                Path quickPlay = null;
                if (action == Action.JOIN) {
                    quickPlay = logs.resolve(log.getFileName() + ".join.json");
                    command.addAll(List.of("--quickPlayPath", profile.relativize(quickPlay).toString().replace('\\', '/'),
                        "--quickPlayMultiplayer", ServerStatus.ADDRESS));
                }
                try (var options = LaunchOptions.displayOverride(profile, settings.options())) {
                    Process game = new ProcessBuilder(command).directory(profile.toFile()).redirectErrorStream(true)
                        .redirectOutput(log.toFile()).start();
                    progress.report(Updater.Progress.of(stage, Updater.Status.DONE, "game", log.toString()));
                    stage = Updater.Step.RUN;
                    progress.report(Updater.Progress.of(stage, Updater.Status.BEGIN, "game", log.toString()));
                    if (quickPlay != null) observeJoin(game, log, quickPlay, progress);
                    int code = game.waitFor();
                    if (code != 0) throw new IOException("Minecraft exited with code " + code + ".");
                    progress.report(Updater.Progress.of(stage, Updater.Status.DONE, "game", log.toString()));
                }
            }
        } catch (Exception failure) {
            progress.report(Updater.Progress.failed(stage, stage == Updater.Step.CHECK ? "" : "game",
                log == null ? "" : log.toString(), failure));
            throw failure;
        }
    }

    private static void observeJoin(Process game, Path log, Path quickPlay, Updater.ProgressSink progress)
            throws InterruptedException {
        progress.report(Updater.Progress.of(Updater.Step.RUN, Updater.Status.BEGIN, "join", ServerStatus.ADDRESS));
        var outcome = Updater.Status.SKIPPED;
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
        do {
            try {
                if (Files.isRegularFile(quickPlay) && Files.size(quickPlay) < 65_536) {
                    var entry = MinecraftFiles.json(Files.readAllBytes(quickPlay));
                    if (entry.has("id") && ServerStatus.ADDRESS.equalsIgnoreCase(entry.get("id").getAsString())
                            && entry.has("type") && "multiplayer".equals(entry.get("type").getAsString())) {
                        outcome = Updater.Status.DONE;
                        break;
                    }
                }
                try (var reader = new RandomAccessFile(log.toFile(), "r")) {
                    reader.seek(Math.max(0, reader.length() - 262_144));
                    byte[] bytes = new byte[(int) (reader.length() - reader.getFilePointer())];
                    reader.readFully(bytes);
                    String tail = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                    if (tail.contains("Couldn't connect to server") || tail.contains("Failed to connect to the server")) {
                        outcome = Updater.Status.FAILED;
                        break;
                    }
                }
            } catch (IOException | RuntimeException ignored) {
                // Minecraft may still be writing one of these files.
            }
        } while (System.nanoTime() < deadline && !game.waitFor(1, TimeUnit.SECONDS));
        progress.report(Updater.Progress.of(Updater.Step.RUN, outcome, "join", ServerStatus.ADDRESS));
    }
}
