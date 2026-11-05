package com.campus.sport.lan;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ranking rules, mirroring ui/leaderboard.js so the phone and the browser
 * produce identical boards from identical events.
 */
public final class Leaderboard {

    private Leaderboard() {
    }

    public static final String[] RANGES = {"day", "week", "month", "all"};

    /** Local start of the period, in epoch millis. */
    public static long periodStart(String range, long timestamp) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(timestamp);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);

        if ("all".equals(range)) return 0L;
        if ("day".equals(range)) return calendar.getTimeInMillis();
        if ("month".equals(range)) {
            calendar.set(Calendar.DAY_OF_MONTH, 1);
            return calendar.getTimeInMillis();
        }
        // Calendar.MONDAY..SUNDAY is 2..1, so Sunday must move back six days.
        int dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK);
        int offset = dayOfWeek == Calendar.SUNDAY ? -6 : Calendar.MONDAY - dayOfWeek;
        calendar.add(Calendar.DAY_OF_MONTH, offset);
        return calendar.getTimeInMillis();
    }

    public static final class Row {
        public String profileId;
        public String username;
        public int points;
        public int durationSeconds;
        public int repetitions;
        long latestAt;

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("profileId", profileId);
            map.put("username", username);
            map.put("points", points);
            map.put("durationSeconds", durationSeconds);
            map.put("repetitions", repetitions);
            return map;
        }
    }

    public static List<Row> rank(List<WorkoutEvent> events, String range, long now) {
        long start = periodStart(range, now);
        Map<String, Row> grouped = new LinkedHashMap<>();
        Set<String> seen = new LinkedHashSet<>();

        for (WorkoutEvent event : events) {
            if (event == null || event.demo) continue;
            if (!seen.add(event.eventId)) continue;
            if (event.occurredAt < start) continue;

            String id = event.profileId.isEmpty() ? event.deviceId : event.profileId;
            if (id.isEmpty()) id = "unknown";

            Row row = grouped.get(id);
            if (row == null) {
                row = new Row();
                row.profileId = id;
                row.username = event.username.isEmpty() ? "同学" : event.username;
                grouped.put(id, row);
            }
            // A player may rename themselves; show the newest name.
            if (event.occurredAt >= row.latestAt) {
                row.latestAt = event.occurredAt;
                if (!event.username.isEmpty()) row.username = event.username;
            }
            row.points += event.points;
            row.durationSeconds += event.durationSeconds;
            row.repetitions += event.repetitions;
        }

        List<Row> rows = new ArrayList<>(grouped.values());
        rows.sort(Comparator
                .comparingInt((Row row) -> row.points).reversed()
                .thenComparing(Comparator.comparingInt((Row row) -> row.durationSeconds).reversed())
                .thenComparing(Comparator.comparingInt((Row row) -> row.repetitions).reversed())
                .thenComparing(row -> row.profileId));
        return rows;
    }

    public static List<Map<String, Object>> rankToMaps(List<WorkoutEvent> events, String range, long now) {
        List<Map<String, Object>> out = new ArrayList<>();
        List<Row> rows = rank(events, range, now);
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> map = rows.get(i).toMap();
            map.put("rank", i + 1);
            out.add(map);
        }
        return out;
    }
}
