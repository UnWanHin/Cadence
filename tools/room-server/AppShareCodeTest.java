package com.campus.sport.lan;

/** Desktop checks for the app-share QR payload. Run via tools/run-jvm-tests.sh. */
public final class AppShareCodeTest {

    private static int failures;

    public static void main(String[] args) {
        String built = AppShareCode.build("http://192.168.43.2:8787/app.apk", "192.168.43.2:8787");
        check("build appends the room fragment",
                "http://192.168.43.2:8787/app.apk#r=192.168.43.2:8787".equals(built));
        check("apk url drops the fragment",
                "http://192.168.43.2:8787/app.apk".equals(AppShareCode.apkUrlOf(built)));
        check("room is recovered",
                "192.168.43.2:8787".equals(AppShareCode.roomOf(built)));
        check("share code is recognised", AppShareCode.isShareCode(built));

        // A plain browser-friendly URL must work too (no fragment).
        String plain = AppShareCode.build("http://10.0.0.5:8787/app.apk", null);
        check("no room means no fragment", "http://10.0.0.5:8787/app.apk".equals(plain));
        check("plain url is still a share code", AppShareCode.isShareCode(plain));
        check("plain url has no room", AppShareCode.roomOf(plain) == null);

        // Rebuilding from an already-fragmented url must not stack fragments.
        String rebuilt = AppShareCode.build(built, "10.0.0.9:8787");
        check("fragment is replaced, not appended",
                "http://192.168.43.2:8787/app.apk#r=10.0.0.9:8787".equals(rebuilt));

        // A room QR must not be mistaken for an install code.
        check("room code is not a share code", !AppShareCode.isShareCode("192.168.43.2:8787"));
        check("room code has no apk url", AppShareCode.apkUrlOf("192.168.43.2:8787").equals("192.168.43.2:8787"));

        // Query strings must not defeat the .apk check.
        check("query string is tolerated",
                AppShareCode.isShareCode("http://10.0.0.5:8787/app.apk?from=qr"));
        check("uppercase APK is recognised",
                AppShareCode.isShareCode("http://10.0.0.5:8787/APP.APK"));

        // A bad room in the fragment must not become a join attempt.
        check("bad room is dropped",
                AppShareCode.roomOf("http://10.0.0.5:8787/app.apk#r=nonsense") == null);
        check("a dotted host name is accepted",
                "my-pc.lan:8787".equals(AppShareCode.roomOf("http://10.0.0.5:8787/app.apk#r=my-pc.lan:8787")));
        check("localhost is accepted",
                "localhost:8787".equals(AppShareCode.roomOf("http://10.0.0.5:8787/app.apk#r=localhost:8787")));

        // Empty / null inputs must be safe.
        check("empty build", AppShareCode.build(null, "x").isEmpty());
        check("null parse", AppShareCode.apkUrlOf(null).isEmpty() && AppShareCode.roomOf(null) == null);
        check("null is not a share code", !AppShareCode.isShareCode(null));

        if (failures > 0) {
            System.err.println("APP SHARE CODE TEST FAILED (" + failures + ")");
            System.exit(1);
        }
        System.out.println("PASS");
    }

    private static void check(String label, boolean condition) {
        if (condition) System.out.println("ok - " + label);
        else { System.err.println("FAIL: " + label); failures++; }
    }
}
