package com.campus.sport.lan;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves the name a profile should be shown under right now.
 *
 * Chat messages keep the name they were sent with so old clients still work,
 * but a person is allowed to rename themselves at any time. The newest name in
 * the room's events or messages therefore wins, and the local profile always
 * uses the name currently saved on this device.
 */
public final class ChatNames {

    private ChatNames() {
    }

    public static Map<String, String> resolve(List<ChatMessage> messages,
                                              List<WorkoutEvent> events,
                                              String localProfileId,
                                              String localUsername) {
        Map<String, String> names = new HashMap<>();
        Map<String, Long> latestAt = new HashMap<>();
        // A room-wide rename is authoritative: it was applied to every message
        // from that profile after the fact, so it wins even if device clocks
        // disagree about event times.
        Map<String, String> confirmedNames = new HashMap<>();
        Map<String, Long> confirmedAt = new HashMap<>();

        if (events != null) {
            for (WorkoutEvent event : events) {
                if (event == null) continue;
                String id = event.profileId == null || event.profileId.isEmpty()
                        ? event.deviceId
                        : event.profileId;
                offer(names, latestAt, id, event.username, event.occurredAt);
            }
        }
        if (messages != null) {
            for (ChatMessage message : messages) {
                if (message == null) continue;
                // A room-wide rename rewrites old messages; keep the rewrite
                // time separate so the new name still beats later events that
                // were recorded under the old name.
                offer(names, latestAt, message.profileId, message.username,
                        Math.max(message.sentAt, message.nameUpdatedAt));
                if (message.nameUpdatedAt > 0 && message.profileId != null
                        && !message.profileId.trim().isEmpty()
                        && message.username != null && !message.username.trim().isEmpty()) {
                    Long previous = confirmedAt.get(message.profileId);
                    if (previous == null || message.nameUpdatedAt >= previous) {
                        confirmedAt.put(message.profileId, message.nameUpdatedAt);
                        confirmedNames.put(message.profileId, message.username.trim());
                    }
                }
            }
        }
        names.putAll(confirmedNames);

        // The name saved on this device is authoritative for this profile; it
        // must apply immediately, before any new event or message is shared.
        if (localProfileId != null && !localProfileId.trim().isEmpty()
                && localUsername != null && !localUsername.trim().isEmpty()) {
            names.put(localProfileId, localUsername.trim());
        }
        return names;
    }

    private static void offer(Map<String, String> names, Map<String, Long> latestAt,
                              String profileId, String username, long at) {
        if (profileId == null || profileId.trim().isEmpty()) return;
        if (username == null || username.trim().isEmpty()) return;
        Long previous = latestAt.get(profileId);
        if (previous == null || at >= previous) {
            latestAt.put(profileId, at);
            names.put(profileId, username.trim());
        }
    }
}
