package com.campus.sport.lan;

import java.util.List;

/** Desktop checks for room discovery. Run via tools/run-jvm-tests.sh. */
public final class RoomDiscoveryTest {

    private static int failures;

    public static void main(String[] args) throws Exception {
        int discoveryPort = 18788;
        int roomPort = 18787;

        // A scan with no announcer must come back empty, not hang or throw.
        long emptyStart = System.currentTimeMillis();
        List<RoomDiscovery.Room> none = RoomDiscovery.scan(discoveryPort, 250);
        check("no announcer yields no rooms", none.isEmpty());
        check("scan respects the timeout", System.currentTimeMillis() - emptyStart < 4000);

        RoomDiscovery.Announcer announcer =
                new RoomDiscovery.Announcer(discoveryPort, roomPort, "YG 的房间");
        announcer.start();
        Thread.sleep(150);
        try {
            List<RoomDiscovery.Room> rooms = RoomDiscovery.scan(discoveryPort, 700);
            check("one room is discovered", rooms.size() == 1);
            if (!rooms.isEmpty()) {
                RoomDiscovery.Room room = rooms.get(0);
                check("room name is carried", "YG 的房间".equals(room.name));
                check("room address ends with the room port",
                        room.address.endsWith(":" + roomPort));
                check("room address is not a broadcast address",
                        !room.address.startsWith("255."));
            }

            // Rename must be visible without restarting the announcer.
            announcer.setRoomName("改过的名字");
            List<RoomDiscovery.Room> renamed = RoomDiscovery.scan(discoveryPort, 700);
            check("rename is picked up", !renamed.isEmpty() && "改过的名字".equals(renamed.get(0).name));
        } finally {
            announcer.close();
        }

        // After closing, the room must disappear from results.
        List<RoomDiscovery.Room> afterClose = RoomDiscovery.scan(discoveryPort, 400);
        check("a closed room is gone", afterClose.isEmpty());

        if (failures > 0) {
            System.err.println("ROOM DISCOVERY TEST FAILED (" + failures + ")");
            System.exit(1);
        }
        System.out.println("PASS");
    }

    private static void check(String label, boolean condition) {
        if (condition) {
            System.out.println("ok - " + label);
        } else {
            System.err.println("FAIL: " + label);
            failures++;
        }
    }
}
