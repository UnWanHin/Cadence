package com.campus.sport.lan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Decides which discovered room should act as the host.
 *
 * Two modes, both deterministic: given the same candidates every device picks
 * the same winner, which is what stops a group from splitting into two rooms.
 *
 * - delay: lowest round-trip first. Because RTT is roughly symmetric, every
 *   device measures similar numbers for the same peer, so they converge.
 * - random: stable inside a time bucket, so all devices agree for a few minutes
 *   and a re-election does not thrash.
 */
public final class HostElection {

    public static final String MODE_DELAY = "delay";
    public static final String MODE_RANDOM = "random";

    /** How long one random pick stays valid. */
    public static final long RANDOM_BUCKET_MS = 5 * 60 * 1000L;

    /** One room that could host, with a measured round-trip time. */
    public static final class Candidate {
        public final String address;
        public final String deviceId;
        public final String name;
        /** Milliseconds, or a negative value when it could not be measured. */
        public final long rttMs;

        public Candidate(String address, String deviceId, String name, long rttMs) {
            this.address = address;
            this.deviceId = deviceId == null ? "" : deviceId;
            this.name = name == null ? "" : name;
            this.rttMs = rttMs;
        }

        boolean measured() {
            return rttMs >= 0;
        }
    }

    private HostElection() {
    }

    /** Returns the chosen candidate, or null when there is nothing to choose. */
    public static Candidate elect(List<Candidate> candidates, String mode, long nowMs) {
        if (candidates == null || candidates.isEmpty()) return null;
        List<Candidate> pool = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (candidate != null && candidate.address != null && !candidate.address.isEmpty()) {
                pool.add(candidate);
            }
        }
        if (pool.isEmpty()) return null;

        // Never hand the room to a host that did not answer: a lottery over
        // unreachable rooms only strands the group.
        List<Candidate> reachable = new ArrayList<>();
        for (Candidate candidate : pool) {
            if (candidate.measured()) reachable.add(candidate);
        }
        List<Candidate> usable = reachable.isEmpty() ? pool : reachable;

        if (MODE_RANDOM.equals(mode)) return pickRandom(usable, nowMs);
        return pickFastest(usable);
    }

    private static Candidate pickFastest(List<Candidate> pool) {
        List<Candidate> sorted = new ArrayList<>(pool);
        sorted.sort(Comparator
                // Measured beats unmeasured, then faster wins.
                .comparing((Candidate c) -> !c.measured())
                .thenComparingLong(c -> c.measured() ? c.rttMs : Long.MAX_VALUE)
                // Tie-break on identity so every device lands on the same room.
                .thenComparing(c -> c.deviceId)
                .thenComparing(c -> c.address));
        return sorted.get(0);
    }

    private static Candidate pickRandom(List<Candidate> pool, long nowMs) {
        List<Candidate> sorted = new ArrayList<>(pool);
        sorted.sort(Comparator
                .comparing((Candidate c) -> c.deviceId)
                .thenComparing(c -> c.address));

        long bucket = Math.floorDiv(nowMs, RANDOM_BUCKET_MS);
        StringBuilder seed = new StringBuilder().append(bucket);
        for (Candidate candidate : sorted) {
            seed.append('|').append(candidate.deviceId).append('@').append(candidate.address);
        }
        long hash = fnv1a(seed.toString());
        int index = (int) Math.floorMod(hash, sorted.size());
        return sorted.get(index);
    }

    private static long fnv1a(String text) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < text.length(); i++) {
            hash ^= text.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash;
    }
}
