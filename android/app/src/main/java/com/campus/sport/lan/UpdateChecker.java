package com.campus.sport.lan;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads a GitHub release and decides whether it is worth installing.
 *
 * Kept free of Android APIs so the parsing and the version comparison can be
 * checked on a desktop JVM against a recorded response.
 */
public final class UpdateChecker {

    /** Where releases are published. */
    public static final String DEFAULT_REPO = "UnWanHin/Cadence";

    /** Asset name the release workflow is expected to publish. */
    public static final String ASSET_NAME = "cadence.apk";

    /** One downloadable release. */
    public static final class Release {
        public final String tag;
        public final String notes;
        public final String apkUrl;
        public final String apkName;
        public final long sizeBytes;

        public Release(String tag, String notes, String apkUrl, String apkName, long sizeBytes) {
            this.tag = tag == null ? "" : tag;
            this.notes = notes == null ? "" : notes;
            this.apkUrl = apkUrl == null ? "" : apkUrl;
            this.apkName = apkName == null ? "" : apkName;
            this.sizeBytes = sizeBytes;
        }

        public boolean hasApk() {
            return !apkUrl.isEmpty();
        }
    }

    private UpdateChecker() {
    }

    /**
     * Reads the tag out of the /releases/latest redirect.
     *
     * The API is rate limited per IP (60/hour anonymous), which a shared campus
     * address burns through immediately, so the redirect is the primary path:
     * it costs no quota and answers with the same information.
     */
    public static String tagFromRedirect(String location) {
        if (location == null) return "";
        String text = location.trim();
        int marker = text.indexOf("/releases/tag/");
        if (marker < 0) return "";
        String tag = text.substring(marker + "/releases/tag/".length());
        int cut = tag.indexOf('/');
        if (cut > 0) tag = tag.substring(0, cut);
        int query = tag.indexOf('?');
        if (query > 0) tag = tag.substring(0, query);
        return tag.trim();
    }

    /** Conventional download URL, so no API call is needed to find the asset. */
    public static String assetUrl(String repo, String tag, String assetName) {
        if (repo == null || repo.isEmpty() || tag == null || tag.isEmpty()) return "";
        return "https://github.com/" + repo + "/releases/download/" + tag + "/"
                + (assetName == null || assetName.isEmpty() ? ASSET_NAME : assetName);
    }

    /**
     * Parses the body of /repos/{owner}/{repo}/releases/latest.
     * Returns null when the payload has no usable tag.
     */
    public static Release parse(String json) {
        if (json == null || json.isBlank()) return null;
        Map<String, Object> root;
        try {
            root = RoomJson.readObject(json);
        } catch (RuntimeException error) {
            return null;
        }
        String tag = WorkoutEvent.str(root.get("tag_name"));
        if (tag.isEmpty()) tag = WorkoutEvent.str(root.get("name"));
        if (tag.isEmpty()) return null;
        String notes = WorkoutEvent.str(root.get("body"));

        Object assetsValue = root.get("assets");
        if (assetsValue instanceof List<?>) {
            for (Object item : (List<?>) assetsValue) {
                if (!(item instanceof Map<?, ?>)) continue;
                Map<?, ?> asset = (Map<?, ?>) item;
                String name = WorkoutEvent.str(asset.get("name"));
                String url = WorkoutEvent.str(asset.get("browser_download_url"));
                if (url.isEmpty()) continue;
                if (!name.toLowerCase(java.util.Locale.ROOT).endsWith(".apk")) continue;
                long size = (long) WorkoutEvent.num(asset.get("size"), 0);
                return new Release(tag, notes, url, name, size);
            }
        }
        // A release without an APK still tells us the current version.
        return new Release(tag, notes, "", "", 0);
    }

    /** True when [latest] is a higher version than [current]. */
    public static boolean isNewer(String current, String latest) {
        return compareVersions(current, latest) < 0;
    }

    /**
     * Compares dotted versions. Returns negative when [a] is older.
     *
     * A leading "v" is ignored and parts compare numerically, so "v0.1.10"
     * beats "v0.1.9". A pre-release suffix ranks below the same numbers
     * ("1.2.0-beta" &lt; "1.2.0"), which keeps a beta tag from being offered to
     * someone already on the release.
     */
    public static int compareVersions(String a, String b) {
        Version left = parseVersion(a);
        Version right = parseVersion(b);
        int length = Math.max(left.numbers.size(), right.numbers.size());
        for (int i = 0; i < length; i++) {
            int l = i < left.numbers.size() ? left.numbers.get(i) : 0;
            int r = i < right.numbers.size() ? right.numbers.get(i) : 0;
            if (l != r) return l < r ? -1 : 1;
        }
        if (left.preRelease.equals(right.preRelease)) return 0;
        // Same numbers: the release beats the pre-release.
        if (left.preRelease.isEmpty()) return 1;
        if (right.preRelease.isEmpty()) return -1;
        return left.preRelease.compareTo(right.preRelease) < 0 ? -1 : 1;
    }

    /** Numbers plus the optional "-beta"/"-rc1" tail. */
    private static final class Version {
        final List<Integer> numbers = new ArrayList<>();
        String preRelease = "";
    }

    private static Version parseVersion(String version) {
        Version out = new Version();
        if (version == null) return out;
        String text = version.trim();
        if (text.startsWith("v") || text.startsWith("V")) text = text.substring(1);

        int dash = text.indexOf('-');
        if (dash < 0) dash = text.indexOf('+');
        String numeric = dash >= 0 ? text.substring(0, dash) : text;
        String tail = dash >= 0 ? text.substring(dash + 1) : "";
        out.preRelease = tail.toLowerCase(java.util.Locale.ROOT);

        for (String piece : numeric.split("\\.")) {
            int value = 0;
            int i = 0;
            while (i < piece.length() && Character.isDigit(piece.charAt(i))) {
                value = value * 10 + (piece.charAt(i) - '0');
                i++;
            }
            out.numbers.add(value);
        }
        return out;
    }
}
