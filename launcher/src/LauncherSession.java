import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class LauncherSession {
    record Snapshot(Map<Updater.Step, String> steps, Map<String, Updater.Progress> counts,
        List<Updater.Progress> active, List<Updater.Progress> history, Updater.Progress current,
        String release, boolean offline, String log, long received, long verified, long reused,
        long bytesPerSecond, long currentFileBytesPerSecond) {}

    private final Map<Updater.Step, String> steps = new EnumMap<>(Updater.Step.class);
    private final Map<String, Updater.Progress> counts = new LinkedHashMap<>();
    private final Map<String, Updater.Progress> active = new LinkedHashMap<>();
    private final ArrayDeque<Updater.Progress> history = new ArrayDeque<>();
    private Updater.Progress current;
    private String release = "";
    private String log = "";
    private boolean offline;
    private long received;
    private long verified;
    private long reused;
    private long previousAt = System.nanoTime();
    private long previousBytes;
    private String previousFile = "";
    private long previousFileBytes;

    synchronized void accept(Updater.Progress event) {
        var step = event.step();
        var status = event.status();
        var file = event.file();
        String key = event.group() + "/" + file;
        if (event.group().equals("release") && (status == Updater.Status.DONE || status == Updater.Status.OFFLINE)) {
            release = file;
            offline = status == Updater.Status.OFFLINE;
        }
        if (event.group().equals("session") && !file.isEmpty()) log = file;
        if ((step == Updater.Step.START || step == Updater.Step.RUN) && !file.isEmpty()) log = file;

        if (status == Updater.Status.TRANSFER) {
            var previous = active.get(key);
            if (previous != null) {
                received += Math.max(0, event.count() - previous.count());
                active.put(key, event);
            }
            return;
        }

        if (step == Updater.Step.DOWNLOAD) {
            switch (status) {
                case BEGIN, RETRY -> active.put(key, event);
                case VERIFY, FAILED -> active.remove(key);
                case VERIFIED -> {
                    active.remove(key);
                    verified++;
                }
                default -> {}
            }
        }
        if (status == Updater.Status.FAILED && event.group().isEmpty()
                && current != null && current.status() == Updater.Status.FAILED) return;
        if (status == Updater.Status.REUSED) reused++;
        if (status == Updater.Status.COUNT) counts.put(step + "/" + event.group(), event);

        if (step == Updater.Step.INSTALL || step == Updater.Step.START) {
            steps.put(Updater.Step.CHECK, "done");
            steps.put(Updater.Step.DOWNLOAD, verified > 0 ? "done" : "skip");
        }
        if (step == Updater.Step.CHECK) {
            if (!"failed".equals(steps.get(step))) steps.put(step, "active");
        } else {
            steps.put(step, switch (status) {
                case DONE -> step == Updater.Step.RUN ? "closed" : "done";
                case SKIPPED -> "skip";
                case FAILED -> "failed";
                default -> step == Updater.Step.RUN ? "running" : "active";
            });
        }
        if (status == Updater.Status.FAILED) {
            steps.put(step, "failed");
        }
        if (status == Updater.Status.REUSED && event.group().equals("assets")) return;
        if (status == Updater.Status.COUNT && !event.file().isEmpty() && step == Updater.Step.CHECK) return;
        current = event;
        if (status != Updater.Status.COUNT || step == Updater.Step.INSTALL || event.count() == event.total()) {
            history.addLast(event);
            if (history.size() > 120) history.removeFirst();
        }
    }

    synchronized Snapshot snapshot() {
        long now = System.nanoTime();
        long elapsed = Math.max(1, now - previousAt);
        long speed = (long) ((received - previousBytes) * 1_000_000_000.0 / elapsed);
        var first = active.values().stream().findFirst().orElse(null);
        String firstKey = first == null ? "" : first.group() + "/" + first.file();
        long firstBytes = first == null ? 0 : first.count();
        long fileSpeed = firstKey.equals(previousFile)
            ? (long) (Math.max(0, firstBytes - previousFileBytes) * 1_000_000_000.0 / elapsed) : 0;
        previousAt = now;
        previousBytes = received;
        previousFile = firstKey;
        previousFileBytes = firstBytes;
        return new Snapshot(Map.copyOf(steps), new LinkedHashMap<>(counts), List.copyOf(active.values()),
            List.copyOf(history), current, release, offline, log, received, verified, reused, speed, fileSpeed);
    }
}
