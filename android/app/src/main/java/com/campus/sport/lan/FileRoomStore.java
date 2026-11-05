package com.campus.sport.lan;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Room state persisted as a single JSON file.
 *
 * Plain Java on purpose: the same class runs inside the APK and inside the
 * desktop test harness, so the protocol verified on a JVM is the protocol that
 * ships on the phone. Swapping in SQLite later only means implementing RoomStore.
 */
public final class FileRoomStore implements RoomStore {

    private static final int MAX_EVENTS = 5000;
    private static final int MAX_CHAT = 1000;

    private final File file;
    private final List<WorkoutEvent> events = new ArrayList<>();
    private final List<ChatMessage> chat = new ArrayList<>();
    private final Object lock = new Object();

    public FileRoomStore(File file) {
        this.file = file;
        load();
    }

    private void load() {
        if (file == null || !file.isFile()) return;
        try {
            String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            Map<String, Object> root = RoomJson.readObject(text);
            Object rawEvents = root.get("workoutEvents");
            if (rawEvents instanceof List) {
                for (Object item : (List<?>) rawEvents) {
                    if (item instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> map = (Map<String, Object>) item;
                        WorkoutEvent event = WorkoutEvent.fromMap(map);
                        if (event != null) events.add(event);
                    }
                }
            }
            Object rawChat = root.get("chatMessages");
            if (rawChat instanceof List) {
                for (Object item : (List<?>) rawChat) {
                    if (item instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> map = (Map<String, Object>) item;
                        ChatMessage message = ChatMessage.fromMap(map, "loaded-" + chat.size());
                        if (message != null) chat.add(message);
                    }
                }
            }
        } catch (IOException | RuntimeException error) {
            System.err.println("[room] 读取 " + file + " 失败，将从空状态开始: " + error.getMessage());
        }
    }

    private void save() {
        if (file == null) return;
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", 2);
        List<Map<String, Object>> eventMaps = new ArrayList<>();
        for (WorkoutEvent event : events) eventMaps.add(event.toMap());
        root.put("workoutEvents", eventMaps);
        List<Map<String, Object>> chatMaps = new ArrayList<>();
        for (ChatMessage message : chat) chatMaps.add(message.toMap());
        root.put("chatMessages", chatMaps);

        try {
            File parent = file.getParentFile();
            if (parent != null) parent.mkdirs();
            File temp = new File(file.getParentFile(), file.getName() + ".tmp");
            Files.write(temp.toPath(), RoomJson.write(root).getBytes(StandardCharsets.UTF_8));
            Files.move(temp.toPath(), file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException error) {
            System.err.println("[room] 保存 " + file + " 失败: " + error.getMessage());
        }
    }

    @Override
    public List<WorkoutEvent> events() {
        synchronized (lock) {
            return new ArrayList<>(events);
        }
    }

    @Override
    public boolean addEvents(List<WorkoutEvent> incoming) {
        if (incoming == null || incoming.isEmpty()) return false;
        synchronized (lock) {
            boolean changed = false;
            for (WorkoutEvent event : incoming) {
                if (event == null) continue;
                boolean known = false;
                for (WorkoutEvent existing : events) {
                    if (existing.eventId.equals(event.eventId)) { known = true; break; }
                }
                if (known) continue;
                events.add(event);
                changed = true;
            }
            if (changed) {
                while (events.size() > MAX_EVENTS) events.remove(0);
                save();
            }
            return changed;
        }
    }

    @Override
    public List<ChatMessage> chat() {
        synchronized (lock) {
            return new ArrayList<>(chat);
        }
    }

    @Override
    public void addChat(ChatMessage message) {
        if (message == null) return;
        synchronized (lock) {
            chat.add(message);
            while (chat.size() > MAX_CHAT) chat.remove(0);
            save();
        }
    }
}
