package dev.yossi.morningsesame;

import java.util.List;
import java.util.Map;

/** When local history is exhausted, avoid resuming a nearly finished episode. */
final class MorningCandidatePolicy {
    private MorningCandidatePolicy() {}

    static String longestRemaining(List<String> ordered, Map<String, Integer> durationSeconds,
            Map<String, Double> accountProgressPercent) {
        String chosen = null;
        double best = -1.0;
        if (ordered == null) return null;
        for (String id : ordered) {
            if (id == null || !id.matches("[A-Za-z0-9_-]{11}")) continue;
            Integer duration = durationSeconds == null ? null : durationSeconds.get(id);
            if (duration == null || duration <= 0) continue;
            Double pct = accountProgressPercent == null ? null : accountProgressPercent.get(id);
            double progress = pct == null || !Double.isFinite(pct) ? 0.0
                    : Math.max(0.0, Math.min(100.0, pct));
            double remaining = duration * (1.0 - progress / 100.0);
            if (chosen == null || remaining > best) {
                chosen = id;
                best = remaining;
            }
        }
        return chosen;
    }
}
