import java.nio.file.*;
import java.util.ArrayList;

/** Run with APPDATA pointed at an isolated QA copy of a healthy Minecraft installation. */
final class SharedMinecraftTest {
    public static void main(String[] args) throws Exception {
        Path shared = MinecraftFiles.sharedDirectory();
        Path client = MinecraftFiles.sharedClient();
        Path profile = Path.of(args[0]);
        Path legacy = Path.of(args[1]);
        var clientStamp = Files.getLastModifiedTime(client);
        Path deletedAsset = shared.resolve("assets/objects").resolve(args[2]);
        if (Files.exists(deletedAsset)) throw new AssertionError("Remove the test asset from the QA copy first");
        var events = new ArrayList<Updater.Progress>();
        var command = new MinecraftFiles(legacy, event -> {
            synchronized (events) { events.add(event); }
        }).prepare(profile, "PlayerQA");
        if (command.stream().noneMatch(argument -> argument.contains(client.toString())) ||
                !command.contains(shared.resolve("assets").toString()) ||
                command.stream().noneMatch(argument -> argument.contains(shared.resolve("libraries").toString())))
            throw new AssertionError("Launch command is missing direct shared paths");
        if (!Files.getLastModifiedTime(client).equals(clientStamp)) throw new AssertionError("Healthy client was replaced");
        if (!Files.isRegularFile(deletedAsset) || events.stream().noneMatch(event ->
                event.status() == Updater.Status.VERIFIED && event.file().equals(deletedAsset.getFileName().toString())))
            throw new AssertionError("Missing asset was not downloaded and verified");
        for (String folder : new String[]{"mods", "config", "saves"})
            if (!Files.readString(profile.resolve(folder).resolve("keep.txt")).equals(folder))
                throw new AssertionError("Player data changed: " + folder);
        System.out.println("PASS: direct Minecraft client/library/asset paths, healthy file reuse, verified asset download and profile preservation.");
    }
}
