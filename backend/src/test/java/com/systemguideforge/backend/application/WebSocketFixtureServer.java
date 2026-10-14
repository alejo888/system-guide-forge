package com.systemguideforge.backend.application;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal RFC 6455 test server: accepts the handshake and records the text frames each client sends. It never sends
 * frames of its own, so tests can prove exactly which page writes reached the server.
 */
final class WebSocketFixtureServer implements AutoCloseable {
    private static final String HANDSHAKE_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private final ServerSocket server;
    private final List<Socket> clients = new CopyOnWriteArrayList<>();
    private final List<String> received = new CopyOnWriteArrayList<>();
    private final AtomicInteger connections = new AtomicInteger();

    WebSocketFixtureServer() throws IOException {
        server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread acceptor = new Thread(this::acceptLoop, "ws-fixture-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    String url(String path) { return "ws://127.0.0.1:" + server.getLocalPort() + path; }

    /** Text frames received from every client, in arrival order. */
    List<String> received() { return List.copyOf(received); }

    /** Completed handshakes. */
    int connections() { return connections.get(); }

    /** Bounded wait for a frame; returns whether it arrived. */
    boolean awaitMessage(String text, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (received.contains(text)) return true;
            Thread.sleep(20);
        }
        return received.contains(text);
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket client = server.accept();
                clients.add(client);
                Thread reader = new Thread(() -> serve(client), "ws-fixture-client");
                reader.setDaemon(true);
                reader.start();
            } catch (IOException e) {
                return;
            }
        }
    }

    private void serve(Socket client) {
        try (client) {
            DataInputStream in = new DataInputStream(client.getInputStream());
            String key = handshakeKey(in);
            if (key == null) return;
            OutputStream out = client.getOutputStream();
            String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                    .digest((key + HANDSHAKE_GUID).getBytes(StandardCharsets.US_ASCII)));
            out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            connections.incrementAndGet();
            while (true) {
                int first = in.readUnsignedByte();
                int second = in.readUnsignedByte();
                long length = second & 0x7F;
                if (length == 126) length = in.readUnsignedShort();
                else if (length == 127) length = in.readLong();
                byte[] mask = new byte[4];
                if ((second & 0x80) != 0) in.readFully(mask);
                byte[] payload = new byte[(int) length];
                in.readFully(payload);
                for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];
                int opcode = first & 0x0F;
                if (opcode == 0x8) return;
                if (opcode == 0x1) received.add(new String(payload, StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            // Client went away; the recorded frames are all the test needs.
        }
    }

    private static String handshakeKey(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        String key = null;
        int previous = -1;
        int current;
        while ((current = in.read()) != -1) {
            if (previous == '\r' && current == '\n') {
                String header = line.toString(StandardCharsets.US_ASCII).trim();
                if (header.isEmpty()) return key;
                if (header.toLowerCase(Locale.ROOT).startsWith("sec-websocket-key:")) key = header.substring(header.indexOf(':') + 1).trim();
                line.reset();
            } else if (current != '\r') {
                line.write(current);
            }
            previous = current;
        }
        return null;
    }

    @Override
    public void close() throws IOException {
        server.close();
        for (Socket client : clients) client.close();
    }
}
