import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.FutureTask;
import java.util.zip.*;
import javax.swing.*;

/** Exercises the production Swing actions with a signed, bundled QA channel. */
final class PackUpgradeUiTest {
    private static final String OLD = "gunnertimes-2026.11.24";
    private static final String NEW = "gunnertimes-2026.11.25";
    private static LauncherWindow app;
    private static Path evidence;
    private static Path payloads;
    private static KeyPair key;

    public static void main(String[] args) throws Exception {
        Path resources = Path.of(args[0]).toRealPath();
        evidence = Files.createDirectories(Path.of(args[1]));
        payloads = Files.createDirectories(resources.resolve("gunnertimes-payload"));
        key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Files.writeString(resources.resolve("gunnertimes-channel.json"), Updater.JSON.toJson(
            new Updater.Config("bundle:/manifest.json", Base64.getEncoder().encodeToString(key.getPublic().getEncoded()))));
        String hashA = publish(OLD, "A", true);
        Path profile = Files.createDirectories(evidence.resolve("Profile فارسی"));
        var keep = new LinkedHashMap<Path, String>();
        for (String relative : List.of("saves/World/level.dat", "config/settings.txt", "mods/manual.jar")) {
            Path path = profile.resolve(relative);
            Files.createDirectories(path.getParent());
            if (relative.endsWith(".jar")) writeJar(path, "manual", "1");
            else Files.writeString(path, relative);
            keep.put(path, Updater.hash(path));
        }
        String launcherHash = Updater.hash(Path.of(LauncherUpdates.class.getProtectionDomain().getCodeSource().getLocation().toURI()));
        SwingUtilities.invokeAndWait(() -> {
            app = new LauncherWindow(new GunnertimesLauncher.Preferences("PackQA", profile.toString(), "en", 4, null),
                ignored -> {}, () -> null);
            app.show();
        });
        try {
            Path official = profile.resolve("mods/fixture.jar");
            var service = new PackService(evidence);
            await(() -> ui(() -> app.availableVersion().equals(OLD)), "release A visible");
            clickDialog("Install", "01-pack-install-preview");
            await(() -> Files.isRegularFile(official) && Updater.hash(official).equals(hashA)
                && ui(() -> app.installedVersion().equals(OLD)), "A installed");
            String hashB = publish(NEW, "B", false);
            ui(() -> { app.checkNow(); return true; });
            await(() -> ui(() -> app.availableVersion().equals(NEW)), "release B visible");
            require(Updater.hash(official).equals(hashA), "check changed A");
            clickDialog("Update", "02-pack-update-preview");
            // The manifest is valid, but the received bytes end before the complete payload.
            await(() -> containsLog(profile, "FAILED"), "truncated payload was reported");
            require(service.installed(profile).version().equals(OLD) && Updater.hash(official).equals(hashA), "failed update changed A");
            await(() -> ui(() -> !app.checking()), "refresh after failure");
            ui(() -> { LauncherUpgradeAgent.capture(app.frame(), evidence.resolve("03-pack-failure.png")); return true; });
            publish(NEW, "B", true);
            clickDialog("Update", "04-pack-retry");
            await(() -> Updater.hash(official).equals(hashB) && ui(() -> app.installedVersion().equals(NEW)), "B installed");
            click(app.frame(), "Pack");
            click(app.frame(), "Verify files");
            await(() -> ui(() -> !app.checking()), "pack verification");
            require(service.inspect(profile, service.installed(profile), ignored -> {}).healthy(), "B unhealthy");
            ui(() -> { LauncherUpgradeAgent.capture(app.frame(), evidence.resolve("05-pack-verified.png")); return true; });
            for (var entry : keep.entrySet()) require(Updater.hash(entry.getKey()).equals(entry.getValue()), "changed " + entry.getKey());
            require(launcherHash.equals(Updater.hash(Path.of(LauncherUpdates.class.getProtectionDomain().getCodeSource().getLocation().toURI()))),
                "pack updated the launcher");
            System.out.println("PASS: Pack Home Install, explicit Update, truncated payload, retry, Verify files, preserved player files and launcher.");
        } finally {
            SwingUtilities.invokeAndWait(() -> app.frame().dispose());
        }
    }

    private static String publish(String release, String version, boolean available) throws Exception {
        Path jar = evidence.resolve("fixture-source.jar");
        if (version.equals("A") || !Files.exists(evidence.resolve("fixture-B.jar"))) {
            writeJar(jar, "fixture", version);
            if (version.equals("B")) Files.copy(jar, evidence.resolve("fixture-B.jar"));
        } else jar = evidence.resolve("fixture-B.jar");
        String hash = Updater.hash(jar);
        var entry = new Updater.Entry("mods/fixture.jar", hash, "bundle:/" + hash, "fixture");
        byte[] manifest = Updater.JSON.toJson(new Updater.Pack(1, release, List.of(entry))).getBytes(StandardCharsets.UTF_8);
        var signer = Signature.getInstance("Ed25519");
        signer.initSign(key.getPrivate());
        signer.update(manifest);
        Files.write(payloads.resolve("manifest.json"), manifest);
        Files.write(payloads.resolve("manifest.json.sig"), signer.sign());
        if (available) {
            Files.copy(jar, payloads.resolve(hash), StandardCopyOption.REPLACE_EXISTING);
        } else {
            byte[] complete = Files.readAllBytes(jar);
            Files.write(payloads.resolve(hash), Arrays.copyOf(complete, complete.length / 2));
        }
        return hash;
    }

    private static void writeJar(Path path, String id, String version) throws Exception {
        try (var jar = new ZipOutputStream(Files.newOutputStream(path))) {
            jar.putNextEntry(new ZipEntry("fabric.mod.json"));
            jar.write(Updater.JSON.toJson(Map.of("id", id, "name", id, "version", version)).getBytes(StandardCharsets.UTF_8));
        }
    }

    private static boolean containsLog(Path profile, String value) throws Exception {
        try (var paths = Files.list(profile.resolve("logs"))) {
            for (Path file : paths.toList()) if (Files.readString(file).contains(value)) return true;
        }
        return false;
    }

    private static void clickDialog(String action, String image) throws Exception {
        click(app.frame(), action);
        await(() -> ui(() -> {
            for (var window : app.frame().getOwnedWindows()) {
                if (window instanceof JDialog dialog && dialog.isShowing()) {
                    JButton button = LauncherUpgradeAgent.find(dialog, "Continue");
                    if (button != null) {
                        LauncherUpgradeAgent.capture(dialog, evidence.resolve(image + ".png"));
                        SwingUtilities.invokeLater(() -> button.doClick(0));
                        return true;
                    }
                }
            }
            return false;
        }), action + " preview");
    }

    private static void click(java.awt.Container root, String label) throws Exception {
        await(() -> ui(() -> {
            JButton button = LauncherUpgradeAgent.find(root, label);
            if (button == null || !button.isEnabled()) return false;
            SwingUtilities.invokeLater(() -> button.doClick(0));
            return true;
        }), label + " enabled");
    }

    private static boolean ui(java.util.concurrent.Callable<Boolean> task) throws Exception {
        var future = new FutureTask<>(task);
        SwingUtilities.invokeAndWait(future);
        return future.get();
    }

    private static void await(java.util.concurrent.Callable<Boolean> condition, String label) throws Exception {
        for (int i = 0; i < 700; i++) {
            if (condition.call()) return;
            Thread.sleep(100);
        }
        throw new AssertionError(label);
    }

    private static void require(boolean check, String label) {
        if (!check) throw new AssertionError(label);
    }
}
