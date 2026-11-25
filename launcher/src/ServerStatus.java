import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

final class ServerStatus {
    static final String HOST = "blade.9craft.ir";
    static final int PORT = 18387;
    static final String ADDRESS = HOST + ":" + PORT;
    enum State { CHECKING, ONLINE, UNKNOWN }
    enum Compatibility { UNKNOWN, COMPATIBLE, INCOMPATIBLE }
    record Result(State state, Integer players, String gameVersion) {
        static Result unknown() { return new Result(State.UNKNOWN, null, ""); }
    }

    static Result probe() { return probe(HOST, PORT, clientProtocol()); }

    static Result probe(String host, int port, int protocol) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 3500);
            socket.setSoTimeout(3500);
            var handshake = new ByteArrayOutputStream();
            var data = new DataOutputStream(handshake);
            writeVar(data, 0);
            writeVar(data, protocol);
            byte[] address = host.getBytes(StandardCharsets.UTF_8);
            writeVar(data, address.length);
            data.write(address);
            data.writeShort(port);
            writeVar(data, 1);
            var out = new DataOutputStream(socket.getOutputStream());
            writeVar(out, handshake.size());
            handshake.writeTo(out);
            writeVar(out, 1);
            writeVar(out, 0);
            out.flush();
            var input = new DataInputStream(socket.getInputStream());
            int frameLength = readVar(input);
            if (frameLength < 2 || frameLength > 65_536) return Result.unknown();
            byte[] frame = input.readNBytes(frameLength);
            if (frame.length != frameLength) return Result.unknown();
            var body = new DataInputStream(new ByteArrayInputStream(frame));
            if (readVar(body) != 0) return Result.unknown();
            int jsonLength = readVar(body);
            if (jsonLength <= 0 || jsonLength != body.available()) return Result.unknown();
            var status = JsonParser.parseString(new String(body.readNBytes(jsonLength), StandardCharsets.UTF_8))
                .getAsJsonObject();
            var version = status.getAsJsonObject("version");
            if (version == null || !version.has("protocol")) return Result.unknown();
            var players = status.getAsJsonObject("players");
            Integer count = players != null && players.has("online") ? players.get("online").getAsInt() : null;
            String game = version.has("name") ? version.get("name").getAsString() : "";
            return new Result(State.ONLINE, count != null && count >= 0 ? count : null, game);
        } catch (IOException | RuntimeException ignored) {
            return Result.unknown();
        }
    }

    static Compatibility compatibility(PackService.Check check, String installed) {
        if (check.available() == null || check.stale() || installed == null) return Compatibility.UNKNOWN;
        try {
            byte[] signedBytes = java.util.Base64.getDecoder().decode(check.available().envelope().manifest());
            var server = MinecraftFiles.json(signedBytes).getAsJsonObject("serverCompatibility");
            if (server == null || !ADDRESS.equalsIgnoreCase(server.get("address").getAsString()))
                return Compatibility.UNKNOWN;
            var expiry = java.time.Instant.parse(server.get("expires").getAsString());
            if (!expiry.isAfter(java.time.Instant.now())) return Compatibility.UNKNOWN;
            var releases = server.getAsJsonArray("allowedPackVersions");
            if (releases == null || releases.isEmpty()) return Compatibility.UNKNOWN;
            return releases.asList().stream().map(JsonElement::getAsString).anyMatch(installed::equals)
                ? Compatibility.COMPATIBLE : Compatibility.INCOMPATIBLE;
        } catch (RuntimeException ignored) {
            return Compatibility.UNKNOWN;
        }
    }

    private static int clientProtocol() {
        Path jar = GunnertimesLauncher.CACHE.resolve("versions/" + MinecraftFiles.VERSION + ".jar");
        try (var archive = new java.util.zip.ZipFile(jar.toFile());
             var stream = archive.getInputStream(archive.getEntry("version.json"))) {
            return MinecraftFiles.json(stream.readNBytes(32_768)).get("protocol_version").getAsInt();
        } catch (IOException | RuntimeException ignored) {
            return -1;
        }
    }

    private static void writeVar(DataOutputStream out, int value) throws IOException {
        while ((value & ~127) != 0) {
            out.writeByte((value & 127) | 128);
            value >>>= 7;
        }
        out.writeByte(value);
    }

    private static int readVar(DataInputStream in) throws IOException {
        int result = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int next = in.readUnsignedByte();
            result |= (next & 127) << shift;
            if ((next & 128) == 0) return result;
        }
        throw new IOException("Invalid status packet");
    }
}
