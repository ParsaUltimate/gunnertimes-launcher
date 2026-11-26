import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.zip.*;

final class LauncherContractTest {
    private static final Updater.ProgressSink SILENT = ignored -> {};

    private static void launcherReleaseTests(Path fixture, KeyPair key) throws Exception {
        require(Arrays.compare(LauncherUpdates.parts("1.10.0"), LauncherUpdates.parts("1.9.9")) > 0,
            "launcher uses numeric versions");
        try {
            LauncherUpdates.parts("1.10.0-rc.1");
            throw new AssertionError("Pre-release accepted");
        } catch (IllegalArgumentException expected) {}
        Path payload = Files.writeString(fixture.resolve("launcher-fixture.jar"), "verified update");
        var assets = new ArrayList<LauncherUpdates.Artifact>();
        for (var type : LauncherUpdates.Type.values()) {
            assets.add(new LauncherUpdates.Artifact(type, type.filename, Files.size(payload),
                LauncherUpdates.assetUrl("1.10.0", type), LauncherUpdates.hash(payload)));
        }
        var release = new LauncherUpdates.Release("1.10.0", "Plain release notes.", assets);
        String notes = LauncherUpdates.signedNotes(release, key.getPrivate());
        var verified = LauncherUpdates.verifyNotes(notes, key.getPublic());
        require(release.equals(verified), "round-trip signed launcher manifest");
        var wrongKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic();
        expectIo(() -> LauncherUpdates.verifyNotes(notes, wrongKey));
        var artifact = verified.artifact(LauncherUpdates.Type.PORTABLE);
        LauncherUpdates.verifyPayload(artifact, payload);
        Files.writeString(payload, "modified update");
        expectIo(() -> LauncherUpdates.verifyPayload(artifact, payload));
        Files.writeString(payload, "partial");
        expectIo(() -> LauncherUpdates.verifyPayload(artifact, payload));
        System.out.println("PASS: launcher update version, signatures, asset selection, bad SHA-512 and truncated download rejection.");
    }

    public static void main(String[] args) throws Exception {
        Path fixture = Files.createTempDirectory("gunnertimes-contract-");
        Path profile = fixture.resolve("Profile with spaces پروفایل");
        var key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        launcherReleaseTests(fixture, key);
        var config = new Updater.Config(fixture.resolve("manifest.json").toUri().toString(),
            Base64.getEncoder().encodeToString(key.getPublic().getEncoded()));
        var service = new PackService(fixture, config);
        Path jarA = fixture.resolve("a.jar");
        Path jarB = fixture.resolve("b.jar");
        writeJar(jarA, "fixture", "one");
        writeJar(jarB, "fixture", "two");
        var first = release(key, "1.6.2", jarA, Set.of());
        var second = release(key, "1.7.0", jarB, Set.of("1.7.0"));
        require(PackService.newer(first.version(), second.version()), "newer comparison");
        require(!PackService.newer(second.version(), first.version()), "downgrade rejected");
        require(service.installed(profile) == null, "fresh profile");
        require(!Files.exists(profile), "read-only installed lookup");

        service.apply(profile, first, Map.of(), SILENT);
        require(service.installed(profile).version().equals("1.6.2"), "first install pins signed release");
        var healthy = service.inspect(profile, service.installed(profile), SILENT);
        require(healthy.healthy(), "verified installation");
        require(healthy.items().getFirst().modName().equals("fixture") &&
            healthy.items().getFirst().modVersion().equals("one"), "display metadata comes from verified JAR");
        var officialCheck = new PackService.Check(second, Instant.now(), false, false, "");
        require(ServerStatus.compatibility(officialCheck, first.version()) == ServerStatus.Compatibility.INCOMPATIBLE,
            "signed explicit incompatibility");
        require(ServerStatus.compatibility(officialCheck, second.version()) == ServerStatus.Compatibility.COMPATIBLE,
            "signed explicit compatibility");
        require(ServerStatus.compatibility(new PackService.Check(second, Instant.now(), true, true, ""), first.version())
            == ServerStatus.Compatibility.UNKNOWN, "cached compatibility cannot block");
        require(ServerStatus.compatibility(PackService.Check.empty(), first.version())
            == ServerStatus.Compatibility.UNKNOWN, "missing compatibility cannot block");
        require(service.installed(profile).version().equals(first.version()), "newer release leaves pinned release alone");

        Path installedJar = profile.resolve("mods/fixture.jar");
        Path saves = Files.createDirectories(profile.resolve("saves"));
        Files.writeString(saves.resolve("keep.txt"), "player-owned");
        Path manual = profile.resolve("mods/manual.jar");
        writeJar(manual, "manual", "untouched");

        var events = new ArrayList<Updater.Progress>();
        Files.move(jarB, fixture.resolve("held.jar"));
        expectIo(() -> service.apply(profile, second, Map.of(), events::add));
        require(events.stream().anyMatch(event -> event.step() == Updater.Step.DOWNLOAD &&
            event.status() == Updater.Status.FAILED), "interrupted update reports download");
        require(service.installed(profile).version().equals(first.version()), "interrupted update preserves pin");
        require(Updater.hash(installedJar).equals(first.pack().files().getFirst().sha512()), "interrupted update preserves mod");
        Files.move(fixture.resolve("held.jar"), jarB);
        service.apply(profile, second, Map.of(), SILENT);
        require(service.installed(profile).version().equals(second.version()), "explicit update replaces pin");
        require(Files.readString(saves.resolve("keep.txt")).equals("player-owned") && Files.isRegularFile(manual),
            "update preserves player worlds and manual mods");

        Files.writeString(installedJar, "damaged");
        var preview = service.inspect(profile, second, SILENT);
        require(preview.problems().getFirst().condition() == PackService.Condition.DAMAGED_OWNED, "repair preview");
        require(Files.readString(installedJar).equals("damaged"), "verification never modifies mod");
        require(preview.problems().getFirst().modVersion().isEmpty(), "damaged files do not supply a version");
        Files.writeString(installedJar, "changed after preview");
        expectIo(() -> service.apply(profile, second, preview.approvedRepairs(), SILENT));
        require(Files.readString(installedJar).equals("changed after preview"), "stale preview cannot authorize replacement");
        var current = service.inspect(profile, second, SILENT);
        service.apply(profile, second, current.approvedRepairs(), SILENT);
        require(service.inspect(profile, second, SILENT).healthy(), "approved repair restores signed file");
        try (var backups = Files.find(profile.resolve(Updater.STATE), 3,
                (file, attrs) -> attrs.isRegularFile() && file.getFileName().toString().startsWith("old-"))) {
            require(backups.anyMatch(file -> {
                try { return Files.readString(file).equals("changed after preview"); }
                catch (IOException ignored) { return false; }
            }), "replaced file has a backup");
        }

        writeJar(manual, "fixture", "conflicting");
        var conflict = service.inspect(profile, second, SILENT);
        require(conflict.problems().getFirst().condition() == PackService.Condition.CONFLICT
            && conflict.problems().getFirst().location().equals("mods/manual.jar"), "conflict names manual mod");
        expectIo(() -> service.apply(profile, second, Map.of(), SILENT));
        require(Files.isRegularFile(manual), "manual conflict preserved");
        writeJar(manual, "manual", "untouched");

        var old = Updater.JSON.fromJson("{\"username\":\"QAUser\",\"profile\":\"C:/profile\",\"ramGiB\":6}",
            GunnertimesLauncher.Preferences.class);
        require(old.options().equals(LaunchOptions.Settings.defaults()) && old.ramGiB() == 6 &&
            old.language().equals("en") && old.username().equals("QAUser"), "legacy preference migration");
        require(GunnertimesLauncher.profilePath(profile.resolve("mods").toString()).equals(profile), "mods parent");
        require(GunnertimesLauncher.profilePath("%LOCALAPPDATA%\\Gunnertimes").equals(GunnertimesLauncher.HOME),
            "copyable environment path");

        Path optionsFile = profile.resolve("options.txt");
        Files.writeString(optionsFile, "fullscreen:true\noverrideWidth:640\nvolume:0.3\n");
        var options = new LaunchOptions.Settings("reduce", "minimize", "windowed", 1100, 700, "bundled", "");
        try (var display = LaunchOptions.displayOverride(profile, options)) {
            String applied = Files.readString(optionsFile);
            require(applied.contains("fullscreen:false") && applied.contains("overrideWidth:1100")
                && applied.contains("overrideHeight:700"), "actual display overrides");
            Files.writeString(optionsFile, applied.replace("volume:0.3", "volume:0.4"));
        }
        require(Files.readString(optionsFile).equals("fullscreen:true\noverrideWidth:640\nvolume:0.4\n"),
            "restore display settings and retain in-game edits");
        require(LaunchOptions.reduceMotion(options), "explicit reduced motion");
        Path bundled = LaunchOptions.resolve(options).executable();
        require(LaunchOptions.validateJava(bundled.toString()).version().startsWith("25"), "packaged Java 25 x64");
        expectIo(() -> LaunchOptions.validateJava(fixture.resolve("missing/java.exe").toString()));
        require(new LauncherText("fa").rtl && !new LauncherText("en").rtl, "locale orientation");

        try (var status = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread connection = Thread.ofVirtual().start(() -> reply(status));
            var response = ServerStatus.probe("127.0.0.1", status.getLocalPort(), -1);
            connection.join();
            require(response.state() == ServerStatus.State.ONLINE && response.players() == 12,
                "player count comes from a protocol response");
        }
        require(ServerStatus.probe("127.0.0.1", 0, -1).state() == ServerStatus.State.UNKNOWN,
            "unreachable server remains unknown");
        var session = new LauncherSession();
        session.accept(Updater.Progress.of(Updater.Step.RUN, Updater.Status.BEGIN, "game", "game.log"));
        session.accept(Updater.Progress.of(Updater.Step.RUN, Updater.Status.FAILED, "join", ServerStatus.ADDRESS));
        require(session.isRunning() && session.snapshot().join().equals("failed")
            && session.snapshot().log().equals("game.log"), "connection outcome separate from client process/log");
        System.out.println("PASS: install, pin, explicit update, interrupted update, retry, repair, conflict, migration, Java, display, compatibility and status.");
    }

    private static PackService.Release release(KeyPair keys, String version, Path file, Set<String> allowed)
            throws Exception {
        var entry = new Updater.Entry("mods/fixture.jar", Updater.hash(file), file.toUri().toString(), "fixture");
        var pack = new Updater.Pack(1, version, List.of(entry));
        var json = Updater.JSON.toJsonTree(pack).getAsJsonObject();
        if (!allowed.isEmpty()) json.add("serverCompatibility", Updater.JSON.toJsonTree(Map.of(
            "address", ServerStatus.ADDRESS, "expires", Instant.now().plusSeconds(300).toString(),
            "allowedPackVersions", allowed)));
        byte[] bytes = Updater.JSON.toJson(json).getBytes(StandardCharsets.UTF_8);
        var signer = Signature.getInstance("Ed25519");
        signer.initSign(keys.getPrivate());
        signer.update(bytes);
        var encoder = Base64.getEncoder();
        return new PackService.Release(pack, new PackService.Envelope(encoder.encodeToString(bytes),
            encoder.encodeToString(signer.sign())));
    }

    private static void writeJar(Path file, String id, String version) throws Exception {
        try (var zip = new ZipOutputStream(Files.newOutputStream(file))) {
            zip.putNextEntry(new ZipEntry("fabric.mod.json"));
            zip.write(Updater.JSON.toJson(Map.of("id", id, "name", id, "version", version)).getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void reply(ServerSocket server) {
        try (var connection = server.accept()) {
            var in = new DataInputStream(connection.getInputStream());
            in.readNBytes(in.readUnsignedByte());
            in.readNBytes(in.readUnsignedByte());
            byte[] response = "{\"version\":{\"name\":\"fixture\",\"protocol\":1},\"players\":{\"online\":12}}"
                .getBytes(StandardCharsets.UTF_8);
            var out = new DataOutputStream(connection.getOutputStream());
            out.writeByte(response.length + 2);
            out.writeByte(0);
            out.writeByte(response.length);
            out.write(response);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static void require(boolean success, String reason) {
        if (!success) throw new AssertionError(reason);
    }

    private interface Operation { void run() throws Exception; }

    private static void expectIo(Operation action) throws Exception {
        try {
            action.run();
            throw new AssertionError("Expected an I/O error");
        } catch (IOException expected) {}
    }
}
