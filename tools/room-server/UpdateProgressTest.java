package com.campus.sport.lan;

/** Download progress maths behind the update dialog. */
public final class UpdateProgressTest {

    private static int failures = 0;

    private static void check(boolean condition, String message) {
        if (condition) {
            System.out.println("ok - " + message);
        } else {
            System.err.println("FAIL: " + message);
            failures += 1;
        }
    }

    public static void main(String[] args) {
        check(UpdateProgress.percent(0, 100) == 0, "nothing downloaded is zero percent");
        check(UpdateProgress.percent(50, 100) == 50, "half is fifty percent");
        check(UpdateProgress.percent(100, 100) == 100, "a complete download is one hundred");
        check(UpdateProgress.percent(200, 100) == 100, "an oversized count is clamped to one hundred");
        check(UpdateProgress.percent(-5, 100) == 0, "a negative count is treated as zero");
        check(UpdateProgress.percent(5, 0) == -1, "an unknown total reports no percentage");
        check(UpdateProgress.percent(5, -1) == -1, "a negative total reports no percentage");

        check("0 B".equals(UpdateProgress.humanBytes(0)), "zero bytes is spelled out");
        check("512 B".equals(UpdateProgress.humanBytes(512)), "sub-kilobyte sizes stay in bytes");
        check("1 KB".equals(UpdateProgress.humanBytes(1024)), "a kilobyte drops the decimal");
        check("3.4 KB".equals(UpdateProgress.humanBytes(3482)), "kilobytes keep one decimal");
        check("30.3 MB".equals(UpdateProgress.humanBytes(31_851_448L)), "the release size reads as megabytes");
        check("1 GB".equals(UpdateProgress.humanBytes(1024L * 1024L * 1024L)), "a gigabyte drops the decimal");
        check("1.5 GB".equals(UpdateProgress.humanBytes(1024L * 1024L * 1024L + 512L * 1024L * 1024L)),
                "gigabytes keep one decimal");
        check("0 B".equals(UpdateProgress.humanBytes(-9)), "a negative size falls back to zero");

        // 31.8 MB scaled down: the label at start and near the end must not regress.
        check(UpdateProgress.percent(0, 31_851_448L) == 0, "an empty progress file is at zero");
        int almost = UpdateProgress.percent(31_800_000L, 31_851_448L);
        check(almost >= 99 && almost <= 100, "a nearly finished download is at ninety-nine or more");

        if (failures == 0) {
            System.out.println("PASS");
        } else {
            System.err.println(failures + " failure(s)");
            System.exit(1);
        }
    }
}
