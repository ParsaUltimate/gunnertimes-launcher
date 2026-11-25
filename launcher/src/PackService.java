import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

final class PackService {
    static final String SIGNED = "signed-release.json";
    record Envelope(String manifest, String signature) {
        Updater.Pack verify(Updater.Config config) throws Exception {
            var decoder = Base64.getDecoder();
            return Updater.verifyManifest(config, decoder.decode(manifest), decoder.decode(signature));
        }
    }
    record Release(Updater.Pack pack, Envelope envelope) {
        String version() { return pack.version(); }
    }
    record Check(Release available, Instant checked, boolean stale, boolean notesStale, String notes) {
        static Check empty() { return new Check(null, null, true, true, ""); }
    }
    record StoredCheck(Envelope envelope, InstantValue time, String notes) {}
    record InstantValue(long millis) {}
    enum Condition { VERIFIED, MISSING, DAMAGED_OWNED, CONFLICT }
    record Item(Updater.Entry entry, Condition condition, String actualHash, String location) {}
    record Inspection(List<Item> items) {
        boolean healthy() { return items.stream().allMatch(i -> i.condition == Condition.VERIFIED); }
        List<Item> problems() { return items.stream().filter(i -> i.condition != Condition.VERIFIED).toList(); }
        Map<String, String> approvedRepairs() {
            var result = new HashMap<String, String>();
            for (Item item : items)
                if (item.condition == Condition.DAMAGED_OWNED) result.put(item.entry.path(), item.actualHash);
            return Map.copyOf(result);
        }
    }

    private final Path checkFile;
    private final Updater.Config config;

    PackService(Path home) throws Exception {
        this(home, Updater.configuration(home.resolve(".embedded-channel")));
    }

    PackService(Path home, Updater.Config config) {
        checkFile = home.resolve("release-check.json");
        this.config = config;
    }

    Release installed(Path profile) throws Exception {
        Path state = profile.resolve(Updater.STATE);
        if (Files.isDirectory(state) && !state.toRealPath().equals(state.toAbsolutePath().normalize()))
            throw new IOException("Linked updater directory.");
        Path file = state.resolve(SIGNED);
        if (!Files.isRegularFile(file)) return null;
        if (Files.isSymbolicLink(file)) throw new IOException("Linked signed release.");
        Envelope envelope = Updater.JSON.fromJson(Files.readString(file), Envelope.class);
        return new Release(envelope.verify(config), envelope);
    }

    Check cached() {
        try {
            if (!Files.isRegularFile(checkFile)) return Check.empty();
            StoredCheck saved = Updater.JSON.fromJson(Files.readString(checkFile), StoredCheck.class);
            return new Check(new Release(saved.envelope.verify(config), saved.envelope),
                Instant.ofEpochMilli(saved.time.millis), true, true, saved.notes == null ? "" : saved.notes);
        } catch (Exception ignored) {
            return Check.empty();
        }
    }

    Check check() throws Exception {
        URI uri = URI.create(config.manifest());
        byte[] bytes = Updater.fetch(uri, false, 4_000_000);
        byte[] signature = Updater.fetch(URI.create(config.manifest() + ".sig"), false, 64);
        var encoder = Base64.getEncoder();
        Envelope envelope = new Envelope(encoder.encodeToString(bytes), encoder.encodeToString(signature));
        Release release = new Release(envelope.verify(config), envelope);
        Instant now = Instant.now();
        Check cached = cached();
        String notes = officialNotes(release.version());
        boolean notesStale = notes == null;
        if (notesStale) notes = cached.available != null && cached.available.version().equals(release.version())
            ? cached.notes : "";
        MinecraftFiles.write(checkFile, Updater.JSON.toJson(new StoredCheck(envelope,
            new InstantValue(now.toEpochMilli()), notes)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new Check(release, now, false, notesStale, notes);
    }

    private static String officialNotes(String version) {
        if (!version.matches("[a-zA-Z0-9._-]{1,80}")) return "";
        try {
            URI uri = URI.create("https://api.github.com/repos/ParsaUltimate/gunnertimes-releases/releases/tags/" + version);
            var request = java.net.http.HttpRequest.newBuilder(uri).timeout(java.time.Duration.ofSeconds(5))
                .header("Accept", "application/vnd.github+json").build();
            var response = Updater.HTTP.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) return "";
            if (response.statusCode() != 200) return null;
            var json = com.google.gson.JsonParser.parseString(response.body()).getAsJsonObject();
            if (json.get("draft").getAsBoolean() || !json.get("tag_name").getAsString().equals(version)) return "";
            var body = json.get("body");
            if (body == null || body.isJsonNull()) return "";
            String plain = body.getAsString().strip();
            return plain.substring(0, Math.min(plain.length(), 1800));
        } catch (Exception ignored) {
            return null;
        }
    }

    static boolean newer(String installed, String available) {
        var format = java.util.regex.Pattern.compile("(?i)(gunnertimes-|v)?(\\d{1,6})[.](\\d{1,6})[.](\\d{1,6})");
        var left = format.matcher(installed);
        var right = format.matcher(available);
        if (!left.matches() || !right.matches()) return false;
        boolean leftCalendar = left.group(2).length() == 4;
        if (leftCalendar != (right.group(2).length() == 4)) return false;
        for (int part = 2; part <= 4; part++) {
            int order = Integer.compare(Integer.parseInt(right.group(part)), Integer.parseInt(left.group(part)));
            if (order != 0) return order > 0;
        }
        return false;
    }

    Inspection inspect(Path profile, Release release, Updater.ProgressSink progress) throws Exception {
        Path canonical = Files.isDirectory(profile) ? profile.toRealPath() : profile.toAbsolutePath().normalize();
        Path ownership = canonical.resolve(Updater.STATE).resolve("installed.json");
        var owned = new HashMap<String, String>();
        if (Files.isRegularFile(ownership)) {
            if (!ownership.toRealPath().equals(ownership)) throw new IOException("Linked ownership file.");
            var installed = Updater.JSON.fromJson(Files.readString(ownership), Updater.Pack.class);
            if (installed != null && installed.files() != null)
                for (var entry : installed.files()) owned.put(entry.path(), entry.sha512());
        }
        Map<String, List<Path>> byId = new HashMap<>();
        Path mods = canonical.resolve("mods");
        if (Files.isDirectory(mods)) {
            if (!mods.toRealPath().equals(mods)) throw new IOException("Linked mods directory.");
            try (var files = Files.newDirectoryStream(mods, "*.jar")) {
                for (Path file : files) {
                    if (!file.toRealPath().equals(file)) throw new IOException("Linked mod: " + file.getFileName());
                    try {
                        String id = Updater.modId(file);
                        if (id != null) byId.computeIfAbsent(id, key -> new ArrayList<>()).add(file);
                    } catch (java.util.zip.ZipException | com.google.gson.JsonParseException | NullPointerException error) {
                        String relative = "mods/" + file.getFileName();
                        if (!owned.containsKey(relative))
                            throw new IOException("Cannot read mod: " + file.getFileName(), error);
                    }
                }
            }
        }
        List<Item> items = new ArrayList<>();
        long checked = 0;
        for (var entry : release.pack.files()) {
            progress.report(Updater.Progress.of(Updater.Step.CHECK, Updater.Status.BEGIN, "pack", entry.path()));
            Path path = Updater.destination(canonical, entry.path());
            String hash = Files.isRegularFile(path) ? Updater.hash(path) : null;
            Condition condition = Objects.equals(hash, entry.sha512()) ? Condition.VERIFIED
                : Files.exists(path) ? Condition.CONFLICT : Condition.MISSING;
            if (hash != null && condition == Condition.CONFLICT && entry.sha512().equals(owned.get(entry.path())))
                condition = Condition.DAMAGED_OWNED;
            String location = entry.path();
            if (entry.modId() != null) {
                for (Path alternative : byId.getOrDefault(entry.modId(), List.of())) {
                    if (alternative.equals(path)) continue;
                    String relative = "mods/" + alternative.getFileName();
                    location = relative;
                    if (condition == Condition.MISSING && Updater.hash(alternative).equals(entry.sha512()))
                        condition = Condition.VERIFIED;
                    else {
                        condition = Condition.CONFLICT;
                        break;
                    }
                }
            }
            items.add(new Item(entry, condition, hash, location));
            if (condition == Condition.VERIFIED)
                progress.report(Updater.Progress.of(Updater.Step.CHECK, Updater.Status.REUSED, "pack", location));
            progress.report(Updater.Progress.count(Updater.Step.CHECK, "pack", location, ++checked, release.pack.files().size()));
        }
        return new Inspection(List.copyOf(items));
    }

    void apply(Path profile, Release release, Map<String, String> repair, Updater.ProgressSink progress)
            throws Exception {
        if (!release.envelope.verify(config).equals(release.pack))
            throw new IOException("Selected release does not match its signature.");
        Updater.installObserved(profile, config, release.pack, false, progress,
            Updater.JSON.toJson(release.envelope), repair);
    }
}
