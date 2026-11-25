import com.google.gson.*;
import java.io.*;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import javax.swing.*;

public final class GunnertimesLauncher {
    private static final Path HOME = Path.of(System.getenv("LOCALAPPDATA"), "Gunnertimes");
    static final Path DEFAULT_PROFILE = HOME.resolve("profiles/Gunnertimes");
    private static final Path PREFERENCES = HOME.resolve("launcher.json");
    private static final Path CACHE = HOME.resolve("game");

    record Preferences(String username, String profile, String language, int ramGiB) {
        Preferences {
            if (username == null) username = "";
            if (profile == null || profile.isBlank()) profile = DEFAULT_PROFILE.toString();
            if (!"fa".equals(language)) language = "en";
            if (ramGiB < 2 || ramGiB > 32) ramGiB = 4;
        }
    }

    static final class InputError extends IOException {
        final String key;
        InputError(String key) {
            super(key);
            this.key = key;
        }
    }
    record SignedRelease(String manifest, String signature) {
        Updater.Pack verify(Updater.Config config) throws Exception {
            var base64 = Base64.getDecoder();
            return Updater.verifyManifest(config, base64.decode(manifest), base64.decode(signature));
        }
    }
    record Release(Updater.Pack pack, SignedRelease signed, boolean offline) {}

    public static void main(String[] args) throws Exception {
        if (args.length == 3 && args[0].equals("--play")) {
            Preferences current = preferences();
            Preferences selected = validated(args[1], args[2], current.language(), Integer.toString(current.ramGiB()), true);
            save(selected);
            play(selected, event -> {
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
        return new Preferences("", DEFAULT_PROFILE.toString(), "en", 4);
    }

    static void save(Preferences preferences) throws IOException {
        MinecraftFiles.write(PREFERENCES, Updater.JSON.toJson(preferences)
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    static Preferences validated(String name, String folder, String language, String ram, boolean requireName)
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
        return new Preferences(name, profilePath(folder).toString(), language, memory);
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

    static void play(Preferences settings, Updater.ProgressSink observer) throws Exception {
        Path profile;
        Path logs;
        Path journal;
        try {
            profile = Files.createDirectories(profilePath(settings.profile())).toRealPath();
            logs = Files.createDirectories(profile.resolve("logs"));
            journal = Files.createTempFile(logs, "gunnertimes-launcher-", ".log");
        } catch (Exception failure) {
            observer.report(Updater.Progress.failed(Updater.Step.CHECK, "session", "", failure));
            throw failure;
        }
        observer.report(Updater.Progress.of(Updater.Step.CHECK, Updater.Status.BEGIN, "session", journal.toString()));
        try (var writer = new PrintWriter(Files.newBufferedWriter(journal), true)) {
            launch(settings, profile, logs, event -> {
                observer.report(event);
                if (event.status() == Updater.Status.TRANSFER ||
                        event.status() == Updater.Status.REUSED && event.group().equals("assets")) return;
                writer.println(event);
            });
        }
    }

    private static void launch(Preferences settings, Path profile, Path logs, Updater.ProgressSink progress)
            throws Exception {
        Updater.Step stage = Updater.Step.CHECK;
        Path log = null;
        progress.report(Updater.Progress.of(stage, Updater.Status.BEGIN, "release", ""));
        try {
            Files.createDirectories(HOME);
            try (FileChannel channel = FileChannel.open(HOME.resolve("launcher.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE); var lock = channel.tryLock()) {
                if (lock == null) throw new IOException("Gunnertimes is already preparing or running a game.");
                Updater.Config config = Updater.configuration(DEFAULT_PROFILE.resolve(".embedded-channel"));
                Path state = Files.createDirectories(profile.resolve(Updater.STATE));
                if (!state.toRealPath().equals(state)) throw new IOException("Linked updater directory.");
                Path signedRelease = state.resolve("signed-release.json");
                Release release = release(config, signedRelease);
                progress.report(Updater.Progress.of(stage,
                    release.offline ? Updater.Status.OFFLINE : Updater.Status.DONE, "release", release.pack.version()));

                List<String> command = new MinecraftFiles(CACHE, progress)
                    .prepare(profile, settings.username(), settings.ramGiB());
                if (release.offline) {
                    verifyOfflineMods(profile, release.pack, progress);
                    progress.report(Updater.Progress.of(Updater.Step.INSTALL, Updater.Status.SKIPPED, "pack", ""));
                } else {
                    Updater.installObserved(profile, config, release.pack, false, progress);
                    stage = Updater.Step.INSTALL;
                    MinecraftFiles.write(signedRelease, Updater.JSON.toJson(release.signed)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                stage = Updater.Step.START;
                progress.report(Updater.Progress.of(stage, Updater.Status.BEGIN, "game", ""));
                log = Files.createTempFile(logs, "gunnertimes-"
                    + LocalDateTime.now().format(DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss")) + "-", ".log");
                Process game = new ProcessBuilder(command).directory(profile.toFile()).redirectErrorStream(true)
                    .redirectOutput(log.toFile()).start();
                progress.report(Updater.Progress.of(stage, Updater.Status.DONE, "game", log.toString()));
                stage = Updater.Step.RUN;
                progress.report(Updater.Progress.of(stage, Updater.Status.BEGIN, "game", log.toString()));
                int code = game.waitFor();
                if (code != 0) throw new IOException("Minecraft exited with code " + code + ".");
                progress.report(Updater.Progress.of(stage, Updater.Status.DONE, "game", log.toString()));
            }
        } catch (Exception failure) {
            progress.report(Updater.Progress.failed(stage, stage == Updater.Step.CHECK ? "" : "game",
                log == null ? "" : log.toString(), failure));
            throw failure;
        }
    }

    private static Release release(Updater.Config config, Path signedRelease) throws Exception {
        byte[] bytes;
        byte[] signature;
        try {
            bytes = Updater.fetch(URI.create(config.manifest()), false, 4_000_000);
            signature = Updater.fetch(URI.create(config.manifest() + ".sig"), false, 64);
        } catch (IOException unavailable) {
            if (!Files.isRegularFile(signedRelease)) throw unavailable;
            SignedRelease cached = Updater.JSON.fromJson(Files.readString(signedRelease), SignedRelease.class);
            return new Release(cached.verify(config), cached, true);
        }
        var base64 = Base64.getEncoder();
        SignedRelease artifact = new SignedRelease(base64.encodeToString(bytes), base64.encodeToString(signature));
        return new Release(Updater.verifyManifest(config, bytes, signature), artifact, false);
    }

    private static void verifyOfflineMods(Path profile, Updater.Pack pack, Updater.ProgressSink progress)
            throws Exception {
        long checked = 0;
        for (Updater.Entry entry : pack.files()) {
            Path path = Updater.destination(profile, entry.path());
            progress.report(Updater.Progress.of(Updater.Step.CHECK, Updater.Status.BEGIN, "pack", entry.path()));
            boolean found = Files.isRegularFile(path) && Updater.hash(path).equals(entry.sha512());
            if (!found && entry.modId() != null && Files.isDirectory(profile.resolve("mods"))) {
                try (var candidates = Files.newDirectoryStream(profile.resolve("mods"), "*.jar")) {
                    for (Path candidate : candidates) {
                        if (Updater.hash(candidate).equals(entry.sha512())) {
                            found = true;
                            break;
                        }
                    }
                }
            }
            if (!found) throw new IOException("Cached release needs repair while online: " + entry.path());
            progress.report(Updater.Progress.of(Updater.Step.CHECK, Updater.Status.REUSED, "pack", entry.path()));
            progress.report(Updater.Progress.count(Updater.Step.CHECK, "pack", entry.path(), ++checked, pack.files().size()));
        }
    }
}
