package com.campus.sport.lan;

/** Desktop checks for release parsing. Run via tools/run-jvm-tests.sh. */
public final class UpdateCheckerTest {

    private static int failures;

    // Shape of a real /releases/latest response, trimmed.
    private static final String FIXTURE = "{"
            + "\"tag_name\":\"v0.2.0\","
            + "\"name\":\"Cadence 0.2.0\","
            + "\"body\":\"- room discovery\\n- QR share\","
            + "\"assets\":[{"
            + "\"name\":\"app-debug.apk\","
            + "\"size\":34852581,"
            + "\"browser_download_url\":\"https://github.com/UnWanHin/Cadence/releases/download/v0.2.0/app-debug.apk\""
            + "}]}";

    public static void main(String[] args) {
        UpdateChecker.Release release = UpdateChecker.parse(FIXTURE);
        check("parse returns a release", release != null);
        if (release != null) {
            check("tag is read", "v0.2.0".equals(release.tag));
            check("notes are read", release.notes.contains("room discovery"));
            check("apk url is read", release.apkUrl.endsWith("/v0.2.0/app-debug.apk"));
            check("apk name is read", "app-debug.apk".equals(release.apkName));
            check("size is read", release.sizeBytes == 34852581L);
            check("hasApk is true", release.hasApk());
        }

        // No apk asset: still a valid release, just nothing to install.
        UpdateChecker.Release noAsset = UpdateChecker.parse(
                "{\"tag_name\":\"v0.3.0\",\"body\":\"docs only\",\"assets\":[]}");
        check("release without apk parses", noAsset != null);
        check("release without apk reports no apk", noAsset != null && !noAsset.hasApk());

        // First asset is a checksum file: must be skipped, not installed.
        UpdateChecker.Release skipped = UpdateChecker.parse("{"
                + "\"tag_name\":\"v0.3.1\",\"assets\":["
                + "{\"name\":\"SHA256SUMS\",\"browser_download_url\":\"https://x/SHA256SUMS\",\"size\":10},"
                + "{\"name\":\"cadence.apk\",\"browser_download_url\":\"https://x/cadence.apk\",\"size\":20}]}");
        check("non-apk assets are skipped",
                skipped != null && "https://x/cadence.apk".equals(skipped.apkUrl));

        // Malformed input must not throw.
        check("garbage is null", UpdateChecker.parse("not json") == null);
        check("empty is null", UpdateChecker.parse("") == null);
        check("null is null", UpdateChecker.parse(null) == null);
        check("no tag is null", UpdateChecker.parse("{\"assets\":[]}") == null);

        // Redirect parsing: the quota-free path.
        check("tag from a redirect",
                "v0.2.0".equals(UpdateChecker.tagFromRedirect(
                        "https://github.com/UnWanHin/Cadence/releases/tag/v0.2.0")));
        check("redirect without tag yields nothing",
                UpdateChecker.tagFromRedirect(
                        "https://github.com/UnWanHin/Cadence/releases").isEmpty());
        check("null redirect yields nothing", UpdateChecker.tagFromRedirect(null).isEmpty());

        String conventional = UpdateChecker.assetUrl("UnWanHin/Cadence", "v0.2.0", "cadence.apk");
        check("conventional asset url",
                "https://github.com/UnWanHin/Cadence/releases/download/v0.2.0/cadence.apk"
                        .equals(conventional));
        check("asset url needs a tag", UpdateChecker.assetUrl("UnWanHin/Cadence", "", "").isEmpty());

        // Version comparison.
        check("same version", !UpdateChecker.isNewer("0.1.0", "0.1.0"));
        check("patch bump", UpdateChecker.isNewer("0.1.0", "0.1.1"));
        check("minor bump", UpdateChecker.isNewer("0.1.9", "0.2.0"));
        check("numeric not lexical", UpdateChecker.isNewer("0.1.9", "0.1.10"));
        check("older remote is not an update", !UpdateChecker.isNewer("0.2.0", "0.1.9"));
        check("v prefix is ignored", UpdateChecker.isNewer("0.1.0", "v0.1.1"));
        check("both prefixed", UpdateChecker.isNewer("v0.1.0", "v0.2.0"));
        check("missing parts count as zero", UpdateChecker.isNewer("1.0", "1.0.1"));
        check("short current equals long", !UpdateChecker.isNewer("1.0.0", "1.0"));
        // A prerelease of the same number must not be offered as an upgrade.
        check("prerelease is not an upgrade", !UpdateChecker.isNewer("1.2.0", "1.2.0-beta"));
        check("prerelease to release is an upgrade", UpdateChecker.isNewer("1.2.0-beta", "1.2.0"));
        check("date style tags", UpdateChecker.isNewer("2024.10.1", "2024.10.2"));

        if (failures > 0) {
            System.err.println("UPDATE CHECKER TEST FAILED (" + failures + ")");
            System.exit(1);
        }
        System.out.println("PASS");
    }

    private static void check(String label, boolean condition) {
        if (condition) System.out.println("ok - " + label);
        else { System.err.println("FAIL: " + label); failures++; }
    }
}
