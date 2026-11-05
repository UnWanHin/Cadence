package com.campus.sport.lan;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Desktop checks for host election. Run via tools/run-jvm-tests.sh. */
public final class HostElectionTest {

    private static int failures;

    public static void main(String[] args) {
        check("no candidates elects nobody",
                HostElection.elect(Collections.emptyList(), HostElection.MODE_DELAY, 0) == null);
        check("null candidates elects nobody",
                HostElection.elect(null, HostElection.MODE_DELAY, 0) == null);

        HostElection.Candidate solo = new HostElection.Candidate("10.0.0.1:8787", "dev-a", "A", 5);
        List<HostElection.Candidate> one = Collections.singletonList(solo);
        check("a single candidate wins by default",
                HostElection.elect(one, HostElection.MODE_DELAY, 0) == solo);
        check("random with one candidate returns it",
                HostElection.elect(one, HostElection.MODE_RANDOM, 12_345) == solo);

        HostElection.Candidate fast = new HostElection.Candidate("10.0.0.2:8787", "dev-b", "B", 3);
        HostElection.Candidate slow = new HostElection.Candidate("10.0.0.3:8787", "dev-c", "C", 40);
        HostElection.Candidate unknown = new HostElection.Candidate("10.0.0.4:8787", "dev-d", "D", -1);

        List<HostElection.Candidate> three = Arrays.asList(slow, unknown, fast);
        check("delay mode picks the fastest",
                HostElection.elect(three, HostElection.MODE_DELAY, 0) == fast);

        List<HostElection.Candidate> reordered = Arrays.asList(fast, slow, unknown);
        check("delay mode ignores input order",
                HostElection.elect(reordered, HostElection.MODE_DELAY, 0) == fast);

        check("an unmeasurable candidate loses to a measured one",
                HostElection.elect(Arrays.asList(unknown, slow), HostElection.MODE_DELAY, 0) == slow);

        // Equal latency: every device must still agree on one winner.
        HostElection.Candidate tieA = new HostElection.Candidate("10.0.0.5:8787", "dev-aa", "AA", 7);
        HostElection.Candidate tieB = new HostElection.Candidate("10.0.0.6:8787", "dev-ab", "AB", 7);
        HostElection.Candidate tie1 = HostElection.elect(Arrays.asList(tieA, tieB), HostElection.MODE_DELAY, 0);
        HostElection.Candidate tie2 = HostElection.elect(Arrays.asList(tieB, tieA), HostElection.MODE_DELAY, 0);
        check("equal latency breaks the tie the same way", tie1 == tie2);
        check("equal latency prefers the smaller device id", tie1 == tieA);

        // Random mode must never hand out a dead room while a live one exists.
        List<HostElection.Candidate> mixed = Arrays.asList(fast, slow, unknown);
        boolean pickedDead = false;
        for (int i = 0; i < 200; i++) {
            HostElection.Candidate picked = HostElection.elect(
                    mixed, HostElection.MODE_RANDOM, i * HostElection.RANDOM_BUCKET_MS);
            if (picked == unknown) pickedDead = true;
        }
        check("random mode never picks an unresponsive room", !pickedDead);

        // Random mode: same bucket, any order, same winner.
        long t = 1_700_000_000_000L;
        List<HostElection.Candidate> pool = new ArrayList<>(Arrays.asList(fast, slow));
        HostElection.Candidate r1 = HostElection.elect(pool, HostElection.MODE_RANDOM, t);
        Collections.reverse(pool);
        HostElection.Candidate r2 = HostElection.elect(pool, HostElection.MODE_RANDOM, t);
        check("random mode is order independent", r1 == r2);
        check("random mode is stable within a bucket",
                HostElection.elect(pool, HostElection.MODE_RANDOM, t + 1000) == r1);

        // Across enough buckets every candidate should get a turn, and none may
        // crash or return null.
        boolean sawAll = true;
        java.util.Set<String> seen = new java.util.HashSet<>();
        int steps = 400;
        for (int i = 0; i < steps; i++) {
            HostElection.Candidate picked = HostElection.elect(
                    pool, HostElection.MODE_RANDOM, t + i * HostElection.RANDOM_BUCKET_MS);
            if (picked == null) { sawAll = false; break; }
            seen.add(picked.deviceId);
        }
        check("random mode spreads over all candidates", sawAll && seen.size() == 2);

        // Changing the roster changes the seed, so a leaver re-runs the lottery.
        HostElection.Candidate extra = new HostElection.Candidate("10.0.0.7:8787", "dev-e", "E", 9);
        List<HostElection.Candidate> bigger = new ArrayList<>(pool);
        bigger.add(extra);
        boolean changed = false;
        for (int i = 0; i < 50 && !changed; i++) {
            // Step by a whole bucket: inside one bucket the pick is fixed by design.
            long at = t + i * HostElection.RANDOM_BUCKET_MS;
            HostElection.Candidate before = HostElection.elect(pool, HostElection.MODE_RANDOM, at);
            HostElection.Candidate after = HostElection.elect(bigger, HostElection.MODE_RANDOM, at);
            if (before != after) changed = true;
        }
        check("a different roster gives a different pick", changed);

        if (failures > 0) {
            System.err.println("HOST ELECTION TEST FAILED (" + failures + ")");
            System.exit(1);
        }
        System.out.println("PASS");
    }

    private static void check(String label, boolean condition) {
        if (condition) {
            System.out.println("ok - " + label);
        } else {
            System.err.println("FAIL: " + label);
            failures++;
        }
    }
}
