package com.campus.sport.lan;

/**
 * Decides when to ask about an update.
 *
 * The app checks on open, but not on every open: a check costs a network round
 * trip and the answer rarely changes within minutes. The throttle is stored, so
 * closing and reopening the app does not bypass it.
 */
public final class UpdatePolicy {

    /** How often the automatic check may run. */
    public static final long AUTO_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L;

    /** Longest announcement shown in the dialog. */
    public static final int NOTES_LIMIT = 600;

    private UpdatePolicy() {
    }

    /** True when [nowMs] is far enough past [lastCheckMs] to check again. */
    public static boolean shouldCheck(long lastCheckMs, long nowMs, long intervalMs) {
        if (lastCheckMs <= 0) return true;
        if (nowMs < lastCheckMs) return true; // clock moved backwards: do not lock out
        return nowMs - lastCheckMs >= intervalMs;
    }

    public static boolean shouldCheck(long lastCheckMs, long nowMs) {
        return shouldCheck(lastCheckMs, nowMs, AUTO_CHECK_INTERVAL_MS);
    }

    /**
     * Trims a release body for display: blank runs collapse, lines are kept as
     * written, and an over-long body is cut on a line boundary.
     */
    public static String notesPreview(String body, int limit) {
        if (body == null) return "";
        String text = body.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (text.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        boolean lastBlank = false;
        for (String line : text.split("\n", -1)) {
            String trimmed = line.stripTrailing();
            boolean blank = trimmed.isEmpty();
            if (blank && lastBlank) continue;
            if (out.length() > 0) out.append('\n');
            out.append(trimmed);
            lastBlank = blank;
        }
        String collapsed = out.toString().trim();
        if (limit <= 0 || collapsed.length() <= limit) return collapsed;

        int cut = collapsed.lastIndexOf('\n', limit);
        if (cut < limit / 2) cut = limit;
        return collapsed.substring(0, cut).stripTrailing() + "…";
    }

    public static String notesPreview(String body) {
        return notesPreview(body, NOTES_LIMIT);
    }
}
