package com.campus.sport.lan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Chooses which local address to advertise.
 *
 * A phone can hold several at once (Wi-Fi, hotspot, cellular, a VPN), and the
 * first one the OS happens to list is often not the one other devices can
 * reach. Wi-Fi and ethernet win, then private IPv4 over public, and tunnelled
 * interfaces lose.
 *
 * Pure Java so the ordering is testable without a device.
 */
public final class LanAddresses {

    private LanAddresses() {
    }

    /** One interface address. */
    public static final class Entry {
        public final String iface;
        public final String ip;

        public Entry(String iface, String ip) {
            this.iface = iface == null ? "" : iface;
            this.ip = ip == null ? "" : ip;
        }
    }

    /** Lower is better; link-local is treated as unusable. */
    public static int score(String ifaceName, String ip) {
        String iface = ifaceName == null ? "" : ifaceName.toLowerCase(java.util.Locale.ROOT);
        if (ip == null || ip.isEmpty()) return 1000;
        if (ip.startsWith("169.254.")) return 900; // link-local, usually unroutable

        int interfaceRank;
        if (iface.startsWith("wlan") || iface.startsWith("ap") || iface.startsWith("wifi")) {
            interfaceRank = 0;
        } else if (iface.startsWith("eth")) {
            interfaceRank = 1;
        } else if (iface.startsWith("rndis") || iface.startsWith("usb")) {
            interfaceRank = 2;
        } else if (iface.startsWith("rmnet") || iface.startsWith("ccmni") || iface.startsWith("pdp")) {
            interfaceRank = 6; // cellular: rarely reachable from the LAN
        } else if (iface.startsWith("tun") || iface.startsWith("ppp") || iface.startsWith("tailscale")) {
            interfaceRank = 8;
        } else {
            interfaceRank = 4;
        }

        int addressRank;
        if (ip.startsWith("192.168.")) addressRank = 0;
        else if (ip.startsWith("10.")) addressRank = 1;
        else if (isPrivate172(ip)) addressRank = 2;
        else addressRank = 3;

        return interfaceRank * 10 + addressRank;
    }

    private static boolean isPrivate172(String ip) {
        if (!ip.startsWith("172.")) return false;
        int firstDot = ip.indexOf('.');
        int secondDot = ip.indexOf('.', firstDot + 1);
        if (firstDot < 0 || secondDot < 0) return false;
        try {
            int second = Integer.parseInt(ip.substring(firstDot + 1, secondDot));
            return second >= 16 && second <= 31;
        } catch (NumberFormatException error) {
            return false;
        }
    }

    /** Best address first. */
    public static List<String> ordered(List<Entry> entries) {
        List<Entry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator
                .comparingInt((Entry entry) -> score(entry.iface, entry.ip))
                .thenComparing(entry -> entry.ip));
        List<String> out = new ArrayList<>(sorted.size());
        for (Entry entry : sorted) {
            if (!out.contains(entry.ip)) out.add(entry.ip);
        }
        return out;
    }
}
