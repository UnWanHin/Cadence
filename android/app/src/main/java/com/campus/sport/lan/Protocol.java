package com.campus.sport.lan;

/**
 * The compatibility contract between app versions sharing one room.
 *
 * Old and new builds are expected to coexist: a phone that cannot update yet
 * must still appear on the leaderboard and still be able to chat. The rules
 * that keep that true are deliberately narrow:
 *
 *   1. Fields are only ever added. Nothing is renamed or removed.
 *   2. Unknown fields are ignored on read, never rejected.
 *   3. A missing optional field falls back to a documented default.
 *   4. The server rejects a payload only for the invariants it always checked:
 *      a non-empty eventId, points within range, and demo runs excluded.
 *
 * [VERSION] is bumped only when a change is not readable by the previous
 * version, which the rules above are designed to make rare.
 */
public final class Protocol {

    /** Wire version this build writes. */
    public static final int VERSION = 2;

    /** Oldest wire version this build can still read and rank. */
    public static final int MIN_READABLE = 1;

    private Protocol() {
    }

    /** True when a peer speaking [remote] can be understood. */
    public static boolean canRead(int remote) {
        return remote >= MIN_READABLE;
    }

    /** True when the peer speaks a newer dialect than this build. */
    public static boolean isNewerThanLocal(int remote) {
        return remote > VERSION;
    }

    /** True when the peer is older than this build but still readable. */
    public static boolean isOlderButReadable(int remote) {
        return remote < VERSION && canRead(remote);
    }
}
