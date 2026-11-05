package com.campus.sport.lan;

import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lets a device find rooms on the same network without typing an address.
 *
 * The host answers UDP probes on a well-known port; the sender takes the reply's
 * source address as the host IP, so a machine with several interfaces (Wi-Fi,
 * hotspot, VPN) always returns an address the asker can actually reach.
 *
 * Pure Java so the whole exchange is verifiable on a desktop JVM.
 */
public final class RoomDiscovery {

    public static final int DEFAULT_PORT = 8788;
    public static final String MAGIC = "CADENCE_ROOM_V1";
    private static final String APP_TAG = "cadence-room";

    private RoomDiscovery() {
    }

    /** A room another device is hosting. */
    public static final class Room {
        public final String address;
        public final String name;

        public Room(String address, String name) {
            this.address = address;
            this.name = name;
        }

        @Override
        public String toString() {
            return name.isEmpty() ? address : name + " · " + address;
        }
    }

    // ---- answering probes --------------------------------------------------

    /** Runs on the hosting device and replies to discovery probes. */
    public static final class Announcer implements Closeable {

        private final int discoveryPort;
        private final int roomPort;
        private volatile String roomName;
        private volatile boolean running;
        private DatagramSocket socket;
        private Thread thread;

        public Announcer(int discoveryPort, int roomPort, String roomName) {
            this.discoveryPort = discoveryPort;
            this.roomPort = roomPort;
            this.roomName = roomName == null ? "" : roomName;
        }

        public void setRoomName(String name) {
            this.roomName = name == null ? "" : name;
        }

        public synchronized void start() throws IOException {
            if (running) return;
            DatagramSocket bound = new DatagramSocket(null);
            bound.setReuseAddress(true);
            bound.bind(new InetSocketAddress(discoveryPort));
            socket = bound;
            running = true;
            thread = new Thread(this::loop, "cadence-room-announcer");
            thread.setDaemon(true);
            thread.start();
        }

        private void loop() {
            byte[] buffer = new byte[512];
            while (running) {
                try {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);
                    String text = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                    if (!MAGIC.equals(text.trim())) continue;
                    byte[] reply = replyPayload().getBytes(StandardCharsets.UTF_8);
                    socket.send(new DatagramPacket(reply, reply.length, packet.getAddress(), packet.getPort()));
                } catch (IOException error) {
                    if (!running) return;
                }
            }
        }

        private String replyPayload() {
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("app", APP_TAG);
            body.put("name", roomName);
            body.put("port", roomPort);
            return RoomJson.write(body);
        }

        @Override
        public synchronized void close() {
            running = false;
            if (socket != null) {
                socket.close();
                socket = null;
            }
            if (thread != null) {
                thread.interrupt();
                thread = null;
            }
        }
    }

    // ---- asking for rooms --------------------------------------------------

    /** Broadcasts a probe and collects replies for [timeoutMs]. */
    public static List<Room> scan(int discoveryPort, int timeoutMs) {
        List<Room> found = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket();
            socket.setBroadcast(true);
            socket.setSoTimeout(Math.max(80, timeoutMs));

            byte[] probe = MAGIC.getBytes(StandardCharsets.UTF_8);
            for (InetAddress target : broadcastTargets()) {
                try {
                    socket.send(new DatagramPacket(probe, probe.length, target, discoveryPort));
                } catch (IOException ignored) {
                    // One unusable interface must not abort the sweep.
                }
            }

            long deadline = System.currentTimeMillis() + Math.max(100, timeoutMs);
            byte[] buffer = new byte[1024];
            while (System.currentTimeMillis() < deadline) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet);
                } catch (IOException timeout) {
                    continue;
                }
                String host = packet.getAddress().getHostAddress();
                String text = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                Map<String, Object> payload = RoomJson.readObject(text);
                if (!APP_TAG.equals(WorkoutEvent.str(payload.get("app")))) continue;
                int port = (int) WorkoutEvent.num(payload.get("port"), RoomAddress.DEFAULT_PORT);
                if (port < 1 || port > 65535) continue;
                String address = host + ":" + port;
                if (!seen.add(address)) continue;
                String name = WorkoutEvent.str(payload.get("name"));
                // The same room answers on every interface (loopback included).
                // Keep one entry per room and prefer a routable address over
                // 127.0.0.1, which only works for the host itself.
                String key = name + "|" + port;
                int existing = indexOfKey(found, name, port);
                if (existing >= 0) {
                    if (isLoopback(found.get(existing).address) && !isLoopback(address)) {
                        found.set(existing, new Room(address, name));
                    }
                    continue;
                }
                found.add(new Room(address, name));
            }
        } catch (IOException ignored) {
            // Treat as "nothing found" rather than an error the caller must handle.
        } finally {
            if (socket != null) socket.close();
        }
        return found;
    }

    private static int indexOfKey(List<Room> rooms, String name, int port) {
        String suffix = ":" + port;
        for (int i = 0; i < rooms.size(); i++) {
            Room room = rooms.get(i);
            if (room.name.equals(name) && room.address.endsWith(suffix)) return i;
        }
        return -1;
    }

    private static boolean isLoopback(String address) {
        return address.startsWith("127.");
    }

    /** Every interface broadcast address, plus the limited broadcast as a fallback. */
    static List<InetAddress> broadcastTargets() {
        List<InetAddress> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress broadcast = ia.getBroadcast();
                    if (broadcast != null && !out.contains(broadcast)) out.add(broadcast);
                }
            }
        } catch (IOException ignored) {
            // Fall back to the limited broadcast below.
        }
        try {
            InetAddress limited = InetAddress.getByName("255.255.255.255");
            if (!out.contains(limited)) out.add(limited);
        } catch (IOException ignored) {
            // Nothing else to try.
        }
        return out;
    }
}
