package com.campus.sport.lan;

/**
 * Payload carried by the "share this app" QR.
 *
 * It is a plain download URL so any camera app can use it, with the room address
 * appended as a fragment:
 *
 *   http://192.168.43.2:8787/app.apk#r=192.168.43.2:8787
 *
 * A browser ignores the fragment and just downloads. The app's own scanner reads
 * it and can offer both "download" and "join this room", which is how a fresh
 * phone gets from scan to installed to joined in one go.
 */
public final class AppShareCode {

    private static final String FRAGMENT = "#r=";

    private AppShareCode() {
    }

    public static String build(String apkUrl, String roomAddress) {
        if (apkUrl == null || apkUrl.isEmpty()) return "";
        String base = apkUrl.trim();
        int hash = base.indexOf('#');
        if (hash >= 0) base = base.substring(0, hash);
        if (roomAddress == null || roomAddress.isEmpty()) return base;
        return base + FRAGMENT + roomAddress.trim();
    }

    /** The download URL, with any fragment removed. */
    public static String apkUrlOf(String raw) {
        if (raw == null) return "";
        String text = raw.trim();
        if (text.isEmpty()) return "";
        int hash = text.indexOf('#');
        return hash >= 0 ? text.substring(0, hash) : text;
    }

    /** The room embedded in the fragment, or null when there is none. */
    public static String roomOf(String raw) {
        if (raw == null) return null;
        String text = raw.trim();
        int at = text.indexOf(FRAGMENT);
        if (at < 0) return null;
        String parsed = RoomAddress.parse(text.substring(at + FRAGMENT.length()));
        if (parsed == null) return null;
        // RoomAddress accepts a bare word as a host name, which is right for a
        // typed address but wrong here: a stray fragment must not become a join
        // attempt. Require something that looks like a host or an IPv4.
        int colon = parsed.lastIndexOf(':');
        String host = colon > 0 ? parsed.substring(0, colon) : parsed;
        return host.indexOf('.') > 0 || "localhost".equals(host) ? parsed : null;
    }

    /** True when the payload points at an installable package. */
    public static boolean isShareCode(String raw) {
        String url = apkUrlOf(raw).toLowerCase(java.util.Locale.ROOT);
        if (url.isEmpty()) return false;
        String path = url;
        int question = path.indexOf('?');
        if (question >= 0) path = path.substring(0, question);
        return path.endsWith(".apk");
    }
}
