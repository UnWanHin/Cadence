package com.campus.sport.lan;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Desktop checks for address preference. Run via tools/run-jvm-tests.sh. */
public final class LanAddressesTest {

    private static int failures;

    public static void main(String[] args) {
        // Wi-Fi beats cellular beats a tunnel, regardless of listing order.
        List<LanAddresses.Entry> mixed = new ArrayList<>(Arrays.asList(
                new LanAddresses.Entry("tun0", "10.8.0.2"),
                new LanAddresses.Entry("rmnet_data0", "100.64.1.9"),
                new LanAddresses.Entry("wlan0", "192.168.43.2")));
        check("wifi beats cellular and tunnel", "192.168.43.2".equals(LanAddresses.ordered(mixed).get(0)));

        // Same interface: 192.168 wins over a public-looking address.
        List<LanAddresses.Entry> sameIface = Arrays.asList(
                new LanAddresses.Entry("wlan0", "203.0.113.9"),
                new LanAddresses.Entry("wlan0", "192.168.1.5"),
                new LanAddresses.Entry("wlan0", "10.0.0.7"));
        List<String> ordered = LanAddresses.ordered(sameIface);
        check("192.168 first within an interface", "192.168.1.5".equals(ordered.get(0)));
        check("10.x next", "10.0.0.7".equals(ordered.get(1)));
        check("public last", "203.0.113.9".equals(ordered.get(2)));

        // Link-local must not be offered as the room address.
        check("link-local scores worse than a private address",
                LanAddresses.score("wlan0", "169.254.3.4") > LanAddresses.score("wlan0", "192.168.0.2"));

        // A hotspot host (ap0) should still beat cellular.
        check("hotspot interface beats cellular",
                LanAddresses.score("ap0", "192.168.43.1") < LanAddresses.score("rmnet_data0", "10.1.2.3"));

        // Duplicates collapse.
        List<String> dupes = LanAddresses.ordered(Arrays.asList(
                new LanAddresses.Entry("wlan0", "192.168.1.5"),
                new LanAddresses.Entry("wlan1", "192.168.1.5")));
        check("duplicate addresses collapse", dupes.size() == 1);

        // Empty input must not throw.
        check("empty input is safe", LanAddresses.ordered(new ArrayList<>()).isEmpty());

        // 172.16-31 counts as private, 172.32 does not.
        check("172.16 is private", LanAddresses.score("wlan0", "172.16.5.5") < LanAddresses.score("wlan0", "172.32.5.5"));

        if (failures > 0) {
            System.err.println("LAN ADDRESS TEST FAILED (" + failures + ")");
            System.exit(1);
        }
        System.out.println("PASS");
    }

    private static void check(String label, boolean condition) {
        if (condition) System.out.println("ok - " + label);
        else { System.err.println("FAIL: " + label); failures++; }
    }
}
