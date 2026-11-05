package com.campus.sport.lan;

import java.util.LinkedHashMap;
import java.util.Map;

/** One finished workout, as shared with the room. Immutable by contract. */
public final class WorkoutEvent {

    public final String eventId;
    public final String profileId;
    public final String deviceId;
    public final String username;
    public final String exercise;
    public final int repetitions;
    public final int durationSeconds;
    public final double quality;
    public final int points;
    public final long occurredAt;
    public final String ruleVersion;
    public final boolean demo;

    public WorkoutEvent(String eventId, String profileId, String deviceId, String username,
                        String exercise, int repetitions, int durationSeconds, double quality,
                        int points, long occurredAt, String ruleVersion, boolean demo) {
        this.eventId = eventId;
        this.profileId = profileId;
        this.deviceId = deviceId;
        this.username = username;
        this.exercise = exercise;
        this.repetitions = repetitions;
        this.durationSeconds = durationSeconds;
        this.quality = quality;
        this.points = points;
        this.occurredAt = occurredAt;
        this.ruleVersion = ruleVersion;
        this.demo = demo;
    }

    /** Returns null when the payload is not a usable event. */
    public static WorkoutEvent fromMap(Map<String, Object> map) {
        if (map == null) return null;
        String eventId = str(map.get("eventId"));
        if (eventId.isEmpty()) return null;
        int points = (int) num(map.get("points"), 0);
        // A demo run must never enter the shared ranking.
        if (num(map.get("demo"), 0) == 1) return null;
        if (points < 0 || points > 10000) return null;
        return new WorkoutEvent(
                eventId,
                str(map.get("profileId")),
                str(map.get("deviceId")),
                str(map.get("username")),
                str(map.get("exercise")),
                (int) num(map.get("repetitions"), 0),
                (int) num(map.get("durationSeconds"), 0),
                num(map.get("quality"), 0),
                points,
                (long) num(map.get("occurredAt"), System.currentTimeMillis()),
                str(map.get("ruleVersion")),
                false);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("eventId", eventId);
        map.put("profileId", profileId);
        map.put("deviceId", deviceId);
        map.put("username", username);
        map.put("exercise", exercise);
        map.put("repetitions", repetitions);
        map.put("durationSeconds", durationSeconds);
        map.put("quality", quality);
        map.put("points", points);
        map.put("occurredAt", occurredAt);
        map.put("ruleVersion", ruleVersion);
        return map;
    }

    static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    static double num(Object value, double fallback) {
        if (value instanceof Number) return ((Number) value).doubleValue();
        if (value instanceof Boolean) return ((Boolean) value) ? 1 : 0;
        if (value instanceof String) {
            try { return Double.parseDouble(((String) value).trim()); } catch (NumberFormatException error) { return fallback; }
        }
        return fallback;
    }
}
