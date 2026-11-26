import java.nio.file.*;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.*;

final class SignLauncherRelease {
    public static void main(String[] args) throws Exception {
        if (args.length == 3 && args[0].equals("generate-key")) {
            var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            Files.write(Path.of(args[1]), pair.getPrivate().getEncoded(), StandardOpenOption.CREATE_NEW);
            Files.writeString(Path.of(args[2]), Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()) + "\n",
                StandardOpenOption.CREATE_NEW);
            return;
        }
        if (args.length != 5 || !args[0].equals("sign"))
            throw new IllegalArgumentException("Usage: sign ARTIFACT_DIRECTORY PRIVATE_KEY NOTES OUTPUT");
        Path directory = Path.of(args[1]);
        Path jar = directory.resolve(LauncherUpdates.Type.PORTABLE.filename);
        String version;
        try (var archive = new java.util.jar.JarFile(jar.toFile());
             var input = archive.getInputStream(archive.getJarEntry("launcher-version.txt"))) {
            version = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).strip();
        }
        var assets = new ArrayList<LauncherUpdates.Artifact>();
        for (var type : LauncherUpdates.Type.values()) {
            Path file = directory.resolve(type.filename);
            assets.add(new LauncherUpdates.Artifact(type, type.filename, Files.size(file),
                LauncherUpdates.assetUrl(version, type), LauncherUpdates.hash(file)));
        }
        var key = KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(Path.of(args[2]))));
        var release = new LauncherUpdates.Release(version, Files.readString(Path.of(args[3])).strip(), assets);
        String notes = LauncherUpdates.signedNotes(release, key);
        var trusted = LauncherUpdates.publicKey(LauncherUpdates.resource("launcher-update-public.txt").strip());
        LauncherUpdates.verifyNotes(notes, trusted);
        Files.writeString(Path.of(args[4]), notes, StandardOpenOption.CREATE_NEW);
    }
}
