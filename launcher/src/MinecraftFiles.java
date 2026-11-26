import com.google.gson.*;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;

final class MinecraftFiles {
    static final String VERSION = "26.3";
    static final String LOADER = "0.20.1";
    static final String LAUNCHER_VERSION = LauncherUpdates.VERSION;
    private static final String FABRIC_ID = "fabric-loader-" + LOADER + "-" + VERSION;
    private static final String BASE_SHA1 = "8696584e8d2da5726487597e6c5777480387ec22";
    private static final String MOJANG_META = "https://piston-meta.mojang.com/v1/packages/"
        + BASE_SHA1 + "/" + VERSION + ".json";
    private static final String FABRIC_SHA256 = "ebb22ba26e35a0b13f540fd7e7d8f95cb28a67d5498e5d8264700b4388c984ce";
    private static final String FABRIC_LOADER_SHA1 = "7c7f9831e9f27b303d5d08decb5f4258a9f03296";
    private static final String FABRIC_META = "https://meta.fabricmc.net/v2/versions/loader/"
        + VERSION + "/" + LOADER + "/profile/json";

    private final Path cache;
    private final Path official;
    private final Updater.ProgressSink progress;

    MinecraftFiles(Path cache, Updater.ProgressSink progress) {
        this.cache = cache;
        this.progress = progress;
        official = sharedDirectory();
    }

    List<String> prepare(Path profile, String username) throws Exception {
        return prepare(profile, username, 4, LaunchOptions.Settings.defaults());
    }

    List<String> prepare(Path profile, String username, int ramGiB, LaunchOptions.Settings options) throws Exception {
        progress.report(Updater.Progress.of(Updater.Step.CHECK, Updater.Status.BEGIN, "minecraft", ""));
        Path versions = official.resolve("versions");
        Path baseFolder = versions.resolve(VERSION);
        Path baseMetadata = download(baseFolder.resolve(VERSION + ".json"), MOJANG_META, BASE_SHA1,
            cache.resolve("versions").resolve(VERSION + ".json"));
        JsonObject base = json(Files.readAllBytes(baseMetadata));
        Path fabricMetadata = versions.resolve(FABRIC_ID).resolve(FABRIC_ID + ".json");
        if (!matchesDigest(fabricMetadata, "SHA-256", FABRIC_SHA256)) {
            Path legacy = cache.resolve("versions").resolve(FABRIC_ID + ".json");
            byte[] payload = matchesDigest(legacy, "SHA-256", FABRIC_SHA256) ? Files.readAllBytes(legacy)
                : Updater.fetch(URI.create(FABRIC_META), false, 100_000);
            if (!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload)).equals(FABRIC_SHA256))
                throw new IOException("Fabric profile checksum failed.");
            validateFabric(json(payload));
            write(fabricMetadata, payload);
        }
        JsonObject fabric = json(Files.readAllBytes(fabricMetadata));
        validateFabric(fabric);
        if (!VERSION.equals(string(base, "id")) || base.getAsJsonObject("javaVersion")
                .get("majorVersion").getAsInt() != 25) {
            throw new IOException("Unexpected Minecraft version metadata.");
        }

        Path libs = official.resolve("libraries");
        List<Path> classpath = new ArrayList<>();
        long totalLibraries = base.getAsJsonArray("libraries").asList().stream()
            .map(JsonElement::getAsJsonObject).filter(MinecraftFiles::allowed)
            .filter(library -> library.getAsJsonObject("downloads").has("artifact")).count()
            + fabric.getAsJsonArray("libraries").size();
        long checkedLibraries = 0;
        progress.report(Updater.Progress.count(Updater.Step.CHECK, "libraries", "", 0, totalLibraries));
        for (JsonElement element : base.getAsJsonArray("libraries")) {
            JsonObject library = element.getAsJsonObject();
            if (!allowed(library)) continue;
            JsonObject artifact = library.getAsJsonObject("downloads").getAsJsonObject("artifact");
            if (artifact == null) continue;
            String name = string(artifact, "path");
            classpath.add(download(libs.resolve(name), string(artifact, "url"),
                string(artifact, "sha1"), cache.resolve("libraries").resolve(name), "libraries"));
            progress.report(Updater.Progress.count(Updater.Step.CHECK, "libraries", name,
                ++checkedLibraries, totalLibraries));
        }
        for (JsonElement element : fabric.getAsJsonArray("libraries")) {
            JsonObject library = element.getAsJsonObject();
            String[] coordinate = string(library, "name").split(":");
            if (coordinate.length != 3) throw new IOException("Unsupported Fabric library coordinate.");
            String name = coordinate[0].replace('.', '/') + "/" + coordinate[1] + "/" + coordinate[2]
                + "/" + coordinate[1] + "-" + coordinate[2] + ".jar";
            String url = string(library, "url") + name;
            String hash = library.has("sha1") ? string(library, "sha1")
                : loaderChecksum(coordinate);
            Path jar = download(libs.resolve(name), url, hash, cache.resolve("libraries").resolve(name), "libraries");
            progress.report(Updater.Progress.count(Updater.Step.CHECK, "libraries", name,
                ++checkedLibraries, totalLibraries));
            classpath.remove(jar);
            classpath.add(0, jar);
        }
        progress.report(Updater.Progress.of(Updater.Step.CHECK, Updater.Status.BEGIN, "minecraft", "client.jar"));
        JsonObject client = base.getAsJsonObject("downloads").getAsJsonObject("client");
        String clientHash = string(client, "sha1");
        Path legacyClient = cache.resolve("versions").resolve(VERSION + ".jar");
        Path localClient = hasHash(legacyClient, clientHash) ? legacyClient
            : profile.resolve(profile.getFileName() + ".jar");
        classpath.add(download(baseFolder.resolve(VERSION + ".jar"), string(client, "url"), clientHash, localClient));

        Path assets = official.resolve("assets");
        JsonObject assetIndex = base.getAsJsonObject("assetIndex");
        String assetId = string(assetIndex, "id");
        String indexPath = "indexes/" + assetId + ".json";
        Path index = download(assets.resolve(indexPath), string(assetIndex, "url"),
            string(assetIndex, "sha1"), cache.resolve("assets").resolve(indexPath));
        prepareAssets(assets, json(Files.readAllBytes(index)).getAsJsonObject("objects"));

        JsonObject logging = base.getAsJsonObject("logging").getAsJsonObject("client");
        JsonObject logFile = logging.getAsJsonObject("file");
        Path logConfig = download(cache.resolve("logging").resolve(string(logFile, "id")),
            string(logFile, "url"), string(logFile, "sha1"), null);
        Path natives = profile.resolve(".gunnertimes/natives");
        Files.createDirectories(natives);
        String uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + username)
            .getBytes(StandardCharsets.UTF_8)).toString().replace("-", "");
        Map<String, String> values = Map.ofEntries(
            Map.entry("auth_player_name", username), Map.entry("auth_uuid", uuid),
            Map.entry("auth_access_token", "0"), Map.entry("clientid", ""), Map.entry("auth_xuid", ""),
            Map.entry("version_name", FABRIC_ID), Map.entry("version_type", "release"),
            Map.entry("game_directory", profile.toString()), Map.entry("assets_root", assets.toString()),
            Map.entry("assets_index_name", assetId), Map.entry("natives_directory", natives.toString()),
            Map.entry("launcher_name", "Gunnertimes"), Map.entry("launcher_version", LAUNCHER_VERSION),
            Map.entry("classpath", String.join(File.pathSeparator, classpath.stream().map(Path::toString).toList()))
        );
        List<String> command = new ArrayList<>();
        command.add(LaunchOptions.resolve(options).executable().toString());
        command.add("-Xms512M");
        command.add("-Xmx" + ramGiB + "G");
        command.add(string(logging, "argument").replace("${path}", logConfig.toString()));
        addArguments(command, base, "jvm", values);
        addArguments(command, fabric, "jvm", values);
        command.add(string(fabric, "mainClass"));
        addArguments(command, base, "game", values);
        addArguments(command, fabric, "game", values);
        if (options.display().equals("fullscreen")) command.add("--fullscreen");
        if (options.display().equals("windowed") && options.width() > 0) {
            command.addAll(List.of("--width", Integer.toString(options.width()), "--height",
                Integer.toString(options.height())));
        }
        return command;
    }

    private static void validateFabric(JsonObject profile) throws IOException {
        if (!FABRIC_ID.equals(string(profile, "id")) || !VERSION.equals(string(profile, "inheritsFrom"))
                || !"net.fabricmc.loader.impl.launch.knot.KnotClient".equals(string(profile, "mainClass"))) {
            throw new IOException("Unexpected Fabric profile metadata.");
        }
    }

    private static String loaderChecksum(String[] coordinate) throws IOException {
        if (Arrays.equals(coordinate, new String[]{"net.fabricmc", "fabric-loader", LOADER}))
            return FABRIC_LOADER_SHA1;
        throw new IOException("Missing hash for Fabric library " + String.join(":", coordinate));
    }

    static Path sharedDirectory() {
        String appData = System.getenv("APPDATA");
        return (appData == null || appData.isBlank()
            ? Path.of(System.getProperty("user.home"), "AppData", "Roaming") : Path.of(appData)).resolve(".minecraft");
    }

    static Path sharedClient() {
        return sharedDirectory().resolve("versions").resolve(VERSION).resolve(VERSION + ".jar");
    }

    private void prepareAssets(Path assets, JsonObject objects) throws Exception {
        Set<String> hashes = new LinkedHashSet<>();
        for (JsonElement object : objects.asMap().values()) hashes.add(string(object.getAsJsonObject(), "hash"));
        progress.report(Updater.Progress.count(Updater.Step.CHECK, "assets", "", 0, hashes.size()));
        ExecutorService workers = Executors.newFixedThreadPool(12);
        CompletionService<Path> downloads = new ExecutorCompletionService<>(workers);
        try {
            for (String hash : hashes) {
                if (!hash.matches("[a-f0-9]{40}")) throw new IOException("Invalid asset checksum.");
                String key = hash.substring(0, 2) + "/" + hash;
                String relative = "objects/" + key;
                downloads.submit(() -> download(assets.resolve(relative),
                    "https://resources.download.minecraft.net/" + key, hash,
                    cache.resolve("assets").resolve(relative), "assets"));
            }
            Throwable firstFailure = null;
            int failed = 0;
            for (int completed = 1; completed <= hashes.size(); completed++) {
                try {
                    downloads.take().get();
                } catch (ExecutionException failure) {
                    if (firstFailure == null) firstFailure = failure.getCause();
                    failed++;
                }
                if (completed % 100 == 0 || completed == hashes.size())
                    progress.report(Updater.Progress.count(Updater.Step.CHECK, "assets", "", completed - failed, hashes.size()));
            }
            if (firstFailure != null) {
                var failure = new IOException(failed + " game assets could not be prepared. "
                    + firstFailure.getMessage(), firstFailure);
                progress.report(Updater.Progress.failed(Updater.Step.DOWNLOAD, "assets", "", failure));
                throw failure;
            }
        } finally {
            workers.shutdownNow();
        }
    }

    static boolean allowed(JsonObject item) {
        if (!item.has("rules")) return true;
        boolean matches = false;
        for (JsonElement element : item.getAsJsonArray("rules")) {
            JsonObject rule = element.getAsJsonObject();
            if (rule.has("features") && rule.getAsJsonObject("features").asMap().values().stream()
                    .anyMatch(JsonElement::getAsBoolean)) continue;
            JsonObject os = rule.getAsJsonObject("os");
            if (os != null) {
                if (os.has("name") && !"windows".equals(string(os, "name"))) continue;
                if (os.has("arch") && !System.getProperty("os.arch").matches(string(os, "arch"))) continue;
                if (os.has("version") && !System.getProperty("os.version").matches(string(os, "version"))) continue;
                if (os.has("versionRange"))
                    throw new IllegalStateException("Unsupported OS version range in launch metadata.");
            }
            matches = "allow".equals(string(rule, "action"));
        }
        return matches;
    }

    private static void addArguments(List<String> command, JsonObject profile, String type,
            Map<String, String> values) throws IOException {
        for (JsonElement element : profile.getAsJsonObject("arguments").getAsJsonArray(type)) {
            JsonElement value = element;
            if (element.isJsonObject()) {
                if (!allowed(element.getAsJsonObject())) continue;
                value = element.getAsJsonObject().get("value");
            }
            JsonArray arguments = value.isJsonArray() ? value.getAsJsonArray() : new JsonArray();
            if (!value.isJsonArray()) arguments.add(value);
            for (JsonElement argument : arguments) {
                String expanded = argument.getAsString();
                for (var pair : values.entrySet())
                    expanded = expanded.replace("${" + pair.getKey() + "}", pair.getValue());
                if (expanded.contains("${")) throw new IOException("Unknown game argument: " + expanded);
                command.add(expanded);
            }
        }
    }

    static JsonObject json(byte[] bytes) {
        return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static String string(JsonObject object, String name) {
        return object.get(name).getAsString();
    }

    private static boolean hasHash(Path path, String expected) throws IOException {
        return matchesDigest(path, "SHA-1", expected);
    }

    private static boolean matchesDigest(Path path, String algorithm, String expected) throws IOException {
        if (path == null || !Files.isRegularFile(path)) return false;
        try {
            var digest = MessageDigest.getInstance(algorithm);
            try (var input = new DigestInputStream(Files.newInputStream(path), digest)) {
                input.transferTo(OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest()).equals(expected);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private Path download(Path path, String url, String hash, Path source) throws Exception {
        return download(path, url, hash, source, "minecraft");
    }

    private Path download(Path path, String url, String hash, Path source, String group) throws Exception {
        String name = path.getFileName().toString();
        if (!hash.matches("[a-f0-9]{40}")) throw new IOException("Invalid checksum for " + path.getFileName());
        if (hasHash(path, hash)) {
            progress.report(Updater.Progress.of(Updater.Step.CHECK, Updater.Status.REUSED, group, name));
            return path;
        }
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), "download-", ".part");
        try {
            boolean reused = hasHash(source, hash);
            if (reused) Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
            else {
                Updater.downloadFile(URI.create(url), false, temporary, group, name, progress);
                progress.report(Updater.Progress.of(Updater.Step.DOWNLOAD, Updater.Status.VERIFY, group, name));
            }
            if (!hasHash(temporary, hash)) throw new IOException("Checksum failed: " + name);
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            progress.report(Updater.Progress.of(reused ? Updater.Step.CHECK : Updater.Step.DOWNLOAD,
                reused ? Updater.Status.REUSED : Updater.Status.VERIFIED, group, name));
            return path;
        } catch (IOException failure) {
            progress.report(Updater.Progress.failed(Updater.Step.DOWNLOAD, group, name, failure));
            throw new IOException("Could not prepare " + name + ": " + failure.getMessage(), failure);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static void write(Path path, byte[] bytes) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), "write-", ".part");
        try {
            Files.write(temporary, bytes);
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
