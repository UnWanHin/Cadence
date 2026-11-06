package com.campus.sport.lan;

import java.util.LinkedHashMap;
import java.util.Map;

/** One chat message in the room. */
public final class ChatMessage {

    public final String messageId;
    public final String username;
    public final String profileId;
    public final String text;
    public final long sentAt;
    /**
     * When the sender last renamed themselves, or 0 when the stored name still
     * belongs to the moment the message was sent. Kept separate from
     * {@link #sentAt} so renaming history does not reorder it.
     */
    public final long nameUpdatedAt;

    public ChatMessage(String messageId, String username, String profileId, String text, long sentAt) {
        this(messageId, username, profileId, text, sentAt, 0L);
    }

    public ChatMessage(String messageId, String username, String profileId, String text,
                       long sentAt, long nameUpdatedAt) {
        this.messageId = messageId;
        this.username = username;
        this.profileId = profileId;
        this.text = text;
        this.sentAt = sentAt;
        this.nameUpdatedAt = nameUpdatedAt;
    }

    /** Returns null when the payload is not a usable message. */
    public static ChatMessage fromMap(Map<String, Object> map, String fallbackId) {
        if (map == null) return null;
        String text = WorkoutEvent.str(map.get("text")).trim();
        if (text.isEmpty()) return null;
        if (text.length() > 500) text = text.substring(0, 500);
        String id = WorkoutEvent.str(map.get("messageId"));
        if (id.isEmpty()) id = fallbackId;

        return new ChatMessage(id, normalizeUsername(map.get("username")),
                WorkoutEvent.str(map.get("profileId")), text,
                (long) WorkoutEvent.num(map.get("sentAt"), System.currentTimeMillis()),
                (long) WorkoutEvent.num(map.get("nameUpdatedAt"), 0));
    }

    /** Trims a display name to the room's 20-character limit. */
    public static String normalizeUsername(Object raw) {
        String username = WorkoutEvent.str(raw).trim();
        if (username.isEmpty()) username = "同学";
        if (username.length() > 20) username = username.substring(0, 20);
        return username;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("messageId", messageId);
        map.put("username", username);
        map.put("profileId", profileId);
        map.put("text", text);
        map.put("sentAt", sentAt);
        map.put("nameUpdatedAt", nameUpdatedAt);
        return map;
    }
}
