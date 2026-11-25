import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

final class LaunchOptions {
    record Settings(String motion, String launchWindow, String display, int width, int height,
                    String javaMode, String javaPath) {
        Settings {
            if (!List.of("system", "reduce", "full").contains(Objects.toString(motion, ""))) motion = "system";
            if (!"minimize".equals(launchWindow)) launchWindow = "visible";
            if (!Set.of("saved", "fullscreen", "windowed").contains(Objects.toString(display, ""))) display = "saved";
            if (width < 640 || width > 7680 || height < 360 || height > 4320) width = height = 0;
            if (!"bundled".equals(javaMode) && (!"custom".equals(javaMode) || javaPath == null || javaPath.isBlank()))
                javaMode = "auto";
            if (javaPath == null) javaPath = "";
        }
        static Settings defaults() { return new Settings("system", "visible", "saved", 0, 0, "auto", ""); }
    }

    record JavaInfo(Path executable, String version, String architecture) {}

    static JavaInfo resolve(Settings settings) throws IOException, InterruptedException {
        if (settings.javaMode.equals("custom")) return validateJava(settings.javaPath);
        if (settings.javaMode.equals("bundled")) return bundled();
        Set<Path> directories = new LinkedHashSet<>();
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null) {
            try { directories.add(Path.of(javaHome, "bin")); } catch (InvalidPathException ignored) {}
        }
        for (String entry : System.getenv().getOrDefault("PATH", "").split(";")) {
            try {
                if (!entry.isBlank()) directories.add(Path.of(entry.replace("\"", "")));
            } catch (InvalidPathException ignored) {}
        }
        for (Path directory : directories) {
            Path candidate = directory.toAbsolutePath().normalize().resolve("java.exe");
            if (!Files.isRegularFile(candidate)) continue;
            if (candidate.getParent().getParent() == null) continue;
            Path release = candidate.getParent().getParent().resolve("release");
            if (!Files.isRegularFile(release)) continue;
            String metadata;
            try { metadata = Files.readString(release); } catch (IOException ignored) { continue; }
            if (!metadata.contains("JAVA_VERSION=\"25.") && !metadata.contains("JAVA_VERSION=\"25\"")) continue;
            try { return validateJava(candidate.toString()); } catch (IOException ignored) {}
        }
        return bundled();
    }

    private static JavaInfo bundled() {
        return new JavaInfo(Path.of(System.getProperty("java.home"), "bin", "java.exe"),
            System.getProperty("java.version"), System.getProperty("os.arch"));
    }

    static JavaInfo validateJava(String value) throws IOException, InterruptedException {
        try {
            Path path = Path.of(value.strip());
            if (!path.isAbsolute() || path.getFileName() == null ||
                    !path.getFileName().toString().equalsIgnoreCase("java.exe") || !Files.isRegularFile(path))
                throw new IOException("Choose an existing java.exe.");
            ProcessBuilder probe = new ProcessBuilder(path.toString(), "-XshowSettings:properties", "-version")
                .redirectErrorStream(true);
            for (String key : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"))
                probe.environment().remove(key);
            Process child = probe.start();
            var read = new java.util.concurrent.FutureTask<>(() -> {
                try (var stdout = child.getInputStream()) {
                    return stdout.readNBytes(65_536);
                }
            });
            Thread.ofVirtual().start(read);
            try {
                if (!child.waitFor(5, TimeUnit.SECONDS)) throw new IOException("Java validation timed out.");
                String output = new String(read.get(1, TimeUnit.SECONDS), StandardCharsets.UTF_8);
                String version = property(output, "java.version");
                String arch = property(output, "os.arch");
                if (child.exitValue() != 0 || !version.matches("25(?:[.+-].*)?")
                        || !Set.of("amd64", "x86_64").contains(arch))
                    throw new IOException("Gunnertimes requires Java 25 x64.");
                return new JavaInfo(path.toRealPath(), version, arch);
            } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure) {
                throw new IOException("Could not read Java version information.", failure);
            } finally {
                if (child.isAlive()) child.destroyForcibly();
                read.cancel(true);
            }
        } catch (InvalidPathException failure) {
            throw new IOException("Invalid Java path.", failure);
        }
    }

    private static String property(String output, String key) {
        var match = Pattern.compile("(?m)^\\s*" + Pattern.quote(key) + "\\s*=\\s*([^\\r\\n]+)").matcher(output);
        return match.find() ? match.group(1).strip() : "";
    }

    static long physicalMemory() {
        var bean = ManagementFactory.getOperatingSystemMXBean();
        return bean instanceof com.sun.management.OperatingSystemMXBean extended ? extended.getTotalMemorySize() : 0;
    }

    static boolean reduceMotion(Settings settings) {
        return settings.motion.equals("reduce") || settings.motion.equals("system")
            && (Boolean.FALSE.equals(java.awt.Toolkit.getDefaultToolkit().getDesktopProperty("win.menu.animation"))
            || Boolean.FALSE.equals(java.awt.Toolkit.getDefaultToolkit().getDesktopProperty("win.uiEffects")));
    }

    static DisplayOverride displayOverride(Path profile, Settings options) throws IOException {
        var requested = new LinkedHashMap<String, String>();
        if (!options.display.equals("saved"))
            requested.put("fullscreen", Boolean.toString(options.display.equals("fullscreen")));
        if (options.display.equals("windowed") && options.width > 0) {
            requested.put("overrideWidth", Integer.toString(options.width));
            requested.put("overrideHeight", Integer.toString(options.height));
        }
        return new DisplayOverride(profile.resolve("options.txt"), requested);
    }

    static final class DisplayOverride implements AutoCloseable {
        private final Path file;
        private final Map<String, String> overrides;
        private final Map<String, String> previous = new LinkedHashMap<>();

        DisplayOverride(Path file, Map<String, String> overrides) throws IOException {
            this.file = file;
            this.overrides = overrides;
            if (overrides.isEmpty()) return;
            if (Files.isSymbolicLink(file)) throw new IOException("Linked game options.");
            List<String> lines = read();
            for (var item : overrides.entrySet()) {
                previous.put(item.getKey(), value(lines, item.getKey()));
                replace(lines, item.getKey(), item.getValue());
            }
            write(lines);
        }

        @Override public void close() throws IOException {
            if (overrides.isEmpty()) return;
            List<String> lines = read();
            boolean changed = false;
            for (var item : overrides.entrySet()) {
                if (Objects.equals(value(lines, item.getKey()), item.getValue())) {
                    replace(lines, item.getKey(), previous.get(item.getKey()));
                    changed = true;
                }
            }
            if (changed) write(lines);
        }

        private List<String> read() throws IOException {
            return Files.exists(file) ? new ArrayList<>(Files.readAllLines(file)) : new ArrayList<>();
        }

        private void write(List<String> lines) throws IOException {
            MinecraftFiles.write(file, (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8));
        }

        private static String value(List<String> lines, String key) {
            String prefix = key + ":";
            for (String line : lines) if (line.startsWith(prefix)) return line.substring(prefix.length());
            return null;
        }

        private static void replace(List<String> lines, String key, String value) {
            String prefix = key + ":";
            for (int index = 0; index < lines.size(); index++) {
                if (!lines.get(index).startsWith(prefix)) continue;
                if (value == null) lines.remove(index);
                else lines.set(index, prefix + value);
                return;
            }
            if (value != null) lines.add(prefix + value);
        }
    }
}
