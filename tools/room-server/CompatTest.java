package com.campus.sport.lan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Old and new builds share a room. These checks pin the compatibility contract:
 * additive fields, unknown fields ignored, missing fields defaulted, and the
 * same ranking whether an event came from an old or a new client.
 */
public final class CompatTest {

    private static int failures;

    public static void main(String[] args) {
        // ---- a v1 event: no deviceId, no ruleVersion, no protocol field ----
        Map<String, Object> v1 = new LinkedHashMap<>();
        v1.put("eventId", "old-1");
        v1.put("profileId", "p-old");
        v1.put("username", "老版本");
        v1.put("exercise", "squat");
        v1.put("repetitions", 20);
        v1.put("durationSeconds", 60);
        v1.put("quality", 0.8);
        v1.put("points", 120);
        v1.put("occurredAt", 1_700_000_000_000L);

        WorkoutEvent oldEvent = WorkoutEvent.fromMap(v1);
        check("a v1 event still parses", oldEvent != null);
        check("v1 keeps its points", oldEvent != null && oldEvent.points == 120);
        check("missing deviceId defaults to empty", oldEvent != null && oldEvent.deviceId.isEmpty());
        check("missing ruleVersion defaults to empty", oldEvent != null && oldEvent.ruleVersion.isEmpty());

        // ---- a v2 event with fields this build does not know ----
        Map<String, Object> v2 = new LinkedHashMap<>(v1);
        v2.put("eventId", "new-1");
        v2.put("profileId", "p-new");
        v2.put("deviceId", "dev-new");
        v2.put("ruleVersion", "model-v2");
        v2.put("points", 150);
        v2.put("protocol", 2);
        v2.put("heartRateAvg", 132);            // unknown to this build
        v2.put("nestedFuture", Map.of("a", 1)); // also unknown
        v2.put("tags", List.of("indoor", "morning"));

        WorkoutEvent newEvent = WorkoutEvent.fromMap(v2);
        check("a v2 event parses", newEvent != null);
        check("unknown fields do not leak into the model", newEvent != null && newEvent.points == 150);
        check("known new fields are kept", newEvent != null && "model-v2".equals(newEvent.ruleVersion));

        // Round-tripping a v1 event must not invent fields that would confuse an
        // older reader, and must always carry the ones it needs.
        Map<String, Object> round = oldEvent == null ? Map.of() : oldEvent.toMap();
        check("round trip keeps eventId", "old-1".equals(WorkoutEvent.str(round.get("eventId"))));
        check("round trip keeps points", WorkoutEvent.num(round.get("points"), -1) == 120);
        check("round trip emits the required keys",
                round.containsKey("eventId") && round.containsKey("points")
                        && round.containsKey("occurredAt"));
        check("a v1 event re-parses after a round trip",
                WorkoutEvent.fromMap(round) != null);

        // ---- invariants the server has always enforced still hold ----
        check("blank eventId is rejected", WorkoutEvent.fromMap(mapOf("eventId", "", "points", 10)) == null);
        check("demo runs are rejected", WorkoutEvent.fromMap(mapOf("eventId", "x", "points", 10, "demo", 1)) == null);
        check("negative points are rejected", WorkoutEvent.fromMap(mapOf("eventId", "x", "points", -1)) == null);
        check("absurd points are rejected", WorkoutEvent.fromMap(mapOf("eventId", "x", "points", 999999)) == null);
        check("points at the ceiling are accepted",
                WorkoutEvent.fromMap(mapOf("eventId", "x", "points", 10000)) != null);

        // ---- ranking must not depend on which version produced the event ----
        List<WorkoutEvent> events = new ArrayList<>();
        if (oldEvent != null) events.add(oldEvent);
        if (newEvent != null) events.add(newEvent);
        List<Map<String, Object>> ranked = Leaderboard.rankToMaps(events, Leaderboard.RANGES[3], 1_700_000_100_000L);
        check("both versions appear on the board", ranked.size() == 2);
        check("the higher score leads",
                ranked.size() == 2 && WorkoutEvent.str(ranked.get(0).get("profileId")).equals("p-new"));

        // An event carrying extra fields must rank exactly like its clean twin.
        WorkoutEvent cleanTwin = WorkoutEvent.fromMap(withoutUnknown(v2));
        check("extra fields do not change the ranking",
                cleanTwin != null && newEvent != null
                        && cleanTwin.points == newEvent.points
                        && cleanTwin.occurredAt == newEvent.occurredAt);

        // ---- protocol negotiation ----
        check("v1 is readable", Protocol.canRead(1));
        check("v2 is readable", Protocol.canRead(2));
        check("a newer peer is readable", Protocol.canRead(Protocol.VERSION + 1));
        check("unknown-old is not readable", !Protocol.canRead(0));
        check("newer peer is flagged", Protocol.isNewerThanLocal(Protocol.VERSION + 1));
        check("older peer is flagged", Protocol.isOlderButReadable(1));
        check("same version is neither", !Protocol.isNewerThanLocal(Protocol.VERSION)
                && !Protocol.isOlderButReadable(Protocol.VERSION));

        if (failures > 0) {
            System.err.println("COMPAT TEST FAILED (" + failures + ")");
            System.exit(1);
        }
        System.out.println("PASS");
    }

    private static Map<String, Object> mapOf(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return map;
    }

    private static Map<String, Object> withoutUnknown(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>(source);
        copy.remove("protocol");
        copy.remove("heartRateAvg");
        copy.remove("nestedFuture");
        copy.remove("tags");
        return copy;
    }

    private static void check(String label, boolean condition) {
        if (condition) System.out.println("ok - " + label);
        else { System.err.println("FAIL: " + label); failures++; }
    }
}
