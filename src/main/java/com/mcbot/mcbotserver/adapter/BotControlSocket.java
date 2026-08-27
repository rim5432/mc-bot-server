package com.mcbot.mcbotserver.adapter;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.rcon.RconConsoleSource;

/**
 * RCON-shaped control socket for servers the vanilla thread does not
 * cover - the dev client's integrated server. Vanilla
 * {@code RconThread.create} demands DedicatedServerProperties through
 * ServerInterface, which IntegratedServer does not implement; this
 * bridge speaks the same Source protocol (little-endian length-
 * prefixed packets, auth type 3, exec type 2) so tool/rcon.py drives
 * either server kind unchanged.
 *
 * <p>Config comes from the same run/server.properties the dedicated
 * server reads (enable-rcon / rcon.port / rcon.password); a client
 * otherwise ignores that file, so there is exactly one config story.
 * Dedicated servers are refused - their vanilla RCON already owns
 * the port, and two listeners would race.
 *
 * <p>Command execution hops to the server tick thread through
 * MinecraftServer.execute and the answer waits on a latch, keeping
 * the one-question-one-answer shape every /bot verb documents.
 * Output capture reuses vanilla RconConsoleSource, which takes the
 * base MinecraftServer type and buffers command feedback.
 */
public final class BotControlSocket {

    /** Per-command wait ceiling; a wedged main thread must not hang
     *  the socket reader forever. */
    private static final long EXEC_WAIT_SECONDS = 10;

    private static volatile ServerSocket listener;
    private static Thread acceptThread;

    private BotControlSocket() {}

    /**
     * Opens the bridge when this server has no vanilla RCON of its
     * own and run/server.properties enables it. Safe to call for
     * every server kind; dedicated servers return immediately.
     *
     * @param server the started server; never null
     */
    public static void startIfEnabled(MinecraftServer server) {
        if (server instanceof DedicatedServer) {
            return;
        }
        Properties props = readServerProperties();
        if (!"true".equalsIgnoreCase(props.getProperty("enable-rcon", "false"))) {
            return;
        }
        String password = props.getProperty("rcon.password", "");
        if (password.isBlank()) {
            return;
        }
        int port = Integer.parseInt(props.getProperty("rcon.port", "25575"));
        try {
            listener = new ServerSocket(port, 8, InetAddress.getByName("127.0.0.1"));
        } catch (IOException e) {
            return;
        }
        acceptThread = new Thread(() -> acceptLoop(server, password), "mcbotserver-control-socket");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    /**
     * Closes the listener; in-flight connections die with it. Safe
     * to call when never started.
     */
    public static void stop() {
        ServerSocket s = listener;
        listener = null;
        if (s != null && !s.isClosed()) {
            try {
                s.close();
            } catch (IOException ignored) {
                // Closing is best-effort during shutdown.
            }
        }
    }

    private static void acceptLoop(MinecraftServer server, String password) {
        while (listener != null) {
            try {
                Socket client = listener.accept();
                Thread t = new Thread(() -> serve(server, password, client), "mcbotserver-control-client");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                return;
            }
        }
    }

    private static void serve(MinecraftServer server, String password, Socket client) {
        try (client) {
            client.setSoTimeout(30_000);
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();
            boolean authed = false;
            while (true) {
                // Source framing: length covers id + type + payload
                // plus the two NUL terminators, so the payload is
                // exactly length - 10 bytes.
                int length = readLittleEndianInt(in);
                if (length < 10 || length > 8192) {
                    throw new IOException("unreasonable packet length: " + length);
                }
                int id = readLittleEndianInt(in);
                int type = readLittleEndianInt(in);
                String payload = readPayload(in, length - 10);
                if (type == 3) {
                    authed = constantTimeEquals(payload, password);
                    writePacket(out, authed ? id : -1, type, "");
                    continue;
                }
                if (type == 2) {
                    String response = authed ? execute(server, payload) : "";
                    writePacket(out, authed ? id : -1, type, response);
                    continue;
                }
                return;
            }
        } catch (IOException | InterruptedException e) {
            // Connection-level failure or shutdown: drop the client.
        }
    }

    /**
     * Runs one command on the tick thread and returns its chat-text
     * answer. Mirrors DedicatedServer's RCON execution shape.
     */
    private static String execute(MinecraftServer server, String command) throws InterruptedException {
        RconConsoleSource out = new RconConsoleSource(server);
        CountDownLatch done = new CountDownLatch(1);
        server.execute(() -> {
            try {
                out.prepareForCommand();
                server.getCommands().performPrefixedCommand(out.createCommandSourceStack(), command);
            } finally {
                done.countDown();
            }
        });
        done.await(EXEC_WAIT_SECONDS, TimeUnit.SECONDS);
        return out.getCommandResponse();
    }

    /**
     * Reads run/server.properties from disk. The dev client's
     * working directory differs between launch styles, so both the
     * bare name and the run/ prefix are tried.
     *
     * @return parsed properties; empty when no file exists
     */
    private static Properties readServerProperties() {
        Properties props = new Properties();
        for (Path candidate : List.of(Path.of("server.properties"), Path.of("run", "server.properties"))) {
            if (Files.isReadable(candidate)) {
                try (InputStream in = Files.newInputStream(candidate)) {
                    props.load(in);
                } catch (IOException ignored) {
                    // Unreadable file means disabled - use defaults.
                }
                return props;
            }
        }
        return props;
    }

    private static boolean constantTimeEquals(String a, String b) {
        byte[] x = a.getBytes(StandardCharsets.UTF_8);
        byte[] y = b.getBytes(StandardCharsets.UTF_8);
        int diff = x.length ^ y.length;
        for (int i = 0; i < Math.min(x.length, y.length); i++) {
            diff |= x[i] ^ y[i];
        }
        return diff == 0;
    }

    private static int readLittleEndianInt(InputStream in) throws IOException {
        int b0 = in.read();
        int b1 = in.read();
        int b2 = in.read();
        int b3 = in.read();
        if ((b0 | b1 | b2 | b3) < 0) {
            throw new EOFException("packet header truncated");
        }
        return (b3 << 24) | (b2 << 16) | (b1 << 8) | b0;
    }

    /**
     * Reads exactly {@code textLength} payload bytes and strips the
     * trailing NUL terminators.
     *
     * @param in         the packet stream; never null
     * @param textLength exact payload byte count; non-negative
     * @return decoded payload text; empty string when absent
     * @throws IOException when the stream breaks mid-packet
     */
    private static String readPayload(InputStream in, int textLength) throws IOException {
        byte[] body = new byte[textLength];
        int off = 0;
        while (off < textLength) {
            int n = in.read(body, off, textLength - off);
            if (n < 0) {
                throw new EOFException("packet body truncated");
            }
            off += n;
        }
        int end = textLength;
        while (end > 0 && body[end - 1] == 0) {
            end--;
        }
        return new String(body, 0, end, StandardCharsets.UTF_8);
    }

    private static void writePacket(OutputStream out, int id, int type, String payload) throws IOException {
        byte[] text = payload.getBytes(StandardCharsets.UTF_8);
        int size = 4 + 4 + text.length + 2;
        writeLittleEndianInt(out, size);
        writeLittleEndianInt(out, id);
        writeLittleEndianInt(out, type);
        out.write(text);
        out.write(0);
        out.write(0);
        out.flush();
    }

    private static void writeLittleEndianInt(OutputStream out, int v) throws IOException {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
        out.write((v >> 16) & 0xFF);
        out.write((v >> 24) & 0xFF);
    }
}
