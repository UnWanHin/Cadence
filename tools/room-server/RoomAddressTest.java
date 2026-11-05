package com.campus.sport.lan;

/** Desktop checks for the QR payload parser. Run via tools/run-jvm-tests.sh. */
public final class RoomAddressTest {

    private static int failures;

    public static void main(String[] args) {
        check("192.168.1.5:8787", RoomAddress.parse("192.168.1.5:8787"), "192.168.1.5:8787");
        check("bare host uses default port", RoomAddress.parse("192.168.1.5"),
                "192.168.1.5:" + RoomAddress.DEFAULT_PORT);
        check("http url", RoomAddress.parse("http://192.168.1.5:8787/"), "192.168.1.5:8787");
        check("https url", RoomAddress.parse("https://10.0.0.2:9000/room"), "10.0.0.2:9000");
        check("url with api query", RoomAddress.parse("http://10.21.73.203:8787/?api=x"), "10.21.73.203:8787");
        check("surrounding whitespace", RoomAddress.parse("  192.168.43.1:8787  "), "192.168.43.1:8787");
        check("fragment is dropped", RoomAddress.parse("192.168.43.1:8787#x"), "192.168.43.1:8787");

        reject("empty", "");
        reject("blank", "   ");
        reject("null", null);
        reject("path only", "/api/v1/events");
        reject("bad port", "192.168.1.5:notaport");
        reject("port too big", "192.168.1.5:70000");
        reject("port zero", "192.168.1.5:0");
        reject("space inside host", "192 168 1 5:8787");

        if (failures > 0) {
            System.err.println("ROOM ADDRESS TEST FAILED (" + failures + ")");
            System.exit(1);
        }
        System.out.println("PASS");
    }

    private static void check(String label, String actual, String expected) {
        if (expected.equals(actual)) {
            System.out.println("ok - " + label);
        } else {
            System.err.println("FAIL: " + label + " → got " + actual + ", want " + expected);
            failures++;
        }
    }

    private static void reject(String label, String raw) {
        String actual = RoomAddress.parse(raw);
        if (actual == null) {
            System.out.println("ok - rejects " + label);
        } else {
            System.err.println("FAIL: rejects " + label + " → got " + actual);
            failures++;
        }
    }
}
