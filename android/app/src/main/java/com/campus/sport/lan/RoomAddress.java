package com.campus.sport.lan;

/**
 * Turns whatever a QR code contains into a `host:port` the app can dial.
 *
 * Pure Java with no Android imports so the parsing rules can be exercised on a
 * desktop JVM without an emulator.
 */
public final class RoomAddress {

    /** Must match LanRoom.DEFAULT_PORT. */
    public static final int DEFAULT_PORT = 8787;

    private RoomAddress() {
    }

    /**
     * Accepts `192.168.1.5:8787`, `192.168.1.5`, `http://192.168.1.5:8787/`,
     * `http://192.168.1.5:8787/?api=...`. Returns null when nothing usable is
     * present so the caller can show "not a room code" instead of dialling junk.
     */
    public static String parse(String raw) {
        if (raw == null) return null;
        String text = raw.trim();
        if (text.isEmpty()) return null;

        String lower = text.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("http://")) text = text.substring(7);
        else if (lower.startsWith("https://")) text = text.substring(8);

        // Drop path, query and fragment.
        int end = text.length();
        for (char stop : new char[] {'/', '?', '#'}) {
            int at = text.indexOf(stop);
            if (at >= 0 && at < end) end = at;
        }
        String authority = text.substring(0, end).trim();
        if (authority.isEmpty()) return null;

        String host = authority;
        String portText = "";
        int colon = authority.lastIndexOf(':');
        if (colon >= 0) {
            host = authority.substring(0, colon);
            portText = authority.substring(colon + 1);
        }
        host = host.trim();
        if (host.isEmpty() || host.contains(" ")) return null;

        int port = DEFAULT_PORT;
        if (!portText.isEmpty()) {
            try {
                port = Integer.parseInt(portText.trim());
            } catch (NumberFormatException error) {
                return null;
            }
        }
        if (port < 1 || port > 65535) return null;
        return host + ":" + port;
    }
}
