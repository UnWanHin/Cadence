package com.campus.sport.lan;

/**
 * Turns download byte counts into something a progress line can show.
 *
 * Kept free of Android APIs so the same maths is checked by the JVM test suite
 * instead of only on a device.
 */
public final class UpdateProgress {

    private static final long KB = 1024L;
    private static final long MB = 1024L * 1024L;
    private static final long GB = 1024L * 1024L * 1024L;

    private UpdateProgress() {
    }

    /**
     * Percentage of the download that is done, or -1 when the server did not
     * send a length and there is nothing to divide by.
     */
    public static int percent(long downloaded, long total) {
        if (total <= 0) return -1;
        if (downloaded <= 0) return 0;
        if (downloaded >= total) return 100;
        return (int) (downloaded * 100 / total);
    }

    /** Compact size for a label, e.g. "0 B", "512 B", "3.4 KB", "31.8 MB". */
    public static String humanBytes(long bytes) {
        if (bytes < 0) bytes = 0;
        if (bytes < KB) return bytes + " B";
        if (bytes < MB) return oneDecimal(bytes, KB) + " KB";
        if (bytes < GB) return oneDecimal(bytes, MB) + " MB";
        return oneDecimal(bytes, GB) + " GB";
    }

    private static String oneDecimal(long bytes, long unit) {
        // Integer maths keeps the output stable across locales; a trailing ".0"
        // is dropped so a whole number does not look like false precision.
        long tenths = bytes * 10 / unit;
        long whole = tenths / 10;
        long rest = tenths % 10;
        return rest == 0 ? String.valueOf(whole) : whole + "." + rest;
    }
}
