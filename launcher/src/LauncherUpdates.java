import com.google.gson.*;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;

final class LauncherUpdates {
    static final String VERSION = version();
    static final String REPOSITORY = "ParsaUltimate/gunnertimes-releases";
    private static final String MARKER = "<!-- gunnertimes-update-v1 ";
    private static final String API = "https://api.github.com/repos/" + REPOSITORY + "/releases?per_page=100&page=";

    enum Type {
        INSTALLED("Gunnertimes-Setup.exe"), PORTABLE("Gunnertimes-Portable.jar");
        final String filename;
        Type(String filename) { this.filename = filename; }
    }

    record Artifact(Type type, String filename, long size, String url, String sha512) {}
    record Release(String version, String notes, List<Artifact> artifacts) {
        Artifact artifact(Type type) throws IOException {
            return artifacts.stream().filter(a -> a.type == type).findFirst()
                .orElseThrow(() -> new IOException("Missing " + type.filename));
        }
    }

    static Type type() {
        return Boolean.getBoolean("gunnertimes.installed") ? Type.INSTALLED : Type.PORTABLE;
    }

    static int[] parts(String value) {
        if (value == null || !value.matches("(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})"))
            throw new IllegalArgumentException("Expected a stable version: " + value);
        return Arrays.stream(value.split("\\.")).mapToInt(Integer::parseInt).toArray();
    }

    private static String version() {
        try {
            String result = resource("launcher-version.txt").strip();
            parts(result);
            return result;
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    static String resource(String name) throws IOException {
        try (InputStream source = LauncherUpdates.class.getResourceAsStream("/" + name)) {
            if (source == null) throw new IOException("Missing launcher resource: " + name);
            return new String(source.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static String assetUrl(String version, Type type) {
        parts(version);
        return "https://github.com/" + REPOSITORY + "/releases/download/launcher-v" + version + "/" + type.filename;
    }

    static Release check() throws Exception {
        JsonObject candidate = null;
        int[] newest = parts(VERSION);
        for (int page = 1; ; page++) {
            var bytes = Updater.fetch(URI.create(API + page), false, 8_000_000);
            var results = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonArray();
            for (JsonElement element : results) {
                JsonObject item = element.getAsJsonObject();
                String tag = item.get("tag_name").getAsString();
                if (item.get("draft").getAsBoolean() || item.get("prerelease").getAsBoolean()
                        || !tag.startsWith("launcher-v")) continue;
                int[] version;
                try { version = parts(tag.substring("launcher-v".length())); }
                catch (IllegalArgumentException ignored) { continue; }
                if (Arrays.compare(version, newest) > 0) {
                    candidate = item;
                    newest = version;
                }
            }
            if (results.size() < 100) break;
        }
        if (candidate == null) return null;
        PublicKey key = publicKey(resource("launcher-update-public.txt").strip());
        Release release = verifyNotes(candidate.get("body").getAsString(), key);
        if (!candidate.get("tag_name").getAsString().equals("launcher-v" + release.version))
            throw new IOException("Launcher release tag does not match signed version.");
        return release;
    }

    static Path download(Artifact asset, Updater.ProgressSink progress) throws Exception {
        Path staging = Files.createDirectories(GunnertimesLauncher.HOME.resolve("launcher-updates"));
        Path payload = Files.createTempFile(staging, "update-", asset.type == Type.INSTALLED ? ".exe" : ".jar");
        boolean verified = false;
        try {
            Updater.downloadFile(URI.create(asset.url), false, payload, "launcher", asset.filename, progress);
            verifyPayload(asset, payload);
            verified = true;
            return payload;
        } finally {
            if (!verified) Files.deleteIfExists(payload);
        }
    }

    static void verifyPayload(Artifact asset, Path payload) throws Exception {
        if (Files.size(payload) != asset.size || !hash(payload).equals(asset.sha512))
            throw new IOException("Downloaded launcher failed size or SHA-512 verification.");
    }

    static String hash(Path path) throws Exception {
        var digest = MessageDigest.getInstance("SHA-512");
        try (var source = new DigestInputStream(Files.newInputStream(path), digest)) {
            source.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static void handoff(Artifact asset, Path payload, boolean persian) throws Exception {
        Path jar = Path.of(LauncherUpdates.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
        Path recipient = asset.type == Type.INSTALLED
            ? jar.getParent().getParent().resolve("Gunnertimes.exe") : jar;
        if (!Files.isRegularFile(recipient)) throw new IOException("Could not locate current launcher: " + recipient);
        if (asset.type == Type.PORTABLE && (!Files.isWritable(recipient) || !Files.isWritable(recipient.getParent())))
            throw new IOException("Launcher folder is read-only: " + recipient.getParent());
        Path helper = payload.getParent().resolve("helper-" + UUID.randomUUID() + ".exe");
        try {
            try (InputStream executable = LauncherUpdates.class.getResourceAsStream("/UpdateHelper.exe")) {
                if (executable == null) throw new IOException("Missing launcher update helper.");
                Files.copy(executable, helper);
            }
            new ProcessBuilder(helper.toString(), Long.toString(ProcessHandle.current().pid()),
                asset.type.name(), payload.toString(), recipient.toString(),
                Path.of(System.getProperty("java.home"), "bin", "javaw.exe").toString(),
                asset.sha512, Boolean.toString(persian)).start();
        } catch (IOException failure) {
            Files.deleteIfExists(helper);
            throw failure;
        }
    }

    static PublicKey publicKey(String value) throws GeneralSecurityException {
        return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(value)));
    }

    static String signedNotes(Release release, PrivateKey key) throws Exception {
        byte[] manifest = Updater.JSON.toJson(release).getBytes(StandardCharsets.UTF_8);
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(key);
        signature.update(manifest);
        var encoder = Base64.getEncoder();
        return release.notes + "\n\n" + MARKER + encoder.encodeToString(manifest) + " "
            + encoder.encodeToString(signature.sign()) + " -->\n";
    }

    static Release verifyNotes(String notes, PublicKey key) throws Exception {
        int start = notes.lastIndexOf(MARKER);
        int end = start < 0 ? -1 : notes.indexOf(" -->", start);
        if (end < 0) throw new IOException("The launcher release has no signed manifest.");
        String[] envelope = notes.substring(start + MARKER.length(), end).split(" ");
        if (envelope.length != 2) throw new IOException("Invalid launcher release envelope.");
        var decoder = Base64.getDecoder();
        byte[] manifest = decoder.decode(envelope[0]);
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(key);
        verifier.update(manifest);
        if (!verifier.verify(decoder.decode(envelope[1]))) throw new IOException("Launcher release signature failed.");
        Release release = Updater.JSON.fromJson(new String(manifest, StandardCharsets.UTF_8), Release.class);
        parts(release.version);
        if (release.notes == null || release.artifacts == null || release.artifacts.size() != Type.values().length)
            throw new IOException("Incomplete launcher release manifest.");
        for (Type type : Type.values()) {
            Artifact asset = release.artifact(type);
            if (!type.filename.equals(asset.filename) || !assetUrl(release.version, type).equals(asset.url)
                    || asset.size <= 0 || asset.size > 500_000_000L || !asset.sha512.matches("[a-f0-9]{128}"))
                throw new IOException("Invalid artifact in launcher release: " + type.filename);
        }
        return release;
    }
}
