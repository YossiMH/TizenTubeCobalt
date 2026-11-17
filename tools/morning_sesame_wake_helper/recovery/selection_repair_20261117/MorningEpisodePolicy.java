package dev.yossi.morningsesame;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

/** Newest qualifying episode; a fallback never makes a rejected episode eligible. */
final class MorningEpisodePolicy {
    static final int MIN_DURATION_SECONDS = 40 * 60;
    // Match the installed protection policy; a longer compilation would launch unprotected.
    static final int MAX_DURATION_SECONDS = 6 * 60 * 60;
    static final double MAX_WATCHED_PERCENT = 25.0;

    private MorningEpisodePolicy() {}

    static String select(Map<String, Integer> newestFirst, Map<String, Double> accountProgress,
            Map<String, Double> observedProgress, Set<String> confirmedStarts, Set<String> recentStarts) {
        if (newestFirst == null) return null;
        Map<String, Double> account = accountProgress == null ? Collections.emptyMap() : accountProgress;
        Map<String, Double> observed = observedProgress == null ? Collections.emptyMap() : observedProgress;
        Set<String> starts = confirmedStarts == null ? Collections.emptySet() : confirmedStarts;
        Set<String> recent = recentStarts == null ? Collections.emptySet() : recentStarts;
        for (Map.Entry<String, Integer> candidate : newestFirst.entrySet()) {
            String id = candidate.getKey();
            Integer duration = candidate.getValue();
            if (!validId(id) || duration == null || duration <= MIN_DURATION_SECONDS
                    || duration > MAX_DURATION_SECONDS || recent.contains(id)) continue;
            // A legacy startup receipt does not say how much was watched. Only an actual
            // local playback observation may establish that such an episode remains eligible.
            if (starts.contains(id) && !observed.containsKey(id)) continue;
            if (!eligibleProgress(account, id) || !eligibleProgress(observed, id)) continue;
            return id;
        }
        return null;
    }

    static boolean validId(String id) {
        return id != null && id.matches("[A-Za-z0-9_-]{11}");
    }

    private static boolean eligibleProgress(Map<String, Double> values, String id) {
        if (!values.containsKey(id)) return true;
        Double value = values.get(id);
        // Malformed evidence is unknown, never permission to replay a known entry.
        return value != null && Double.isFinite(value) && value >= 0.0
                && value <= MAX_WATCHED_PERCENT;
    }
}
