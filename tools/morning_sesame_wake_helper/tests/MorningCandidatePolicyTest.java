package dev.yossi.morningsesame;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class MorningCandidatePolicyTest {
    private static int checks;
    private static void check(boolean condition, String why) {
        checks++;
        if (!condition) throw new AssertionError(why);
    }
    public static void main(String[] args) {
        String a = "aaaaaaaaaaa", b = "bbbbbbbbbbb", c = "ccccccccccc";
        List<String> ids = Arrays.asList(a, b, c);
        Map<String, Integer> durations = new HashMap<>();
        durations.put(a, 3600); durations.put(b, 3900); durations.put(c, 5400);
        Map<String, Double> progress = new HashMap<>();
        progress.put(a, 40.0); progress.put(b, 1.0); progress.put(c, 60.0);
        check(b.equals(MorningCandidatePolicy.longestRemaining(ids, durations, progress)),
                "prefer longest expected remaining play time, not first or longest total");
        progress.put(a, 0.0); progress.put(b, 80.0); progress.put(c, 100.0);
        check(a.equals(MorningCandidatePolicy.longestRemaining(ids, durations, progress)),
                "avoid nearly finished videos when local play history has exhausted");
        progress.clear();
        check(c.equals(MorningCandidatePolicy.longestRemaining(ids, durations, progress)),
                "without progress data prefer longest full episode");
        progress.put(c, Double.NaN); progress.put(b, Double.POSITIVE_INFINITY);
        check(c.equals(MorningCandidatePolicy.longestRemaining(ids, durations, progress)),
                "invalid percentages are handled without affecting playback");
        progress.put(c, 99.0); progress.put(a, -15.0); progress.put(b, 101.0);
        check(a.equals(MorningCandidatePolicy.longestRemaining(ids, durations, progress)),
                "out of range percentages are safely clamped");
        durations.remove(c);
        progress.clear();
        check(b.equals(MorningCandidatePolicy.longestRemaining(ids, durations, progress)),
                "missing durations cannot outrank known qualifying videos");
        durations.put(b, 3600);
        check(a.equals(MorningCandidatePolicy.longestRemaining(ids, durations, progress)),
                "ties preserve the channel ordering");
        check(MorningCandidatePolicy.longestRemaining(Collections.emptyList(), durations, progress) == null,
                "empty candidate set returns no selection");
        check(MorningCandidatePolicy.longestRemaining(null, durations, progress) == null,
                "null candidate set returns no selection");
        System.out.println("MorningCandidatePolicy: " + checks + " fallback selection checks passed.");
    }
}
