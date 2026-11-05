package com.campus.sport.lan;

import java.io.File;
import java.io.IOException;

/**
 * Desktop entry point for the embedded room service.
 *
 * The APK runs RoomHttpServer from an Android Service; this main lets the same
 * classes be compiled and exercised with plain javac/java, which is how the
 * HTML <-> phone protocol gets verified without an Android SDK.
 *
 * Usage: RoomServerMain [port] [dataDir] [webRoot] [roomName] [discoveryPort]
 */
public final class RoomServerMain {

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8787;
        String dataDir = args.length > 1 ? args[1] : "room-data";
        String webRoot = args.length > 2 ? args[2] : "ui";
        String roomName = args.length > 3 ? args[3] : "桌面测试房";
        int discoveryPort = args.length > 4 ? Integer.parseInt(args[4]) : RoomDiscovery.DEFAULT_PORT;

        File data = new File(dataDir);
        data.mkdirs();

        RoomStore store = new FileRoomStore(new File(data, "store.json"));
        RoomHttpServer server = new RoomHttpServer(store, new File(webRoot), port);
        server.start();

        // Announce the same way the APK does, so the desktop harness exercises
        // the real discovery path.
        RoomDiscovery.Announcer announcer = new RoomDiscovery.Announcer(discoveryPort, port, roomName);
        try {
            announcer.start();
        } catch (IOException error) {
            System.err.println("[room] 广播未启动（端口可能被占用）: " + error.getMessage());
        }
        RoomDiscovery.Announcer finalAnnouncer = announcer;

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            finalAnnouncer.close();
            server.stop();
        }));
        System.out.println("Cadence room service (JVM)");
        System.out.println("  本机: http://127.0.0.1:" + port + "/");
        for (String address : RoomHttpServer.lanAddresses()) {
            System.out.println("  局域网: http://" + address + ":" + port + "/");
        }
        System.out.println("  接口: " + RoomHttpServer.API_PREFIX + " 数据: " + data.getAbsolutePath());
        System.out.println("  广播: " + roomName + "（UDP " + discoveryPort + "）");
        Thread.sleep(Long.MAX_VALUE);
    }
}
