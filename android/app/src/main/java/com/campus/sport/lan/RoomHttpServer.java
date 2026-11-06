package com.campus.sport.lan;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The room service, embedded in the app so a phone can host without a server.
 *
 * Deliberately dependency-free (raw ServerSocket + HTTP/1.1) so the exact same
 * code is verified by the desktop test harness.
 */
public final class RoomHttpServer {

    public static final String API_PREFIX = "/api/v1";
    public static final String APK_PATH = "/app.apk";
    public static final String APK_FILE_NAME = "cadence.apk";
    private static final String APK_CONTENT_TYPE = "application/vnd.android.package-archive";
    private static final int MAX_BODY_BYTES = 4 * 1024 * 1024;

    private final RoomStore store;
    private final File webRoot;
    private final int port;
    private volatile File apkFile;
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private volatile boolean running;

    public RoomHttpServer(RoomStore store, File webRoot, int port) {
        this.store = store;
        this.webRoot = webRoot;
        this.port = port;
    }

    /** Lets the host hand out its own APK over the same port. */
    public void setApkFile(File file) {
        this.apkFile = file != null && file.isFile() ? file : null;
    }

    public int port() {
        return port;
    }

    public boolean isRunning() {
        return running;
    }

    public synchronized void start() throws IOException {
        if (running) return;
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(port));
        running = true;
        acceptThread = new Thread(this::acceptLoop, "cadence-room-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public synchronized void stop() {
        running = false;
        if (serverSocket != null) {
            try { serverSocket.close(); } catch (IOException ignored) { /* closing */ }
            serverSocket = null;
        }
        if (acceptThread != null) {
            acceptThread.interrupt();
            acceptThread = null;
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                Thread worker = new Thread(() -> handle(socket), "cadence-room-conn");
                worker.setDaemon(true);
                worker.start();
            } catch (IOException error) {
                if (!running) return;
            }
        }
    }

    // ---- request handling -------------------------------------------------

    private void handle(Socket socket) {
        try (Socket client = socket) {
            client.setSoTimeout(15000);
            InputStream in = client.getInputStream();
            Request request = Request.read(in);
            if (request == null) return;
            Response response = route(request);
            response.writeTo(client.getOutputStream(), request.method.equals("HEAD"));
        } catch (IOException ignored) {
            // Client went away; nothing to do.
        }
    }

    private Response route(Request request) {
        String path = request.path;
        // A browser POSTing JSON cross-origin preflights with OPTIONS. Answer
        // before any routing, otherwise the real request never gets sent.
        if ("OPTIONS".equals(request.method)) return Response.empty(204);
        if (APK_PATH.equals(path)) return serveApk();
        if (!path.startsWith(API_PREFIX)) {
            return serveStatic(path);
        }
        String api = path.substring(API_PREFIX.length());
        if (api.isEmpty()) api = "/";

        try {
            if ("/health".equals(api)) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("ok", true);
                body.put("storage", "room");
                body.put("protocol", Protocol.VERSION);
                body.put("events", store.events().size());
                return Response.json(200, body);
            }
            if ("/events".equals(api)) {
                if ("GET".equals(request.method)) {
                    Map<String, Object> body = new LinkedHashMap<>();
                    List<Map<String, Object>> list = new ArrayList<>();
                    for (WorkoutEvent event : store.events()) list.add(event.toMap());
                    body.put("events", list);
                    return Response.json(200, body);
                }
                if ("POST".equals(request.method)) {
                    Map<String, Object> payload = RoomJson.readObject(request.bodyText());
                    List<WorkoutEvent> incoming = new ArrayList<>();
                    Object raw = payload.get("events");
                    if (raw instanceof List) {
                        for (Object item : (List<?>) raw) {
                            if (item instanceof Map) {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> map = (Map<String, Object>) item;
                                WorkoutEvent event = WorkoutEvent.fromMap(map);
                                if (event != null) incoming.add(event);
                            }
                        }
                    }
                    store.addEvents(incoming);
                    Map<String, Object> body = new LinkedHashMap<>();
                    List<Map<String, Object>> list = new ArrayList<>();
                    for (WorkoutEvent event : store.events()) list.add(event.toMap());
                    body.put("events", list);
                    return Response.json(200, body);
                }
                return Response.error(405, "METHOD_NOT_ALLOWED", "只支持 GET/POST");
            }
            if ("/chat".equals(api)) {
                if ("GET".equals(request.method)) {
                    List<Map<String, Object>> list = new ArrayList<>();
                    List<ChatMessage> messages = store.chat();
                    int from = Math.max(0, messages.size() - 100);
                    for (int i = from; i < messages.size(); i++) list.add(messages.get(i).toMap());
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("messages", list);
                    return Response.json(200, body);
                }
                if ("POST".equals(request.method)) {
                    Map<String, Object> payload = RoomJson.readObject(request.bodyText());
                    ChatMessage message = ChatMessage.fromMap(payload, "m-" + System.currentTimeMillis());
                    if (message == null) return Response.error(400, "VALIDATION_ERROR", "消息不能为空");
                    store.addChat(message);
                    return Response.json(201, message.toMap());
                }
                return Response.error(405, "METHOD_NOT_ALLOWED", "只支持 GET/POST");
            }
            if ("/profile".equals(api)) {
                if (!"POST".equals(request.method)) {
                    return Response.error(405, "METHOD_NOT_ALLOWED", "只支持 POST");
                }
                Map<String, Object> payload = RoomJson.readObject(request.bodyText());
                String profileId = WorkoutEvent.str(payload.get("profileId")).trim();
                String rawName = WorkoutEvent.str(payload.get("username")).trim();
                if (profileId.isEmpty() || rawName.isEmpty()) {
                    return Response.error(400, "VALIDATION_ERROR", "缺少用户标识或昵称");
                }
                String username = ChatMessage.normalizeUsername(rawName);
                boolean updated = store.renameProfile(profileId, username);
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("profileId", profileId);
                body.put("username", username);
                body.put("updated", updated);
                return Response.json(200, body);
            }
            if ("/leaderboard".equals(api)) {
                String range = request.query.getOrDefault("range", "week");
                List<Map<String, Object>> rows = Leaderboard.rankToMaps(store.events(), range, System.currentTimeMillis());
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("range", range);
                body.put("rows", rows);
                return Response.json(200, body);
            }
            return Response.error(404, "NOT_FOUND", "接口不存在");
        } catch (RuntimeException error) {
            return Response.error(400, "VALIDATION_ERROR", "请求内容无法解析");
        }
    }

    private Response serveApk() {
        File file = apkFile;
        if (file == null || !file.isFile()) {
            return Response.error(404, "NOT_FOUND", "本机没有可分享的安装包");
        }
        return Response.file(200, APK_CONTENT_TYPE, file,
                "attachment; filename=\"" + APK_FILE_NAME + "\"");
    }

    private Response serveStatic(String requestPath) {
        if (webRoot == null || !webRoot.isDirectory()) {
            return Response.error(404, "NOT_FOUND", "未配置静态目录");
        }
        String relative = requestPath.equals("/") ? "index.html" : requestPath.replaceFirst("^/+", "");
        File target = new File(webRoot, relative);
        try {
            String base = webRoot.getCanonicalPath();
            String resolved = target.getCanonicalPath();
            if (!resolved.equals(base) && !resolved.startsWith(base + File.separator)) {
                return Response.error(403, "FORBIDDEN", "路径不允许");
            }
            if (target.isDirectory()) target = new File(target, "index.html");
            if (!target.isFile()) return Response.error(404, "NOT_FOUND", "文件不存在");
            byte[] bytes = Files.readAllBytes(target.toPath());
            return Response.bytes(200, contentType(target.getName()), bytes);
        } catch (IOException error) {
            return Response.error(404, "NOT_FOUND", "文件不存在");
        }
    }

    private static String contentType(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".html")) return "text/html; charset=utf-8";
        if (lower.endsWith(".js") || lower.endsWith(".mjs")) return "text/javascript; charset=utf-8";
        if (lower.endsWith(".css")) return "text/css; charset=utf-8";
        if (lower.endsWith(".json")) return "application/json; charset=utf-8";
        if (lower.endsWith(".wasm")) return "application/wasm";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".task")) return "application/octet-stream";
        return "application/octet-stream";
    }

    /**
     * IPv4 addresses other devices on the same network can reach, best first.
     * Ordering matters: the first entry is what the UI shows and what the QR
     * code carries, and a phone usually has several candidates.
     */
    public static List<String> lanAddresses() {
        List<LanAddresses.Entry> entries = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress address : Collections.list(ni.getInetAddresses())) {
                    if (address.getAddress().length == 4) {
                        entries.add(new LanAddresses.Entry(ni.getName(), address.getHostAddress()));
                    }
                }
            }
        } catch (IOException ignored) {
            // Best effort only.
        }
        return LanAddresses.ordered(entries);
    }

    // ---- tiny HTTP layer --------------------------------------------------

    private static final class Request {
        String method = "GET";
        String path = "/";
        String body = "";
        final Map<String, String> query = new LinkedHashMap<>();

        String bodyText() {
            return body;
        }

        static Request read(InputStream in) throws IOException {
            // Headers end at a blank line: CRLF CRLF (or bare LF LF).
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            int[] tail = {0, 0, 0, 0};
            int current;
            while ((current = in.read()) != -1) {
                buffer.write(current);
                tail[0] = tail[1];
                tail[1] = tail[2];
                tail[2] = tail[3];
                tail[3] = current;
                boolean crlfPair = tail[0] == '\r' && tail[1] == '\n' && tail[2] == '\r' && tail[3] == '\n';
                boolean lfPair = tail[1] == '\n' && tail[3] == '\n' && tail[2] != '\r';
                if (crlfPair || lfPair) break;
                if (buffer.size() > 64 * 1024) return null;
            }
            if (buffer.size() == 0) return null;
            String headerBlock = buffer.toString(StandardCharsets.ISO_8859_1.name());
            return finishReading(in, headerBlock);
        }

        private static Request finishReading(InputStream in, String headerBlock) throws IOException {
            String[] lines = headerBlock.split("\r\n");
            if (lines.length == 0) return null;
            String[] parts = lines[0].split(" ");
            if (parts.length < 2) return null;

            Request request = new Request();
            request.method = parts[0].toUpperCase(Locale.ROOT);
            String target = parts[1];

            int questionMark = target.indexOf('?');
            if (questionMark >= 0) {
                String queryText = target.substring(questionMark + 1);
                target = target.substring(0, questionMark);
                for (String pair : queryText.split("&")) {
                    if (pair.isEmpty()) continue;
                    int equals = pair.indexOf('=');
                    String key = equals < 0 ? pair : pair.substring(0, equals);
                    String value = equals < 0 ? "" : pair.substring(equals + 1);
                    request.query.put(decode(key), decode(value));
                }
            }
            request.path = decode(target);

            int contentLength = 0;
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                if (colon < 0) continue;
                String name = lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT);
                if (name.equals("content-length")) {
                    try {
                        contentLength = Integer.parseInt(lines[i].substring(colon + 1).trim());
                    } catch (NumberFormatException ignored) {
                        contentLength = 0;
                    }
                }
            }
            if (contentLength > MAX_BODY_BYTES) contentLength = MAX_BODY_BYTES;
            if (contentLength > 0) {
                byte[] body = new byte[contentLength];
                int read = 0;
                while (read < contentLength) {
                    int chunk = in.read(body, read, contentLength - read);
                    if (chunk < 0) break;
                    read += chunk;
                }
                request.body = new String(body, 0, read, StandardCharsets.UTF_8);
            }
            return request;
        }

        private static String decode(String value) {
            try {
                return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
            } catch (IOException | IllegalArgumentException error) {
                return value;
            }
        }
    }

    private static final class Response {
        int status;
        String contentType;
        byte[] body;
        File file;
        String disposition;

        static Response json(int status, Object payload) {
            Response response = new Response();
            response.status = status;
            response.contentType = "application/json; charset=utf-8";
            response.body = RoomJson.write(payload).getBytes(StandardCharsets.UTF_8);
            return response;
        }

        static Response error(int status, String code, String message) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("code", code);
            details.put("message", message);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", details);
            return json(status, body);
        }

        /** Streams from disk: the APK is far too big to buffer in memory. */
        static Response file(int status, String contentType, File file, String disposition) {
            Response response = new Response();
            response.status = status;
            response.contentType = contentType;
            response.file = file;
            response.disposition = disposition;
            return response;
        }

        static Response bytes(int status, String contentType, byte[] body) {
            Response response = new Response();
            response.status = status;
            response.contentType = contentType;
            response.body = body;
            return response;
        }

        static Response empty(int status) {
            return bytes(status, "text/plain; charset=utf-8", new byte[0]);
        }

        void writeTo(OutputStream rawOut, boolean headOnly) throws IOException {
            OutputStream out = new BufferedOutputStream(rawOut, 32 * 1024);
            StringBuilder head = new StringBuilder();
            head.append("HTTP/1.1 ").append(status).append(' ').append(reason()).append("\r\n");
            head.append("Access-Control-Allow-Origin: *\r\n");
            head.append("Access-Control-Allow-Methods: GET, POST, DELETE, OPTIONS\r\n");
            head.append("Access-Control-Allow-Headers: Content-Type\r\n");
            head.append("Cache-Control: no-store\r\n");
            head.append("X-Content-Type-Options: nosniff\r\n");
            head.append("Connection: close\r\n");
            long length = file != null ? file.length() : (body == null ? 0 : body.length);
            head.append("Content-Type: ").append(contentType).append("\r\n");
            if (disposition != null) head.append("Content-Disposition: ").append(disposition).append("\r\n");
            head.append("Content-Length: ").append(length).append("\r\n\r\n");
            out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));

            if (!headOnly) {
                if (file != null) {
                    InputStream in = new java.io.FileInputStream(file);
                    try {
                        byte[] chunk = new byte[64 * 1024];
                        int read;
                        while ((read = in.read(chunk)) > 0) out.write(chunk, 0, read);
                    } finally {
                        in.close();
                    }
                } else if (body != null && body.length > 0) {
                    out.write(body);
                }
            }
            out.flush();
        }

        private String reason() {
            switch (status) {
                case 200: return "OK";
                case 201: return "Created";
                case 204: return "No Content";
                case 400: return "Bad Request";
                case 403: return "Forbidden";
                case 404: return "Not Found";
                case 405: return "Method Not Allowed";
                default: return "Internal Server Error";
            }
        }
    }
}
