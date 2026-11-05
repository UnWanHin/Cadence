package com.campus.sport.lan;

/** Desktop checks for the auto-update schedule. Run via tools/run-jvm-tests.sh. */
public final class UpdatePolicyTest {

    private static int failures;
    private static final long HOUR = 60 * 60 * 1000L;

    public static void main(String[] args) {
        long now = 1_700_000_000_000L;

        check("never checked means check now", UpdatePolicy.shouldCheck(0, now));
        check("just checked means wait", !UpdatePolicy.shouldCheck(now, now));
        check("under the interval means wait", !UpdatePolicy.shouldCheck(now - HOUR, now));
        check("over the interval means check", UpdatePolicy.shouldCheck(now - 7 * HOUR, now));
        check("exactly the interval means check",
                UpdatePolicy.shouldCheck(now - UpdatePolicy.AUTO_CHECK_INTERVAL_MS, now));
        // A clock change must not silence updates forever.
        check("a backwards clock does not lock out", UpdatePolicy.shouldCheck(now + HOUR, now));

        // Announcement previews.
        check("null body is empty", UpdatePolicy.notesPreview(null).isEmpty());
        check("blank body is empty", UpdatePolicy.notesPreview("   \n\n ").isEmpty());
        check("single line is kept",
                "- 修复了聊天列表".equals(UpdatePolicy.notesPreview("- 修复了聊天列表")));
        // Runs collapse to a single blank line, which keeps release notes readable.
        check("blank runs collapse to one",
                "a\n\nb".equals(UpdatePolicy.notesPreview("a\n\n\n\nb")));
        check("crlf is normalised",
                "a\nb".equals(UpdatePolicy.notesPreview("a\r\nb")));

        StringBuilder longBody = new StringBuilder();
        for (int i = 0; i < 200; i++) longBody.append("- 第 ").append(i).append(" 条说明\n");
        String preview = UpdatePolicy.notesPreview(longBody.toString(), 200);
        check("long body is truncated", preview.length() <= 201);
        check("truncation is marked", preview.endsWith("…"));
        check("truncation keeps whole lines", !preview.contains("…-"));

        if (failures > 0) {
            System.err.println("UPDATE POLICY TEST FAILED (" + failures + ")");
            System.exit(1);
        }
        System.out.println("PASS");
    }

    private static void check(String label, boolean condition) {
        if (condition) System.out.println("ok - " + label);
        else { System.err.println("FAIL: " + label); failures++; }
    }
}
