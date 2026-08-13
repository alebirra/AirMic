package com.airmic.app;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

/**
 * UDP discovery handshake (AirMic protocol v1, port 47800).
 *
 * The phone sends "AIRMIC_DISCOVER_V1" (broadcast or unicast) and the PC
 * answers "AIRMIC_SERVER_V1|<pcName>|<audioPort>" from its own address.
 */
public final class Discovery {

    public static final int DISCOVERY_PORT = 47800;
    public static final int DEFAULT_AUDIO_PORT = 47811;

    private static final byte[] QUERY = "AIRMIC_DISCOVER_V1".getBytes(StandardCharsets.US_ASCII);
    private static final String RESPONSE_PREFIX = "AIRMIC_SERVER_V1|";
    private static final String BROADCAST_ADDR = "255.255.255.255";

    private Discovery() {
    }

    public static final class Result {
        public final String host;
        public final int audioPort;
        public final String pcName;

        Result(String host, int audioPort, String pcName) {
            this.host = host;
            this.audioPort = audioPort;
            this.pcName = pcName;
        }
    }

    /**
     * Blocking discovery. Call from a worker thread.
     *
     * @param unicastIp target IP for manual discovery, or null for 255.255.255.255 broadcast
     * @param attempts  number of query packets to send
     * @param intervalMs pause between attempts; also the per-attempt listen window
     * @return the server's answer, or null on timeout
     */
    public static Result discover(String unicastIp, int attempts, int intervalMs) {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket();
            socket.setBroadcast(true);
            socket.setSoTimeout(250);
            InetAddress target = InetAddress.getByName(
                    unicastIp != null ? unicastIp : BROADCAST_ADDR);

            byte[] buf = new byte[256];
            for (int i = 0; i < attempts; i++) {
                socket.send(new DatagramPacket(QUERY, QUERY.length, target, DISCOVERY_PORT));

                long deadline = System.currentTimeMillis() + intervalMs;
                while (System.currentTimeMillis() < deadline) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    try {
                        socket.receive(p);
                    } catch (SocketTimeoutException e) {
                        continue;
                    }
                    String msg = new String(p.getData(), p.getOffset(), p.getLength(),
                            StandardCharsets.US_ASCII);
                    if (msg.startsWith(RESPONSE_PREFIX)) {
                        String[] parts = msg.substring(RESPONSE_PREFIX.length()).split("\\|");
                        String name = parts.length > 0 && !parts[0].isEmpty()
                                ? parts[0] : p.getAddress().getHostAddress();
                        int port = DEFAULT_AUDIO_PORT;
                        if (parts.length > 1) {
                            try {
                                port = Integer.parseInt(parts[1].trim());
                            } catch (NumberFormatException ignored) {
                            }
                        }
                        return new Result(p.getAddress().getHostAddress(), port, name);
                    }
                }
            }
            return null;
        } catch (IOException e) {
            return null;
        } finally {
            if (socket != null) {
                socket.close();
            }
        }
    }
}
