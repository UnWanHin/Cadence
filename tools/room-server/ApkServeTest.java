package com.campus.sport.lan;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

/** Checks that the room service can hand out an APK. Run via tools/run-jvm-tests.sh. */
public final class ApkServeTest {

    private static int failures;

    public static void main(String[] args) throws Exception {
        int port = 18887;
        Path dir = Files.createTempDirectory("cadence-apk");
        File apk = new File(dir.toFile(), "sample.apk");
        byte[] payload = new byte[300_000];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i % 251);
        Files.write(apk.toPath(), payload);

        RoomHttpServer server = new RoomHttpServer(new FileRoomStore(new File(dir.toFile(), "store.json")), null, port);
        try {
            server.start();

            // Before wiring a file the route must fail cleanly, not 200 with junk.
            HttpURLConnection before = open(port, "GET");
            check("no apk wired returns 404", before.getResponseCode() == 404);
            before.disconnect();

            server.setApkFile(apk);

            HttpURLConnection get = open(port, "GET");
            check("apk route returns 200", get.getResponseCode() == 200);
            check("apk content type",
                    "application/vnd.android.package-archive".equals(get.getContentType()));
            check("apk is an attachment",
                    String.valueOf(get.getHeaderField("Content-Disposition")).contains("cadence.apk"));
            check("apk length header", get.getContentLengthLong() == payload.length);
            byte[] body = readAll(get.getInputStream());
            get.disconnect();
            check("apk bytes match", body.length == payload.length && body[payload.length - 1] == payload[payload.length - 1]);

            HttpURLConnection head = open(port, "HEAD");
            check("HEAD reports the same length", head.getContentLengthLong() == payload.length);
            check("HEAD has no body", readAll(head.getInputStream()).length == 0);
            head.disconnect();

            // A directory is not an APK; must not be downloadable.
            server.setApkFile(dir.toFile());
            HttpURLConnection bad = open(port, "GET");
            check("a directory is rejected", bad.getResponseCode() == 404);
            bad.disconnect();
        } finally {
            server.stop();
            Files.deleteIfExists(apk.toPath());
            Files.deleteIfExists(new File(dir.toFile(), "store.json").toPath());
            Files.deleteIfExists(dir);
        }

        if (failures > 0) {
            System.err.println("APK SERVE TEST FAILED (" + failures + ")");
            System.exit(1);
        }
        System.out.println("PASS");
    }

    private static HttpURLConnection open(int port, String method) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + RoomHttpServer.APK_PATH).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(3000);
        c.setReadTimeout(5000);
        return c;
    }

    private static byte[] readAll(InputStream in) throws Exception {
        if (in == null) return new byte[0];
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
        return out.toByteArray();
    }

    private static void check(String label, boolean condition) {
        if (condition) System.out.println("ok - " + label);
        else { System.err.println("FAIL: " + label); failures++; }
    }
}
